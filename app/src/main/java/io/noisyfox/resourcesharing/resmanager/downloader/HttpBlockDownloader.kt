package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.FileBlock
import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.resourcesharing.resmanager.safeClose
import io.noisyfox.resourcesharing.resmanager.safeForEach
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.min


internal class HttpBlockDownloader(
        private val url: String,
        private val fileWriter: MarkedFileWriter
) : BlockDownloader {
    override val downloadListeners: MutableList<BlockDownloaderListener> = mutableListOf()

    private val httpClient = OkHttpClient()
    private val threadLock = java.lang.Object()

    private var httpSession: HttpSession? = null
    private var workingThread: Thread? = null
    private val assignedBlocks: PriorityQueue<Int> = PriorityQueue()
    private val workingRunnable = Runnable {
        val thread = Thread.currentThread()

        var currentBlock: FileBlock? = null
        var currentHttpSession: HttpSession? = null
        var currentHttpInputStream: InputStream? = null
        var currentInputIndex = 0L

        val buffer = ByteArray(1024)

        try {
            while (!thread.isInterrupted) {
                if (currentBlock != null) {
                    // Clean up current job and start next
                    currentBlock.safeClose()
                    currentBlock = null
                    continue
                } else {
                    val nextBlock: Int = synchronized(threadLock) {
                        if (assignedBlocks.isEmpty()) {
                            if (currentHttpSession == null) {
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
                        // Close stream if already opened
                        currentHttpSession?.safeClose()
                        currentHttpSession = null
                        currentHttpInputStream = null
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
                try {
                    blockLoop@ while (!thread.isInterrupted) {
                        // Current write position in the entire file
                        val blockAbsOffset: Long = currentBlock.writer.file.metadata.getBlockOffset(currentBlock.index) + currentBlock.currentPointer
                        // Check current stream
                        if (currentHttpSession == null) {
                            currentHttpSession = synchronized(threadLock) {
                                if (thread.isInterrupted) {
                                    throw InterruptedException("Stopped")
                                }

                                // Create http session
                                val builder = Request.Builder()
                                        .url(url)
                                        .get()
                                if (blockAbsOffset != 0L) {
                                    builder.addHeader("Range", "bytes=$blockAbsOffset-")
                                }
                                val request = builder.build()
                                val s = HttpSession(httpClient.newCall(request))
                                httpSession = s
                                s
                            }
                            val res = try {
                                currentHttpSession.execute()
                            } catch (e: Exception) {
                                currentHttpSession.safeClose()
                                currentHttpSession = null
                                throw e
                            }

                            // Check success
                            val code = res.code()
                            var isSuccess =
                                    when {
                                        !res.isSuccessful -> false
                                        blockAbsOffset == 0L -> true
                                        code == HttpURLConnection.HTTP_PARTIAL -> {
                                            // Check range
                                            val h = res.header("Content-Range")
                                            h?.startsWith("bytes $blockAbsOffset-") ?: false
                                        }
                                        else -> false
                                    }
                            if (isSuccess) {
                                // Try open stream
                                try {
                                    currentHttpInputStream = res.body()!!.byteStream()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    isSuccess = false
                                }
                            }
                            if (!isSuccess) {
                                currentHttpSession.safeClose()
                                currentHttpSession = null
                                currentHttpInputStream = null
                                throw IOException("Http request failed!")
                            } else {
                                currentInputIndex = blockAbsOffset
                            }
                        } else {
                            if (currentInputIndex != blockAbsOffset) {
                                currentHttpSession.safeClose()
                                currentHttpSession = null
                                currentHttpInputStream = null
                                continue
                            }
                        }

                        // Start real download
                        var blockCheckTime = 0
                        while (!thread.isInterrupted) {
                            // Check if current block is still assigned
                            if (blockCheckTime++ > 5) {
                                val id = currentBlock.index
                                val valid = synchronized(threadLock) {
                                    id in assignedBlocks
                                }
                                if (!valid) {
                                    break@blockLoop
                                }
                                blockCheckTime = 0
                            }

                            val remain = currentBlock.remain
                            if (remain <= 0) {
                                // Block download finished
                                currentBlock.close()
                                val id = currentBlock.index

                                if (finishBlock(id)) {
                                    downloadListeners.safeForEach {
                                        it.onBlockDownloaded(this, id)
                                    }
                                }
                                break@blockLoop
                            }

                            val requiredSize = min(remain, buffer.size)
                            val readSize = try {
                                currentHttpInputStream!!.read(buffer, 0, requiredSize)
                            } catch (e: Exception) {
                                currentHttpSession!!.safeClose()
                                currentHttpSession = null
                                currentHttpInputStream = null
                                throw e
                            }
                            if (readSize == -1) {
                                currentHttpSession!!.safeClose()
                                currentHttpSession = null
                                currentHttpInputStream = null
                                throw IOException("Unexpected EOF!")
                            }
                            currentInputIndex += readSize
                            currentBlock.append(buffer, 0, readSize)
                        }
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    currentBlock.safeClose()
                    val id = currentBlock.index
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
            currentHttpSession?.safeClose()
        }
    }

    private fun finishBlock(block: Int): Boolean = synchronized(threadLock) {
        if (block in assignedBlocks) {
            assignedBlocks.remove(block)
            true
        } else false
    }

    override fun start() {
        synchronized(threadLock) {
            val t = workingThread
            if (t != null && t.isAlive) {
                return
            }
            workingThread = thread(start = true, name = "HttpBlockDownloader working thread") {
                workingRunnable.run()
            }
        }
    }

    override fun stop() {
        val t: Thread? = synchronized(threadLock) {
            httpSession?.cancel()
            httpSession = null

            val t = workingThread
            workingThread = null

            t
        }

        t?.let {
            while (t.isAlive) {
                t.interrupt()
                t.join(100)
            }
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
}

private class HttpSession(private val httpCall: Call) : Closeable {
    private val threadLock = java.lang.Object()
    private var response: Response? = null
    private var closed = false

    fun execute(): Response {
        synchronized(threadLock) {
            if (closed) {
                throw IOException("Already closed!")
            }
        }
        val r = httpCall.execute()

        synchronized(threadLock) {
            if (closed) {
                r.safeClose()
                throw IOException("Closed!")
            }
            response = r
        }

        return r
    }

    fun cancel() {
        synchronized(threadLock) {
            if (closed) {
                return
            }
            httpCall.cancel()
        }
    }

    override fun close() {
        synchronized(threadLock) {
            if (closed) {
                return
            }
            closed = true

            httpCall.cancel()
            response?.safeClose()
            response = null
        }
    }
}
