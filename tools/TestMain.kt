import com.brendler.mwb.protocol.*

fun main(args: Array<String>) {
    val fmts = args[3].split(",").map { WireFormat.valueOf(it) }
    val done = java.util.concurrent.CountDownLatch(1)
    val holder = arrayOfNulls<MwbSession>(1)
    val listener = object : MwbListener {
        override fun onStatus(connected: Boolean, text: String) { println("STATUS connected=$connected $text") }
        override fun onLog(message: String) { println("LOG $message") }
        override fun onMouse(x: Int, y: Int, wheel: Int, flags: Int) { println("MOUSE x=$x y=$y wheel=$wheel flags=0x${flags.toString(16)}") }
        override fun onKey(vk: Int, flags: Int) { println("KEY vk=$vk flags=0x${flags.toString(16)}") }
        override fun onClipboardText(text: String) { println("CLIP $text"); Thread { holder[0]!!.sendClipboardText("Resposta do Android: ç ã ^") }.start() }
        override fun onHideMouse() { println("HIDE"); Thread { Thread.sleep(200); println("SWITCHBACK=" + holder[0]!!.requestSwitchBack(65495, 30000)) }.start() }
    }
    val s = MwbSession("127.0.0.1", args[0].toInt(), args[1], args[2], 4242, fmts, listener)
    holder[0] = s
    s.start()
    Thread.sleep(args.getOrNull(4)?.toLong() ?: 6000)
    s.stop()
    println("WORKING=" + s.workingFormat)
}
