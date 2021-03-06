package io.noisyfox.libfilemanager

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantReadWriteLock

class MarkedFile(
        val id: String,
        fileLock: ReentrantReadWriteLock,
        private val manager: FileManager,
        private val hash: String,
        val metadata: MetadataModel) {

    internal val statusFile = File(manager.baseDir, "$hash.prog.json")
    internal val dataFile = File(manager.baseDir, "$hash.data")

    val readLock = fileLock.readLock()

    val writeLock = fileLock.writeLock()

    fun getCompanionFile(suffix: String): File = File(manager.baseDir, "$hash.$suffix")

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

    fun readBlock(block: Int): ByteArray = readBlock(block, 0, metadata.blocks[block].size)

    fun readBlock(block: Int, offset: Int, len: Int): ByteArray {
        if (offset < 0 || len < 0 || offset + len > metadata.blocks[block].size) {
            throw IndexOutOfBoundsException()
        }

        openStream().use {
            val n = metadata.getBlockOffset(block) + offset
            if (it.skip(n) != n) {
                throw IOException("Unable to set read offset!")
            }
            val result = ByteArray(len)
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
