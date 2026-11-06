package com.brendler.mwb.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Formato dos pacotes do Mouse Without Borders (PowerToys).
 * Baseado no código aberto: src/modules/MouseWithoutBorders/App/Core (DATA.cs, PackageType.cs).
 *
 * Pacote pequeno = 32 bytes, pacote grande = 64 bytes. Tudo em little-endian.
 *  0: tipo (1 byte) | 1: checksum | 2-3: número mágico (derivado da chave)
 *  4: id (int) | 8: origem (int) | 12: destino (int)
 * 16..: dados (mouse, teclado ou máquinas), 32..63: nome da máquina (ASCII, 32 caracteres)
 */
object PackageType {
    const val INVALID = 0xFF
    const val ERROR = 0xFE
    const val HI = 2
    const val HELLO = 3
    const val BYE_BYE = 4
    const val HEARTBEAT = 20
    const val AWAKE = 21
    const val HIDE_MOUSE = 50
    const val HEARTBEAT_EX = 51
    const val HEARTBEAT_EX_L2 = 52
    const val HEARTBEAT_EX_L3 = 53
    const val CLIPBOARD = 69
    const val CLIPBOARD_DRAG_DROP = 70
    const val CLIPBOARD_DRAG_DROP_END = 71
    const val EXPLORER_DRAG_DROP = 72
    const val CLIPBOARD_CAPTURE = 73
    const val CAPTURE_SCREEN_COMMAND = 74
    const val CLIPBOARD_DRAG_DROP_OPERATION = 75
    const val CLIPBOARD_DATA_END = 76
    const val MACHINE_SWITCHED = 77
    const val CLIPBOARD_ASK = 78
    const val CLIPBOARD_PUSH = 79
    const val NEXT_MACHINE = 121
    const val KEYBOARD = 122
    const val MOUSE = 123
    const val CLIPBOARD_TEXT = 124
    const val CLIPBOARD_IMAGE = 125
    const val HANDSHAKE = 126
    const val HANDSHAKE_ACK = 127
    const val MATRIX = 128

    fun isBig(type: Int): Boolean = when (type) {
        HELLO, AWAKE, HEARTBEAT, HEARTBEAT_EX, HANDSHAKE, HANDSHAKE_ACK, CLIPBOARD_PUSH,
        CLIPBOARD, CLIPBOARD_ASK, CLIPBOARD_IMAGE, CLIPBOARD_TEXT, CLIPBOARD_DATA_END -> true
        else -> (type and MATRIX) == MATRIX
    }
}

/** Mensagens do Windows usadas nos pacotes de mouse (campo dwFlags). */
object WinMsg {
    const val MOUSEMOVE = 0x200
    const val LBUTTONDOWN = 0x201
    const val LBUTTONUP = 0x202
    const val RBUTTONDOWN = 0x204
    const val RBUTTONUP = 0x205
    const val MBUTTONDOWN = 0x207
    const val MBUTTONUP = 0x208
    const val MOUSEWHEEL = 0x20A
    const val XBUTTONDOWN = 0x20B
    const val XBUTTONUP = 0x20C

    /** Bit 0x80 no campo dwFlags do teclado = tecla solta. */
    const val LLKHF_UP = 0x80
    const val LLKHF_EXTENDED = 0x01
}

object MwbIds {
    const val NONE = 0
    const val ALL = 255

    /** Deslocamento que o MWB usa para indicar movimento relativo do mouse. */
    const val MOVE_MOUSE_RELATIVE = 100000
}

class MwbPacket(val raw: ByteArray) {
    private val bb: ByteBuffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

    val isBig: Boolean get() = raw.size >= 64

    var type: Int
        get() = raw[0].toInt() and 0xFF
        set(v) { raw[0] = v.toByte() }

    var id: Int
        get() = bb.getInt(4)
        set(v) { bb.putInt(4, v) }

    var src: Int
        get() = bb.getInt(8)
        set(v) { bb.putInt(8, v) }

    var des: Int
        get() = bb.getInt(12)
        set(v) { bb.putInt(12, v) }

    // Mouse
    var mouseX: Int
        get() = bb.getInt(16)
        set(v) { bb.putInt(16, v) }
    var mouseY: Int
        get() = bb.getInt(20)
        set(v) { bb.putInt(20, v) }
    var wheelDelta: Int
        get() = bb.getInt(24)
        set(v) { bb.putInt(24, v) }
    var mouseFlags: Int
        get() = bb.getInt(28)
        set(v) { bb.putInt(28, v) }

    // Teclado (wVk em 24, dwFlags em 28)
    var vk: Int
        get() = bb.getInt(24)
        set(v) { bb.putInt(24, v) }
    var keyFlags: Int
        get() = bb.getInt(28)
        set(v) { bb.putInt(28, v) }

    /** Campos Machine1..Machine4 (offsets 16, 20, 24, 28), usados no handshake e na matriz. */
    fun machine(index: Int): Int = bb.getInt(16 + 4 * (index - 1))
    fun setMachine(index: Int, value: Int) { bb.putInt(16 + 4 * (index - 1), value) }

    var machineName: String
        get() {
            if (raw.size < 64) return ""
            val s = String(raw, 32, 32, Charsets.US_ASCII)
            return s.trim()
        }
        set(v) {
            if (raw.size < 64) return
            val padded = v.take(32).padEnd(32, ' ')
            val b = padded.toByteArray(Charsets.US_ASCII)
            System.arraycopy(b, 0, raw, 32, 32)
        }

    companion object {
        fun create(type: Int): MwbPacket {
            val p = MwbPacket(ByteArray(if (PackageType.isBig(type)) 64 else 32))
            p.type = type
            return p
        }
    }
}
