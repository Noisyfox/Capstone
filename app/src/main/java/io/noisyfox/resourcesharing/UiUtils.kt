package io.noisyfox.resourcesharing


fun toReadableSpeed(speed: Float): String {
    if (speed <= 0) {
        return "0B/s"
    }

    if (speed < 500) {
        return "%.2fB/s".format(speed)
    }

    var s = speed / 1024F
    if (s < 500) {
        return "%.2fKB/s".format(s)
    }

    s /= 1024F
    if (s < 500) {
        return "%.2fMB/s".format(s)
    }

    s /= 1024F
    return "%.2fGB/s".format(s)
}

fun toReadableSize(size: Long): String {
    if (size <= 0) {
        return "0B"
    }

    if (size < 500) {
        return "${size}B"
    }

    var s = size / 1024F
    if (s < 500) {
        return "%.2fKB".format(s)
    }

    s /= 1024F
    if (s < 500) {
        return "%.2fMB".format(s)
    }

    s /= 1024F
    return "%.2fGB".format(s)
}

fun toReadableTime(millis: Long): String {
    val s = millis / 1000

    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, (s % 60))
}
