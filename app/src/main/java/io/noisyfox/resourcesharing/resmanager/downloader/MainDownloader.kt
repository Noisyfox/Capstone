package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.resourcesharing.resmanager.ResContext
import io.noisyfox.resourcesharing.resmanager.safeClose
import io.noisyfox.resourcesharing.resmanager.safeForEach
import java.io.Closeable

internal class MainDownloader(
        private val resContext: ResContext
) : BlockDownloaderListener, Closeable {

    private enum class Status {
        Starting,
        Running,
        Stopping,
        Stopped,
        StartAfterStopped,
        StopAfterStarted
    }

    private val service = resContext.service
    private val listeners = service.downloadListeners
    private val file = resContext.file

    private var fileWriter: MarkedFileWriter? = null

    private val blockDownloaderEden = mutableListOf<BlockDownloader>()
    private val blockDownloaders = mutableListOf<BlockDownloader>()

    private var currentStatus: Status = Status.Stopped

    internal fun start(): Boolean {
        service.assertOnWorkingThread()

        return when (currentStatus) {
            Status.Stopping -> {
                currentStatus = Status.StartAfterStopped
                false
            }
            Status.StartAfterStopped -> false
            Status.StopAfterStarted -> {
                currentStatus = Status.Starting
                false
            }
            Status.Running -> true
            Status.Starting -> false
            Status.Stopped -> {
                if (blockDownloaders.isNotEmpty()) {
                    throw IllegalStateException("blockDownloaders not empty! Fetal error!")
                }

                val w = file.tryOpenWriter()
                        ?: throw IllegalStateException("Unable to open writer!")

                fileWriter = w

                // Pure http download
                val d = HttpBlockDownloader(file.metadata.url, w)
                d.downloadListeners += this
                blockDownloaderEden.add(d)
                d.assignBlocks(HashSet(w.openableBlocks))

                blockDownloaderEden.safeForEach {
                    if (it.start()) {
                        blockDownloaderEden.remove(it)
                        blockDownloaders.add(it)
                    }
                }

                if (blockDownloaderEden.isEmpty()) {
                    currentStatus = Status.Running
                    true
                } else {
                    currentStatus = Status.Starting
                    false
                }
            }
        }
    }

    internal fun stop(): Boolean {
        service.assertOnWorkingThread()

        return when (currentStatus) {
            Status.Starting -> {
                currentStatus = Status.StopAfterStarted
                false
            }
            Status.Stopping -> false
            Status.Stopped -> true
            Status.StartAfterStopped -> {
                currentStatus = Status.Stopping
                false
            }
            Status.StopAfterStarted -> false
            Status.Running -> {
                blockDownloaders.safeForEach {
                    if (it.stop()) {
                        blockDownloaders.remove(it)
                    }
                }
                if (blockDownloaders.isEmpty()) {
                    currentStatus = Status.Stopped
                    fileWriter?.safeClose()
                    fileWriter = null
                    true
                } else {
                    currentStatus = Status.Stopping
                    false
                }
            }
        }
    }

    internal fun isStopped(): Boolean {
        service.assertOnWorkingThread()

        return currentStatus == Status.Stopped
    }

    override fun onBlockDownloaderStarted(downloader: BlockDownloader) {
        service.runOnWorkingThread {
            if (downloader !in blockDownloaderEden) {
                throw IllegalStateException("Downloader not in Eden!")
            }
            blockDownloaderEden.remove(downloader)
            blockDownloaders.add(downloader)

            if (blockDownloaderEden.isEmpty()) {
                when (currentStatus) {
                    Status.Starting -> {
                        currentStatus = Status.Running
                        listeners.safeForEach {
                            it.onDownloadStarted(service, file.id)
                        }
                    }
                    Status.StopAfterStarted -> {
                        currentStatus = Status.Running
                        if (stop()) {
                            listeners.safeForEach {
                                it.onDownloadStopped(service, file.id)
                            }
                        }
                    }
                    else -> throw IllegalStateException("Unexpected status $currentStatus")
                }
            }
        }
    }

    override fun onBlockDownloaderStopped(downloader: BlockDownloader) {
        service.runOnWorkingThread {
            if (downloader !in blockDownloaders) {
                throw IllegalStateException("Downloader not in list!")
            }
            blockDownloaders.remove(downloader)

            if (blockDownloaders.isEmpty()) {
                when (currentStatus) {
                    Status.Stopping -> {
                        currentStatus = Status.Stopped
                        fileWriter?.safeClose()
                        fileWriter = null

                        listeners.safeForEach {
                            it.onDownloadStopped(service, file.id)
                        }
                    }
                    Status.StartAfterStopped -> {
                        currentStatus = Status.Stopped
                        fileWriter?.safeClose()
                        fileWriter = null

                        if (start()) {
                            listeners.safeForEach {
                                it.onDownloadStarted(service, file.id)
                            }
                        }
                    }
                    else -> throw IllegalStateException("Unexpected status $currentStatus")
                }
            }
        }
    }

    override fun onBlockDownloaded(downloader: BlockDownloader, block: Int) {
        service.runOnWorkingThread {
            listeners.safeForEach {
                it.onBlockDownloaded(service, file.id, block)
            }

            val w = fileWriter!!
            if (w.writableBlocks.isEmpty()) {
                listeners.safeForEach {
                    it.onDownloadCompleted(service, file.id)
                }

                stop()
            }
        }
    }

    override fun onBlockDownloadFailed(downloader: BlockDownloader, block: Int, ex: Throwable?) {
        service.runOnWorkingThread {
            listeners.safeForEach {
                it.onBlockDownloadFailed(service, file.id, block, ex)
            }

            // TODO: retry for X times or failed!
        }
    }

    override fun close() {
        stop()
    }
}