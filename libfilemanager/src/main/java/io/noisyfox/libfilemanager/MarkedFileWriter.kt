package io.noisyfox.libfilemanager

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantReadWriteLock

class MarkedFileWriter(
        val file: MarkedFile,
        private val lock: ReentrantReadWriteLock.WriteLock
) : Closeable {
    private var unlocked: Boolean = false
    private val openedBlocks: MutableSet<Int> = mutableSetOf()

    var status: ProgressModel = file.readStatus()
        private set

    val blockCount
        get() = file.metadata.blocks.count()

    val writableBlocks
        @Synchronized get() = ensureOpen {
            (0 until blockCount) - status.completedBlocks
        }

    val openableBlocks
        @Synchronized get() = ensureOpen {
            writableBlocks - openedBlocks
        }

    @Synchronized
    fun isBlockOpened(index: Int) {
        index in openedBlocks
    }

    @Synchronized
    fun openBlock(index: Int): FileBlock = ensureOpen {
        val meta = file.metadata
        val blockDef = meta.blocks[index]

        if (index in openedBlocks) {
            throw IllegalStateException("Block already opened!")
        } else if (index !in writableBlocks) {
            throw IllegalStateException("Block not writable!")
        }

        // Map file to memory
        RandomAccessFile(file.dataFile, "rw").use { raf ->
            raf.setLength(meta.size) // Ensure file is large enough

            val memory = raf.channel.map(
                    FileChannel.MapMode.READ_WRITE,
                    meta.getBlockOffset(index),
                    blockDef.size.toLong()
            )
            // Get current download position
            val position = status.otherBlocks[index.toString()]?.progress ?: 0
            memory.position(position)

            openedBlocks.add(index)
            FileBlock(this, index, blockDef, memory)
        }
    }

    @Synchronized
    fun clearFile() = ensureOpen {
        if (openedBlocks.isNotEmpty()) {
            throw IllegalStateException("Must close all blocks first!")
        }

        // Clear status first
        status = ProgressModel()
        jacksonObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.statusFile, status)

        // Delete the data file
        file.dataFile.delete()
    }

    @Synchronized
    internal fun closeBlock(block: FileBlock) = ensureOpen {
        if (block.index !in openedBlocks) {
            throw IllegalStateException("Block not opened!")
        }
        openedBlocks.remove(block.index)

        flushBlock2(block)
    }

    @Synchronized
    internal fun flushBlock(block: FileBlock) = ensureOpen {
        if (block.index !in openedBlocks) {
            throw IllegalStateException("Block not opened!")
        }

        flushBlock2(block)
    }

    private fun flushBlock2(block: FileBlock) {
        val memory = block.memoryBlock

        // Flush file
        memory.force()

        // Update and write progress file
        val progress = block.currentPointer
        if (progress == block.block.size) {
            // Check hash
            val hash = memory.getSHA256HexString()
            if (hash == block.block.hash) {
                status = status.withBlockComplete(block.index)
                // Check if all completed
                if (((0 until blockCount) - status.completed).isEmpty()) {
                    status = status.withAllComplete()
                }
            } else {
                status = status.withStatus(block.index, BlockProgressModel(progress, ProgressStatus.HashMismatch))
            }
        } else {
            status = status.withStatus(block.index, BlockProgressModel(progress, ProgressStatus.Downloading))
        }
        jacksonObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.statusFile, status)
    }

    @Synchronized
    override fun close() {
        if (!unlocked) {
            // Ensure all block closed
            if (openedBlocks.isNotEmpty()) {
                throw IllegalStateException("Must close all blocks first!")
            }

//            try {
//                // Do close
//            } finally {
//                if (!unlocked) {
            lock.unlock()
            unlocked = true
//                }
//            }
        }
    }

    private inline fun <R> ensureOpen(action: () -> R): R {
        if (unlocked) {
            throw IllegalStateException("Already closed!")
        }

        return action()
    }
}

class FileBlock(
        val writer: MarkedFileWriter,
        val index: Int,
        val block: BlockModel,
        internal val memoryBlock: MappedByteBuffer
) : Closeable {

    private var closed = false

    var currentPointer
        get() = ensureOpen { memoryBlock.position() }
        private set(value) = ensureOpen { memoryBlock.position(value) }

    val remain: Int
        get() = block.size - currentPointer

    fun seekBack(newPointer: Int) {
        if (newPointer > currentPointer) {
            throw IndexOutOfBoundsException("Only seek backwards are allowed!")
        }

        currentPointer = newPointer
    }

    fun append(buffer: ByteArray): Unit = append(buffer, 0, buffer.size)

    fun append(buffer: ByteArray, offset: Int, len: Int): Unit = ensureOpen { memoryBlock.put(buffer, offset, len) }

    fun flush() = writer.flushBlock(this)

    override fun close() {
        if (!closed) {
            try {
                writer.closeBlock(this)
            } finally {
                closed = true
            }
        }
    }

    private inline fun <R> ensureOpen(action: () -> R): R {
        if (!closed) {
            throw IllegalStateException("Already closed!")
        }

        return action()
    }

    protected fun finalize() {
        close()
    }
}
