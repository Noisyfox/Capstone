package io.noisyfox.resourcesharing.resmanager

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.noisyfox.libfilemanager.MarkedFile
import java.io.File

class BlockInspector(
        file: MarkedFile
) {
    private val inspectionFile: File = file.getCompanionFile("insp.json")

    private var blockDistributeMap: Map<String, Set<Int>>? = null

    @Synchronized
    private fun readInspections(): Map<String, InternalBlockInspectionModel> {
        try {
            return jacksonObjectMapper()
                    .readValue(inspectionFile)
        } catch (e: Exception) {
        }

        return mapOf()
    }

    fun getInspection(block: Int): BlockInspectionModel? = getInspections()[block]

    fun getInspections(): Map<Int, BlockInspectionModel> {
        val (insp, bMap) = synchronized(this) {
            Pair(readInspections(), blockDistributeMap)
        }

        val result = mutableMapOf<Int, BlockInspectionModel>()
        // Merge data
        insp.forEach { b, i ->
            val block = b.toInt()
            result[block] = BlockInspectionModel(url = i.url, distributed = false, downloaded = true)
        }

        bMap?.forEach { url, blocks ->
            blocks.forEach { block ->
                val old = result[block]
                if (old == null) {
                    result[block] = BlockInspectionModel(url = url, distributed = true, downloaded = false)
                } else {
                    if (old.url == url) {
                        result[block] = old.copy(distributed = true)
                    }
                }
            }
        }

        return result
    }

    @Synchronized
    fun withBlockDownloaded(block: Int, downloaderId: String) {
        (readInspections() + Pair(block.toString(), InternalBlockInspectionModel(url = downloaderId))).save()
    }

    fun withBlockRedestributed(dMap: Map<String, Set<Int>>) {
        blockDistributeMap = dMap
    }

    @Synchronized
    private fun Map<String, InternalBlockInspectionModel>.save() {
        jacksonObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(inspectionFile, this)
    }

    @Synchronized
    fun clear() {
        inspectionFile.delete()
        blockDistributeMap = null
    }
}

private data class InternalBlockInspectionModel(
        val url: String
)

data class BlockInspectionModel(
        val url: String,
        val distributed: Boolean,
        val downloaded: Boolean
)
