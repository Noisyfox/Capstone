package io.noisyfox.resourcesharing


import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.noisyfox.resourcesharing.resmanager.ResDownloadListener
import io.noisyfox.resourcesharing.resmanager.ResService
import kotlinx.android.synthetic.main.fragment_main.*
import org.slf4j.LoggerFactory

/**
 * A simple [Fragment] subclass.
 * Use the [MainFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class MainFragment : Fragment(), ResDownloadListener, Logging.LoggingListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val service = MainApplication.resourceService

        btn_direct_download.setOnClickListener {
            service.startDownload(MainApplication.TEST_FILE_1)
        }
        btn_smart_download.setOnClickListener {

        }
        btn_clear_cache.setOnClickListener {
            service.clearResource(MainApplication.TEST_FILE_1)
        }
    }

    override fun onResume() {
        super.onResume()

        val service = MainApplication.resourceService
        service.downloadListeners += this

        logText.text = ""
        onLog(Logging.getAllMessages())
        Logging.listeners += this
    }

    override fun onPause() {
        val service = MainApplication.resourceService
        service.downloadListeners -= this

        Logging.listeners -= this

        super.onPause()
    }

    override fun onLog(message: String) {
        activity.runOnUiThread {
            logText.append("$message\n")
            logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onBlockDownloaded(service: ResService, fileId: String, block: Int) {
        logger.info("Block $block download completed for file $fileId.")
    }

    override fun onBlockDownloadFailed(service: ResService, fileId: String, block: Int, ex: Throwable?) {
        logger.info("Block $block download failed for file $fileId.", ex)
    }

    override fun onDownloadCompleted(service: ResService, fileId: String) {
        logger.info("Download completed for file $fileId.")
    }

    override fun onDownloadFailed(service: ResService, fileId: String, ex: Throwable?) {
        logger.info("Download failed for file $fileId.", ex)
    }

    override fun onDownloadStopped(service: ResService, fileId: String) {
        logger.info("Download stopped for file $fileId.")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MainFragment::class.java)

        @JvmStatic
        fun newInstance() = MainFragment()
    }
}
