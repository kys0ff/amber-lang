package off.kys.amber_lang.utils

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.readlink
import platform.posix.stat

fun getPathParent(path: String): String? {
    val lastSeparator = path.lastIndexOf('/')
    if (lastSeparator == -1) {
        return null // No parent directory
    }
    return path.substring(0, lastSeparator)
}

fun joinPaths(base: String, vararg parts: String): String {
    var result = base.trimEnd('/')
    for (part in parts) {
        result += "/" + part.trimStart('/').trimEnd('/')
    }
    return result
}

fun normalizePath(path: String): String {
    val components = path.split('/').filter { it.isNotEmpty() }
    val stack = mutableListOf<String>()

    for (component in components) {
        when (component) {
            ".", "" -> { /* ignore */ }
            ".." -> {
                if (stack.isNotEmpty()) {
                    stack.removeAt(stack.size - 1)
                }
            }
            else -> stack.add(component)
        }
    }
    val normalized = stack.joinToString("/")
    return if (path.startsWith("/")) "/$normalized" else normalized
}

@OptIn(ExperimentalForeignApi::class)
fun fileExists(path: String): Boolean {
    val file = fopen(path, "r")
    if (file != null) {
        fclose(file)
        return true
    }
    return false
}

@OptIn(ExperimentalForeignApi::class)
fun isDirectory(path: String): Boolean {
    memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) == 0) {
            return (st.st_mode.toInt() and S_IFMT) == S_IFDIR
        }
    }
    return false
}

@OptIn(ExperimentalForeignApi::class)
fun getExecutablePath(): String {
    memScoped {
        val bufferSize = 4096
        val buffer = allocArray<ByteVar>(bufferSize)
        val size = readlink("/proc/self/exe", buffer, (bufferSize - 1).toULong())
        if (size != (-1).toLong()) {
            buffer[size.toInt()] = 0
            return buffer.toKString()
        }
    }
    return ""
}

fun getExecutableDirectory(): String {
    val path = getExecutablePath()
    return getPathParent(path) ?: "."
}

fun makeRelative(path: String, base: String): String {
    val normalizedPath = normalizePath(path)
    val normalizedBase = normalizePath(base).trimEnd('/') + "/"
    
    return if (normalizedPath.startsWith(normalizedBase)) {
        normalizedPath.substring(normalizedBase.length)
    } else {
        // If not under base, return as is or handle accordingly
        normalizedPath
    }
}
