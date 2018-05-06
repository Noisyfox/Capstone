package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.resourcesharing.resmanager.DownloaderStatistics
import kotlin.concurrent.thread

/**
 * A background task updating download statistics.
 */
internal class DownloadMonitor(
        private val downloader: MainDownloader,
        private val statistics: DownloaderStatistics
) : DownloaderComponent {

    private val threadLock = java.lang.Object()
    private var requestToStop = false
    private var workingThread: Thread? = null

    private val workingRunnable = Runnable {
        try {
            downloader.onComponentStarted(this)

            while (!requestToStop) {
                statistics.updateSpeed()

                downloader.updateDownloader()

                // Wait 5s
                synchronized(threadLock) {
                    threadLock.wait(5 * 1000)
                }
            }
        } catch (_: InterruptedException) {
            // Ignored
        } finally {
            synchronized(threadLock) {
                requestToStop = false
                workingThread = null
            }
            downloader.onComponentStopped(this)
        }
    }

    override fun start(): Boolean {
        synchronized(threadLock) {
            if (requestToStop) {
                throw IllegalStateException("Previous session quiting!")
            }

            val t = workingThread
            if (t != null && t.isAlive) {
                return true
            }
            workingThread = thread(start = true, name = "DownloadMonitor working thread") {
                workingRunnable.run()
            }
            return false
        }
    }

    override fun stop(): Boolean {
        synchronized(threadLock) {
            val t = workingThread ?: return true

            requestToStop = true
            threadLock.notifyAll()

            return false
        }
    }
}