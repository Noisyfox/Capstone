package io.noisyfox.resourcesharing.resmanager

import java.io.Closeable
import java.io.IOException


inline fun <T> Iterable<T>.safeForEach(action: (T) -> Unit) {
    val listCopy = this.toList()
    for (element in listCopy) action(element)
}

fun Closeable.safeClose() {
    try {
        this.close()
    } catch (e: IOException) {
        // Ignored
        e.printStackTrace()
    }
}
