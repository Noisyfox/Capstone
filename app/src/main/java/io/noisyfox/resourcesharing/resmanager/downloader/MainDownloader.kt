package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.resourcesharing.resmanager.ResContext
import io.noisyfox.resourcesharing.resmanager.safeClose
import io.noisyfox.resourcesharing.resmanager.safeForEach
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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

                currentStatus = Status.Starting

                // Pure http download
                val d = HttpBlockDownloader(file.metadata.url, w)
                if (hatchNewDownloader(d)) {
                    d.downloadListeners += this
                    blockDownloaderEden.add(d)
                    d.assignBlocks(HashSet(w.openableBlocks))
                }

                if (blockDownloaderEden.isEmpty()) {
                    currentStatus = Status.Running
                    true
                } else {
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
                if (blockDownloaderEden.isEmpty() && blockDownloaders.isEmpty()) {
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

    private fun hatchNewDownloader(downloader: BlockDownloader): Boolean {
        service.assertOnWorkingThread()

        return when (currentStatus) {
            Status.Starting, Status.Running -> {
                blockDownloaderEden.add(downloader)
                if (downloader.start()) {
                    blockDownloaderEden.remove(downloader)
                    blockDownloaders.add(downloader)
                }
                true
            }
            Status.Stopping -> false
            Status.Stopped -> false
            Status.StartAfterStopped -> false
            Status.StopAfterStarted -> false
        }
    }

    override fun onBlockDownloaderStarted(downloader: BlockDownloader) {
        service.runOnWorkingThread {
            if (downloader !in blockDownloaderEden) {
                throw IllegalStateException("Downloader not in Eden!")
            }
            blockDownloaderEden.remove(downloader)
            blockDownloaders.add(downloader)

            when (currentStatus) {
                Status.Starting -> {
                    if (blockDownloaderEden.isEmpty()) {
                        currentStatus = Status.Running
                        listeners.safeForEach {
                            it.onDownloadStarted(service, file.id)
                        }
                    }
                }
                Status.StopAfterStarted -> {
                    if (blockDownloaderEden.isEmpty()) {
                        currentStatus = Status.Running

                        if (stop()) {
                            listeners.safeForEach {
                                it.onDownloadStopped(service, file.id)
                            }
                        }
                    }
                }
                Status.Running -> {
                    // Ignore
                }
                Status.Stopping, Status.StartAfterStopped -> {
                    // Oops! God told us to stop
                    if (downloader.stop()) {
                        service.postOnWorkingThread {
                            onBlockDownloaderStopped(downloader)
                        }
                    }
                }
                Status.Stopped -> {
                    // Something goes wrong!
                    logger.error("Child downloader started after main downloader stopped! Not good!")
                    if (downloader.stop()) {
                        service.postOnWorkingThread {
                            onBlockDownloaderStopped(downloader)
                        }
                    }
                }
            }
        }
    }

    override fun onBlockDownloaderStopped(downloader: BlockDownloader) {
        service.runOnWorkingThread {
            when (downloader) {
                in blockDownloaderEden -> {
                    logger.warn("Child downloader died in Eden!")
                    blockDownloaderEden.remove(downloader)
                }
                in blockDownloaders -> blockDownloaders.remove(downloader)
                else -> throw IllegalStateException("Downloader not in list!")
            }

            when (currentStatus) {
                Status.Starting, Status.StopAfterStarted -> {
                    // Ignored
                    logger.debug("Child downloader stopped during main downloader starting.")
                }
                Status.Running -> {
                    // Ignored
                }
                Status.Stopping -> {
                    if (blockDownloaderEden.isEmpty() && blockDownloaders.isEmpty()) {
                        currentStatus = Status.Stopped
                        fileWriter?.safeClose()
                        fileWriter = null

                        listeners.safeForEach {
                            it.onDownloadStopped(service, file.id)
                        }
                    }
                }
                Status.StartAfterStopped -> {
                    if (blockDownloaderEden.isEmpty() && blockDownloaders.isEmpty()) {
                        currentStatus = Status.Stopped
                        fileWriter?.safeClose()
                        fileWriter = null

                        if (start()) {
                            listeners.safeForEach {
                                it.onDownloadStarted(service, file.id)
                            }
                        }
                    }
                }
                Status.Stopped -> {
                    // Ignored
                    logger.warn("Child downloader stopped after main downloader stopped. Not ideal.")
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

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(MainDownloader::class.java)
    }
}