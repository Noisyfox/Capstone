package io.noisyfox.libfilemanager

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.min

class FileManager(internal val baseDir: String) {

    private val fileLocks: ConcurrentHashMap<String, ReentrantReadWriteLock> = ConcurrentHashMap()

    @Throws(FileNotFoundException::class)
    fun getFile(fileId: String): MarkedFile {
        val h = fileId.getSHA256HexString()

        // Open meta file
        val metadata = jacksonObjectMapper().readValue<MetadataModel>(File(baseDir, "$h.meta.json"))

        val lock = fileLocks.getOrPut(h) { ReentrantReadWriteLock() }
        return MarkedFile(lock, this, h, metadata)
    }

    companion object {
        fun createMetaFromFile(file: String, id: String, metaOutDir: String, url: String = "", blockSize: Int = 1024 * 1024 /* 1 MB */) {
            val inputFile = File(file)
            val meta = FileInputStream(inputFile).buffered().use {
                val fileSize = inputFile.length()

                val mainDigest = MessageDigest.getInstance("SHA-256")
                val blockDigest = MessageDigest.getInstance("SHA-256")

                val buffer = ByteArray(1024)

                val blocks = mutableListOf<BlockModel>()

                var currentBlock: Int
                do {
                    currentBlock = 0
                    blockDigest.reset()
                    while (true) {
                        val blockRemain = blockSize - currentBlock
                        if (blockRemain == 0) {
                            break
                        }
                        val s = it.read(buffer, 0, min(blockRemain, 1024))
                        if (s == -1) {
                            break
                        }
                        currentBlock += s
                        mainDigest.update(buffer, 0, s)
                        blockDigest.update(buffer, 0, s)
                    }
                    if (currentBlock != 0) {
                        blocks.add(BlockModel(blockDigest.toHexString(), currentBlock))
                    }
                } while (currentBlock != 0)

                MetadataModel(id, url, fileSize, mainDigest.toHexString(), blocks)
            }

            jacksonObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(File(metaOutDir, "${id.getSHA256HexString()}.meta.json"), meta)
        }
    }
}
