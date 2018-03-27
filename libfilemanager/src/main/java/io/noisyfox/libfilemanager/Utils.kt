package io.noisyfox.libfilemanager

import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.locks.Lock


fun MessageDigest.toHexString(): String {
    return this.digest().fold("", { str, it -> str + "%02x".format(it) })
}

fun String.getSHA256HexString(): String {
    val d = MessageDigest.getInstance("SHA-256")
    d.update(this.toByteArray(Charset.forName("UTF-8")))

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

inline fun <T, R> T.unlockOnException(action: (T) -> R): R where T : Lock {
    lock()
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
