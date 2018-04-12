package io.noisyfox.resourcesharing.resmanager


inline fun <T> Iterable<T>.safeForEach(action: (T) -> Unit) {
    val listCopy = this.toList()
    for (element in listCopy) action(element)
}
