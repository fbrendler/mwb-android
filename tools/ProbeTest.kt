import com.brendler.mwb.protocol.*

// Teste do MwbProbe contra tools/fakepc.py: args = porta chave
fun main(args: Array<String>) {
    val magic = MwbCrypto.magicNumber(args[1])
    val r = MwbProbe.identify("127.0.0.1", args[0].toInt(), args[1], magic)
    println("PROBE name=${r?.name} format=${r?.format}")
    val wrong = MwbProbe.identify("127.0.0.1", args[0].toInt(), "ChaveErrada12345", MwbCrypto.magicNumber("ChaveErrada12345"))
    println("PROBE wrongkey=${wrong?.name}")
}
