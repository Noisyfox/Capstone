package io.noisyfox.resourcesharing.resmanager.downloader

internal interface BlockDownloader {
    val downloadListeners: MutableList<BlockDownloaderListener>

    fun start()

    fun stop()

    fun assignBlocks(blocks: Set<Int>)

    fun unassignBlocks(blocks: Set<Int>)
}

internal interface BlockDownloaderListener {
    fun onBlockDownloaded(downloader: BlockDownloader, block: Int)

    fun onDownloadCompleted(downloader: BlockDownloader)

    fun onBlockDownloadFailed(downloader: BlockDownloader, block: Int, ex: Throwable?)
}
