package com.brendler.mwb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.brendler.mwb.protocol.MwbClipboard
import com.brendler.mwb.protocol.MwbTransfer
import java.io.FileInputStream
import java.util.concurrent.Executors
import com.brendler.mwb.protocol.MwbListener
import com.brendler.mwb.protocol.MwbSession
import com.brendler.mwb.protocol.WireFormat

/**
 * Guarda a conexão com o Mouse Without Borders fora da tela (a conexão continua se a tela girar ou for fechada)
 * e entrega mouse e teclado do PC para o [RemoteInput].
 */
object MwbController : MwbListener {

    interface Ui {
        fun onStatus(connected: Boolean, text: String)
        fun onLog(line: String)
        fun onLayoutChanged() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()
    private val remote = RemoteInput(
        switchBack = { x, y -> session?.requestSwitchBack(x, y) ?: false },
        log = { msg -> onLog(msg) }
    )

    @Volatile private var session: MwbSession? = null

    /** Contexto do app (para salvar arquivos e mexer na área de transferência). */
    @Volatile private var appContext: Context? = null

    /** Transferências de arquivos, uma de cada vez. */
    private val transfers = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        watchNetwork(context.applicationContext)
    }

    /** Quando o Wi-Fi volta, tenta reconectar na hora (em vez de esperar a próxima tentativa). */
    private fun watchNetwork(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val s = session ?: return
                    if (!isConnected) {
                        onLog("Rede disponível: tentando conectar ao PC.")
                        s.wakeUp()
                    }
                }

                override fun onLost(network: android.net.Network) {
                    if (session != null) onLog("Rede perdida. O app volta a conectar quando a rede voltar.")
                }
            })
        } catch (e: Exception) {
            onLog("Não consegui acompanhar a rede: ${e.message}")
        }
    }

    // ---------- conexão automática ----------

    private const val PREFS = "mwb_prefs"

    fun isAutoConnectEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_connect", true)

    fun setAutoConnect(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_connect", enabled).apply()
    }

    /** Identificador deste aparelho no Mouse sem Bordas (criado uma vez e guardado). */
    fun machineId(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getInt("machine_id", 0)
        if (id <= 1000) {
            id = kotlin.random.Random.nextInt(100000, Int.MAX_VALUE)
            prefs.edit().putInt("machine_id", id).apply()
        }
        return id
    }

    /**
     * Conecta sozinho ao último PC usado, se a opção estiver ligada e os dados estiverem salvos.
     * Chamado quando o serviço de controle liga (ex.: tablet ligado) e quando o app abre.
     */
    fun autoStart(context: Context, reason: String) {
        init(context)
        val ctx = appContext ?: return
        if (isRunning || !isAutoConnectEnabled(ctx)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pc = (prefs.getString("pc", null) ?: prefs.getString("ip", "") ?: "").trim()
        val key = prefs.getString("key", "") ?: ""
        val name = prefs.getString("name", null) ?: return
        val port = prefs.getInt("port", 15101)
        if (pc.isEmpty() || key.length < 16) return
        onLog("Conectando automaticamente a \"$pc\" ($reason).")
        start(pc, port, key, name, machineId(ctx))
    }
    @Volatile var ui: Ui? = null

    @Volatile var isConnected: Boolean = false
        private set
    @Volatile var statusText: String = "Desconectado"
        private set
    @Volatile var isRunning: Boolean = false
        private set


    /** Último layout recebido do PC (4 posições) e nome do PC conectado. */
    @Volatile var layoutNames: List<String> = emptyList()
        private set
    @Volatile var layoutTwoRows: Boolean = false
        private set
    @Volatile var peerName: String = ""
        private set

    fun start(host: String, port: Int, key: String, machineName: String, machineId: Int) {
        stop()
        isRunning = true
        val ctx = appContext
        val resolver: ((String) -> String?)? = if (ctx == null || PcFinder.isIp(host)) null
        else { name -> PcFinder.find(ctx, name, port, key) { onLog(it) } }
        val s = MwbSession(host, port, key, machineName, machineId, WireFormat.values().toList(), this, resolver)
        session = s
        s.start()
    }

    fun stop() {
        val s = session
        session = null
        isRunning = false
        if (s != null) {
            Thread { s.stop() }.start() // fecha o socket fora da thread da tela
        }
        remote.onLeave()
    }

    fun logText(): String = synchronized(logBuffer) { logBuffer.toString() }

    /** Último texto que veio do PC (para não mandar de volta o mesmo texto). */
    @Volatile var lastFromPc: String? = null
        private set
    private var lastSentText: String? = null
    private var lastSentTime = 0L

    /** Cópia no Android → área de transferência do PC. */
    fun sendClipboardText(text: String) {
        if (text.isEmpty() || !isConnected) return
        if (text == lastFromPc) return
        val now = System.currentTimeMillis()
        if (text == lastSentText && now - lastSentTime < 2000) return
        lastSentText = text
        lastSentTime = now
        val s = session ?: return
        if (MwbClipboard.encode(text).size > MwbClipboard.MAX_BYTES) {
            // Texto muito grande: vai pela conexão de transferência, como no MWB
            transfers.execute {
                try {
                    val t = s.newTransfer() ?: return@execute
                    t.pushText(text)
                    onLog("Texto grande enviado para o PC (${text.length} caracteres).")
                } catch (e: Exception) {
                    onLog("Falha ao enviar texto grande ao PC: ${e.message}")
                }
            }
            return
        }
        Thread {
            if (s.sendClipboardText(text)) onLog("Texto enviado para o PC (${text.length} caracteres).")
        }.start()
    }

    // ---------- arquivos e imagens ----------

    /** Envia arquivos ao PC. O PC salva na Área de Trabalho, pasta MouseWithoutBorders, e abre a pasta. */
    fun sendFiles(files: List<FileStore.Outgoing>) {
        if (files.isEmpty()) return
        if (!isConnected) {
            files.forEach { closeOutgoing(it) }
            toast("Conecte o Mouse Without Borders for Android by Brendler© ao PC antes de enviar arquivos.")
            return
        }
        toast(if (files.size == 1) "Enviando ${files[0].name} ao PC…" else "Enviando ${files.size} arquivos ao PC…")
        for (f in files) {
            transfers.execute {
                try {
                    val t = session?.newTransfer() ?: throw IllegalStateException("sem conexão com o PC")
                    onLog("Enviando ${f.name} (${FileStore.formatSize(f.size)}) ao PC...")
                    val start = System.currentTimeMillis()
                    var lastStep = 0
                    FileInputStream(f.pfd.fileDescriptor).use { input ->
                        t.pushFile(f.name, f.size, input) { done, total ->
                            val step = if (total > 0) (done * 4 / total).toInt() else 4
                            if (total > 5L * 1024 * 1024 && step in 1..3 && step > lastStep) {
                                lastStep = step
                                onLog("  ${step * 25}% de ${f.name}")
                            }
                        }
                    }
                    val secs = (System.currentTimeMillis() - start) / 1000.0
                    onLog("Arquivo enviado: ${f.name} em ${"%.1f".format(secs)} s. No PC: Área de Trabalho › MouseWithoutBorders.")
                    toast("${f.name} enviado ao PC")
                } catch (e: Exception) {
                    onLog("Falha ao enviar ${f.name}: ${e.message ?: e.javaClass.simpleName}")
                    toast("Falha ao enviar ${f.name}")
                } finally {
                    closeOutgoing(f)
                }
            }
        }
    }

    private fun closeOutgoing(f: FileStore.Outgoing) {
        try { f.pfd.close() } catch (_: Exception) { }
        f.tempFile?.delete()
    }

    /** O mouse veio para o Android logo depois de o PC copiar algo grande: busca o conteúdo. */
    override fun onBigClipboardReady() {
        val ctx = appContext ?: return
        transfers.execute {
            val t = session?.newTransfer() ?: return@execute
            var target: FileStore.Target? = null
            try {
                onLog("Recebendo o conteúdo copiado no PC...")
                val start = System.currentTimeMillis()
                var lastStep = 0
                val r = t.pull({ name, size ->
                    onLog("Recebendo $name (${FileStore.formatSize(size)})...")
                    FileStore.openDownload(ctx, name).also { target = it }?.stream
                }) { done, total ->
                    val step = if (total > 0) (done * 4 / total).toInt() else 4
                    if (total > 5L * 1024 * 1024 && step in 1..3 && step > lastStep) {
                        lastStep = step
                        onLog("  ${step * 25}%")
                    }
                }
                val secs = (System.currentTimeMillis() - start) / 1000.0
                when (r.kind) {
                    MwbTransfer.Kind.TEXT -> {
                        val text = MwbClipboard.decode(r.bytes ?: ByteArray(0))
                        if (text.isNotEmpty()) onClipboardText(text)
                    }
                    MwbTransfer.Kind.IMAGE -> onClipboardImage(r.bytes ?: ByteArray(0))
                    MwbTransfer.Kind.FILE -> {
                        val tg = target
                        if (tg != null) {
                            FileStore.finish(ctx, tg, r.complete)
                            if (r.complete) {
                                onLog("Arquivo recebido: ${r.name} (${FileStore.formatSize(r.size)}) em ${"%.1f".format(secs)} s. Salvo em ${tg.shownPath}.")
                                toast("Arquivo do PC salvo em ${tg.shownPath}")
                                if (tg.uri != null) setClipUri(ctx, tg.uri, r.name)
                            } else {
                                onLog("O arquivo ${r.name} chegou incompleto (${r.receivedBytes} de ${r.size} bytes) e foi descartado.")
                            }
                        } else {
                            onLog("Não consegui criar o arquivo ${r.name} no Android.")
                        }
                    }
                    MwbTransfer.Kind.MESSAGE -> onLog("O PC respondeu: ${MwbTransfer.fileNameOnly(r.name)}")
                }
            } catch (e: Exception) {
                target?.let { FileStore.finish(ctx, it, false) }
                onLog("Falha ao receber do PC: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Imagem copiada no PC: salva em Imagens/MouseWithoutBorders e coloca na área de transferência. */
    override fun onClipboardImage(png: ByteArray) {
        val ctx = appContext ?: return
        try {
            val saved = FileStore.saveImage(ctx, png)
            if (saved == null) {
                onLog("O PC mandou uma imagem que não consegui abrir.")
                return
            }
            onLog("Imagem recebida do PC. Salva em ${saved.second}.")
            toast("Imagem do PC salva em ${saved.second}")
            saved.first?.let { setClipUri(ctx, it, "Imagem do PC") }
        } catch (e: Exception) {
            onLog("Falha ao salvar a imagem do PC: ${e.message}")
        }
    }

    private fun setClipUri(ctx: Context, uri: android.net.Uri, label: String) {
        main.post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, label, uri))
            } catch (_: Exception) { }
        }
    }

    private fun toast(msg: String) {
        val ctx = appContext ?: return
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    // ----- MwbListener (chamado de threads de rede) -----

    override fun onStatus(connected: Boolean, text: String) {
        isConnected = connected
        statusText = text
        if (!connected) remote.onLeave()
        main.post { ui?.onStatus(connected, text) }
    }

    override fun onLog(message: String) {
        synchronized(logBuffer) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logBuffer.append(time).append(" > ").append(message).append('\n')
            if (logBuffer.length > 20000) logBuffer.delete(0, logBuffer.length - 15000)
        }
        main.post { ui?.onLog(message) }
    }

    override fun onMouse(x: Int, y: Int, wheel: Int, flags: Int) = remote.onMouse(x, y, wheel, flags)

    override fun onKey(vk: Int, flags: Int) = remote.onKey(vk, flags)

    override fun onHideMouse() = remote.onLeave()

    override fun onWrongPc(expected: String, actual: String) {
        onLog("Atenção: neste endereço está o PC \"$actual\", não \"$expected\". Vou procurar de novo na próxima conexão.")
        appContext?.let { PcFinder.forget(it, expected) }
    }

    override fun onPeer(name: String) {
        peerName = name
        main.post { ui?.onLayoutChanged() }
    }

    override fun onClipboardText(text: String) {
        lastFromPc = text
        onLog("Texto recebido do PC (${text.length} caracteres). Já pode colar no Android.")
        val service = KvmAccessibilityService.instance
        if (service != null) service.setSystemClipboard(text, fromPc = true)
        else onLog("Serviço de controle desligado: não consegui colocar o texto na área de transferência do Android.")
    }

    override fun onLayout(names: List<String>, twoRows: Boolean) {
        layoutNames = names
        layoutTwoRows = twoRows
        main.post { ui?.onLayoutChanged() }
    }
}
