package io.noisyfox.resourcesharing.resmanager.downloader

import java.io.IOException

internal interface DownloaderComponent {
    /**
     * @return True if already started, false otherwise.
     */
    fun start(): Boolean

    /**
     * @return True if already stopped, false otherwise.
     */
    fun stop(): Boolean
}

/**
 * All callbacks will be called from block downloader's own working thread
 */
internal interface DownloaderComponentListener {
    fun onComponentStarted(component: DownloaderComponent)

    fun onComponentStopped(component: DownloaderComponent)
}

internal interface BlockDownloader : DownloaderComponent {
    val downloadListeners: MutableList<BlockDownloaderListener>

    fun assignBlocks(blocks: Set<Int>)

    fun unassignBlocks(blocks: Set<Int>)
}

/**
 * All callbacks will be called from block downloader's own working thread
 */
internal interface BlockDownloaderListener : DownloaderComponentListener {
    fun onBlockDownloaded(downloader: BlockDownloader, block: Int)

    fun onBlockDownloadFailed(downloader: BlockDownloader, block: Int, ex: Throwable?)
}

internal open class BlockDownloadException(
        msg: String
) : IOException(msg)

internal class HashMismatchException : BlockDownloadException("Hash mismatch!")
