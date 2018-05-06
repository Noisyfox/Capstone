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
    }

    private class BlockViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val blockView: ImageView = itemView as ImageView
    }

    private class BlockViewAdapter(
            val blockCount: Int
    ) : RecyclerView.Adapter<BlockViewHolder>() {

        private var inspection: Map<Int, BlockInspectionModel>? = null

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
            val insp = inspection?.get(position)
            if (insp == null) {
                holder.blockView.setImageResource(R.drawable.block_normal)
                holder.blockView.imageTintList = ColorStateList.valueOf(Color.GRAY)
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

        private fun getColor(url: String): Int{
            return if(url.startsWith("http", true)){
                0xff93e045
            } else {
                0xffFBC042
            }.toInt()
        }
    }
}
