package io.noisyfox.resourcesharing.resmanager

data class DownloaderStatistics(
    var downloadBytes: Long = 0L
) {

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

}
