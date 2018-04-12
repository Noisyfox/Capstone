package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.FileBlock
import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.resourcesharing.resmanager.safeForEach
import java.util.*
import kotlin.concurrent.thread

internal class HttpDownloader(
        private val url: String,
        private val fileWriter: MarkedFileWriter
) : BlockDownloader {
    override val downloadListeners: MutableList<BlockDownloaderListener> = mutableListOf()

    private val threadLock = java.lang.Object()
    private var workingThread: Thread? = null
    private val assignedBlocks: PriorityQueue<Int> = PriorityQueue()
    private val workingRunnable = Runnable {
        val thread = Thread.currentThread()

        var currentBlock: FileBlock? = null

        try {
            while (!thread.isInterrupted) {
                if (currentBlock != null) {
                    // This shouldn't happen! Give up current job and start over!
                    currentBlock.close()
                    currentBlock = null
                    continue
                } else {
                    val nextBlock: Int = synchronized(threadLock) {
                        if (assignedBlocks.isEmpty()) {
                            if (true) { // TODO: check if stream opened
                                // No stream opened, wait forever
                                threadLock.wait()
                            } else {
                                // Stream already opened, wait for 1s and check again
                                threadLock.wait(1000)
                            }

                            if (assignedBlocks.isEmpty()) {
                                return@synchronized -1
                            }
                        }
                        return@synchronized assignedBlocks.peek()
                    }
                    if (nextBlock == -1) {
                        // TODO: close stream if already opened
                        continue
                    }
                    // Open block for writing
                    try {
                        currentBlock = fileWriter.openBlock(nextBlock)
                    } catch (e: Exception) {
                        val cb = synchronized(threadLock) {
                            if (nextBlock in assignedBlocks) {
                                assignedBlocks.remove(nextBlock)
                                true
                            } else false
                        }
                        if (cb) {
                            downloadListeners.safeForEach {
                                it.onBlockDownloadFailed(this, nextBlock, e)
                            }
                        }
                        continue
                    }
                }

                // Work with current block
                // Current write position in the entire file
                val blockAbsOffset = currentBlock.currentPointer
                +currentBlock.writer.file.metadata.getBlockOffset(currentBlock.index)
                // Check current stream
                

            }
        } catch (_: InterruptedException) {
            // Ignored
        } finally {
            currentBlock?.close()
            // TODO: make sure stream closed
        }
    }

    override fun start() {
        synchronized(threadLock) {
            val t = workingThread
            if (t != null && t.isAlive) {
                return
            }
            workingThread = thread(start = true, name = "HttpDownloader working thread") {
                workingRunnable.run()
            }
        }
    }

    override fun stop() {
        val t: Thread? = synchronized(threadLock) {
            val t = workingThread
            workingThread = null
            t
        }

        if (t == null || !t.isAlive) {
            return
        }

        t.interrupt()
        t.join()
    }

    override fun assignBlocks(blocks: Set<Int>) {
        synchronized(threadLock) {
            val n = blocks - assignedBlocks
            assignedBlocks += n

            threadLock.notifyAll()
        }
    }

    override fun unassignBlocks(blocks: Set<Int>) {
        synchronized(threadLock) {
            assignedBlocks -= blocks

            threadLock.notifyAll()
        }
    }
}
