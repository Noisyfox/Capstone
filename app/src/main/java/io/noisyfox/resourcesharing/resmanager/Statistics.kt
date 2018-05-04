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
    private val speedTotal: SpeedMonitor = SpeedMonitor()
    private val speedHttp: SpeedMonitor = SpeedMonitor()
    private val speedP2p: SpeedMonitor = SpeedMonitor()

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

        speedTotal.reset()
        speedHttp.reset()
        speedP2p.reset()
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
        speedTotal.logData(downloadBytes)
        speedHttp.logData(httpDownloadBytes)
        speedP2p.logData(p2pDownloadBytes)

        downloadTime = System.currentTimeMillis() - downloadStartTime

        downloadSpeed = speedTotal.averageSpeed
        httpDownloadSpeed = speedHttp.averageSpeed
        p2pDownloadSpeed = speedP2p.averageSpeed
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

    private val speedTotal: SpeedMonitor = SpeedMonitor()

    @Synchronized
    fun reset() {
        uploadBytes = 0L
        uploadSpeed = 0.0F
        uploadBlocks = 0
        speedTotal.reset()
    }

    @Synchronized
    fun onDataSent(length: Int) {
        uploadBytes += length

        updateSpeed()
    }

    @Synchronized
    fun updateSpeed() {
        speedTotal.logData(uploadBytes)

        uploadSpeed = speedTotal.peekSpeed
    }

    @Synchronized
    fun updateAndCopy(): UploaderStatistics {
        updateSpeed()

        return copy()
    }
}

data class FileStatistics(
        val upload: UploaderStatistics,
        val download: DownloaderStatistics
)

class SpeedMonitor(
        private val retainPeriod: Long = 60 * 1000
) {

    private var startTime: Long = 0L
    private var lastSize: Long = 0L

    private val data: MutableList<Pair<Long, Long>> = mutableListOf()

    var averageSpeed: Float = 0F
        private set

    /**
     * Speed in last 5 sec
     */
    var peekSpeed: Float = 0F
        private set

    @Synchronized
    fun reset() {
        data.clear()
        startTime = System.currentTimeMillis()
        lastSize = 0

        calc()
    }

    @Synchronized
    fun logData(size: Long) {
        data.add(Pair(System.currentTimeMillis(), size))
        lastSize = size
        calc()
    }

    @Synchronized
    fun peekSpeed(p: Long, current: Long = System.currentTimeMillis()): Float {
        val start = current - p

        return data.firstOrNull { (timestamp, _) ->
            timestamp >= start
        }?.let { (timestamp, size) ->
            calcSpeed(lastSize - size, current - timestamp)
        } ?: 0F
    }

    private fun calc() {
        val t = System.currentTimeMillis()

        // Remove old data
        val lastKeep = t - retainPeriod
        data.removeIf { (timestamp, _) ->
            timestamp < lastKeep
        }

        averageSpeed = calcSpeed(lastSize, t - startTime)
        peekSpeed = peekSpeed(5000, current = t)
    }

    companion object {
        private fun calcSpeed(size: Long, millis: Long): Float {
            val dTimeInSec = millis.toFloat() / 1000F

            val s = if (dTimeInSec < 0.001F) {
                0F
            } else {
                size / dTimeInSec
            }

            return if (s < 0F) {
                0F
            } else {
                s
            }
        }
    }
}
