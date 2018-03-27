package io.noisyfox.libfilemanager

import com.fasterxml.jackson.annotation.JsonProperty

data class MetadataModel(
        val name: String,
        val url: String,
        val size: Long,
        val hash: String,
        val blocks: List<BlockModel>
)

data class BlockModel(
        val hash: String,
        val size: Long
)

data class ProgressModel(
        val completed: Boolean = false,
        @JsonProperty("completed_blocks") val completedBlocks: List<Int> = listOf(),
        @JsonProperty("other_blocks") val otherBlocks: Map<String, BlockProgressModel> = mapOf()
)

enum class ProgressStatus {
    Downloading,
    Failure
}

data class BlockProgressModel(
        val progress: Long,
        val status: ProgressStatus
)
