package com.brendler.mwb

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.brendler.mwb.protocol.MwbIds
import com.brendler.mwb.protocol.WinMsg
import kotlin.math.abs

/**
 * Converte os eventos do Mouse Without Borders (mouse e teclado do Windows) em ações no Android,
 * usando o serviço de acessibilidade. A lógica de mouse/teclado vem do app BRENDLER anterior.
 */
class RemoteInput(
    /** Pede ao PC para trazer o mouse de volta (x, y de 0 a 65535 na tela do PC). */
    private val switchBack: (Int, Int) -> Boolean = { _, _ -> false },
    private val log: (String) -> Unit = {}
) {

    private var lastX = 0f
    private var lastY = 0f
    private var isMouseDown = false
    private var mouseDownStartX = 0f
    private var mouseDownStartY = 0f

    private var isShiftDown = false
    private var isCapsLock = false
    private var isCtrlDown = false
    private var pendingAccent: String? = null

    @Volatile
    private var hasMouseEntered = false

    // Devolução do mouse ao PC: o caminho de volta é a borda por onde o mouse entrou.
    private enum class Edge { LEFT, RIGHT, TOP, BOTTOM }
    private var entryEdge = Edge.LEFT
    private var armed = false
    private var pinnedCount = 0
    private var lastSwitchTime = 0L
    private var lastModeRelative: Boolean? = null

    private fun edgeName(e: Edge): String = when (e) {
        Edge.LEFT -> "esquerda"
        Edge.RIGHT -> "direita"
        Edge.TOP -> "superior"
        Edge.BOTTOM -> "inferior"
    }

    private fun distanceToEdge(edge: Edge, w: Int, h: Int): Float = when (edge) {
        Edge.LEFT -> lastX
        Edge.RIGHT -> (w - 1) - lastX
        Edge.TOP -> lastY
        Edge.BOTTOM -> (h - 1) - lastY
    }

    private fun screenSize(service: KvmAccessibilityService): Pair<Int, Int> {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * Evento de mouse do MWB. x e y vêm de 0 a 65535 (proporção da tela do Android),
     * ou como deslocamento relativo (valores a partir de 100000) quando o PowerToys usa o modo relativo.
     */
    @Synchronized
    fun onMouse(rawX: Int, rawY: Int, wheel: Int, flags: Int) {
        val service = KvmAccessibilityService.instance
        if (service == null) {
            // Sem o serviço de acessibilidade o Android não consegue mostrar o cursor nem clicar.
            // Em vez de deixar o mouse "preso" aqui, devolvemos na hora para o PC.
            returnBecauseServiceOff()
            return
        }
        serviceOffWarned = false
        val (w, h) = screenSize(service)
        if (w <= 0 || h <= 0) return

        val relative = abs(rawX) >= MwbIds.MOVE_MOUSE_RELATIVE && abs(rawY) >= MwbIds.MOVE_MOUSE_RELATIVE
        if (relative) {
            if (flags == WinMsg.MOUSEMOVE) {
                val dx = if (rawX < 0) rawX + MwbIds.MOVE_MOUSE_RELATIVE else rawX - MwbIds.MOVE_MOUSE_RELATIVE
                val dy = if (rawY < 0) rawY + MwbIds.MOVE_MOUSE_RELATIVE else rawY - MwbIds.MOVE_MOUSE_RELATIVE
                lastX = (lastX + dx).coerceIn(0f, (w - 1).toFloat())
                lastY = (lastY + dy).coerceIn(0f, (h - 1).toFloat())
            }
        } else {
            lastX = (rawX / 65535f * w).coerceIn(0f, (w - 1).toFloat())
            lastY = (rawY / 65535f * h).coerceIn(0f, (h - 1).toFloat())
        }

        if (lastModeRelative != relative && flags == WinMsg.MOUSEMOVE) {
            lastModeRelative = relative
            log(if (relative) "Mouse do PC em modo relativo." else "Mouse do PC em modo absoluto.")
        }

        // Logo depois de devolver o mouse, o PC ainda pode mandar alguns pacotes atrasados:
        // ignorá-los evita o cursor "piscar" no Android e uma entrada falsa.
        if (!hasMouseEntered && System.currentTimeMillis() - lastSwitchTime < 400) return

        if (!hasMouseEntered) {
            hasMouseEntered = true
            service.wakeUpScreen()
            val dl = lastX
            val dr = (w - 1) - lastX
            val dt = lastY
            val db = (h - 1) - lastY
            val min = minOf(minOf(dl, dr), minOf(dt, db))
            entryEdge = when (min) {
                dl -> Edge.LEFT
                dr -> Edge.RIGHT
                dt -> Edge.TOP
                else -> Edge.BOTTOM
            }
            armed = false
            pinnedCount = 0
            log("Mouse entrou no Android pela borda ${edgeName(entryEdge)}.")
        }
        service.updateCursor(lastX, lastY)

        if (flags == WinMsg.MOUSEMOVE) checkReturnToPc(w, h, relative)

        when (flags) {
            WinMsg.LBUTTONDOWN -> {
                mouseDownStartX = lastX
                mouseDownStartY = lastY
                isMouseDown = true
            }
            WinMsg.LBUTTONUP -> {
                if (isMouseDown) {
                    isMouseDown = false
                    val dx = lastX - mouseDownStartX
                    val dy = lastY - mouseDownStartY
                    if (dx * dx + dy * dy < 100) {
                        service.clickAt(lastX, lastY)
                    } else {
                        service.drag(mouseDownStartX, mouseDownStartY, lastX, lastY)
                    }
                } else {
                    service.clickAt(lastX, lastY)
                }
            }
            WinMsg.RBUTTONDOWN -> service.longClickAt(lastX, lastY)
            WinMsg.MBUTTONDOWN -> service.pressBack()
            WinMsg.MOUSEWHEEL -> if (wheel != 0) service.scroll(lastX, lastY, wheel.toFloat())
        }
    }

    /**
     * No modo relativo, quem devolve o mouse é este aparelho (como no MWB original).
     * No modo absoluto o PC faz isso sozinho; aqui só entramos se o cursor ficar preso na borda de volta.
     */
    private fun checkReturnToPc(w: Int, h: Int, relative: Boolean) {
        val d = distanceToEdge(entryEdge, w, h)
        if (!armed) {
            if (d > 60f) armed = true // só vale depois de se afastar da borda de entrada
            return
        }
        if (d > 1f) {
            pinnedCount = 0
            return
        }
        pinnedCount++
        if (!relative && pinnedCount < 5) return

        val now = System.currentTimeMillis()
        if (now - lastSwitchTime < 700) return
        lastSwitchTime = now

        // posição de chegada na tela do PC: junto da borda oposta, na mesma altura (ou largura)
        val x: Int
        val y: Int
        when (entryEdge) {
            Edge.LEFT -> { x = 65535 - 40; y = (lastY / h * 65535).toInt() }
            Edge.RIGHT -> { x = 40; y = (lastY / h * 65535).toInt() }
            Edge.TOP -> { x = (lastX / w * 65535).toInt(); y = 65535 - 40 }
            Edge.BOTTOM -> { x = (lastX / w * 65535).toInt(); y = 40 }
        }
        if (switchBack(x.coerceIn(0, 65535), y.coerceIn(0, 65535))) {
            log(if (relative) "Borda alcançada: devolvendo o mouse ao PC." else "O PC não devolveu sozinho; pedindo a troca de máquina.")
            hasMouseEntered = false
            armed = false
            pinnedCount = 0
            isMouseDown = false
            KvmAccessibilityService.instance?.hideCursor()
        }
    }

    private var serviceOffWarned = false
    private var lastServiceOffReturn = 0L

    private fun returnBecauseServiceOff() {
        val now = System.currentTimeMillis()
        if (now - lastServiceOffReturn < 500) return
        lastServiceOffReturn = now
        // O PC fica à esquerda ou à direita; voltar no meio da altura, perto da borda direita do PC
        val ok = switchBack(65535 - 40, 32768)
        if (!serviceOffWarned) {
            serviceOffWarned = true
            log(
                "O mouse chegou, mas o serviço de acessibilidade está DESLIGADO. " +
                    (if (ok) "Mouse devolvido ao PC. " else "Não consegui devolver o mouse ao PC. ") +
                    "Abra o app e toque em Ativar (depois de reinstalar, o Android desliga o serviço)."
            )
        }
    }

    /** Tecla do Windows (virtual-key). Em flags, o bit 0x80 indica que a tecla foi solta. */
    @Synchronized
    fun onKey(vk: Int, flags: Int) {
        val service = KvmAccessibilityService.instance
        val isDown = (flags and WinMsg.LLKHF_UP) == 0
        try {
            // Gerenciamento de Shift, CapsLock e Ctrl
            if (vk == 16 || vk == 160 || vk == 161) {
                isShiftDown = isDown
            } else if (vk == 17 || vk == 162 || vk == 163) {
                isCtrlDown = isDown
            } else if (vk == 20 && isDown) {
                isCapsLock = !isCapsLock
            }

            if (!isDown) return

            if (isCtrlDown) {
                when (vk) {
                    67 -> { // Ctrl + C
                        val clipText = service?.copySelectedText() ?: service?.getSystemClipboard()
                        if (!clipText.isNullOrEmpty()) {
                            MwbController.sendClipboardText(clipText)
                        }
                    }
                    86 -> service?.pasteClipboardText() // Ctrl + V
                    88 -> service?.cutInputText() // Ctrl + X
                }
            } else {
                when (vk) {
                    0x08 -> service?.deleteInputText() // Backspace
                    0x2E -> service?.deleteForwardInputText() // Delete
                    0x0D -> service?.appendInputText("\n") // Enter
                    0x09 -> service?.appendInputText("\t") // Tab
                    0x1B -> service?.pressBack() // Escape
                    0x24 -> service?.pressHome() // Home
                    0x25 -> service?.moveCursorLeft() // Seta Esquerda
                    0x27 -> service?.moveCursorRight() // Seta Direita
                    0x26 -> service?.moveCursorLeft() // Seta Cima
                    0x28 -> service?.moveCursorRight() // Seta Baixo
                    else -> {
                        val charStr = getCharFromVk(vk, isShiftDown, isCapsLock)
                        if (charStr != null) {
                            service?.appendInputText(charStr)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteInput", "Erro ao processar tecla vk=$vk", e)
        }
    }

    /** O mouse do PC saiu deste aparelho (ou a conexão caiu). */
    @Synchronized
    fun onLeave() {
        isMouseDown = false
        hasMouseEntered = false
        isShiftDown = false
        isCtrlDown = false
        pendingAccent = null
        armed = false
        pinnedCount = 0
        KvmAccessibilityService.instance?.hideCursor()
    }

    private fun processAccentComposition(rawChar: String): String {
        val accent = pendingAccent ?: return rawChar
        pendingAccent = null

        return when (accent) {
            "~" -> when (rawChar) {
                "a" -> "ã"
                "A" -> "Ã"
                "o" -> "õ"
                "O" -> "Õ"
                "e" -> "ẽ"
                "E" -> "Ẽ"
                "u" -> "ũ"
                "U" -> "Ũ"
                "i" -> "ĩ"
                "I" -> "Ĩ"
                "n" -> "ñ"
                "N" -> "Ñ"
                " " -> "~"
                else -> "~$rawChar"
            }
            "^" -> when (rawChar) {
                "a" -> "â"
                "A" -> "Â"
                "e" -> "ê"
                "E" -> "Ê"
                "i" -> "î"
                "I" -> "Î"
                "o" -> "ô"
                "O" -> "Ô"
                "u" -> "û"
                "U" -> "Û"
                " " -> "^"
                else -> "^$rawChar"
            }
            "´" -> when (rawChar) {
                "a" -> "á"
                "A" -> "Á"
                "e" -> "é"
                "E" -> "É"
                "i" -> "í"
                "I" -> "Í"
                "o" -> "ó"
                "O" -> "Ó"
                "u" -> "ú"
                "U" -> "Ú"
                "c" -> "ç"
                "C" -> "Ç"
                " " -> "´"
                else -> "´$rawChar"
            }
            "`" -> when (rawChar) {
                "a" -> "à"
                "A" -> "À"
                "e" -> "è"
                "E" -> "È"
                "i" -> "ì"
                "I" -> "Ì"
                "o" -> "ò"
                "O" -> "Ò"
                "u" -> "ù"
                "U" -> "Ù"
                " " -> "`"
                else -> "`$rawChar"
            }
            else -> rawChar
        }
    }

    private fun getCharFromVk(vk: Int, isShift: Boolean, isCaps: Boolean): String? {
        // Tecla Ç no ABNT2 (VK = 186 / 0xBA)
        if (vk == 186) {
            val upper = isShift xor isCaps
            return processAccentComposition(if (upper) "Ç" else "ç")
        }

        // Teclas de acento do ABNT2 (códigos do Windows, layout "Português (Brasil ABNT2)"):
        //   VK 222 (0xDE, VK_OEM_7), ao lado do Ç:  ~ e, com Shift, ^
        //   VK 219 (0xDB, VK_OEM_4), ao lado do P:  ´ e, com Shift, `
        // São teclas "mortas": o acento espera a próxima letra (ã, â, á, à).
        if (vk == 222 || vk == 219) {
            val nextAccent = if (vk == 222) {
                if (isShift) "^" else "~"
            } else {
                if (isShift) "`" else "´"
            }
            if (pendingAccent != null) {
                return processAccentComposition(nextAccent)
            }
            pendingAccent = nextAccent
            return null
        }

        // Tecla ' e " (VK 192 / 0xC0, à esquerda do 1)
        if (vk == 192) {
            return processAccentComposition(if (isShift) "\"" else "'")
        }

        // Tecla \ e | (VK 226 / 0xE2, à esquerda do Z)
        if (vk == 226) {
            return processAccentComposition(if (isShift) "|" else "\\")
        }

        // Tecla / e ? no ABNT2 (VK = 193)
        if (vk == 193) {
            return processAccentComposition(if (isShift) "?" else "/")
        }

        // Tecla . do teclado numérico ABNT2 (VK = 194)
        if (vk == 194) {
            return processAccentComposition(".")
        }

        val rawChar = when (vk) {
            32 -> " " // Espaço
            in 65..90 -> { // Letras A-Z
                val upper = isShift xor isCaps
                val c = vk.toChar()
                if (upper) c.toString() else c.lowercaseChar().toString()
            }
            in 48..57 -> { // Números 0-9
                if (isShift) {
                    when (vk) {
                        48 -> ")"
                        49 -> "!"
                        50 -> "@"
                        51 -> "#"
                        52 -> "$"
                        53 -> "%"
                        54 -> "¨" // Trema ABNT2
                        55 -> "&"
                        56 -> "*"
                        57 -> "("
                        else -> ""
                    }
                } else {
                    (vk - 48).toString()
                }
            }
            in 96..105 -> (vk - 96).toString() // Teclado numérico 0-9
            106 -> "*"
            107 -> "+"
            109 -> "-"
            110 -> "."
            111 -> "/"
            187 -> if (isShift) "+" else "="
            188 -> if (isShift) "<" else ","
            189 -> if (isShift) "_" else "-"
            190 -> if (isShift) ">" else "."
            191 -> if (isShift) ":" else ";"
            220 -> if (isShift) "}" else "]"
            221 -> if (isShift) "{" else "["
            else -> null
        } ?: return null

        return processAccentComposition(rawChar)
    }
}
