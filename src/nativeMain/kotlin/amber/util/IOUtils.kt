package amber.util

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
fun readFile(path: String): String {
    val file = fopen(path, "r") ?: return ""

    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        rewind(file)

        return memScoped {
            val buffer = allocArray<ByteVar>(size + 1)
            fread(buffer, 1u,size.toULong(), file)
            buffer[size] = 0
            buffer.toKString()
        }
    } finally {
        fclose(file)
    }
}