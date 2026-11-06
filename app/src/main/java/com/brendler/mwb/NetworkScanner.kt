package com.brendler.mwb

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

object NetworkScanner {

    /** Início do IP deste aparelho na rede Wi-Fi, por exemplo "192.168.0". */
    fun getLocalSubnetPrefix(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val ip = dhcp.ipAddress
                return String.format(Locale.US, "%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff)
            }
        } catch (_: Exception) {
            // Ignore
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val parts = (addr.hostAddress ?: continue).split(".")
                        if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }

        return "192.168.0"
    }

    /** Procura na sub-rede um PC com o Mouse Without Borders (porta 15101 aberta). */
    suspend fun scanForPc(
        subnetPrefix: String,
        port: Int = 15101,
        timeoutMs: Int = 300,
        onFound: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val jobs = mutableListOf<Job>()
        val foundIpRef = AtomicReference<String?>(null)

        for (i in 1..254) {
            val testIp = "$subnetPrefix.$i"
            jobs.add(launch {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(testIp, port), timeoutMs)
                    socket.close()
                    if (foundIpRef.compareAndSet(null, testIp)) {
                        withContext(Dispatchers.Main) { onFound(testIp) }
                    }
                } catch (_: Exception) {
                    // IP não respondeu na porta
                }
            })
        }

        jobs.joinAll()
        return@withContext foundIpRef.get()
    }
}
