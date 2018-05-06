package io.noisyfox.libfilemanager

import com.fasterxml.jackson.annotation.JsonProperty

data class MetadataModel(
        val name: String,
        val url: String,
        val size: Long,
        val hash: String,
        val blocks: List<BlockModel>
) {
    private val offsets: List<Long>

    init {
        val _offsets = mutableListOf<Long>()

        blocks.fold(0L) { offset, b ->
            _offsets.add(offset)

            offset + b.size
        }

        offsets = _offsets
    }

    fun getBlockOffset(index: Int) = offsets[index]
}

data class BlockModel(
        val hash: String,
        val size: Int
)

data class ProgressModel(
        val completed: Boolean = false,
        @JsonProperty("completed_blocks") val completedBlocks: List<Int> = listOf(),
        @JsonProperty("other_blocks") val otherBlocks: Map<String, BlockProgressModel> = mapOf()
) {
    fun withBlockComplete(index: Int): ProgressModel {
        if (index in completedBlocks) {
            return this
        }
        ensureNotComplete()

        return copy(
                completedBlocks = (completedBlocks + index).sorted(),
                otherBlocks = otherBlocks.filter { it.key != index.toString() }
        )
    }

    fun withAllComplete(): ProgressModel {
        if (otherBlocks.isNotEmpty()) {
            throw IllegalStateException("otherBlocks is not empty! Progress could lost!")
        }

        return copy(completed = true, otherBlocks = mapOf())
    }

    fun withStatus(index: Int, progress: BlockProgressModel): ProgressModel {
        ensureNotComplete()
        if (index in completedBlocks) {
            throw IllegalStateException("Can't update a completed block!")
        }

        return copy(otherBlocks = otherBlocks + Pair(index.toString(), progress))
    }

    private fun ensureNotComplete() {
        if (completed) {
            throw IllegalStateException("Can't update a completed file!")
        }
    }
}

enum class ProgressStatus {
    Downloading,
    HashMismatch,
    Failure,
    Completed
}

data class BlockProgressModel(
        val progress: Int,
        val status: ProgressStatus
)
