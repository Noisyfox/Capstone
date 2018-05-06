package io.noisyfox.resourcesharing

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.Fragment
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.noisyfox.resourcesharing.resmanager.BlockInspectionModel
import kotlinx.android.synthetic.main.fragment_blocks.*
import org.slf4j.LoggerFactory


class BlockViewFragment : Fragment() {
    private val handler = Handler()
    private val statisticsUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            updateBlocks()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_blocks, container, false)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        block_view.layoutManager = GridAutoFitLayoutManager(context, 16)
        val meta = MainApplication.fileManager.getFile(MainApplication.TEST_FILE_1).metadata
        block_view.adapter = BlockViewAdapter(meta.blocks.size)

        updateBlocks()
    }

    override fun onResume() {
        super.onResume()

        val service = MainApplication.resourceService

        handler.postDelayed(statisticsUpdateRunnable, 1000)
    }

    override fun onPause() {
        val service = MainApplication.resourceService

        handler.removeCallbacks(statisticsUpdateRunnable)

        super.onPause()
    }

    private fun updateBlocks() {

        val insp = MainApplication.resourceService.getBlockInspections(MainApplication.TEST_FILE_1)

        val adapter = block_view.adapter as BlockViewAdapter

        adapter.setData(insp)

        val meta = MainApplication.fileManager.getFile(MainApplication.TEST_FILE_1).metadata

        var httpAss: Long = 0L
        var httpDown: Long = 0L
        var p2PAss: Long = 0L
        var p2PDown: Long = 0L
        var other: Long = 0L

        meta.blocks.forEachIndexed { i, b ->
            val ins = insp[i]
            if (ins == null) {
                other += b.size
            } else {
                if (ins.downloaded) {
                    if (ins.url.startsWith("http", true)) {
                        httpDown += b.size
                    } else {
                        p2PDown += b.size
                    }
                } else if (ins.distributed) {
                    if (ins.url.startsWith("http", true)) {
                        httpAss += b.size
                    } else {
                        p2PAss += b.size
                    }
                } else {
                    other += b.size
                }
            }
        }

        RowHolder(row_http_ass).run {
            size.text = toReadableSize(httpAss)
            percent.text = toReadablePercent(httpAss, meta.size)
        }

        RowHolder(row_http_down).run {
            size.text = toReadableSize(httpDown)
            percent.text = toReadablePercent(httpDown, meta.size)
        }

        RowHolder(row_p2p_ass).run {
            size.text = toReadableSize(p2PAss)
            percent.text = toReadablePercent(p2PAss, meta.size)
        }

        RowHolder(row_p2p_down).run {
            size.text = toReadableSize(p2PDown)
            percent.text = toReadablePercent(p2PDown, meta.size)
        }

        RowHolder(row_other).run {
            size.text = toReadableSize(other)
            percent.text = toReadablePercent(other, meta.size)
        }
    }

    private class RowHolder(row: View) {
        val percent: TextView = row.findViewById(R.id.percent)
        val size: TextView = row.findViewById(R.id.size)
    }

    private class BlockViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val blockView: ImageView = itemView as ImageView
    }

    private class BlockViewAdapter(
            val blockCount: Int
    ) : RecyclerView.Adapter<BlockViewHolder>() {

        private var inspection: Map<Int, BlockInspectionModel> = mapOf()

        fun setData(insp: Map<Int, BlockInspectionModel>) {
            inspection = insp
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_block, parent, false)

            return BlockViewHolder(v)
        }

        override fun getItemCount(): Int = blockCount

        override fun onBindViewHolder(holder: BlockViewHolder, position: Int) {
            val insp = inspection[position]
            if (insp == null) {
                holder.blockView.imageTintList = ColorStateList.valueOf(Color.GRAY)
                holder.blockView.setImageResource(R.drawable.block_normal)
            } else {
                holder.blockView.imageTintList = ColorStateList.valueOf(getColor(insp.url))
                if (insp.downloaded) {
                    holder.blockView.setImageResource(R.drawable.block_ok)
                } else if (insp.distributed) {
                    holder.blockView.setImageResource(R.drawable.block_normal)
                } else {
                    holder.blockView.imageTintList = ColorStateList.valueOf(Color.GRAY)
                    holder.blockView.setImageResource(R.drawable.block_normal)
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BlockViewFragment::class.java)

        @JvmStatic
        fun newInstance() = BlockViewFragment()

        private inline fun logException(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                logger.error("", e)
            }
        }

        private fun getColor(url: String): Int {
            return if (url.startsWith("http", true)) {
                0xff93e045
            } else {
                0xffFBC042
            }.toInt()
        }
    }
}
