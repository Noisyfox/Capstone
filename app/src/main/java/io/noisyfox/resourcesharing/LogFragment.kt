package io.noisyfox.resourcesharing

import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.fragment_log.*
import org.slf4j.LoggerFactory

class LogFragment : Fragment(), Logging.LoggingListener {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_log, container, false)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        btn_clear_log.setOnClickListener {
            LogFragment.logException {
                Logging.clear()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        logText.text = ""
        onLog(Logging.getAllMessages())
        Logging.listeners += this
    }

    override fun onPause() {
        Logging.listeners -= this

        super.onPause()
    }

    override fun onLog(message: String) {
        activity.runOnUiThread {
            logText.append("$message\n")
            logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onLogClear() {
        activity.runOnUiThread {
            logText.text = ""
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LogFragment::class.java)

        @JvmStatic
        fun newInstance() = LogFragment()

        private inline fun logException(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                logger.error("", e)
            }
        }
    }
}