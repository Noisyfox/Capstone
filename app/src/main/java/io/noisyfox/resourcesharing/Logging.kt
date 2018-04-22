package io.noisyfox.resourcesharing

import io.noisyfox.resourcesharing.resmanager.safeForEach
import java.text.SimpleDateFormat
import java.util.*

class Logging {

    interface LoggingListener {
        fun onLog(message: String)
    }

    companion object {
        private val timeFormatter = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT)
        private val messages = mutableListOf<String>()

        val listeners: MutableList<LoggingListener> = mutableListOf()

        fun init() {
            messages.clear()
            listeners.clear()
        }

        fun log(message: String) {
            val l = "[${timeFormatter.format(Date())}]$message"
            messages.add(l)
            listeners.safeForEach {
                it.onLog(l)
            }
        }

        fun getAllMessages(): String {
            return messages.joinToString("\n")
        }
    }
}