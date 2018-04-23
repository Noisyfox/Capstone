package io.noisyfox.resourcesharing.resmanager.downloader

internal interface BlockDownloader {
    val downloadListeners: MutableList<BlockDownloaderListener>

    /**
     * @return True if already started, false otherwise.
     */
    fun start(): Boolean

    /**
     * @return True if already stopped, false otherwise.
     */
    fun stop(): Boolean

    fun assignBlocks(blocks: Set<Int>)

    fun unassignBlocks(blocks: Set<Int>)
}

/**
 * All callbacks will be called from block downloader's own working thread
 */
internal interface BlockDownloaderListener {
    fun onBlockDownloaderStarted(downloader: BlockDownloader)

    fun onBlockDownloaded(downloader: BlockDownloader, block: Int)

    fun onBlockDownloadFailed(downloader: BlockDownloader, block: Int, ex: Throwable?)

    fun onBlockDownloaderStopped(downloader: BlockDownloader)
}
