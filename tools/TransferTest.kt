import com.brendler.mwb.protocol.*
import java.io.File

// Teste do MwbTransfer contra tools/fakeclip.py: args = porta chave modo [arquivo]
fun main(args: Array<String>) {
    val t = MwbTransfer("127.0.0.1", args[0].toInt(), args[1], WireFormat.NOVO, 4242, "POCOPAD")
    when (args[2]) {
        "push" -> {
            val f = File(args[3])
            t.pushFile(f.name, f.length(), f.inputStream())
            println("PUSHED ${f.length()} sha=" + sha(f.readBytes()))
        }
        "pushtext" -> { t.pushText("Texto do Android ç ã"); println("PUSHED text") }
        else -> {
            val out = File("/tmp/claude-0/-home-claude/53ef80c1-dff6-513b-9b85-1fd1a8fbe922/scratchpad/recv.bin")
            val r = t.pull({ name, size -> println("SINK $name $size"); out.outputStream() })
            println("PULLED kind=${r.kind} name=${r.name} size=${r.size} got=${r.receivedBytes} complete=${r.complete}")
            when (r.kind) {
                MwbTransfer.Kind.TEXT -> { val txt = MwbClipboard.decode(r.bytes!!); println("TEXT len=${txt.length} end=${txt.takeLast(8)}") }
                MwbTransfer.Kind.IMAGE -> println("IMAGE sha=" + sha(r.bytes!!))
                MwbTransfer.Kind.FILE -> println("FILE sha=" + sha(out.readBytes()))
                else -> {}
            }
        }
    }
}

fun sha(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(16)
