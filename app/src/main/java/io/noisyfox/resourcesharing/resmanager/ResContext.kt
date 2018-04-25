package io.noisyfox.resourcesharing.resmanager

import io.noisyfox.libfilemanager.MarkedFile
import io.noisyfox.libfilemanager.getSHA256HexString
import io.noisyfox.resourcesharing.resmanager.downloader.MainDownloader
import org.iotivity.base.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.util.*

/**
 * Any call to this class must on service's main thread!
 */
internal class ResContext(
        internal val service: ResService,
        val file: MarkedFile
) : Closeable {

    internal val downloader: MainDownloader = MainDownloader(this)

    private val baseHash = file.metadata.name.getSHA256HexString()
    private val baseUri: String = "${service.baseUri}/$baseHash"
    private val baseInterface = "${service.baseInterface}.${baseHash.substring(0..16)}"
    private var fileHandler: OcResourceHandle? = null
    private val entityHandler: OcPlatform.EntityHandler = OcPlatform.EntityHandler { request ->
        val uri = request.resourceUri
        if (!uri.startsWith(baseUri)) {
            return@EntityHandler EntityHandlerResult.FORBIDDEN
        }

        when (request.requestType) {
            RequestType.GET -> {
                when (uri) {
                    baseUri -> {
                        val queryParameters = request.queryParameters
                        val block = queryParameters[ResService.PARAM_BLOCK]

                        if (block == null) {
                            handleIndex(request)
                        } else {
                            handleData(request)
                        }
                    }
                    else -> EntityHandlerResult.FORBIDDEN
                }
            }
            else -> EntityHandlerResult.FORBIDDEN
        }
    }

    private var _sharing = true
    var isResourceSharing
        get() = _sharing
        set(value) {
            service.assertOnWorkingThread()

            _sharing = value
            if (_sharing) {
                registerResource()
            } else {
                unregisterResource()
            }
        }

    fun registerResource() {
        service.assertOnWorkingThread()

        if (!file.isComplete() || !_sharing) {
            return
        }

        if (fileHandler != null) {
            return
        }

        val fH = OcPlatform.registerResource(
                baseUri,
                ResService.RES_TYPE_FILE,
                baseInterface,
                entityHandler,
                EnumSet.of(ResourceProperty.DISCOVERABLE, ResourceProperty.OBSERVABLE)
        )

        fileHandler = fH

        logger.debug("Start sharing file ${file.id}")
    }

    fun unregisterResource() {
        service.assertOnWorkingThread()

        if (fileHandler == null) {
            return
        }

        try {
            OcPlatform.unregisterResource(fileHandler)
        } catch (e: OcException) {
            e.printStackTrace()
        }

        fileHandler = null

        logger.debug("Stop sharing file ${file.id}")
    }

    @Throws(IOException::class)
    fun clearResource() {
        service.assertOnWorkingThread()

        if (!downloader.isStopped()) {
            throw IllegalStateException("Downloader still running!")
        }

        unregisterResource()
        val w = file.tryOpenWriter() ?: throw IOException("File still in use!")
        w.use {
            w.clearFile()
        }
    }

    fun startDownload() {
        service.assertOnWorkingThread()

        if (!file.isComplete()) {
            if (downloader.start()) {
                service.postOnWorkingThread {
                    service.downloadListeners.safeForEach {
                        it.onDownloadStarted(service, file.id)
                    }
                }
            }
        } else {
            service.postOnWorkingThread {
                service.downloadListeners.safeForEach {
                    it.onDownloadStarted(service, file.id)
                }
                service.downloadListeners.safeForEach {
                    it.onDownloadCompleted(service, file.id)
                }
                service.downloadListeners.safeForEach {
                    it.onDownloadStopped(service, file.id)
                }
            }
        }
    }

    fun stopDownload() {
        service.assertOnWorkingThread()

        if (downloader.stop()) {
            service.postOnWorkingThread {
                service.downloadListeners.safeForEach {
                    it.onDownloadStopped(service, file.id)
                }
            }
        }
    }

    private fun handleIndex(request: OcResourceRequest): EntityHandlerResult {
        val queryParameters = request.queryParameters
        val command = queryParameters[ResService.PARAM_COMMAND]

        return try {
            when (command) {
                null, ResService.COMMAND_INDEX -> {
                    // TODO: Generate index data
                    EntityHandlerResult.OK
                }
                ResService.COMMAND_HASH -> {
                    val rep = OcRepresentation()
                    rep.setValue(ResService.PARAM_COMMAND, ResService.COMMAND_HASH)
                    rep.setValue(ResService.PARAM_HASH, file.metadata.hash)

                    request.sendResponse(rep)
                    EntityHandlerResult.OK
                }
                else -> EntityHandlerResult.FORBIDDEN
            }
        } catch (e: Exception) {
            e.printStackTrace()
            EntityHandlerResult.ERROR
        }
    }

    private fun handleData(request: OcResourceRequest): EntityHandlerResult {
        val queryParameters = request.queryParameters

        val block = queryParameters[ResService.PARAM_BLOCK]?.let {
            try {
                it.toInt()
            } catch (e: NumberFormatException) {
                null
            }
        } ?: return EntityHandlerResult.ERROR

        if (block < 0 || block >= file.metadata.blocks.count()) {
            return EntityHandlerResult.FORBIDDEN
        }

        val command = queryParameters[ResService.PARAM_COMMAND]

        return try {
            when (command) {
                null, ResService.COMMAND_DATA -> {
                    val data = file.readBlock(block)

                    val rep = OcRepresentation()
                    rep.setValue(ResService.PARAM_COMMAND, ResService.COMMAND_DATA)
                    rep.setValue(ResService.PARAM_DATA, data)

                    request.sendResponse(rep)
                    EntityHandlerResult.OK
                }
                ResService.COMMAND_HASH -> {
                    val rep = OcRepresentation()
                    rep.setValue(ResService.PARAM_COMMAND, ResService.COMMAND_HASH)
                    rep.setValue(ResService.PARAM_HASH, file.metadata.blocks[block].hash)

                    request.sendResponse(rep)
                    EntityHandlerResult.OK
                }
                else -> EntityHandlerResult.FORBIDDEN
            }
        } catch (e: Exception) {
            e.printStackTrace()
            EntityHandlerResult.ERROR
        }
    }

    override fun close() {
        service.assertOnWorkingThread()

        downloader.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ResContext::class.java)
    }
}
