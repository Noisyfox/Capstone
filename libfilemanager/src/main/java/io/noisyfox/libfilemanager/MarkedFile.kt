package io.noisyfox.libfilemanager

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.*
import java.util.concurrent.locks.ReentrantReadWriteLock

class MarkedFile(
        val id: String,
        fileLock: ReentrantReadWriteLock,
        manager: FileManager,
        hash: String,
        val metadata: MetadataModel) {

    internal val statusFile = File(manager.baseDir, "$hash.prog.json")
    internal val dataFile = File(manager.baseDir, "$hash.data")

    val readLock = fileLock.readLock()

    val writeLock = fileLock.writeLock()

    /**
     * Get stream for read
     */
    fun openStream(): InputStream {
        readLock.withTryLock("File already opened for writing!") {
            ensureComplete()
            readLock.unlockOnException { lock ->
                FileInputStream(dataFile).closeOnException { stream ->
                    return LockedInputStream(stream, lock)
                }
            }
        }
    }

    fun readBlock(block: Int): ByteArray {
        openStream().use {
            val n = metadata.getBlockOffset(block)
            if (it.skip(n) != n) {
                throw IOException("Unable to set read offset!")
            }
            val result = ByteArray(metadata.blocks[block].size)
            it.readFull(result)

            return result
        }
    }

    private fun ensureComplete() {
        if (!_isComplete()) {
            throw IllegalStateException("File not complete!")
        }
    }

    fun isComplete(): Boolean {
        readLock.tryLock {
            return _isComplete()
        }

        return false
    }

    private fun _isComplete() = try {
        readStatus().completed
    } catch (e: FileNotFoundException) {
        false
    }

    fun openWriter(): MarkedFileWriter {
        writeLock.unlockOnException { lock ->
            return MarkedFileWriter(this, lock)
        }
    }

    fun tryOpenWriter(): MarkedFileWriter? {
        writeLock.tryLock {
            return openWriter()
        }

        return null
    }

    internal fun readStatus(): ProgressModel {
        try {
            return jacksonObjectMapper()
                    .readValue(statusFile)
        } catch (e: FileNotFoundException) {
            if (writeLock.isHeldByCurrentThread) {
                // Create progress file
                val v = ProgressModel()
                jacksonObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .writeValue(statusFile, v)
                return v
            }
            throw e
        }
    }

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
            try {
                innerStream.close()
            } finally {
                if (!unlocked) {
                    lock.unlock()
                    unlocked = true
                }
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
