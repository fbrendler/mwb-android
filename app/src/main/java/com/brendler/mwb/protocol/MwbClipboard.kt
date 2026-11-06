package com.brendler.mwb.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Formato da área de transferência de texto do Mouse Without Borders
 * (PowerToys: Core/Clipboard.cs e Helper/FormHelper.cs).
 */
object MwbClipboard {
    /** Bytes de dados em cada pacote ClipboardText (64 - 48 = começa no byte 16). */
    const val DATA_SIZE = 48

    /** O MWB manda direto pela conexão principal só até 1 MB comprimido. */
    const val MAX_BYTES = 1024 * 1024

    /** Separador entre os formatos (TXT, RTF, HTM) usado pelo MWB. */
    const val TEXT_TYPE_SEP = "{4CFF57F7-BEDD-43d5-AE8F-27A61E886F2F}"

    /** Texto -> "TXT<texto>{sep}" em UTF-16LE, comprimido com Deflate puro (como o DeflateStream do .NET). */
    fun encode(text: String): ByteArray {
        val plain = ("TXT" + text + TEXT_TYPE_SEP).toByteArray(Charsets.UTF_16LE)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(plain)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Dados recebidos (com zeros de sobra no fim) -> texto simples. */
    fun decode(data: ByteArray): String {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buf, 0, n)
                if (out.size() > 16 * 1024 * 1024) break
            }
        }
        inflater.end()
        val all = String(out.toByteArray(), Charsets.UTF_16LE)
        val parts = all.split(TEXT_TYPE_SEP).map { it.trimEnd('\u0000') }.filter { it.isNotBlank() }
        val txt = parts.firstOrNull { it.startsWith("TXT", ignoreCase = true) }
        if (txt != null) return txt.substring(3)
        // Sem marcação de formato: o MWB antigo trata como texto simples
        return parts.firstOrNull { !it.startsWith("RTF", true) && !it.startsWith("HTM", true) } ?: ""
    }
}
