package amber.runtime

sealed interface GarbageCollector {
    val name: String
    val header: String
    val initCall: String
}

object BoehmGC : GarbageCollector {
    override val name = "Boehm GC"
    override val header = "#include <gc.h>"
    override val initCall = "GC_INIT();"
}

object NoGC : GarbageCollector {
    override val name = "None"
    override val header = ""
    override val initCall = ""
}
