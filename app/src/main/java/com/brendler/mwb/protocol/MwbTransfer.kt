package com.brendler.mwb.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Transferência de dados grandes do Mouse Without Borders (imagens, textos acima de 1 MB e arquivos).
 * Usa uma conexão separada na porta da área de transferência (15100 = porta principal - 1),
 * com a mesma criptografia da conexão principal.
 *
 * Baseado no PowerToys: Core/Clipboard.cs (ShakeHand, ReceiveAndProcessClipboardData)
 * e Class/SocketStuff.cs (SendOrReceiveClipboardData, SendClipboardData, SendFileEx).
 *
 * Sequência:
 *  1. cada lado envia: [cabeçalho de 32 bytes: sal + IV] + bloco aleatório de 16 bytes + pacote de 64 bytes
 *     (tipo Clipboard = "quero receber", ClipboardPush = "vou enviar"; origem = ID; nome da máquina);
 *  2. quem envia manda um cabeçalho de 1024 bytes em UTF-16LE: "<tamanho>*<nome>"
 *     (nome "text" = texto comprimido, "image" = imagem PNG, outro = arquivo);
 *  3. depois vêm os dados, completados com zeros até múltiplo de 32 bytes, e a conexão é fechada.
 */
class MwbTransfer(
    private val host: String,
    private val port: Int,
    private val key: String,
    private val format: WireFormat,
    private val machineId: Int,
    private val machineName: String
) {
    enum class Kind { TEXT, IMAGE, FILE, MESSAGE }

    /** Resultado de um recebimento. Para TEXT e IMAGE, [bytes] traz os dados; para FILE, eles foram para o arquivo. */
    class Received(
        val kind: Kind,
        val name: String,
        val size: Long,
        val receivedBytes: Long,
        val bytes: ByteArray? = null
    ) {
        val complete: Boolean get() = receivedBytes >= size
    }

    /** Onde gravar um arquivo recebido. Devolver null cancela o recebimento. */
    fun interface FileSink {
        fun open(fileName: String, size: Long): OutputStream?
    }

    private val random = SecureRandom()

    // ---------- recebendo (PC -> Android) ----------

    /** Pede ao PC o conteúdo grande que ele copiou por último. */
    fun pull(sink: FileSink, progress: (Long, Long) -> Unit = { _, _ -> }): Received {
        connect().use { sock ->
            val ch = handshake(sock, push = false, postAction = POST_ACTION_OTHER)
            val header = ch.readFully(HEADER_BYTES) ?: throw IOException("O PC fechou a conexão sem mandar dados.")
            val headerText = String(header, Charsets.UTF_16LE).replace("\u0000", "")
            val star = headerText.indexOf('*')
            if (star < 0) throw IOException("Cabeçalho inválido: $headerText")
            val size = headerText.substring(0, star).trim().toLongOrNull() ?: throw IOException("Cabeçalho inválido: $headerText")
            val name = headerText.substring(star + 1)

            if (size <= 0) {
                // O PC manda tamanho 0 com uma mensagem (pasta, arquivo grande demais, arquivo não encontrado)
                return Received(Kind.MESSAGE, name, 0, 0)
            }

            val kind = when {
                name.startsWith("text", ignoreCase = true) -> Kind.TEXT
                name.startsWith("image", ignoreCase = true) -> Kind.IMAGE
                else -> Kind.FILE
            }
            val memory = if (kind == Kind.FILE) null else ByteArrayOutputStream()
            val out: OutputStream = memory ?: (sink.open(fileNameOnly(name), size) ?: return Received(Kind.MESSAGE, name, size, 0))

            var received = 0L
            try {
                val buf = ByteArray(64 * 1024)
                while (received < size) {
                    val n = ch.read(buf)
                    if (n <= 0) break
                    val use = minOf(n.toLong(), size - received).toInt()
                    out.write(buf, 0, use)
                    received += use
                    progress(received, size)
                }
                out.flush()
            } finally {
                if (memory == null) out.close()
            }
            return Received(kind, fileNameOnly(name), size, received, memory?.toByteArray())
        }
    }

    // ---------- enviando (Android -> PC) ----------

    /**
     * Envia um arquivo ao PC. Com [POST_ACTION_DESKTOP] o PC salva na Área de Trabalho,
     * pasta MouseWithoutBorders, e abre a pasta.
     */
    fun pushFile(fileName: String, size: Long, input: InputStream, postAction: Int = POST_ACTION_DESKTOP, progress: (Long, Long) -> Unit = { _, _ -> }) {
        // Nomes começando com "text" ou "image" seriam tratados como área de transferência pelo PC:
        // por isso vai com uma pasta na frente (o PC usa só o nome final).
        val safe = sanitizeFileName(fileName)
        push("$size*${sanitizeFileName(machineName)}\\$safe", input, size, postAction, progress)
    }

    /** Texto grande (acima de 1 MB comprimido) para a área de transferência do PC. */
    fun pushText(text: String) {
        val data = MwbClipboard.encode(text)
        push("${data.size}*text", data.inputStream(), data.size.toLong(), POST_ACTION_OTHER) { _, _ -> }
    }

    /** Imagem PNG para a área de transferência do PC. */
    fun pushImage(png: ByteArray) {
        push("${png.size}*image", png.inputStream(), png.size.toLong(), POST_ACTION_OTHER) { _, _ -> }
    }

    private fun push(headerText: String, input: InputStream, size: Long, postAction: Int, progress: (Long, Long) -> Unit) {
        connect().use { sock ->
            val ch = handshake(sock, push = true, postAction = postAction)
            val header = ByteArray(HEADER_BYTES)
            val hb = headerText.toByteArray(Charsets.UTF_16LE)
            System.arraycopy(hb, 0, header, 0, minOf(hb.size, HEADER_BYTES - 2))
            ch.write(header)

            var sent = 0L
            val buf = ByteArray(64 * 1024)
            while (sent < size) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                if (n <= 0) break
                ch.write(buf, 0, n)
                sent += n
                progress(sent, size)
            }
            if (sent < size) throw IOException("O arquivo terminou antes do esperado ($sent de $size bytes).")
            // Completa com zeros até múltiplo de 32 bytes (o PC descarta o que passar do tamanho)
            val pad = 32 - (sent % 32).toInt()
            ch.write(ByteArray(pad))
            ch.flush()
            // Fecha o envio e espera o PC terminar de ler e fechar
            sock.shutdownOutput()
            sock.soTimeout = 30_000
            try {
                val drain = ByteArray(1024)
                while (sock.getInputStream().read(drain) >= 0) { /* espera o fim */ }
            } catch (_: IOException) { }
        }
    }

    // ---------- conexão e criptografia ----------

    private fun connect(): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 8000)
        s.soTimeout = 30_000
        return s
    }

    private fun handshake(sock: Socket, push: Boolean, postAction: Int): Channel {
        val out = sock.getOutputStream()
        val inp = sock.getInputStream()

        // --- envio ---
        val enc: CbcEncryptor
        if (format.hasHeader) {
            val header = ByteArray(MwbCrypto.HEADER_SIZE)
            random.nextBytes(header)
            out.write(header)
            enc = CbcEncryptor(MwbCrypto.deriveKey(key, header.copyOfRange(0, 16), format.iterations), header.copyOfRange(16, 32))
        } else {
            enc = CbcEncryptor(MwbCrypto.deriveKey(key, MwbCrypto.legacySalt(), format.iterations), MwbCrypto.legacyIv())
        }
        val ch = Channel(inp, out, enc)
        val junk = ByteArray(MwbCrypto.BLOCK)
        random.nextBytes(junk)
        ch.write(junk)

        val pkg = ByteArray(64)
        pkg[0] = (if (push) PackageType.CLIPBOARD_PUSH else PackageType.CLIPBOARD).toByte()
        putInt(pkg, 8, machineId)          // origem
        putInt(pkg, 16, postAction)         // PostAction (mesmo lugar dos dados do mouse)
        val nm = machineName.take(32).padEnd(32, ' ').toByteArray(Charsets.US_ASCII)
        System.arraycopy(nm, 0, pkg, 32, 32)
        ch.write(pkg)
        ch.flush()

        // --- recebimento ---
        val dec: CbcDecryptor
        if (format.hasHeader) {
            val header = readExactly(inp, MwbCrypto.HEADER_SIZE) ?: throw IOException("O PC fechou a conexão da área de transferência.")
            dec = CbcDecryptor(MwbCrypto.deriveKey(key, header.copyOfRange(0, 16), format.iterations), header.copyOfRange(16, 32))
        } else {
            dec = CbcDecryptor(MwbCrypto.deriveKey(key, MwbCrypto.legacySalt(), format.iterations), MwbCrypto.legacyIv())
        }
        ch.dec = dec
        ch.readFully(MwbCrypto.BLOCK) ?: throw IOException("O PC fechou a conexão da área de transferência.")
        val answer = ch.readFully(64) ?: throw IOException("O PC recusou a conexão da área de transferência.")
        val t = answer[0].toInt() and 0xFF
        if (t != PackageType.CLIPBOARD && t != PackageType.CLIPBOARD_PUSH) {
            throw IOException("Resposta inesperada do PC (tipo $t). Confira a Security Key.")
        }
        return ch
    }

    /** Fluxo AES-CBC contínuo sobre o socket, em blocos de 16 bytes. */
    private class Channel(private val inp: InputStream, private val out: OutputStream, private val enc: CbcEncryptor) {
        var dec: CbcDecryptor? = null
        private val pendingOut = ByteArrayOutputStream()
        private var plainBuf = ByteArray(0)
        private var plainPos = 0
        private val raw = ByteArray(64 * 1024)
        private var rawLeft = ByteArray(0)
        private var eof = false

        fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
            pendingOut.write(b, off, len)
            val all = pendingOut.toByteArray()
            val whole = all.size - all.size % MwbCrypto.BLOCK
            if (whole > 0) {
                out.write(enc.encrypt(all.copyOfRange(0, whole)))
                pendingOut.reset()
                pendingOut.write(all, whole, all.size - whole)
            }
        }

        fun flush() = out.flush()

        /** Lê bytes já decifrados; devolve -1 no fim da conexão. */
        fun read(b: ByteArray): Int {
            if (plainPos >= plainBuf.size && !fill()) return -1
            val n = minOf(b.size, plainBuf.size - plainPos)
            System.arraycopy(plainBuf, plainPos, b, 0, n)
            plainPos += n
            return n
        }

        fun readFully(n: Int): ByteArray? {
            val r = ByteArray(n)
            var got = 0
            while (got < n) {
                if (plainPos >= plainBuf.size && !fill()) return null
                val k = minOf(n - got, plainBuf.size - plainPos)
                System.arraycopy(plainBuf, plainPos, r, got, k)
                plainPos += k
                got += k
            }
            return r
        }

        private fun fill(): Boolean {
            while (true) {
                if (eof) return false
                val n = try { inp.read(raw) } catch (e: java.net.SocketException) { -1 }
                if (n < 0) { eof = true; return false }
                if (n == 0) continue
                val all = ByteArray(rawLeft.size + n)
                System.arraycopy(rawLeft, 0, all, 0, rawLeft.size)
                System.arraycopy(raw, 0, all, rawLeft.size, n)
                val whole = all.size - all.size % MwbCrypto.BLOCK
                rawLeft = all.copyOfRange(whole, all.size)
                if (whole == 0) continue
                plainBuf = dec!!.decrypt(all.copyOfRange(0, whole))
                plainPos = 0
                return true
            }
        }
    }

    companion object {
        const val POST_ACTION_OTHER = 0
        const val POST_ACTION_DESKTOP = 1
        private const val HEADER_BYTES = 1024

        private fun putInt(b: ByteArray, off: Int, v: Int) {
            b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte(); b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
        }

        private fun readExactly(inp: InputStream, n: Int): ByteArray? {
            val b = ByteArray(n)
            var got = 0
            while (got < n) {
                val k = try { inp.read(b, got, n - got) } catch (_: EOFException) { -1 }
                if (k < 0) return null
                got += k
            }
            return b
        }

        /** Só o nome do arquivo (o PC manda o caminho completo do Windows). */
        fun fileNameOnly(path: String): String {
            val i = maxOf(path.lastIndexOf('\\'), path.lastIndexOf('/'))
            return (if (i >= 0) path.substring(i + 1) else path).trim().ifEmpty { "arquivo" }
        }

        /** Caracteres que o Windows não aceita em nomes de arquivo (e o * que separa o cabeçalho). */
        fun sanitizeFileName(name: String): String =
            name.map { if (it in "\\/:*?\"<>|" || it.code < 32) '_' else it }.joinToString("").trim().ifEmpty { "arquivo" }
    }
}
