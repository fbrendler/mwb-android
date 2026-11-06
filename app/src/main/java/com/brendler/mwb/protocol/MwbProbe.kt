package com.brendler.mwb.protocol

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Descobre o nome de um PC com o Mouse Without Borders pelo próprio protocolo:
 * ao receber uma conexão na porta 15101, o PC manda logo o cabeçalho da criptografia
 * e pacotes Handshake que trazem o nome dele (bytes 32..63).
 * Só funciona com a Security Key certa, e não envia nada ao PC (só lê e fecha).
 */
object MwbProbe {

    class Result(val name: String, val format: WireFormat)

    /** [magic] = MwbCrypto.magicNumber(key), calculado uma vez antes (é demorado). */
    fun identify(ip: String, port: Int, key: String, magic: Long, timeoutMs: Int = 2500): Result? {
        val raw = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                s.soTimeout = timeoutMs
                // Lê os 10 Handshakes inteiros (32 + 16 + 10 x 64 bytes): fechar com dados não lidos
                // faria o Android "derrubar" a conexão (RST), e o PC poderia reabrir todas as conexões.
                readUpTo(s.getInputStream(), 32 + 16 + 10 * 64)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        for (fmt in WireFormat.values()) {
            val name = try { tryFormat(raw, fmt, key, magic) } catch (_: Exception) { null }
            if (name != null) return Result(name, fmt)
        }
        return null
    }

    private fun tryFormat(raw: ByteArray, fmt: WireFormat, key: String, magic: Long): String? {
        val dec: CbcDecryptor
        var off: Int
        if (fmt.hasHeader) {
            if (raw.size < 32 + 16 + 64) return null
            dec = CbcDecryptor(MwbCrypto.deriveKey(key, raw.copyOfRange(0, 16), fmt.iterations), raw.copyOfRange(16, 32))
            off = 32
        } else {
            if (raw.size < 16 + 64) return null
            dec = CbcDecryptor(MwbCrypto.deriveKey(key, MwbCrypto.legacySalt(), fmt.iterations), MwbCrypto.legacyIv())
            off = 0
        }
        dec.decrypt(raw.copyOfRange(off, off + 16)) // bloco aleatório inicial
        off += 16
        val p = dec.decrypt(raw.copyOfRange(off, off + 64))

        // Confere o número mágico e o checksum, como na conexão normal
        val m = ((p[3].toInt() and 0xFF) shl 24) + ((p[2].toInt() and 0xFF) shl 16)
        if ((m.toLong() and 0xFFFFFFFFL) != (magic and 0xFFFF0000L)) return null
        var sum = 0
        for (i in 2 until 32) sum += p[i].toInt() and 0xFF
        if ((sum and 0xFF) != (p[1].toInt() and 0xFF)) return null
        if ((p[0].toInt() and 0xFF) != PackageType.HANDSHAKE) return null

        return String(p, 32, 32, Charsets.US_ASCII).trim().trimEnd('\u0000').ifEmpty { null }
    }

    private fun readUpTo(inp: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val k = try { inp.read(b, got, n - got) } catch (_: Exception) { -1 }
            if (k < 0) break
            got += k
        }
        return if (got >= 16 + 64) b.copyOf(got) else null
    }
}
