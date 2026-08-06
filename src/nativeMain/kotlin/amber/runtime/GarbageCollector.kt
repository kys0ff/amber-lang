package amber.runtime

sealed interface GarbageCollector {
    val name: String
    val header: String
    val initCall: String
    val libraryName: String
    val staticLibrary: String

    fun malloc(size: String): String
    fun mallocAtomic(size: String): String
    fun registerRoot(pointer: String): String
}

object BoehmGC : GarbageCollector {
    override val name = "Boehm GC"
    override val header = "#include <gc.h>"
    override val initCall = "GC_INIT();"
    override val libraryName = "gc"
    override val staticLibrary = "libgc.a"

    override fun malloc(size: String) = "GC_MALLOC($size)"
    override fun mallocAtomic(size: String) = "GC_MALLOC_ATOMIC($size)"
    override fun registerRoot(pointer: String) = "GC_add_roots($pointer, $pointer + 1)"
}

object NoGC : GarbageCollector {
    override val name = "None"
    override val header = ""
    override val initCall = ""
    override val libraryName = ""
    override val staticLibrary = ""

    override fun malloc(size: String) = "malloc($size)"
    override fun mallocAtomic(size: String) = "malloc($size)"
    override fun registerRoot(pointer: String) = ""
}
