package com.brendler.mwb.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

interface MwbListener {
    fun onStatus(connected: Boolean, text: String)
    fun onLog(message: String)

    /** Dados brutos do pacote de mouse: x e y em 0..65535 (ou relativos), roda e tipo do evento (WinMsg). */
    fun onMouse(x: Int, y: Int, wheel: Int, flags: Int)

    /** Tecla do Windows (virtual-key) e flags (WinMsg.LLKHF_UP = soltou). */
    fun onKey(vk: Int, flags: Int)

    fun onHideMouse()

    /** Nome do PC que aceitou a conexão. */
    fun onPeer(name: String) {}

    /** Layout das máquinas enviado pelo PC: 4 posições (vazio = sem máquina) e se são duas linhas. */
    fun onLayout(names: List<String>, twoRows: Boolean) {}

    /** Texto copiado no PC (área de transferência do MWB). */
    fun onClipboardText(text: String) {}

    /** Imagem pequena (PNG) copiada no PC, recebida pela conexão principal. */
    fun onClipboardImage(png: ByteArray) {}

    /** O PC copiou algo grande (arquivo, imagem ou texto > 1 MB) e o mouse acabou de vir para este aparelho. */
    fun onBigClipboardReady() {}

    /** O PC que respondeu tem outro nome (o endereço guardado mudou de dono). */
    fun onWrongPc(expected: String, actual: String) {}
}

/**
 * Conexão deste aparelho como "mais uma máquina" do Mouse Without Borders.
 * Conecta no PowerToys do PC (porta 15101), faz o handshake criptografado e recebe mouse e teclado.
 */
/** O MWB só busca o conteúdo grande se o mouse trocar de máquina em até 30 s depois da cópia. */
private const val BIG_CLIPBOARD_TIMEOUT_MS = 30_000L

class MwbSession(
    private val host: String,
    private val port: Int,
    keyRaw: String,
    machineName: String,
    private val machineId: Int,
    private val formats: List<WireFormat>,
    private val listener: MwbListener,
    /** Converte o nome do PC em IP (o Android não conhece nomes de PCs Windows). Null = usar [host] direto. */
    private val resolver: ((String) -> String?)? = null
) {
    private enum class Outcome { ENDED, REJECTED, UNREACHABLE }

    private val key = MwbCrypto.normalizeKey(keyRaw)
    private val name = machineName.take(32)
    private val random = SecureRandom()
    private val packetId = AtomicInteger(random.nextInt(100000))

    @Volatile private var stopped = false
    @Volatile private var conn: Conn? = null
    @Volatile private var controllerId = 0
    @Volatile var workingFormat: WireFormat? = null
        private set
    private var thread: Thread? = null
    private var magic: Long = 0

    /** IP usado na conexão atual (o [host] pode ser um nome, como "VAIO"). */
    @Volatile private var address: String = host

    fun start() {
        if (thread?.isAlive == true) return
        stopped = false
        thread = Thread({ loop() }, "mwb-session").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        val c = conn
        if (c != null) {
            try { c.sendByeBye() } catch (_: Exception) { }
            c.close()
        }
        listener.onStatus(false, "Desconectado")
        listener.onLog("Desconectado pelo usuário")
    }

    /**
     * Pede ao PC (que está controlando este aparelho) para trazer o mouse de volta.
     * x e y são a posição de chegada na tela do PC, de 0 a 65535.
     * Devolve false se ainda não sabemos quem é o PC controlador.
     */
    fun requestSwitchBack(x: Int, y: Int): Boolean {
        val id = controllerId
        val c = conn
        if (id == 0 || c == null || !c.trusted) return false
        return try {
            c.sendNextMachine(id, x, y)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Envia texto para a área de transferência do PC (como o MWB faz com textos de até 1 MB).
     * Devolve false se não houver conexão autenticada.
     */
    fun sendClipboardText(text: String): Boolean {
        val c = conn
        if (c == null || !c.trusted) return false
        return try {
            c.sendClipboardText(text)
            true
        } catch (e: Exception) {
            listener.onLog("Falha ao enviar texto ao PC: ${e.message}")
            false
        }
    }

    /** Conexão de transferência (porta da área de transferência = porta principal - 1). */
    fun newTransfer(): MwbTransfer? {
        val c = conn ?: return null
        if (!c.trusted) return null
        val fmt = workingFormat ?: return null
        return MwbTransfer(address, port - 1, key, fmt, machineId, name.trim())
    }

    private fun loop() {
        try {
            magic = MwbCrypto.magicNumber(key)
        } catch (e: Exception) {
            listener.onLog("Erro ao preparar a chave: ${e.message}")
            listener.onStatus(false, "Erro na Security Key")
            return
        }

        var failures = 0
        while (!stopped) {
            val resolved = try { resolver?.invoke(host) ?: if (resolver == null) host else null } catch (e: Exception) { null }
            if (stopped) break
            if (resolved == null) {
                failures++
                val wait = retryDelay(failures, 5000)
                listener.onStatus(false, "PC \"$host\" não encontrado. Tentando de novo em ${wait / 1000} s...")
                sleepInterruptibly(wait)
                continue
            }
            address = resolved
            val first = workingFormat
            val order = if (first != null) listOf(first) + formats.filter { it != first } else formats
            var rejectedAll = true
            var hadConnection = false
            for (fmt in order) {
                if (stopped) break
                when (attempt(fmt)) {
                    Outcome.ENDED -> { rejectedAll = false; hadConnection = true; break }
                    Outcome.UNREACHABLE -> { rejectedAll = false; break }
                    Outcome.REJECTED -> { /* tenta a próxima versão */ }
                }
            }
            if (stopped) break
            failures = if (hadConnection) 0 else failures + 1
            if (rejectedAll) {
                workingFormat = null
                listener.onLog("O PC não aceitou a conexão. Confira a Security Key: depois de atualizar o PowerToys ela pode ter mudado e precisa ser copiada de novo.")
                val wait = retryDelay(failures, 5000)
                listener.onStatus(false, "Não autenticado. Tentando de novo em ${wait / 1000} s...")
                sleepInterruptibly(wait)
            } else {
                val wait = if (hadConnection) 2000L else retryDelay(failures, 3000)
                listener.onStatus(false, "Sem conexão. Tentando de novo em ${wait / 1000} s...")
                sleepInterruptibly(wait)
            }
        }
    }

    /** Espera cada vez maior quando o PC não responde (até 30 s), para não gastar bateria. */
    private fun retryDelay(failures: Int, base: Long): Long =
        minOf(base shl minOf(maxOf(failures - 1, 0), 4), 30_000L)

    @Volatile private var wakeRequested = false

    /** A rede voltou: tenta conectar agora, sem esperar o fim da pausa. */
    fun wakeUp() {
        wakeRequested = true
    }

    private fun sleepInterruptibly(ms: Long) {
        wakeRequested = false
        var left = ms
        while (left > 0 && !stopped && !wakeRequested) {
            Thread.sleep(minOf(left, 200))
            left -= 200
        }
    }

    private fun attempt(fmt: WireFormat): Outcome {
        val shownHost = if (address == host) host else "$host ($address)"
        listener.onStatus(false, "Conectando a $shownHost...")
        listener.onLog("Conectando a $shownHost:$port (criptografia ${fmt.label})...")
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(address, port), 5000)
        } catch (e: IOException) {
            try { sock.close() } catch (_: Exception) { }
            listener.onLog("Não foi possível conectar: ${e.message ?: e.javaClass.simpleName}")
            return Outcome.UNREACHABLE
        }
        val c = Conn(sock, fmt)
        conn = c
        try {
            return c.run()
        } catch (e: Exception) {
            if (!stopped) listener.onLog("Conexão encerrada: ${e.message ?: e.javaClass.simpleName}")
            return if (c.trusted) Outcome.ENDED else Outcome.REJECTED
        } finally {
            c.close()
            if (conn === c) conn = null
        }
    }

    private inner class Conn(val sock: Socket, val fmt: WireFormat) {
        private val input = DataInputStream(sock.getInputStream())
        private val output = sock.getOutputStream()
        private val sendLock = Any()
        private var enc: CbcEncryptor? = null
        private var dec: CbcDecryptor? = null
        private val nonceInverted = IntArray(4)

        @Volatile var trusted = false
        @Volatile private var closed = false
        private var peerName = ""
        private var warnedClipboard = false
        private val seenTypes = java.util.TreeMap<Int, Int>()
        private var warnedOtherDest = false
        private var warnedHeartbeatEx = false
        private var connectedAtMs = 0L

        fun close() {
            closed = true
            try { sock.close() } catch (_: Exception) { }
        }

        fun run(): Outcome {
            val outcome = runInner()
            if (trusted) {
                val secs = (System.currentTimeMillis() - connectedAtMs) / 1000
                val resumo = seenTypes.entries.joinToString(", ") { "tipo ${it.key} x${it.value}" }
                listener.onLog("Conexão com o PC terminou após ${secs}s. Pacotes recebidos: ${if (resumo.isEmpty()) "nenhum" else resumo}.")
            }
            return outcome
        }

        private fun runInner(): Outcome {
            sock.soTimeout = 8000
            startEncrypt()
            sendHandshakes()
            startDecrypt()

            var invalid = 0
            while (!closed && !stopped) {
                val p = readPacket()
                if (p == null) {
                    return if (trusted) Outcome.ENDED else Outcome.REJECTED
                }
                if (p.type == PackageType.INVALID || p.type == PackageType.ERROR) {
                    invalid++
                    if (!trusted && invalid > 5) return Outcome.REJECTED
                    if (trusted && invalid > 30) return Outcome.ENDED
                    continue
                }
                when (p.type) {
                    PackageType.HANDSHAKE -> sendAck(p)
                    PackageType.HANDSHAKE_ACK -> if (!trusted) {
                        if (verifyAck(p)) {
                            trusted = true
                            peerName = p.machineName
                            sock.soTimeout = 0
                            val shown = if (peerName.isEmpty()) host else peerName
                            listener.onLog("Autenticado no PC \"$shown\" (criptografia ${fmt.label}).")
                            listener.onStatus(true, "Conectado a $shown")
                            listener.onPeer(shown)
                            if (resolver != null && peerName.isNotEmpty() && !peerName.equals(host.trim(), ignoreCase = true)) {
                                listener.onWrongPc(host.trim(), peerName)
                            }
                            workingFormat = fmt
                            connectedAtMs = System.currentTimeMillis()
                            listener.onLog("ID deste aparelho: $machineId, nome: $name.")
                            sendHeartbeat()
                            startHeartbeatThread()
                        } else {
                            listener.onLog("Resposta de handshake inválida.")
                            return Outcome.REJECTED
                        }
                    }
                    else -> if (trusted) {
                        val n = (seenTypes[p.type] ?: 0) + 1
                        seenTypes[p.type] = n
                        if (n == 1 && p.type != PackageType.MOUSE && p.type != PackageType.KEYBOARD) {
                            listener.onLog("PC enviou pacote tipo ${p.type}.")
                        }
                        if (dispatch(p)) return Outcome.ENDED
                    }
                }
            }
            return if (trusted) Outcome.ENDED else Outcome.REJECTED
        }

        // ---------- criptografia ----------

        private fun startEncrypt() {
            if (fmt.hasHeader) {
                val header = ByteArray(MwbCrypto.HEADER_SIZE)
                random.nextBytes(header)
                synchronized(sendLock) {
                    output.write(header)
                    output.flush()
                }
                val k = MwbCrypto.deriveKey(key, header.copyOfRange(0, 16), fmt.iterations)
                enc = CbcEncryptor(k, header.copyOfRange(16, 32))
            } else {
                val k = MwbCrypto.deriveKey(key, MwbCrypto.legacySalt(), fmt.iterations)
                enc = CbcEncryptor(k, MwbCrypto.legacyIv())
            }
            // Primeiro bloco do fluxo: 16 bytes aleatórios (o PC descarta)
            val junk = ByteArray(MwbCrypto.BLOCK)
            random.nextBytes(junk)
            writeEncrypted(junk)
        }

        private fun startDecrypt() {
            if (fmt.hasHeader) {
                val header = ByteArray(MwbCrypto.HEADER_SIZE)
                input.readFully(header)
                val k = MwbCrypto.deriveKey(key, header.copyOfRange(0, 16), fmt.iterations)
                dec = CbcDecryptor(k, header.copyOfRange(16, 32))
            } else {
                val k = MwbCrypto.deriveKey(key, MwbCrypto.legacySalt(), fmt.iterations)
                dec = CbcDecryptor(k, MwbCrypto.legacyIv())
            }
            // descarta o bloco aleatório inicial
            val junk = ByteArray(MwbCrypto.BLOCK)
            input.readFully(junk)
            dec!!.decrypt(junk)
        }

        private fun writeEncrypted(data: ByteArray) {
            synchronized(sendLock) {
                val e = enc ?: return
                output.write(e.encrypt(data))
                output.flush()
            }
        }

        // ---------- envio ----------

        private fun send(p: MwbPacket) {
            if (p.src == 0) p.src = machineId
            val raw = p.raw
            raw[1] = 0
            raw[2] = ((magic shr 16) and 0xFF).toByte()
            raw[3] = ((magic shr 24) and 0xFF).toByte()
            var sum = 0
            for (i in 2 until 32) sum += raw[i].toInt() and 0xFF
            raw[1] = (sum and 0xFF).toByte()
            writeEncrypted(raw)
        }

        private fun sendHandshakes() {
            val raw = ByteArray(64)
            random.nextBytes(raw)
            val p = MwbPacket(raw)
            p.type = PackageType.HANDSHAKE
            p.machineName = name
            p.src = machineId
            // O PC deve responder com os 4 campos de "máquina" invertidos
            for (i in 1..4) nonceInverted[i - 1] = p.machine(i).inv()
            repeat(10) { send(p) }
        }

        private fun sendAck(received: MwbPacket) {
            val ack = MwbPacket.create(PackageType.HANDSHAKE_ACK)
            ack.src = machineId
            ack.machineName = name
            for (i in 1..4) ack.setMachine(i, received.machine(i).inv())
            send(ack)
        }

        private fun verifyAck(p: MwbPacket): Boolean {
            for (i in 1..4) if (p.machine(i) != nonceInverted[i - 1]) return false
            return true
        }

        fun sendHeartbeat() {
            val p = MwbPacket.create(PackageType.HEARTBEAT)
            p.id = packetId.incrementAndGet()
            p.des = MwbIds.ALL
            p.machineName = name
            send(p)
        }

        fun sendNextMachine(target: Int, x: Int, y: Int) {
            val p = MwbPacket.create(PackageType.NEXT_MACHINE)
            p.id = packetId.incrementAndGet()
            p.des = target
            p.mouseX = x
            p.mouseY = y
            p.wheelDelta = target // máquina de destino: o próprio PC controlador
            send(p)
        }

        fun sendByeBye() {
            if (!trusted) return
            val p = MwbPacket.create(PackageType.BYE_BYE)
            p.id = packetId.incrementAndGet()
            p.des = MwbIds.ALL
            send(p)
        }

        private fun startHeartbeatThread() {
            Thread({
                try {
                    while (!closed && !stopped) {
                        Thread.sleep(5000)
                        if (closed || stopped) break
                        sendHeartbeat()
                    }
                } catch (_: Exception) {
                    close()
                }
            }, "mwb-heartbeat").apply { isDaemon = true; start() }
        }

        // ---------- recebimento ----------

        private fun readEncrypted(n: Int): ByteArray? {
            val buf = ByteArray(n)
            try {
                input.readFully(buf)
            } catch (_: EOFException) {
                return null
            }
            return dec!!.decrypt(buf)
        }

        /** Lê um pacote (32 ou 64 bytes). Pacote com magia/checksum errados vira tipo INVALID. */
        private fun readPacket(): MwbPacket? {
            val first = readEncrypted(32) ?: return null
            var valid = true
            val m = ((first[3].toInt() and 0xFF) shl 24) + ((first[2].toInt() and 0xFF) shl 16)
            if ((m.toLong() and 0xFFFFFFFFL) != (magic and 0xFFFF0000L)) valid = false
            var sum = 0
            for (i in 2 until 32) sum += first[i].toInt() and 0xFF
            if ((sum and 0xFF) != (first[1].toInt() and 0xFF)) valid = false
            first[1] = 0; first[2] = 0; first[3] = 0
            if (!valid) first[0] = PackageType.INVALID.toByte()

            val type = first[0].toInt() and 0xFF
            return if (PackageType.isBig(type)) {
                val second = readEncrypted(32) ?: return null
                val raw = ByteArray(64)
                System.arraycopy(first, 0, raw, 0, 32)
                System.arraycopy(second, 0, raw, 32, 32)
                MwbPacket(raw)
            } else {
                MwbPacket(first)
            }
        }

        /** Devolve true se a conexão deve ser encerrada. */
        private fun dispatch(p: MwbPacket): Boolean {
            when (p.type) {
                PackageType.MOUSE -> if (p.des == machineId || p.des == MwbIds.ALL) {
                    if (p.src != 0) controllerId = p.src
                    listener.onMouse(p.mouseX, p.mouseY, p.wheelDelta, p.mouseFlags)
                } else if (!warnedOtherDest) {
                    warnedOtherDest = true
                    listener.onLog("Mouse recebido para outro destino (id ${p.des}); este aparelho é ${machineId}.")
                }
                PackageType.KEYBOARD -> if (p.des == machineId || p.des == MwbIds.ALL) {
                    if (p.src != 0) controllerId = p.src
                    listener.onKey(p.vk, p.keyFlags)
                }
                PackageType.HIDE_MOUSE -> listener.onHideMouse()
                PackageType.BYE_BYE -> {
                    listener.onLog("O PC encerrou o Mouse Without Borders.")
                    return true
                }
                PackageType.HELLO -> sendHeartbeat()
                PackageType.CLIPBOARD_TEXT, PackageType.CLIPBOARD_IMAGE -> onClipboardChunk(p)
                PackageType.CLIPBOARD_DATA_END -> onClipboardEnd()
                PackageType.CLIPBOARD -> {
                    // "Tenho algo grande na área de transferência": buscamos quando o mouse vier para cá
                    bigClipboardTime = System.currentTimeMillis()
                    listener.onLog("O PC copiou um arquivo, imagem ou texto grande. Leve o mouse para o Android para receber.")
                }
                PackageType.MACHINE_SWITCHED -> if (p.des == machineId &&
                    System.currentTimeMillis() - bigClipboardTime < BIG_CLIPBOARD_TIMEOUT_MS
                ) {
                    bigClipboardTime = 0
                    listener.onBigClipboardReady()
                }
                PackageType.CLIPBOARD_PUSH, PackageType.CLIPBOARD_ASK -> if (!warnedClipboard) {
                    warnedClipboard = true
                    listener.onLog("O PC pediu a área de transferência deste aparelho (tipo ${p.type}); ainda não suportado.")
                }
                PackageType.HEARTBEAT_EX -> if (!warnedHeartbeatEx) {
                    warnedHeartbeatEx = true
                    listener.onLog("O PC indicou chave recém-gerada (Heartbeat_ex).")
                }
                else -> if ((p.type and PackageType.MATRIX) == PackageType.MATRIX) onMatrix(p)
                /* heartbeat, arrastar e soltar etc.: ignorados por enquanto */
            }
            return false
        }

        // ---------- área de transferência (texto) ----------
        // O MWB manda o conteúdo comprimido (Deflate) em pacotes ClipboardText de 64 bytes,
        // com 48 bytes de dados cada (bytes 16..63), e termina com ClipboardDataEnd.
        // O texto é UTF-16LE no formato "TXT<texto>{separador}" (pode vir também RTF e HTM).
        private var clipBuffer: java.io.ByteArrayOutputStream? = null
        private var clipIsImage = false
        private var bigClipboardTime = 0L

        private fun onClipboardChunk(p: MwbPacket) {
            var buf = clipBuffer
            if (buf == null) {
                buf = java.io.ByteArrayOutputStream()
                clipBuffer = buf
                clipIsImage = p.type == PackageType.CLIPBOARD_IMAGE
            }
            if (buf.size() < MwbClipboard.MAX_BYTES) {
                buf.write(p.raw, 64 - MwbClipboard.DATA_SIZE, MwbClipboard.DATA_SIZE)
            }
        }

        private fun onClipboardEnd() {
            val buf = clipBuffer ?: return
            clipBuffer = null
            if (clipIsImage) {
                listener.onClipboardImage(buf.toByteArray())
                return
            }
            val text = try {
                MwbClipboard.decode(buf.toByteArray())
            } catch (e: Exception) {
                listener.onLog("Não consegui ler o texto copiado no PC: ${e.message}")
                return
            }
            if (text.isNotEmpty()) listener.onClipboardText(text)
        }

        fun sendClipboardText(text: String) {
            val data = MwbClipboard.encode(text)
            synchronized(sendLock) {
                var index = 0
                while (index < data.size) {
                    val p = MwbPacket.create(PackageType.CLIPBOARD_TEXT)
                    p.id = packetId.incrementAndGet()
                    p.src = machineId
                    p.des = MwbIds.ALL
                    val len = minOf(MwbClipboard.DATA_SIZE, data.size - index)
                    System.arraycopy(data, index, p.raw, 64 - MwbClipboard.DATA_SIZE, len)
                    send(p)
                    index += MwbClipboard.DATA_SIZE
                }
                val end = MwbPacket.create(PackageType.CLIPBOARD_DATA_END)
                end.id = packetId.incrementAndGet()
                end.src = machineId
                end.des = MwbIds.ALL
                send(end)
            }
        }

        // ---------- layout das máquinas (Matrix) ----------
        // O PC manda o layout em 4 pacotes: origem = posição (1 a 4), nome da máquina nos bytes 32..63.
        // Bit 4 no tipo = duas linhas; bit 2 = volta circular.
        private val matrixNames = Array(4) { "" }
        private var lastMatrixText = ""
        private var matrixSends = 0
        private val matrixTimes = ArrayDeque<Long>()
        private var warnedMatrixFlood = false

        private fun onMatrix(p: MwbPacket) {
            val pos = p.src
            if (pos !in 1..4) return
            matrixNames[pos - 1] = p.machineName
            if (pos != 4) return

            matrixSends++
            val now = System.currentTimeMillis()
            matrixTimes.addLast(now)
            while (matrixTimes.isNotEmpty() && now - matrixTimes.first() > 5000) matrixTimes.removeFirst()

            val twoRows = (p.type and 4) == 4
            val circle = (p.type and 2) == 2
            val shown = matrixNames.joinToString(" | ") { if (it.isEmpty()) "(vazio)" else it }
            val text = "$shown/${twoRows}/${circle}"
            if (text != lastMatrixText) {
                lastMatrixText = text
                listener.onLayout(matrixNames.toList(), twoRows)
                val here = matrixNames.any { it.equals(name.trim(), ignoreCase = true) }
                listener.onLog(
                    "Layout recebido do PC: [$shown], " +
                        (if (twoRows) "2 linhas" else "1 linha") +
                        ", volta circular: ${if (circle) "sim" else "não"}."
                )
                listener.onLog(
                    if (here) "O nome deste aparelho (\"${name.trim()}\") está no layout."
                    else "ATENÇÃO: o nome deste aparelho (\"${name.trim()}\") NÃO está no layout do PC. O nome no app precisa ser igual ao do layout."
                )
            }
            if (!warnedMatrixFlood && matrixTimes.size >= 10) {
                warnedMatrixFlood = true
                listener.onLog(
                    "O PC está reenviando o layout sem parar (${matrixTimes.size} vezes em 5 s). " +
                        "Provável causa: o PowerToys regravando as configurações em ciclo no PC."
                )
            }
        }
    }
}
