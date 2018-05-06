package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.resourcesharing.resmanager.ResService
import org.iotivity.base.*
import java.util.*
import kotlin.concurrent.thread

/**
 * All callbacks will be called from block downloader's own working thread
 */
internal interface ResourceFindListener : DownloaderComponentListener {
    fun onResourceFound(r: OcResource)
    fun onResourceLost(r: OcResource)
}

internal class ResFinder(
        private val downloader: MainDownloader
) : DownloaderComponent, OcPlatform.OnResourceFoundListener {

    private val threadLock = java.lang.Object()
    private var requestToStop = false
    private var workingThread: Thread? = null

    private val resourceDiscovered = mutableMapOf<OcResourceIdentifier, ResourceHolder>()
    private val workingRunnable = Runnable {
        try {
            downloader.onComponentStarted(this)

            while (!requestToStop) {
                OcPlatform.findResource(
                        "",
                        "${OcPlatform.WELL_KNOWN_QUERY}?rt=${ResService.RES_TYPE_FILE}&if=${downloader.resContext.baseInterface}",
//                        EnumSet.complementOf(EnumSet.of(OcConnectivityType.CT_ADAPTER_NFC, OcConnectivityType.CT_FLAG_SECURE)),
                        EnumSet.of(OcConnectivityType.CT_ADAPTER_IP),
                        this,
                        QualityOfService.HIGH
                )

                // Check outdated resource
                val currentTime = System.currentTimeMillis()
                val outdated = resourceDiscovered.filter { (currentTime - it.value.lastFound) > 120 * 1000 }
                outdated.forEach { k, _ -> resourceDiscovered.remove(k) }
                outdated.forEach { _, v -> downloader.onResourceLost(v.resource) }

                // Wait 60s
                synchronized(threadLock) {
                    threadLock.wait(60 * 1000)
                }
            }
        } catch (_: InterruptedException) {
            // Ignored
        } finally {
            synchronized(threadLock) {
                requestToStop = false
                workingThread = null
                resourceDiscovered.clear()
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
            resourceDiscovered.clear()
            workingThread = thread(start = true, name = "ResFinder working thread") {
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

    override fun onResourceFound(p0: OcResource?) {
        if (p0 == null) {
            return
        }

        val newResource = synchronized(threadLock) {
            val t = workingThread
            if (t == null || !t.isAlive || requestToStop) {
                return@synchronized null
            }

            val r = resourceDiscovered[p0.uniqueIdentifier]
            if (r == null) {
                val n = ResourceHolder(p0)
                resourceDiscovered[p0.uniqueIdentifier] = n

                p0.allHosts.first { it.startsWith("coap+tcp") }?.let {
                    p0.host = it
                }

                n
            } else {
                r.foundAgain()
                null
            }
        }

        if (newResource != null) {
            downloader.onResourceFound(newResource.resource)
        }
    }

    override fun onFindResourceFailed(p0: Throwable?, p1: String?) {
    }

    private class ResourceHolder(
            val resource: OcResource,
            var lastFound: Long = System.currentTimeMillis()
    ) {
        fun foundAgain() {
            lastFound = System.currentTimeMillis()
        }
    }
}