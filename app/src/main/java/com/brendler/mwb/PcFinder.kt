package com.brendler.mwb

import android.content.Context
import com.brendler.mwb.protocol.MwbCrypto
import com.brendler.mwb.protocol.MwbProbe
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Encontra o PC pelo nome, como o PowerToys faz (o Android não conhece nomes de PCs Windows).
 * Ordem: endereço IP digitado → último endereço que funcionou → NetBIOS (nome do Windows na rede)
 * → DNS do roteador → varredura da rede procurando a porta 15101 e perguntando o nome a cada PC.
 * Para confirmar o nome, o app lê o cumprimento (Handshake) do PC, que só abre com a Security Key certa.
 */
object PcFinder {

    class Found(val name: String, val ip: String)

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIp(s: String) = ipv4.matches(s.trim())

    /** Devolve o IP do PC com esse nome, ou null se não achar. Bloqueia: chame fora da thread da tela. */
    fun find(ctx: Context, pcName: String, port: Int, key: String, log: (String) -> Unit): String? {
        val name = pcName.trim()
        if (isIp(name)) return name
        val wanted = name.uppercase(Locale.ROOT)
        val prefs = ctx.getSharedPreferences("mwb_prefs", Context.MODE_PRIVATE)
        val magic = MwbCrypto.magicNumber(key)

        fun matches(ip: String): Boolean {
            val r = MwbProbe.identify(ip, port, key, magic) ?: return false
            return r.name.equals(name, ignoreCase = true)
        }

        fun remember(ip: String): String {
            prefs.edit().putString("pc_addr_$wanted", ip).apply()
            return ip
        }

        // 1) último endereço que funcionou: só confere se a porta está aberta
        //    (o nome é confirmado depois, no cumprimento da conexão principal)
        prefs.getString("pc_addr_$wanted", null)?.let { cached ->
            if (portOpen(cached, port, 1500)) return cached
        }

        log("Procurando o PC \"$name\" na rede...")

        // 2) NetBIOS: pergunta "quem é VAIO?" para a rede toda (o Windows responde)
        for (ip in netbiosQuery(name)) {
            if (matches(ip)) {
                log("PC \"$name\" encontrado em $ip (nome do Windows).")
                return remember(ip)
            }
        }

        // 3) DNS do roteador (funciona em muitas redes domésticas)
        for (ip in dnsLookup(name) + dnsLookup("$name.local")) {
            if (matches(ip)) {
                log("PC \"$name\" encontrado em $ip (DNS).")
                return remember(ip)
            }
        }

        // 4) varredura: quem tem a porta do MWB aberta e se chama assim
        val found = scan(port, key, magic)
        found.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
            log("PC \"$name\" encontrado em ${it.ip}.")
            return remember(it.ip)
        }
        if (found.isNotEmpty()) {
            log("Na rede há: " + found.joinToString(", ") { "${it.name} (${it.ip})" } + ". Nenhum se chama \"$name\".")
        } else {
            log("Nenhum PC com o Mouse sem Bordas respondeu com essa Security Key. Confira a chave e se o PC está na mesma rede.")
        }
        return null
    }

    /** Esquece o endereço guardado (ex.: o PC desse IP tem outro nome agora). */
    fun forget(ctx: Context, pcName: String) {
        ctx.getSharedPreferences("mwb_prefs", Context.MODE_PRIVATE).edit()
            .remove("pc_addr_" + pcName.trim().uppercase(Locale.ROOT)).apply()
    }

    private fun portOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(ip, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    /** Lista os PCs com o Mouse sem Bordas na rede (nome e IP). Precisa da Security Key certa. */
    fun scan(port: Int, key: String, magic: Long = MwbCrypto.magicNumber(key)): List<Found> {
        val open = openHosts(port)
        if (open.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(8, open.size))
        try {
            val futures = open.map { ip -> pool.submit(Callable { MwbProbe.identify(ip, port, key, magic)?.let { Found(it.name, ip) } }) }
            return futures.mapNotNull { try { it.get(15, TimeUnit.SECONDS) } catch (_: Exception) { null } }
        } finally {
            pool.shutdownNow()
        }
    }

    /** IPs da(s) rede(s) local(is) com a porta aberta. */
    private fun openHosts(port: Int): List<String> {
        val candidates = localSubnets().flatMap { prefix -> (1..254).map { "$prefix.$it" } }.toSet() - localAddresses()
        val pool = Executors.newFixedThreadPool(64)
        try {
            val futures = candidates.map { ip ->
                pool.submit(Callable {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress(ip, port), 400) }
                        ip
                    } catch (_: Exception) {
                        null
                    }
                })
            }
            return futures.mapNotNull { try { it.get(10, TimeUnit.SECONDS) } catch (_: Exception) { null } }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Começo dos IPs deste aparelho (ex.: "192.168.0"), em todas as redes ativas. */
    private fun localSubnets(): List<String> = localAddresses().map { it.substringBeforeLast('.') }.distinct()

    private fun localAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (a in iface.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        a.hostAddress?.let { list.add(it) }
                    }
                }
            }
        } catch (_: Exception) { }
        return list
    }

    private fun dnsLookup(name: String): List<String> {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            pool.submit(Callable {
                InetAddress.getAllByName(name).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            }).get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Consulta de nome NetBIOS (porta UDP 137) por broadcast. Devolve os IPs que responderem. */
    private fun netbiosQuery(name: String): List<String> {
        val result = mutableListOf<String>()
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 400
                val id = (System.nanoTime() and 0xFFFF).toInt()
                val q = buildNbnsQuery(id, name)
                val targets = localSubnets().map { "$it.255" } + "255.255.255.255"
                for (t in targets) {
                    try { sock.send(DatagramPacket(q, q.size, InetAddress.getByName(t), 137)) } catch (_: Exception) { }
                }
                val buf = ByteArray(1024)
                val end = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                    parseNbnsAnswer(buf, p.length, id)?.let { if (it !in result) result.add(it) }
                }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun buildNbnsQuery(id: Int, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x10)           // consulta, broadcast
        out.write(0); out.write(1)                  // 1 pergunta
        repeat(6) { out.write(0) }
        out.write(32)
        val n = name.uppercase(Locale.ROOT).take(15).padEnd(15, ' ') + '\u0000'
        for (c in n) {
            val b = c.code and 0xFF
            out.write('A'.code + (b shr 4)); out.write('A'.code + (b and 0x0F))
        }
        out.write(0)
        out.write(0); out.write(0x20)               // tipo NB
        out.write(0); out.write(0x01)               // classe IN
        return out.toByteArray()
    }

    private fun parseNbnsAnswer(b: ByteArray, len: Int, id: Int): String? {
        if (len < 12 + 34 + 10 + 6) return null
        if (((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)) != id) return null
        if ((b[2].toInt() and 0x80) == 0) return null    // não é resposta
        val answers = (b[6].toInt() and 0xFF) shl 8 or (b[7].toInt() and 0xFF)
        if (answers < 1) return null
        val rdata = 12 + 34 + 2 + 2 + 4 + 2           // nome, tipo, classe, TTL, tamanho
        if (len < rdata + 6) return null
        val o = rdata + 2                            // pula as flags do nome
        return "${b[o].toInt() and 0xFF}.${b[o + 1].toInt() and 0xFF}.${b[o + 2].toInt() and 0xFF}.${b[o + 3].toInt() and 0xFF}"
    }
}
