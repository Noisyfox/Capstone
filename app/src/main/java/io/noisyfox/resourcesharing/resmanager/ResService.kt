package io.noisyfox.resourcesharing.resmanager

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import io.noisyfox.libfilemanager.FileManager
import io.noisyfox.libfilemanager.getSHA256HexString
import org.iotivity.base.*
import org.iotivity.ca.CaInterface
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

interface ResDownloadListener {
    fun onDownloadStarted(service: ResService, fileId: String)

    fun onBlockDownloaded(service: ResService, fileId: String, block: Int)

    fun onBlockDownloadFailed(service: ResService, fileId: String, block: Int, ex: Throwable?)

    fun onDownloadCompleted(service: ResService, fileId: String)

    fun onDownloadFailed(service: ResService, fileId: String, ex: Throwable?)

    fun onDownloadStopped(service: ResService, fileId: String)
}

class ResService(
        val namespace: String,
        val fileManager: FileManager
) {

    internal val namespaceHash = namespace.getSHA256HexString()
    internal val baseUri = "/foxres/$namespaceHash"
    internal val baseInterface = "foxres.${namespaceHash.substring(0..16)}"

    private val workingThread = object : HandlerThread("Resource Service Main Thread") {
        override fun onLooperPrepared() {
//            handler = Handler(looper)
        }
    }
    private lateinit var handler: Handler

    val downloadListeners: MutableList<ResDownloadListener> = mutableListOf()
    private val managedResources: MutableMap<String, ResContext> = mutableMapOf()
    private var indexHandler: OcResourceHandle? = null
    private val indexEntityHandler: OcPlatform.EntityHandler = OcPlatform.EntityHandler { request ->
        if (request.resourceUri != baseUri) {
            return@EntityHandler EntityHandlerResult.FORBIDDEN
        }

        when (request.requestType) {
            RequestType.GET -> {
                handleRequest(request)
            }
            else -> EntityHandlerResult.FORBIDDEN
        }
    }

    val isAlive get() = workingThread.isAlive

    fun startService(onInited: ((ResService) -> Unit)? = null) {
        workingThread.start()

        handler = Handler(workingThread.looper)
        runOnWorkingThread2 {

            // init iotivity namespace
            // register main index resource
            indexHandler = OcPlatform.registerResource(
                    baseUri,
                    RES_TYPE_INDEX,
                    baseInterface,
                    indexEntityHandler,
                    EnumSet.of(ResourceProperty.DISCOVERABLE, ResourceProperty.OBSERVABLE)
            )

            // TODO: start resource index discovery

            onInited?.invoke(this)
        }
    }

    fun stopService() {
        // TODO: unregister all resources

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
            val f = managedResources.getOrPut(fileId) {
                ResContext(this, fileManager.getFile(fileId))
            }

            // Check if f is complete
            if (f.file.isComplete()) {
                // Register to iotivity
                f.registerResource()
            }
        }
    }

    /**
     * Delete the downloaded content.
     *
     * If the file is currently downloading, stop the download first.
     */
    fun clearResource(fileId: String) {
        runOnWorkingThread2 {
            // stop downloading
            stopDownload(fileId)

            val f = getResContext(fileId)

            // Lock the file for writing
            val w = f.file.tryOpenWriter() ?: throw IOException("File still in use!")
            w.use {
                f.unregisterResource()
                w.clearFile()
            }
        }
    }

    fun startDownload(fileId: String) {
        runOnWorkingThread2 {
            val f = getResContext(fileId)

            if (f.file.isComplete()) {
                downloadListeners.safeForEach {
                    it.onDownloadCompleted(this, fileId)
                }
            } else {
                f.downloader.start()
            }
        }
    }

    fun stopDownload(fileId: String) {
        runOnWorkingThread2 {
            val f = getResContext(fileId)
            f.downloader.stop()
        }
    }

    private fun getResContext(fileId: String): ResContext = managedResources[fileId]
            ?: throw FileNotFoundException("File $fileId not registered!")

    private fun handleRequest(request: OcResourceRequest): EntityHandlerResult {
        val queryParameters = request.queryParameters
        val command = queryParameters[PARAM_COMMAND]
        if (command != null && command != COMMAND_INDEX) { // Optional command get_index
            return EntityHandlerResult.FORBIDDEN
        }

        // TODO: Generate index data
        // Send index
        return try {
            val rep = OcRepresentation()
            rep.setValue(PARAM_COMMAND, COMMAND_INDEX)

            request.sendResponse(rep)
            EntityHandlerResult.OK
        } catch (e: OcException) {
            e.printStackTrace()
            EntityHandlerResult.ERROR
        }
    }

    internal fun assertOnWorkingThread() {
        if (Thread.currentThread() != workingThread) {
            throw IllegalStateException("Must be called on ResService working thread!")
        }
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

        val result = queue.poll(workingThread)
        if (result == null) {
            throw IllegalStateException("Service not running!")
        } else {
            if (result.ex != null) {
                throw result.ex
            }

            return result
        }
    }

    private data class AsyncState<out T>(
            val result: T? = null,
            val ex: Throwable? = null
    )

    companion object {
        const val PARAM_COMMAND: String = "cmd"
        const val PARAM_HASH: String = "hash"
        const val PARAM_DATA: String = "data"
        const val COMMAND_INDEX: String = "get_index"
        const val COMMAND_HASH: String = "get_hash"
        const val COMMAND_DATA: String = "get_data"

        const val RES_TYPE_INDEX: String = "res.index"
        const val RES_TYPE_DATA: String = "res.data"

        fun initOcPlatform(context: Context) {
            CaInterface.setBTConfigure(2)
            val cfg = PlatformConfig(
                    context,
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

        private fun <R> BlockingQueue<R>.poll(workingThread: Thread): R? {
            var result: R? = null
            while (result == null && workingThread.isAlive) {
                result = this.poll(100, TimeUnit.MILLISECONDS)
            }

            return result
        }
    }
}

fun OcResourceRequest.sendResponse(representation: OcRepresentation) {
    val response = OcResourceResponse()
    response.setRequestHandle(requestHandle)
    response.setResourceHandle(resourceHandle)
    response.setResourceRepresentation(representation)
    OcPlatform.sendResponse(response)
}
