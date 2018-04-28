package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.FileBlock
import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.libfilemanager.ProgressStatus
import io.noisyfox.resourcesharing.resmanager.ResContext
import io.noisyfox.resourcesharing.resmanager.ResService
import io.noisyfox.resourcesharing.resmanager.safeClose
import io.noisyfox.resourcesharing.resmanager.safeForEach
import org.iotivity.base.OcHeaderOption
import org.iotivity.base.OcRepresentation
import org.iotivity.base.OcResource
import org.iotivity.base.QualityOfService
import java.util.*
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class SharingBlockDownloader(
        override val id: Long,
        internal val resource: OcResource,
        private val fileWriter: MarkedFileWriter
) : BlockDownloader {

    override val downloadListeners: MutableList<BlockDownloaderListener> = mutableListOf()

    private val threadLock = java.lang.Object()
    private var requestToStop = false
    private var workingThread: Thread? = null

    // Reverse order, so it starts from the last block
    private val assignedBlocks: PriorityQueue<Int> = PriorityQueue(Comparator<Int> { o1, o2 -> o2.compareTo(o1) })

    private val workingRunnable = Runnable {
        var currentBlock: FileBlock? = null

        try {
            downloadListeners.safeForEach {
                it.onComponentStarted(this)
            }

            val bufferSize = if ("+tcp:" in resource.host) BUFFER_SIZE_TCP else BUFFER_SIZE_UDP

            while (!requestToStop) {
                if (currentBlock != null) {
                    // Clean up current job and start next
                    currentBlock.safeClose()
                    currentBlock = null
                    continue
                } else {
                    val nextBlock: Int = synchronized(threadLock) {
                        if (requestToStop) {
                            return@synchronized -1
                        }
                        if (assignedBlocks.isEmpty()) {
                            threadLock.wait()
                            if (assignedBlocks.isEmpty()) {
                                return@synchronized -1
                            }
                        }
                        return@synchronized assignedBlocks.peek()
                    }
                    if (nextBlock == -1) {
                        continue
                    }
                    // Open block for writing
                    try {
                        currentBlock = fileWriter.openBlock(nextBlock)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (finishBlock(nextBlock)) {
                            downloadListeners.safeForEach {
                                it.onBlockDownloadFailed(this, nextBlock, e)
                            }
                        }
                        continue
                    }
                }

                // Work with current block
                val id = currentBlock.index
                try {
                    var blockCheckTime = 0
                    blockLoop@ while (!requestToStop) {
                        // Check if current block is still assigned
                        if (blockCheckTime++ > 10) {
                            val valid = synchronized(threadLock) {
                                id in assignedBlocks
                            }
                            if (!valid) {
                                break@blockLoop
                            }
                            blockCheckTime = 0
                        }

                        val len = minOf(currentBlock.remain, bufferSize)
                        if (len <= 0) {
                            // Block download finished
                            val status = currentBlock.flush()
                            currentBlock.close()

                            if (finishBlock(id)) {
                                if (status == ProgressStatus.Completed) {
                                    downloadListeners.safeForEach {
                                        it.onBlockDownloaded(this, id)
                                    }
                                } else {
                                    val err = when (status) {
                                        ProgressStatus.HashMismatch -> HashMismatchException()
                                        else -> BlockDownloadException("Unknown error!")
                                    }
                                    downloadListeners.safeForEach {
                                        it.onBlockDownloadFailed(this, id, err)
                                    }
                                }
                            }
                            break@blockLoop
                        }

                        val range = ResContext.toRange(currentBlock.currentPointer, len)
                        // Send block request
                        val queryParams = mapOf(
                                ResService.PARAM_COMMAND to ResService.COMMAND_DATA,
                                ResService.PARAM_BLOCK to id.toString(),
                                ResService.PARAM_RANGE to range
                        )
                        val queue = LinkedBlockingDeque<AsyncState<OcRepresentation>>(1)
                        resource.get(queryParams, object : OcResource.OnGetListener {
                            override fun onGetCompleted(p0: MutableList<OcHeaderOption>?, p1: OcRepresentation?) {
                                queue.add(AsyncState(p1, null))
                            }

                            override fun onGetFailed(p0: Throwable?) {
                                queue.add(AsyncState(null, p0))
                            }
                        }, QualityOfService.HIGH)
                        var result: AsyncState<OcRepresentation>? = null
                        while (!requestToStop && result == null) {
                            if (!synchronized(threadLock) { id in assignedBlocks }) {
                                break@blockLoop
                            }

                            result = queue.poll(100, TimeUnit.MILLISECONDS)
                        }

                        // Read resource block
                        val resp = result?.result
                        if (resp == null) {
                            throw result?.ex?.let { BlockDownloadException("Error read from remote resource.", it) }
                                    ?: BlockDownloadException("Error read from remote resource.")
                        }

                        // Write to file block
                        val rr = resp.getValue<String>(ResService.PARAM_RANGE)
                        if (rr != range) {
                            throw BlockDownloadException("Unexpected data range!")
                        }
                        val d = resp.getValue<ByteArray>(ResService.PARAM_DATA)
                        if (d.size != len) {
                            throw BlockDownloadException("Unexpected data size!")
                        }
                        currentBlock.append(d)
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    currentBlock.safeClose()
                    currentBlock = null

                    if (finishBlock(id)) {
                        downloadListeners.safeForEach {
                            it.onBlockDownloadFailed(this, id, e)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Ignored
        } finally {
            currentBlock?.safeClose()

            synchronized(threadLock) {
                requestToStop = false
                workingThread = null
            }
            downloadListeners.safeForEach {
                it.onComponentStopped(this)
            }
        }
    }

    override fun start(): Boolean {
        synchronized(threadLock) {
            if (requestToStop) {
                throw IllegalStateException("Previous session quiting!")
            }

            val t = workingThread
            if (t != null && t.isAlive) {
                return true
            }
            workingThread = thread(start = true, name = "SharingBlockDownloader working thread") {
                workingRunnable.run()
            }
            return false
        }
    }

    override fun stop(): Boolean {
        synchronized(threadLock) {
            val t = workingThread ?: return true

            requestToStop = true
            threadLock.notifyAll()

            return false
        }
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

    private fun finishBlock(block: Int): Boolean = synchronized(threadLock) {
        if (block in assignedBlocks) {
            assignedBlocks.remove(block)
            true
        } else false
    }

    private data class AsyncState<out T>(
            val result: T? = null,
            val ex: Throwable? = null
    )

    companion object {
        const val BUFFER_SIZE_TCP = 300 * 1024
        const val BUFFER_SIZE_UDP = 10 * 1024
    }
}