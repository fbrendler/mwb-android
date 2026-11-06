package com.brendler.mwb

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView

class KvmAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "KvmAccessibilityService"

        @Volatile
        var instance: KvmAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    @Volatile
    var invertScroll: Boolean = true

    @Volatile
    var syncClipboardEnabled: Boolean = true

    @Volatile
    private var lastFromPc: String? = null

    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    private var windowManager: WindowManager? = null
    private var cursorImageView: ImageView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var isCursorAdded = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeInputBuffer = StringBuilder()
    private var activeCursorPos = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MwbController.init(this)
        Log.d(TAG, "KVM Accessibility Service Connected")
        // O serviço liga sozinho junto com o tablet: aproveita para conectar ao último PC
        MwbController.autoStart(this, "serviço de controle iniciado")

        mainHandler.post {
            initCursorOverlay()
            initClipboardListener()
        }
    }

    private fun initClipboardListener() {
        try {
            val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                if (!syncClipboardEnabled) return@OnPrimaryClipChangedListener
                try {
                    val clipData = clipboardManager.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString()
                        if (!text.isNullOrEmpty() && text != lastFromPc) {
                            MwbController.sendClipboardText(text)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao ler área de transferência: ${e.localizedMessage}")
                }
            }
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar listener de clipboard: ${e.localizedMessage}")
        }
    }

    /**
     * Define o texto da área de transferência do Android na UI thread (colar texto vindo do PC)
     */
    fun setSystemClipboard(text: String, fromPc: Boolean = false) {
        lastFromPc = text
        mainHandler.post {
            try {
                val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Mouse Without Borders for Android by Brendler©", text)
                clipboardManager.setPrimaryClip(clip)
                if (fromPc) android.widget.Toast.makeText(this, "Texto copiado do PC", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao definir clipboard: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Lê o texto atual da área de transferência do Android
     */
    fun getSystemClipboard(): String? {
        return try {
            val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cola o texto da área de transferência diretamente na UI thread no campo focado (Ctrl + V)
     */
    fun pasteClipboardText() {
        mainHandler.post {
            try {
                val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboardManager.primaryClip
                var text: String? = null
                if (clip != null && clip.itemCount > 0) {
                    text = clip.getItemAt(0).text?.toString()
                }

                // Em segundo plano o Android pode esconder a área de transferência: usa o último texto do PC
                if (text.isNullOrEmpty()) text = MwbController.lastFromPc

                if (!text.isNullOrEmpty()) {
                    appendInputText(text)
                } else {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?: findFocusedEditableNode(root)
                        focusNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao colar texto: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Recorta o texto do campo focado, envia para o clipboard e apaga do campo (Ctrl + X)
     */
    fun cutInputText() {
        mainHandler.post {
            val root = rootInActiveWindow ?: return@post
            val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFocusedEditableNode(root)
                ?: return@post

            try {
                if (!focusNode.isEditable) return@post
                val currentText = activeInputBuffer.toString().ifEmpty { focusNode.text?.toString() ?: "" }
                if (currentText.isNotEmpty()) {
                    setSystemClipboard(currentText)
                    MwbController.sendClipboardText(currentText)

                    activeInputBuffer.clear()
                    activeCursorPos = 0

                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }
                    focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao recortar texto: ${e.localizedMessage}")
            } finally {
                @Suppress("DEPRECATION")
                focusNode.recycle()
            }
        }
    }

    /**
     * Ctrl + C: copia o trecho selecionado no campo de texto e devolve o texto
     */
    fun copySelectedText(): String? {
        val root = rootInActiveWindow ?: return getSystemClipboard()
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditableNode(root)

        if (node != null) {
            try {
                val text = node.text?.toString() ?: ""
                val a = node.textSelectionStart
                val b = node.textSelectionEnd
                if (a in 0..text.length && b in 0..text.length && a != b) {
                    val selected = text.substring(minOf(a, b), maxOf(a, b))
                    setSystemClipboard(selected)
                    return selected
                }
                node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            } catch (e: Exception) {
                // Fallback
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        return getSystemClipboard()
    }

    private fun initCursorOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            cursorImageView = ImageView(this).apply {
                setImageResource(R.drawable.ic_mouse_pointer)
                visibility = View.GONE
            }

            cursorParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = 0
            }

            windowManager?.addView(cursorImageView, cursorParams)
            isCursorAdded = true
            Log.d(TAG, "Cursor overlay criado com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar cursor overlay: ${e.localizedMessage}", e)
        }
    }

    /**
     * Atualiza a posição do ponteiro do mouse na tela em tempo real adaptando às dimensões ativas
     */
    fun updateCursor(x: Float, y: Float) {
        mainHandler.post {
            try {
                val params = cursorParams ?: return@post
                val view = cursorImageView ?: return@post
                val wm = windowManager ?: return@post

                val displayWidth: Int
                val displayHeight: Int

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    displayWidth = bounds.width()
                    displayHeight = bounds.height()
                } else {
                    @Suppress("DEPRECATION")
                    val dm = resources.displayMetrics
                    displayWidth = dm.widthPixels
                    displayHeight = dm.heightPixels
                }

                val clampedX = x.coerceIn(0f, (displayWidth - 1).toFloat()).toInt()
                val clampedY = y.coerceIn(0f, (displayHeight - 1).toFloat()).toInt()

                params.gravity = Gravity.TOP or Gravity.LEFT
                params.x = clampedX
                params.y = clampedY

                if (view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                }

                if (isCursorAdded) {
                    wm.updateViewLayout(view, params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar cursor: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Acorda/Liga a tela do Android quando o mouse entra no dispositivo
     */
    fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                    "KvmClient:WakeUpLock"
                )
                wakeLock.acquire(3000)
                Log.d(TAG, "Tela acendida pelo mouse.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao acordar tela: ${e.localizedMessage}")
        }
    }

    /**
     * Esconde o ponteiro do mouse quando o controle volta para o PC
     */
    fun hideCursor() {
        mainHandler.post {
            try {
                cursorImageView?.visibility = View.GONE
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun clearInputBuffer() {
        activeInputBuffer.clear()
        activeCursorPos = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Sem lógica de evento necessária
    }

    override fun onInterrupt() {
        Log.d(TAG, "KVM Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.post {
            if (isCursorAdded && cursorImageView != null) {
                try {
                    windowManager?.removeView(cursorImageView)
                } catch (e: Exception) {
                    // Ignore
                }
                isCursorAdded = false
            }
        }
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "KVM Accessibility Service Destroyed")
    }

    /**
     * Simula um clique/toque único em (x, y) e reseta o buffer de entrada
     */
    fun clickAt(x: Float, y: Float) {
        clearInputBuffer()
        updateCursor(x, y)
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Simula clique longo em (x, y)
     */
    fun longClickAt(x: Float, y: Float) {
        clearInputBuffer()
        updateCursor(x, y)
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 600)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Simula arrasto/gesto de deslize de (startX, startY) para (endX, endY)
     */
    fun drag(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 200) {
        updateCursor(endX, endY)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Simula a roda do mouse (scroll)
     */
    fun scroll(x: Float, y: Float, deltaY: Float) {
        updateCursor(x, y)
        val directionMultiplier = if (invertScroll) 1f else -1f
        val scrollDistance = if (deltaY > 0) 300f * directionMultiplier else -300f * directionMultiplier
        val endY = (y + scrollDistance).coerceIn(0f, 4000f)
        drag(x, y, x, endY, 150)
    }

    /**
     * Pressiona o botão Voltar do Android
     */
    fun pressBack() {
        clearInputBuffer()
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Pressiona o botão Home do Android
     */
    fun pressHome() {
        clearInputBuffer()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun findFocusedEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findFocusedEditableNode(child)
            if (result != null) return result
        }

        if (node.isEditable) return node
        return null
    }

    private fun isCommonHintText(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("pergunte") ||
                lower.contains("pesquis") ||
                lower.contains("digite") ||
                lower.contains("search") ||
                lower.contains("type") ||
                lower.contains("google")
    }

    /**
     * Insere/anexa texto mantendo o buffer e cursor em tempo real
     */
    fun appendInputText(textToInsert: String) {
        val root = rootInActiveWindow ?: return
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditableNode(root)
            ?: return

        try {
            if (!focusNode.isEditable) return

            if (activeInputBuffer.isEmpty()) {
                val nodeText = focusNode.text?.toString() ?: ""
                val hintText = focusNode.hintText?.toString() ?: ""
                if (nodeText.isNotEmpty() && !nodeText.equals(hintText, ignoreCase = true) && !isCommonHintText(nodeText)) {
                    activeInputBuffer.append(nodeText)
                    activeCursorPos = nodeText.length
                }
            }

            if (activeCursorPos in 0..activeInputBuffer.length) {
                activeInputBuffer.insert(activeCursorPos, textToInsert)
                activeCursorPos += textToInsert.length
            } else {
                activeInputBuffer.append(textToInsert)
                activeCursorPos = activeInputBuffer.length
            }

            val updatedText = activeInputBuffer.toString()

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updatedText)
            }
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, activeCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, activeCursorPos)
            }
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inserir texto: ${e.localizedMessage}")
        } finally {
            @Suppress("DEPRECATION")
            focusNode.recycle()
        }
    }

    /**
     * Apaga 1 caractere com exatamente 1 toque de Backspace
     */
    fun deleteInputText() {
        val root = rootInActiveWindow ?: return
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditableNode(root)
            ?: return

        try {
            if (!focusNode.isEditable) return

            if (activeInputBuffer.isEmpty()) {
                val nodeText = focusNode.text?.toString() ?: ""
                val hintText = focusNode.hintText?.toString() ?: ""
                if (nodeText.isNotEmpty() && !nodeText.equals(hintText, ignoreCase = true) && !isCommonHintText(nodeText)) {
                    activeInputBuffer.append(nodeText)
                    activeCursorPos = nodeText.length
                }
            }

            if (activeInputBuffer.isNotEmpty() && activeCursorPos > 0) {
                activeInputBuffer.deleteAt(activeCursorPos - 1)
                activeCursorPos--
            }

            val updatedText = activeInputBuffer.toString()

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updatedText)
            }
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, activeCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, activeCursorPos)
            }
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao apagar texto: ${e.localizedMessage}")
        } finally {
            @Suppress("DEPRECATION")
            focusNode.recycle()
        }
    }

    /**
     * Apaga o caractere após o cursor (Delete key)
     */
    fun deleteForwardInputText() {
        deleteInputText()
    }

    /**
     * Move o cursor 1 posição para a esquerda com 1 toque na Seta Esquerda
     */
    fun moveCursorLeft() {
        val root = rootInActiveWindow ?: return
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditableNode(root)
            ?: return

        try {
            if (!focusNode.isEditable) return

            if (activeInputBuffer.isEmpty()) {
                val nodeText = focusNode.text?.toString() ?: ""
                val hintText = focusNode.hintText?.toString() ?: ""
                if (nodeText.isNotEmpty() && !nodeText.equals(hintText, ignoreCase = true) && !isCommonHintText(nodeText)) {
                    activeInputBuffer.append(nodeText)
                    activeCursorPos = focusNode.textSelectionStart.coerceIn(0, nodeText.length)
                }
            }

            if (activeCursorPos > 0) {
                activeCursorPos--
            }

            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, activeCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, activeCursorPos)
            }
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao mover cursor para esquerda: ${e.localizedMessage}")
        } finally {
            @Suppress("DEPRECATION")
            focusNode.recycle()
        }
    }

    /**
     * Move o cursor 1 posição para a direita com 1 toque na Seta Direita
     */
    fun moveCursorRight() {
        val root = rootInActiveWindow ?: return
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditableNode(root)
            ?: return

        try {
            if (!focusNode.isEditable) return

            if (activeInputBuffer.isEmpty()) {
                val nodeText = focusNode.text?.toString() ?: ""
                val hintText = focusNode.hintText?.toString() ?: ""
                if (nodeText.isNotEmpty() && !nodeText.equals(hintText, ignoreCase = true) && !isCommonHintText(nodeText)) {
                    activeInputBuffer.append(nodeText)
                    activeCursorPos = focusNode.textSelectionStart.coerceIn(0, nodeText.length)
                }
            }

            if (activeCursorPos < activeInputBuffer.length) {
                activeCursorPos++
            }

            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, activeCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, activeCursorPos)
            }
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao mover cursor para direita: ${e.localizedMessage}")
        } finally {
            @Suppress("DEPRECATION")
            focusNode.recycle()
        }
    }
}
