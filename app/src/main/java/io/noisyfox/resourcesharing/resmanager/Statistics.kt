package io.noisyfox.resourcesharing.resmanager

data class DownloaderStatistics(
        var downloadBytes: Long = 0L,
        var downloadSpeed: Float = 0.0F,
        var downloadBlocks: Int = 0,
        var downloadTime: Long = 0L,
        var httpDownloadBytes: Long = 0L,
        var httpDownloadSpeed: Float = 0.0F,
        var httpDownloadBlocks: Int = 0,
        var p2pDownloadBytes: Long = 0L,
        var p2pDownloadSpeed: Float = 0.0F,
        var p2pDownloadBlocks: Int = 0
) {

    private var downloadStartTime: Long = 0

    @Synchronized
    fun reset() {
        downloadBytes = 0L
        downloadSpeed = 0.0F
        downloadBlocks = 0
        downloadTime = 0L
        httpDownloadBytes = 0L
        httpDownloadSpeed = 0.0F
        httpDownloadBlocks = 0
        p2pDownloadBytes = 0L
        p2pDownloadSpeed = 0.0F
        p2pDownloadBlocks = 0
    }

    @Synchronized
    fun onDownloadStarted() {
        reset()
        downloadStartTime = System.currentTimeMillis()
    }

    @Synchronized
    fun onHttpDownloaded(size: Int) {
        downloadBytes += size
        httpDownloadBytes += size
        updateSpeed()
    }

    @Synchronized
    fun onP2pDownloaded(size: Int) {
        downloadBytes += size
        p2pDownloadBytes += size
        updateSpeed()
    }

    @Synchronized
    fun onHttpBlockDownloaded() {
        downloadBlocks++
        httpDownloadBlocks++
    }

    @Synchronized
    fun onP2pBlockDownloaded() {
        downloadBlocks++
        p2pDownloadBlocks++
    }

    @Synchronized
    fun updateSpeed() {
        downloadTime = System.currentTimeMillis() - downloadStartTime

        val dTimeInSec = downloadTime.toFloat() / 1000F

        if (dTimeInSec < 0.001F) {
            downloadSpeed = 0F
            httpDownloadSpeed = 0F
            p2pDownloadSpeed = 0F

        } else {
            downloadSpeed = downloadBytes / dTimeInSec
            httpDownloadSpeed = httpDownloadBytes / dTimeInSec
            p2pDownloadSpeed = p2pDownloadBytes / dTimeInSec
        }
    }

}

//data class ServiceStatistics(
//
//) {
//
//}

data class UploaderStatistics(
        var uploadBytes: Long = 0L,
        var uploadSpeed: Float = 0.0F,
        var uploadBlocks: Int = 0
) {

    @Synchronized
    fun reset() {
        uploadBytes = 0L
        uploadSpeed = 0.0F
        uploadBlocks = 0
    }

    @Synchronized
    fun onDataSent(length: Int) {
        uploadBytes += length
    }
}

data class FileStatistics(
        val upload: UploaderStatistics,
        val download: DownloaderStatistics
)
