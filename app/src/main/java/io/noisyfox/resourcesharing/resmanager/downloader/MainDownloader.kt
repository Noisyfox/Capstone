package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.resourcesharing.resmanager.ResContext
import io.noisyfox.resourcesharing.resmanager.safeClose
import io.noisyfox.resourcesharing.resmanager.safeForEach
import java.io.Closeable

internal class MainDownloader(
        private val resContext: ResContext
) : BlockDownloaderListener, Closeable {

    private val service = resContext.service
    private val listeners = service.downloadListeners
    private val file = resContext.file

    private var fileWriter: MarkedFileWriter? = null
    private val blockDownloaders = mutableListOf<BlockDownloader>()

    internal fun start() {
        service.assertOnWorkingThread()

        if (blockDownloaders.isNotEmpty()) {
            return
        }

        val w = file.tryOpenWriter() ?: throw IllegalStateException("Unable to open writer!")
        fileWriter = w

        // Pure http download
        val d = HttpBlockDownloader(file.metadata.url, w)
        d.downloadListeners += this
        blockDownloaders.add(d)
        d.start()
        d.assignBlocks(HashSet(w.openableBlocks))
    }

    internal fun stop() {
        service.assertOnWorkingThread()

        if (blockDownloaders.isEmpty()) {
            return
        }

        blockDownloaders.safeForEach {
            it.stop()
        }
        blockDownloaders.clear()
        fileWriter?.safeClose()
        fileWriter = null

        listeners.safeForEach {
            it.onDownloadStopped(service, resContext.file.id)
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