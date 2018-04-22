package io.noisyfox.resourcesharing.resmanager

import io.noisyfox.libfilemanager.MarkedFile
import io.noisyfox.libfilemanager.getSHA256HexString
import io.noisyfox.resourcesharing.resmanager.downloader.MainDownloader
import org.iotivity.base.*
import java.util.*

internal class ResContext(
        internal val service: ResService,
        val file: MarkedFile
) {
    internal val downloader: MainDownloader = MainDownloader(this)

    private val baseHash = file.metadata.name.getSHA256HexString()
    val baseUri: String = "${service.baseUri}/$baseHash}"
    private val baseInterface = "${service.baseInterface}.${baseHash.substring(0..16)}"
    private val dataInterface = "$baseInterface.data"
    private var indexHandler: OcResourceHandle? = null
    private var blockHandlers: List<OcResourceHandle>? = null
    private val entityHandler: OcPlatform.EntityHandler = OcPlatform.EntityHandler { request ->
        val uri = request.resourceUri
        if (!uri.startsWith(baseUri)) {
            return@EntityHandler EntityHandlerResult.FORBIDDEN
        }

        when (request.requestType) {
            RequestType.GET -> {
                if (uri == baseUri) {
                    handleIndex(request)
                } else {
                    handleData(request)
                }
            }
            else -> EntityHandlerResult.FORBIDDEN
        }
    }

    fun registerResource() {
        if (indexHandler != null) {
            return
        }

        val iH = OcPlatform.registerResource(
                baseUri,
                ResService.RES_TYPE_INDEX,
                baseInterface,
                entityHandler,
                EnumSet.of(ResourceProperty.DISCOVERABLE, ResourceProperty.OBSERVABLE)
        )
        val bHs = mutableListOf<OcResourceHandle>()
        for (i in 0 until file.metadata.blocks.size) {
            try {
                bHs.add(OcPlatform.registerResource(
                        "$baseUri/$i",
                        ResService.RES_TYPE_DATA,
                        dataInterface,
                        entityHandler,
                        EnumSet.of(ResourceProperty.DISCOVERABLE, ResourceProperty.OBSERVABLE)
                ))
            } catch (e: OcException) {
                e.printStackTrace()
                bHs.add(iH)
                bHs.forEach {
                    try {
                        OcPlatform.unregisterResource(it)
                    } catch (e: OcException) {
                        e.printStackTrace()
                    }
                }
                throw e
            }
        }

        indexHandler = iH
        blockHandlers = bHs
    }

    fun unregisterResource() {
        if (indexHandler == null) {
            return
        }

        val handlers = blockHandlers!! + indexHandler!!
        handlers.forEach {
            try {
                OcPlatform.unregisterResource(it)
            } catch (e: OcException) {
                e.printStackTrace()
            }
        }

        indexHandler = null
        blockHandlers = null
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
        } catch (e: OcException) {
            e.printStackTrace()
            EntityHandlerResult.ERROR
        }
    }

    private fun handleData(request: OcResourceRequest): EntityHandlerResult {
        val uri = request.resourceUri

        // Parse uri
        if (!uri.startsWith("$baseUri/")) {
            return EntityHandlerResult.FORBIDDEN
        }
        val block = try {
            uri.substring(baseUri.length + 1).toInt()
        } catch (e: NumberFormatException) {
            return EntityHandlerResult.FORBIDDEN
        }
        if (block < 0 || block >= file.metadata.blocks.count()) {
            return EntityHandlerResult.FORBIDDEN
        }

        val queryParameters = request.queryParameters
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
        } catch (e: OcException) {
            e.printStackTrace()
            EntityHandlerResult.ERROR
        }
    }
}
