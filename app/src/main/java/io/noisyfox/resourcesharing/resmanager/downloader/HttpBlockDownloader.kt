package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.FileBlock
import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.libfilemanager.ProgressStatus
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
        override val id: Long,
        private val url: String,
        private val fileWriter: MarkedFileWriter
) : BlockDownloader {
    override val downloadListeners: MutableList<BlockDownloaderListener> = mutableListOf()

    private val httpClient = OkHttpClient()

    private val threadLock = java.lang.Object()
    private var requestToStop = false
    private var workingThread: Thread? = null
    private var httpSession: HttpSession? = null

    private val assignedBlocks: PriorityQueue<Int> = PriorityQueue()

    private val workingRunnable = Runnable {
        var currentBlock: FileBlock? = null

        var currentHttpSession: HttpSession? = null
        var currentInputIndex = 0L

        val buffer = ByteArray(1024)

        try {
            downloadListeners.safeForEach {
                it.onComponentStarted(this)
            }

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
                        synchronized(threadLock) {
                            httpSession = null
                        }
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
                    blockLoop@ while (!requestToStop) {
                        // Current write position in the entire file
                        val blockAbsOffset: Long = currentBlock.writer.file.metadata.getBlockOffset(currentBlock.index) + currentBlock.currentPointer
                        // Check current stream
                        if (currentHttpSession == null) {
                            currentHttpSession = synchronized(threadLock) {
                                if (requestToStop) {
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
                            val isSuccess =
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
                            if (!isSuccess) {
                                currentHttpSession.safeClose()
                                currentHttpSession = null
                                throw IOException("Http request failed!")
                            } else {
                                currentInputIndex = blockAbsOffset
                            }
                        } else {
                            if (currentInputIndex != blockAbsOffset) {
                                currentHttpSession.safeClose()
                                currentHttpSession = null
                                continue
                            }
                        }

                        // Start real download
                        var blockCheckTime = 0
                        while (!requestToStop && currentHttpSession != null) {
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

                            val remain = currentBlock.remain
                            if (remain <= 0) {
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

                            val requiredSize = min(remain, buffer.size)
                            val readSize = try {
                                currentHttpSession.read(buffer, 0, requiredSize)
                            } catch (e: Exception) {
                                currentHttpSession.safeClose()
                                currentHttpSession = null
                                throw e
                            }
                            if (readSize == -1) {
                                currentHttpSession.safeClose()
                                currentHttpSession = null
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

            synchronized(threadLock) {
                requestToStop = false
                workingThread = null
                httpSession = null
            }
            downloadListeners.safeForEach {
                it.onComponentStopped(this)
            }
        }
    }

    private fun finishBlock(block: Int): Boolean = synchronized(threadLock) {
        if (block in assignedBlocks) {
            assignedBlocks.remove(block)
            true
        } else false
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
            workingThread = thread(start = true, name = "HttpBlockDownloader working thread") {
                workingRunnable.run()
            }
            return false
        }
    }

    override fun stop(): Boolean {
        synchronized(threadLock) {
            val t = workingThread ?: return true

            requestToStop = true
            httpSession?.cancel()
            threadLock.notifyAll()
//            t.interrupt()

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
}

private class HttpSession(private val httpCall: Call) : Closeable {
    private var inputStream: InputStream? = null
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

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        var input = inputStream
        if (input == null) {
            input = synchronized<InputStream>(threadLock) {
                val i = inputStream
                if (i == null) {
                    val resp = response ?: throw IOException("Response not fetched!")
                    val i2 = resp.body()!!.byteStream()
                    inputStream = i2
                    i2
                } else {
                    i
                }
            }
        }
        return input.read(buffer, offset, length)
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
            inputStream = null
        }
    }
}
