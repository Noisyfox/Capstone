package io.noisyfox.libfilemanager

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.locks.Lock
import kotlin.math.min


fun MessageDigest.toHexString(): String {
    return this.digest().fold("", { str, it -> str + "%02x".format(it) })
}

fun String.getSHA256HexString(): String {
    val d = MessageDigest.getInstance("SHA-256")
    d.update(this.toByteArray(Charset.forName("UTF-8")))

    return d.toHexString()
}

fun ByteBuffer.getSHA256HexString() = this.getSHA256HexString(0, this.limit())

fun ByteBuffer.getSHA256HexString(offset: Int, len: Int): String {
    val d = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024)

    position(offset)

    var remain = len
    while (remain > 0) {
        val s = min(remain, 1024)
        get(buffer, 0, s)
        d.update(buffer, 0, s)
        remain -= s
    }

    return d.toHexString()
}

inline fun <T> Lock.withTryLock(errorMsg: String, action: () -> T): T {
    if (tryLock()) {
        try {
            return action()
        } finally {
            unlock()
        }
    } else {
        throw IllegalStateException(errorMsg)
    }
}

fun Lock.tryLockEx(errorMsg: String) {
    if (!tryLock()) {
        throw IllegalStateException(errorMsg)
    }
}

inline fun Lock.tryLock(action: () -> Unit) {
    if (tryLock()) {
        try {
            action()
        } finally {
            unlock()
        }
    }
}

inline fun <T, R> T.unlockOnException(lockFirst: Boolean = true, action: (T) -> R): R where T : Lock {
    if (lockFirst) {
        lock()
    }
    try {
        return action(this)
    } catch (e: Exception) {
        unlock()
        throw e
    }
}

inline fun <T, R> T.closeOnException(action: (T) -> R): R where T : AutoCloseable {
    try {
        return action(this)
    } catch (e: Exception) {
        close()
        throw e
    }
}
