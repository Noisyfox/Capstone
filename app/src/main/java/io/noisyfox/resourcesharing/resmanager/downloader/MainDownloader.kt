package io.noisyfox.resourcesharing.resmanager.downloader

import io.noisyfox.libfilemanager.MarkedFileWriter
import io.noisyfox.resourcesharing.resmanager.ResContext
import io.noisyfox.resourcesharing.resmanager.safeClose
import io.noisyfox.resourcesharing.resmanager.safeForEach
import org.iotivity.base.OcResource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

internal class MainDownloader(
        internal val resContext: ResContext
) : BlockDownloaderListener, ResourceFindListener, Closeable {

    private enum class Status {
        Starting,
        Running,
        Stopping,
        Stopped,
        StartAfterStopped,
        StopAfterStarted
    }

    private val service = resContext.service
    private val listeners = service.downloadListeners
    private val file = resContext.file

    private var fileWriter: MarkedFileWriter? = null

    private val componentEden = mutableListOf<DownloaderComponent>()
    private val components = mutableListOf<DownloaderComponent>()
    private val blockDistributeMap: MutableMap<Long, Pair<BlockDownloader, MutableSet<Int>>> = mutableMapOf()
    private val nextDownloaderId: AtomicLong = AtomicLong(0)

    private var currentStatus: Status = Status.Stopped

    private var _useHttp = true
    internal var isHttpDownloadEnabled: Boolean
        get() = _useHttp
        set(value) {
            service.assertOnWorkingThread()

            when (currentStatus) {
                Status.Stopped -> _useHttp = value
                else -> throw IllegalStateException("Can't change during download!")
            }
        }

    private var _useResDiscovery = true
    internal var isResourceDiscoveryEnabled: Boolean
        get() = _useResDiscovery
        set(value) {
            service.assertOnWorkingThread()

            when (currentStatus) {
                Status.Stopped -> _useResDiscovery = value
                else -> throw IllegalStateException("Can't change during download!")
            }
        }

    internal fun start(): Boolean {
        service.assertOnWorkingThread()

        return when (currentStatus) {
            Status.Stopping -> {
                currentStatus = Status.StartAfterStopped
                false
            }
            Status.StartAfterStopped -> false
            Status.StopAfterStarted -> {
                currentStatus = Status.Starting
                false
            }
            Status.Running -> true
            Status.Starting -> false
            Status.Stopped -> {
                if (components.isNotEmpty()) {
                    throw IllegalStateException("Components not empty! Fetal error!")
                }

                val w = file.tryOpenWriter()
                        ?: throw IllegalStateException("Unable to open writer!")

                fileWriter = w

                currentStatus = Status.Starting

                // Pure http download
                if (isHttpDownloadEnabled) {
                    val d = HttpBlockDownloader(nextDownloaderId.getAndIncrement(), file.metadata.url, w)
                    if (hatchNewDownloader(d)) {
                        d.downloadListeners += this
//                    d.assignBlocks(HashSet(w.openableBlocks))
                    }
                }

                if (isResourceDiscoveryEnabled) {
                    val finder = ResFinder(this)
                    hatchNewDownloader(finder)
                }

                if (componentEden.isEmpty()) {
                    currentStatus = Status.Running
                    rebalanceBlocks()
                    true
                } else {
                    false
                }
            }
        }
    }

    internal fun stop(): Boolean {
        service.assertOnWorkingThread()

        return when (currentStatus) {
            Status.Starting -> {
                currentStatus = Status.StopAfterStarted
                false
            }
            Status.Stopping -> false
            Status.Stopped -> true
            Status.StartAfterStopped -> {
                currentStatus = Status.Stopping
                false
            }
            Status.StopAfterStarted -> false
            Status.Running -> {
                components.safeForEach {
                    if (it.stop()) {
                        components.remove(it)
                    }
                }
                if (componentEden.isEmpty() && components.isEmpty()) {
                    currentStatus = Status.Stopped
                    fileWriter?.safeClose()
                    fileWriter = null
                    true
                } else {
                    currentStatus = Status.Stopping
                    false
                }
            }
        }
    }

    internal fun isStopped(): Boolean {
        service.assertOnWorkingThread()

        return currentStatus == Status.Stopped
    }

    /**
     * Rebalance blocks among downloaders
     */
    private fun rebalanceBlocks() {
        service.assertOnWorkingThread()

        val w = fileWriter!!

        val httpDownloaders = components.filterIsInstance<HttpBlockDownloader>()
        val sharingDownloaders = components.filterIsInstance<SharingBlockDownloader>()
        val hdCount = httpDownloaders.size
        val sdCount = sharingDownloaders.size

        val total = hdCount + sdCount
        if (total == 0) {
            // Nothing to do
            return
        }

        // Remove absent from blockDistributeMap
        val allDownloaders = (httpDownloaders + sharingDownloaders)
        val ids = allDownloaders.map { it.id }.toSet()
        val absent = blockDistributeMap.keys.filter { it !in ids }
        blockDistributeMap -= absent

        // Evenly distribute for now, TODO: add block rebalance strategy
        val remainBlocks = w.writableBlocks
        val size = ceil(remainBlocks.size.toFloat() / total.toFloat()).toInt()

        // Remove blocks if assigned more than <size> blocks
        blockDistributeMap.forEach { _, (downloader, blocks) ->
            if (blocks.size > size) {
                val extra = when (downloader) {
                    is HttpBlockDownloader -> {
                        blocks.toList().sortedDescending().take(blocks.size - size)
                    }
                    else -> {
                        blocks.toList().sorted().take(blocks.size - size)
                    }
                }.toSet()

                downloader.unassignBlocks2(extra)
            }
        }

        val unassignedBlocks = (remainBlocks - (blockDistributeMap.values.map { it.second }.flatten())).sorted().toMutableList()
        allDownloaders.map { Pair(it.getAssignedBlocksCount(), it) }.sortedBy { it.first }
                .forEach { (c, downloader) ->
                    val s = when (downloader) {
                        is HttpBlockDownloader -> {
                            unassignedBlocks.take(size - c).toSet()
                        }
                        else -> {
                            unassignedBlocks.takeLast(size - c).toSet()
                        }
                    }
                    unassignedBlocks.removeAll(s)
                    downloader.assignBlocks2(s)
                }

        if (unassignedBlocks.isNotEmpty()) {
            // Hmmmm, shouldn't happen
            // Give all to the last downloader!
            allDownloaders.last().assignBlocks2(unassignedBlocks.toSet())
        }
    }

    private fun BlockDownloader.getAssignedBlocksCount(): Int {
        service.assertOnWorkingThread()

        return blockDistributeMap[id]?.second?.size ?: 0
    }

    private fun BlockDownloader.unassignBlocks2(blocks: Set<Int>) {
        service.assertOnWorkingThread()

        blockDistributeMap[id]?.second?.removeAll(blocks)
        this.unassignBlocks(blocks)
    }

    private fun BlockDownloader.assignBlocks2(blocks: Set<Int>) {
        service.assertOnWorkingThread()

        blockDistributeMap.getOrPut(id) { Pair(this, mutableSetOf()) }.second.addAll(blocks)
        this.assignBlocks(blocks)
    }

    private fun hatchNewDownloader(downloader: DownloaderComponent): Boolean {
        service.assertOnWorkingThread()

        return when (currentStatus) {
            Status.Starting -> {
                componentEden.add(downloader)
                if (downloader.start()) {
                    componentEden.remove(downloader)
                    components.add(downloader)
                }
                true
            }
            Status.Running -> {
                componentEden.add(downloader)
                if (downloader.start()) {
                    onComponentStarted(downloader)
                }
                true
            }
            Status.Stopping -> false
            Status.Stopped -> false
            Status.StartAfterStopped -> false
            Status.StopAfterStarted -> false
        }
    }

    override fun onComponentStarted(component: DownloaderComponent) {
        service.runOnWorkingThread {
            if (component !in componentEden) {
                throw IllegalStateException("Downloader not in Eden!")
            }
            componentEden.remove(component)
            components.add(component)

            when (currentStatus) {
                Status.Starting -> {
                    if (componentEden.isEmpty()) {
                        currentStatus = Status.Running
                        listeners.safeForEach {
                            it.onDownloadStarted(service, file.id)
                        }
                        rebalanceBlocks()
                    }
                }
                Status.StopAfterStarted -> {
                    if (componentEden.isEmpty()) {
                        currentStatus = Status.Running

                        if (stop()) {
                            listeners.safeForEach {
                                it.onDownloadStopped(service, file.id)
                            }
                        }
                    }
                }
                Status.Running -> {
                    rebalanceBlocks()
                }
                Status.Stopping, Status.StartAfterStopped -> {
                    // Oops! God told us to stop
                    if (component.stop()) {
                        onComponentStopped(component)
                    }
                }
                Status.Stopped -> {
                    // Something goes wrong!
                    logger.error("Child downloader started after main downloader stopped! Not good!")
                    if (component.stop()) {
                        service.postOnWorkingThread {
                            onComponentStopped(component)
                        }
                    }
                }
            }
        }
    }

    override fun onComponentStopped(component: DownloaderComponent) {
        service.runOnWorkingThread {
            when (component) {
                in componentEden -> {
                    logger.warn("Child downloader died in Eden!")
                    componentEden.remove(component)
                }
                in components -> components.remove(component)
                else -> throw IllegalStateException("Downloader not in list!")
            }

            if (component is BlockDownloader) {
                blockDistributeMap.remove(component.id)
            }

            when (currentStatus) {
                Status.Starting, Status.StopAfterStarted -> {
                    // Ignored
                    logger.debug("Child downloader stopped during main downloader starting.")
                    if (componentEden.isEmpty() && components.isEmpty()) {
                        logger.error("All child downloader stopped during main downloader starting!")

                        currentStatus = Status.Running
                        if (stop()) {
                            listeners.safeForEach {
                                it.onDownloadStopped(service, file.id)
                            }
                        }
                    }
                }
                Status.Running -> {
                    if (componentEden.isEmpty() && components.isEmpty()) {
                        logger.error("All child downloader stopped during main downloader running!")

                        if (stop()) {
                            listeners.safeForEach {
                                it.onDownloadStopped(service, file.id)
                            }
                        }
                    } else {
                        rebalanceBlocks()
                    }
                }
                Status.Stopping -> {
                    if (componentEden.isEmpty() && components.isEmpty()) {
                        currentStatus = Status.Stopped
                        fileWriter?.safeClose()
                        fileWriter = null

                        listeners.safeForEach {
                            it.onDownloadStopped(service, file.id)
                        }
                    }
                }
                Status.StartAfterStopped -> {
                    if (componentEden.isEmpty() && components.isEmpty()) {
                        currentStatus = Status.Stopped
                        fileWriter?.safeClose()
                        fileWriter = null

                        if (start()) {
                            listeners.safeForEach {
                                it.onDownloadStarted(service, file.id)
                            }
                            rebalanceBlocks()
                        }
                    }
                }
                Status.Stopped -> {
                    // Ignored
                    logger.warn("Child downloader stopped after main downloader stopped. Not ideal.")
                }
            }
        }
    }

    override fun onBlockDownloaded(downloader: BlockDownloader, block: Int) {
        service.runOnWorkingThread {
            blockDistributeMap[downloader.id]?.second?.remove(block)
            downloader.unassignBlocks(setOf(block))

            logger.info("Block $block/${file.metadata.blocks.size - 1} download completed for file ${file.id} by downloader $downloader.")
            listeners.safeForEach {
                it.onBlockDownloaded(service, file.id, block)
            }

            val w = fileWriter!!
            if (w.writableBlocks.isEmpty()) {
                listeners.safeForEach {
                    it.onDownloadCompleted(service, file.id)
                }

                if (stop()) {
                    listeners.safeForEach {
                        it.onDownloadStopped(service, file.id)
                    }
                }
            }
        }
    }

    override fun onBlockDownloadFailed(downloader: BlockDownloader, block: Int, ex: Throwable?) {
        service.runOnWorkingThread {
            blockDistributeMap[downloader.id]?.second?.remove(block)
            downloader.unassignBlocks(setOf(block))

            logger.info("Block $block/${file.metadata.blocks.size - 1} download failed for file ${file.id} by downloader $downloader.", ex)
            listeners.safeForEach {
                it.onBlockDownloadFailed(service, file.id, block, ex)
            }

            // TODO: retry for X times or failed!
        }
    }

    override fun onResourceFound(r: OcResource) {
        logger.debug("Resource found: ${r.host}${r.uri}")

        service.runOnWorkingThread {
            val w = fileWriter ?: return@runOnWorkingThread

            val d = SharingBlockDownloader(nextDownloaderId.getAndIncrement(), r, w)
            if (hatchNewDownloader(d)) {
                d.downloadListeners += this
            }
        }
    }

    override fun onResourceLost(r: OcResource) {
        logger.debug("Resource lost: ${r.host}${r.uri}")

        service.runOnWorkingThread {
            (components + componentEden)
                    .filter {
                        it is SharingBlockDownloader
                                && it.resource.uniqueIdentifier == r.uniqueIdentifier
                    }
                    .forEach {
                        if (it.stop()) {
                            onComponentStopped(it)
                        }
                    }
        }
    }

    override fun close() {
        stop()
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(MainDownloader::class.java)
    }
}