package com.brendler.mwb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.brendler.mwb.protocol.MwbCrypto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : AppCompatActivity(), MwbController.Ui {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: MaterialButton
    private lateinit var etPcName: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var btnScanNetwork: MaterialButton
    private lateinit var etDeviceName: TextInputEditText
    private lateinit var etSecurityKey: TextInputEditText
    private lateinit var switchInvertScroll: MaterialSwitch
    private lateinit var tvScreenResolution: TextView
    private lateinit var cardStatus: MaterialCardView
    private lateinit var ivStatusBg: ImageView
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var tvLogs: TextView
    private lateinit var svLog: NestedScrollView
    private lateinit var layoutTiles: LinearLayout
    private lateinit var ivLayoutHint: ImageView
    private lateinit var tvLayoutHint: TextView

    private lateinit var btnSendFiles: MaterialButton
    private lateinit var btnConnectKey: MaterialButton
    private lateinit var switchAutoConnect: MaterialSwitch
    private lateinit var tilDeviceName: TextInputLayout
    private lateinit var panelAdvanced: LinearLayout
    private lateinit var ivAdvancedChevron: ImageView

    /** Seletor de arquivos do Android para enviar ao PC. */
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val files = uris.mapNotNull { FileStore.openForSending(this, it) }
        if (files.isEmpty()) Toast.makeText(this, "Não consegui abrir o arquivo.", Toast.LENGTH_LONG).show()
        else MwbController.sendFiles(files)
    }

    companion object {
        /** Repositório do projeto no GitHub (troque aqui se o nome do repositório for outro). */
        const val PROJECT_URL = "https://github.com/fbrendler/mwb-android"
    }

    private val prefs by lazy { getSharedPreferences("mwb_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MwbController.init(this)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        showScreenResolution()
        loadSettings()
        setupListeners()
        if (savedInstanceState == null) MwbController.autoStart(this, "app aberto")
    }

    override fun onStart() {
        super.onStart()
        MwbController.ui = this
        tvLogs.text = MwbController.logText().ifEmpty { "[System] Mouse Without Borders for Android by Brendler© pronto.\n" }
        svLog.post { svLog.fullScroll(android.view.View.FOCUS_DOWN) }
        onStatus(MwbController.isConnected, MwbController.statusText)
        onLayoutChanged()
    }

    override fun onStop() {
        super.onStop()
        if (MwbController.ui === this) MwbController.ui = null
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        onStatus(MwbController.isConnected, MwbController.statusText)
        KvmAccessibilityService.instance?.invertScroll = switchInvertScroll.isChecked
    }

    private fun initViews() {
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        etPcName = findViewById(R.id.etPcName)
        etServerPort = findViewById(R.id.etServerPort)
        btnScanNetwork = findViewById(R.id.btnScanNetwork)
        etDeviceName = findViewById(R.id.etDeviceName)
        etSecurityKey = findViewById(R.id.etSecurityKey)
        switchInvertScroll = findViewById(R.id.switchInvertScroll)
        tvScreenResolution = findViewById(R.id.tvScreenResolution)
        btnSendFiles = findViewById(R.id.btnSendFiles)
        btnConnectKey = findViewById(R.id.btnConnectKey)
        switchAutoConnect = findViewById(R.id.switchAutoConnect)
        tilDeviceName = findViewById(R.id.tilDeviceName)
        panelAdvanced = findViewById(R.id.panelAdvanced)
        ivAdvancedChevron = findViewById(R.id.ivAdvancedChevron)
        cardStatus = findViewById(R.id.cardStatus)
        ivStatusBg = findViewById(R.id.ivStatusBg)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        layoutTiles = findViewById(R.id.layoutTiles)
        ivLayoutHint = findViewById(R.id.ivLayoutHint)
        tvLayoutHint = findViewById(R.id.tvLayoutHint)
        btnConnect = findViewById(R.id.btnConnect)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        tvLogs = findViewById(R.id.tvLogs)
        svLog = findViewById(R.id.svLog)
    }

    private fun showScreenResolution() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            w = metrics.widthPixels
            h = metrics.heightPixels
        }
        tvScreenResolution.text = "$w × $h"
    }

    // ---------- configurações salvas ----------

    private fun defaultDeviceName(): String {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val raw = when {
            model.contains("2405CPCFBG", ignoreCase = true) || model.contains("POCO", ignoreCase = true) -> "POCOPAD"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return sanitizeName(raw)
    }

    /** O nome aparece no PowerToys: só letras, números, hífen e sublinhado, no máximo 32 caracteres. */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim().map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') c else '-'
        }.joinToString("").trim('-').take(32)
        return cleaned.ifEmpty { "ANDROID" }
    }

    private fun loadSettings() {
        etPcName.setText(prefs.getString("pc", null) ?: prefs.getString("ip", "") ?: "")
        etServerPort.setText(prefs.getInt("port", 15101).toString())
        etSecurityKey.setText(prefs.getString("key", "") ?: "")
        etDeviceName.setText(prefs.getString("name", null) ?: defaultDeviceName())
        switchInvertScroll.isChecked = prefs.getBoolean("invert_scroll", true)
        switchAutoConnect.isChecked = MwbController.isAutoConnectEnabled(this)
    }

    private fun saveSettings(pc: String, port: Int, key: String, name: String) {
        prefs.edit()
            .putString("pc", pc)
            .putInt("port", port)
            .putString("key", key)
            .putString("name", name)
            .apply()
    }

    private fun machineId(): Int = MwbController.machineId(this)

    // ---------- botões ----------

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Procure por 'Mouse Without Borders for Android by Brendler©' nas Configurações e ative o serviço.", Toast.LENGTH_LONG).show()
        }

        // ----- Sobre -----
        findViewById<TextView>(R.id.tvVersion).text = "Versão " + appVersion()

        findViewById<android.view.View>(R.id.btnReportIssue).setOnClickListener {
            // Copia o log para a pessoa colar no relato e abre a página de problemas do GitHub
            val log = MwbController.logText()
            if (log.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Log", log))
                Toast.makeText(this, "Log copiado: cole no relato do problema.", Toast.LENGTH_LONG).show()
            }
            openUrl("$PROJECT_URL/issues/new")
        }
        findViewById<android.view.View>(R.id.btnProjectPage).setOnClickListener { openUrl(PROJECT_URL) }

        // Ajustes do app no Android (Início automático e bateria, no Xiaomi)
        findViewById<android.view.View>(R.id.btnAppSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            MwbController.setAutoConnect(this, isChecked)
        }

        switchInvertScroll.setOnCheckedChangeListener { _, isChecked ->
            KvmAccessibilityService.instance?.invertScroll = isChecked
            prefs.edit().putBoolean("invert_scroll", isChecked).apply()
        }

        btnScanNetwork.setOnClickListener { scanNetworkForPc() }

        btnSendFiles.setOnClickListener {
            if (!MwbController.isConnected) {
                Toast.makeText(this, "Conecte ao PC antes de enviar arquivos.", Toast.LENGTH_SHORT).show()
            } else {
                pickFiles.launch(arrayOf("*/*"))
            }
        }

        // O quadro "Este aparelho" do layout acompanha o nome digitado
        etDeviceName.doAfterTextChanged { onLayoutChanged() }

        btnCopyLog.setOnClickListener {
            val text = MwbController.logText().ifEmpty { tvLogs.text.toString() }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Log Mouse Without Borders for Android by Brendler©", text))
            Toast.makeText(this, "Log copiado. Cole numa conversa para enviar.", Toast.LENGTH_SHORT).show()
        }

        val toggleConnection = android.view.View.OnClickListener {
            if (MwbController.isRunning) {
                MwbController.stop()
            } else {
                connect()
            }
        }
        btnConnect.setOnClickListener(toggleConnection)
        btnConnectKey.setOnClickListener(toggleConnection)

        // Copiar o nome deste aparelho (como o botão de copiar do PowerToys)
        tilDeviceName.setEndIconOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Nome do dispositivo", etDeviceName.text.toString().trim()))
            Toast.makeText(this, "Nome copiado", Toast.LENGTH_SHORT).show()
        }

        // Opções avançadas: abre e fecha
        findViewById<android.view.View>(R.id.rowAdvanced).setOnClickListener {
            val open = panelAdvanced.visibility != android.view.View.VISIBLE
            panelAdvanced.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            ivAdvancedChevron.animate().rotation(if (open) 180f else 0f).setDuration(150).start()
        }
    }

    /** Procura PCs com o Mouse sem Bordas na rede e pergunta o nome de cada um (precisa da chave). */
    private fun scanNetworkForPc() {
        val port = etServerPort.text.toString().trim().toIntOrNull() ?: 15101
        val key = MwbCrypto.normalizeKey(etSecurityKey.text.toString())
        if (key.length < 16) {
            etSecurityKey.error = "Digite a Chave de Segurança primeiro"
            return
        }

        btnScanNetwork.isEnabled = false
        btnScanNetwork.text = getString(R.string.btn_scanning)
        onLog("Procurando PCs com o Mouse sem Bordas na rede (porta $port)...")

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                try { PcFinder.scan(port, key) } catch (_: Exception) { emptyList() }
            }
            btnScanNetwork.isEnabled = true
            btnScanNetwork.text = getString(R.string.btn_scan)

            when (found.size) {
                0 -> {
                    onLog("Nenhum PC respondeu. Confira a chave, se o Mouse sem Bordas está ligado e se o PC está na mesma rede.")
                    Toast.makeText(this@MainActivity, "Nenhum PC encontrado.", Toast.LENGTH_LONG).show()
                }
                1 -> {
                    etPcName.setText(found[0].name)
                    onLog("PC encontrado: ${found[0].name} (${found[0].ip}).")
                    Toast.makeText(this@MainActivity, "PC encontrado: ${found[0].name}", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    onLog("PCs encontrados: " + found.joinToString(", ") { "${it.name} (${it.ip})" })
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Escolha o PC")
                        .setItems(found.map { "${it.name}  ·  ${it.ip}" }.toTypedArray()) { _, i ->
                            etPcName.setText(found[i].name)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        }
    }

    private fun updateAccessibilityStatus() {
        if (KvmAccessibilityService.isServiceRunning()) {
            tvAccessibilityStatus.text = getString(R.string.accessibility_enabled)
            tvAccessibilityStatus.setTextColor(color(R.color.pt_success))
            btnEnableAccessibility.isEnabled = false
            btnEnableAccessibility.text = getString(R.string.btn_accessibility_active)
            btnEnableAccessibility.setIconResource(R.drawable.ic_pt_check)
        } else {
            tvAccessibilityStatus.text = getString(R.string.accessibility_disabled)
            tvAccessibilityStatus.setTextColor(color(R.color.pt_critical))
            btnEnableAccessibility.isEnabled = true
            btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility)
            btnEnableAccessibility.icon = null
        }
    }

    private fun connect() {
        val pc = etPcName.text.toString().trim()
        val port = etServerPort.text.toString().trim().toIntOrNull() ?: 15101
        val key = MwbCrypto.normalizeKey(etSecurityKey.text.toString())
        val name = sanitizeName(etDeviceName.text.toString())

        if (key.length < 16) {
            etSecurityKey.error = "Copie a Chave de Segurança do PowerToys (16 caracteres ou mais)"
            return
        }
        if (pc.isEmpty()) {
            etPcName.error = "Digite o nome do PC (ex.: VAIO)"
            return
        }

        etDeviceName.setText(name)
        saveSettings(pc, port, key, name)

        MwbController.start(pc, port, key, name, machineId())
        onStatus(false, "Conectando a $pc...")
    }

    // ---------- MwbController.Ui ----------

    override fun onStatus(connected: Boolean, text: String) {
        val running = MwbController.isRunning
        val failed = !connected && (text.contains("Não autenticado") || text.contains("Erro") || text.contains("não encontrado"))

        // Barra de status no estilo InfoBar do Windows: verde, amarelo, vermelho ou cinza
        val state = when {
            connected -> StatusLook(R.color.pt_success, R.color.pt_success_bg, R.drawable.ic_pt_check, R.string.status_hint_connected)
            failed -> StatusLook(R.color.pt_critical, R.color.pt_critical_bg, R.drawable.ic_pt_info, R.string.status_hint_error)
            running -> StatusLook(R.color.pt_caution, R.color.pt_caution_bg, R.drawable.ic_pt_sync, R.string.status_hint_connecting)
            else -> StatusLook(R.color.pt_neutral, R.color.pt_neutral_bg, R.drawable.ic_pt_minus, R.string.status_hint_disconnected)
        }
        cardStatus.setCardBackgroundColor(color(state.background))
        ivStatusBg.imageTintList = ColorStateList.valueOf(color(state.accent))
        ivStatusIcon.setImageResource(state.icon)
        tvConnectionStatus.text = if (!running && !connected) getString(R.string.status_disconnected) else text
        tvStatusDetail.text = if ((connected || running) && !KvmAccessibilityService.isServiceRunning())
            getString(R.string.status_hint_service_off) else getString(state.hint)

        // Conectar = botão de destaque (azul); Desconectar = botão normal, como no PowerToys
        if (running) {
            btnConnect.text = getString(R.string.btn_disconnect)
            btnConnect.backgroundTintList = ColorStateList.valueOf(color(R.color.pt_control))
            btnConnect.setTextColor(color(R.color.pt_text_primary))
            btnConnect.iconTint = ColorStateList.valueOf(color(R.color.pt_text_primary))
            btnConnect.strokeColor = ColorStateList.valueOf(color(R.color.pt_control_stroke))
            btnConnect.strokeWidth = dp(1)
        } else {
            btnConnect.text = getString(R.string.btn_connect)
            btnConnect.backgroundTintList = ColorStateList.valueOf(color(R.color.pt_accent))
            btnConnect.setTextColor(color(R.color.pt_on_accent))
            btnConnect.iconTint = ColorStateList.valueOf(color(R.color.pt_on_accent))
            btnConnect.strokeWidth = 0
        }
        btnConnect.isEnabled = true
        btnConnectKey.text = getString(if (running) R.string.btn_disconnect else R.string.btn_connect_pt)
        onLayoutChanged()
    }

    override fun onLog(line: String) {
        tvLogs.append("> $line\n")
        svLog.post { svLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // ---------- layout dos dispositivos ----------

    override fun onLayoutChanged() {
        val myName = etDeviceName.text.toString().trim()
        val peer = MwbController.peerName
        val received = MwbController.layoutNames.any { it.isNotBlank() }

        // Antes de o PC mandar o layout: mostra só o PC e este aparelho
        val names: List<String> = if (received) {
            MwbController.layoutNames
        } else {
            val pcName = peer.ifEmpty { etPcName.text.toString().trim().ifEmpty { getString(R.string.layout_pc) } }
            listOf(pcName, myName)
        }
        val columns = if (received && MwbController.layoutTwoRows) 2 else names.size

        layoutTiles.removeAllViews()
        names.chunked(columns).forEachIndexed { index, rowNames ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (index > 0) setPadding(0, dp(8), 0, 0)
            }
            rowNames.forEachIndexed { i, n ->
                row.addView(makeTile(n, myName, peer), LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
            layoutTiles.addView(row)
        }

        val inLayout = names.any { it.equals(myName, ignoreCase = true) }
        when {
            !received -> setLayoutHint(R.string.layout_hint_waiting, R.color.pt_text_secondary, R.drawable.ic_pt_layout)
            inLayout -> setLayoutHint(R.string.layout_hint_ok, R.color.pt_text_secondary, R.drawable.ic_pt_layout)
            else -> setLayoutHint(R.string.layout_hint_missing, R.color.pt_caution, R.drawable.ic_pt_info)
        }
    }

    private fun setLayoutHint(text: Int, @ColorRes c: Int, @DrawableRes icon: Int) {
        tvLayoutHint.setText(text)
        tvLayoutHint.setTextColor(color(c))
        ivLayoutHint.setImageResource(icon)
        ivLayoutHint.imageTintList = ColorStateList.valueOf(color(c))
    }

    /** Um quadro do layout: ícone, nome e (quando sabemos) o estado da conexão. */
    private fun makeTile(name: String, myName: String, peer: String): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        if (name.isBlank()) {
            tile.setBackgroundResource(R.drawable.bg_pt_tile_empty)
            tile.addView(TextView(this).apply {
                text = getString(R.string.layout_empty)
                textSize = 12f
                setTextColor(color(R.color.pt_neutral))
            })
            return tile
        }

        val isMe = name.equals(myName, ignoreCase = true)
        val isPeer = peer.isNotEmpty() && name.equals(peer, ignoreCase = true)
        tile.setBackgroundResource(if (isMe) R.drawable.bg_pt_tile_self else R.drawable.bg_pt_tile)

        tile.addView(ImageView(this).apply {
            setImageResource(if (isMe) R.drawable.ic_pt_tablet else R.drawable.ic_pt_monitor)
            imageTintList = ColorStateList.valueOf(color(if (isMe) R.color.pt_accent else R.color.pt_text_primary))
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)))

        tile.addView(TextView(this).apply {
            text = name
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(color(R.color.pt_text_primary))
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val status: Pair<Int, Int>? = when {
            isMe && MwbController.isConnected -> R.string.layout_connected to R.color.pt_success
            isMe && MwbController.isRunning -> R.string.layout_connecting to R.color.pt_caution
            isMe -> R.string.layout_offline to R.color.pt_neutral
            isPeer && MwbController.isConnected -> R.string.layout_connected to R.color.pt_success
            else -> null
        }
        if (status != null) {
            tile.addView(TextView(this).apply {
                text = "● " + getString(status.first)
                textSize = 10f
                maxLines = 1
                setTextColor(color(status.second))
            })
        }
        return tile
    }

    // ---------- utilitários ----------

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Abra no navegador: $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private class StatusLook(@ColorRes val accent: Int, @ColorRes val background: Int, @DrawableRes val icon: Int, val hint: Int)

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
