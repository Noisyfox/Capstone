package io.noisyfox.resourcesharing.resmanager

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import io.noisyfox.libfilemanager.FileManager
import io.noisyfox.libfilemanager.MarkedFile
import io.noisyfox.libfilemanager.getSHA256HexString
import org.iotivity.base.*
import org.iotivity.ca.CaInterface
import java.util.*
import java.util.concurrent.LinkedBlockingDeque

class ResService(
        val namespace: String,
        val fileManager: FileManager
) {

    private val namespaceHash = namespace.getSHA256HexString()
    private val workingThread = object : HandlerThread("Resource Service Main Thread") {
        override fun onLooperPrepared() {
//            handler = Handler(looper)
        }
    }
    private lateinit var handler: Handler

    private val managedResources: MutableMap<String, MarkedFile> = mutableMapOf()

    val isAlive get() = workingThread.isAlive

    fun startService(onInited: ((ResService) -> Unit)? = null) {
        workingThread.start()

        handler = Handler(workingThread.looper)
        runOnWorkingThread2 {
            onInited?.invoke(this)
        }
    }

    fun stopService() {
        workingThread.quitSafely()
        workingThread.join()
    }

    /**
     * Add file for management. A file can be opened once it's registered.
     * If the file isn't complete, then you can download it.
     *
     * If given file is completed, then register file to iotivity so other devices can find.
     */
    fun registerResource(fileId: String) {
        runOnWorkingThread2 {
            val f = managedResources[fileId] ?: run<MarkedFile> {
                val nf = fileManager.getFile(fileId)
                managedResources[fileId] = nf
                nf
            }

            // Check if f is complete
            if (f.isComplete()) {
                // Register to iotivity
                registerResource(f)
            }
        }
    }

    private fun registerResource(file: MarkedFile) {
    }

    fun runOnWorkingThread(action: () -> Unit): Unit = runOnWorkingThread2(action)
    fun <R> runOnWorkingThread(action: () -> R): R = runOnWorkingThread2(action)
    fun postOnWorkingThread(action: () -> Unit) = postOnWorkingThread2(action)

    private inline fun postOnWorkingThread2(crossinline action: () -> Unit) {
        if (!handler.post {
                    action()
                }
        ) {
            throw IllegalStateException("Service not running!")
        }
    }

    private inline fun runOnWorkingThread2(crossinline action: () -> Unit) {
        runOnWorkingThread3(action)
    }

    private inline fun <R> runOnWorkingThread2(crossinline action: () -> R): R = runOnWorkingThread3(action).result!!

    private inline fun <R> runOnWorkingThread3(crossinline action: () -> R): AsyncState<R> {
        if (Thread.currentThread() == workingThread) {
            return AsyncState(action(), null)
        }

        // Post and wait for result
        val queue = LinkedBlockingDeque<AsyncState<R>>(1)
        if (!handler.post {
                    val result = try {
                        AsyncState(action(), null)
                    } catch (e: Throwable) {
                        AsyncState<R>(null, e)
                    }
                    queue.add(result)
                }
        ) {
            throw IllegalStateException("Service not running!")
        }

        val r = queue.take()
        if (r.ex != null) {
            throw r.ex
        }

        return r
    }

    private data class AsyncState<out T>(
            val result: T? = null,
            val ex: Throwable? = null
    )

    companion object {
        fun initOcPlatform(activity: Activity) {
            CaInterface.setBTConfigure(2)
            val cfg = PlatformConfig(
                    activity,
                    activity.baseContext,
                    ServiceType.IN_PROC,
                    ModeType.CLIENT_SERVER,
                    "0.0.0.0",
                    0,
                    QualityOfService.MEDIUM
            )
            cfg.setAvailableTransportType(
                    // Don't support NFC
                    EnumSet.complementOf(EnumSet.of(OcConnectivityType.CT_ADAPTER_NFC))
            )
            OcPlatform.Configure(cfg)
        }
    }
}
