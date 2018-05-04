package io.noisyfox.resourcesharing


import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.noisyfox.resourcesharing.resmanager.ResDownloadListener
import io.noisyfox.resourcesharing.resmanager.ResService
import io.noisyfox.resourcesharing.resmanager.downloader.DownloaderStatus
import kotlinx.android.synthetic.main.fragment_main.*
import org.slf4j.LoggerFactory

/**
 * A simple [Fragment] subclass.
 * Use the [MainFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class MainFragment : Fragment(), ResDownloadListener {

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
            logException {
                service.startDownload(MainApplication.TEST_FILE_1, true, false)
            }
        }
        btn_res_only_download.setOnClickListener {
            logException {
                service.startDownload(MainApplication.TEST_FILE_1, false, true)
            }
        }
        btn_smart_download.setOnClickListener {
            logException {
                service.startDownload(MainApplication.TEST_FILE_1, true, true)
            }
        }
        btn_stop.setOnClickListener {
            logException {
                service.stopDownload(MainApplication.TEST_FILE_1)
            }
        }
        btn_clear_cache.setOnClickListener {
            logException {
                service.clearResource(MainApplication.TEST_FILE_1)
            }
        }

        val status = service.getDownloadStatus(MainApplication.TEST_FILE_1)
        when (status) {
            DownloaderStatus.Stopped -> onDownloadStopped(service, MainApplication.TEST_FILE_1)
            else -> onDownloadStarted(service, MainApplication.TEST_FILE_1)
        }
    }

    override fun onResume() {
        super.onResume()

        val service = MainApplication.resourceService
        service.downloadListeners += this
    }

    override fun onPause() {
        val service = MainApplication.resourceService
        service.downloadListeners -= this

        super.onPause()
    }

    override fun onDownloadStarted(service: ResService, fileId: String) {
        activity.runOnUiThread {
            btn_stop.isEnabled = true
            btn_clear_cache.isEnabled = false
            btn_smart_download.isEnabled = false
            btn_direct_download.isEnabled = false
            btn_res_only_download.isEnabled = false
        }
    }

    override fun onBlockDownloaded(service: ResService, fileId: String, block: Int) {
    }

    override fun onBlockDownloadFailed(service: ResService, fileId: String, block: Int, ex: Throwable?) {
    }

    override fun onDownloadCompleted(service: ResService, fileId: String) {
    }

    override fun onDownloadFailed(service: ResService, fileId: String, ex: Throwable?) {
    }

    override fun onDownloadStopped(service: ResService, fileId: String) {
        activity.runOnUiThread {
            btn_stop.isEnabled = false
            btn_clear_cache.isEnabled = true
            btn_smart_download.isEnabled = true
            btn_direct_download.isEnabled = true
            btn_res_only_download.isEnabled = true
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MainFragment::class.java)

        @JvmStatic
        fun newInstance() = MainFragment()

        private inline fun logException(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                logger.error("", e)
            }
        }
    }
}
