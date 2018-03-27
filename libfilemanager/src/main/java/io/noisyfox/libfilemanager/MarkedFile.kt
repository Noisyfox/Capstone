package io.noisyfox.libfilemanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.locks.ReentrantReadWriteLock

class MarkedFile(
        private val fileLock: ReentrantReadWriteLock,
        private val manager: FileManager,
        private val hash: String,
        val metadata: MetadataModel) {

    fun getReadLock() = fileLock.readLock()

    fun getWriteLock() = fileLock.writeLock()

    /**
     * Get stream for read
     */
    fun openStream(): InputStream {
        getReadLock().withTryLock("File already opened for writing!") {
            ensureComplete()
            getReadLock().unlockOnException { lock ->
                FileInputStream(getDataFile()).closeOnException { stream ->
                    return LockedInputStream(stream, lock)
                }
            }
        }
    }

    private fun ensureComplete() {
        if (!_isComplete()) {
            throw IllegalStateException("File not complete!")
        }
    }

    fun isComplete(): Boolean {
        getReadLock().tryLock {
            return _isComplete()
        }

        return false
    }

    private fun _isComplete() = readStatus().completed

    fun openWriter() {
        getWriteLock().lock()
        _openWriter()
    }

    fun tryOpenWriter() {
        if (getWriteLock().tryLock()) {
            _openWriter()
        }
    }

    fun _openWriter() {

    }

    private fun readStatus(): ProgressModel {
        return jacksonObjectMapper()
                .readValue(getStatusFile())
    }

    private fun getStatusFile() = File(manager.baseDir, "$hash.prog.json")
    private fun getDataFile() = File(manager.baseDir, "$hash.data")

    private class LockedInputStream(private val innerStream: InputStream,
                                    private val lock: ReentrantReadWriteLock.ReadLock) : InputStream() {

        private var unlocked: Boolean = false

        override fun read(): Int {
            return innerStream.read()
        }

        override fun read(b: ByteArray?): Int {
            return innerStream.read(b)
        }

        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            return innerStream.read(b, off, len)
        }

        override fun skip(n: Long): Long {
            return innerStream.skip(n)
        }

        override fun available(): Int {
            return innerStream.available()
        }

        override fun reset() {
            innerStream.reset()
        }

        @Synchronized
        override fun close() {
            innerStream.close()

            if (!unlocked) {
                lock.unlock()
                unlocked = true
            }
        }

        override fun mark(readlimit: Int) {
            innerStream.mark(readlimit)
        }

        override fun markSupported(): Boolean {
            return innerStream.markSupported()
        }
    }
}

class FileBlock {

}
