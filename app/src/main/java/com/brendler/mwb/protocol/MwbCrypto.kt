package com.brendler.mwb.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * O PowerToys mudou a criptografia do MWB em 2026, então o app conhece as três versões:
 *  - NOVO: sal e IV aleatórios por conexão (enviados no início), PBKDF2 com 100 mil iterações
 *  - INTERMEDIARIO: sal e IV aleatórios por conexão, PBKDF2 com 50 mil iterações
 *  - ANTIGO: sal e IV fixos (sem cabeçalho), PBKDF2 com 50 mil iterações
 */
enum class WireFormat(val label: String, val iterations: Int, val hasHeader: Boolean) {
    NOVO("novo", 100_000, true),
    INTERMEDIARIO("intermediário", 50_000, true),
    ANTIGO("antigo", 50_000, false)
}

object MwbCrypto {
    private const val OLD_SECRET = "18446744073709551615" // ulong.MaxValue, usado como sal/IV na versão antiga
    const val BLOCK = 16
    const val HEADER_SIZE = 32 // 16 de sal + 16 de IV

    /** A chave aparece no PowerToys em grupos de 4 caracteres separados por espaços; os espaços são ignorados. */
    fun normalizeKey(raw: String): String = raw.filter { !it.isWhitespace() }

    /** Número mágico (Get24BitHash do PowerToys): vai nos bytes 2 e 3 de cada pacote. */
    fun magicNumber(key: String): Long {
        val bytes = ByteArray(32)
        for (i in 0 until 32) {
            if (i < key.length) bytes[i] = key[i].code.toByte()
        }
        val sha = MessageDigest.getInstance("SHA-512")
        var h = sha.digest(bytes)
        repeat(50_000) { h = sha.digest(h) }
        val v = ((h[0].toInt() and 0xFF) shl 23) +
            ((h[1].toInt() and 0xFF) shl 16) +
            ((h[h.size - 1].toInt() and 0xFF) shl 8) +
            (h[2].toInt() and 0xFF)
        return v.toLong() and 0xFFFFFFFFL
    }

    fun deriveKey(key: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(key.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }

    /** Sal e IV da versão antiga (sem cabeçalho na conexão). */
    fun legacySalt(): ByteArray = OLD_SECRET.toByteArray(Charsets.UTF_16LE)
    fun legacyIv(): ByteArray = OLD_SECRET.substring(0, BLOCK).toByteArray(Charsets.US_ASCII)
}

/** AES-256 em modo CBC contínuo (o estado passa de um pacote para o próximo), sem padding. */
class CbcEncryptor(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private var prev: ByteArray = iv.copyOf()

    fun encrypt(data: ByteArray): ByteArray {
        require(data.size % MwbCrypto.BLOCK == 0)
        val out = ByteArray(data.size)
        val block = ByteArray(MwbCrypto.BLOCK)
        var o = 0
        while (o < data.size) {
            for (i in 0 until MwbCrypto.BLOCK) {
                block[i] = (data[o + i].toInt() xor prev[i].toInt()).toByte()
            }
            val enc = cipher.doFinal(block)
            System.arraycopy(enc, 0, out, o, MwbCrypto.BLOCK)
            prev = enc
            o += MwbCrypto.BLOCK
        }
        return out
    }
}

class CbcDecryptor(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private var prev: ByteArray = iv.copyOf()

    fun decrypt(data: ByteArray): ByteArray {
        require(data.size % MwbCrypto.BLOCK == 0)
        val out = ByteArray(data.size)
        var o = 0
        while (o < data.size) {
            val cb = data.copyOfRange(o, o + MwbCrypto.BLOCK)
            val dec = cipher.doFinal(cb)
            for (i in 0 until MwbCrypto.BLOCK) {
                out[o + i] = (dec[i].toInt() xor prev[i].toInt()).toByte()
            }
            prev = cb
            o += MwbCrypto.BLOCK
        }
        return out
    }
}
