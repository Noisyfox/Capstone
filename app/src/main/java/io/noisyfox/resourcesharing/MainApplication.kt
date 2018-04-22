package io.noisyfox.resourcesharing

import android.app.Application
import android.content.res.Resources
import android.util.TypedValue
import io.noisyfox.libfilemanager.FileManager
import io.noisyfox.resourcesharing.resmanager.ResService
import org.slf4j.impl.HandroidLoggerAdapter
import java.io.File
import java.io.FileOutputStream

class MainApplication : Application() {

    init {
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
        HandroidLoggerAdapter.APP_NAME = "Resource Sharing"
    }

    override fun onCreate() {
        super.onCreate()
        Logging.init()

        // Init data directory
        val dir = File(filesDir, "files")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        resources.openRawResource(R.raw.a5aacdc9cf26eced948b20841d7b2bb8758cfdf8f99e54bd866f7859e101de6b_meta).use { input ->
            FileOutputStream(File(dir, resources.getMetafileName(R.raw.a5aacdc9cf26eced948b20841d7b2bb8758cfdf8f99e54bd866f7859e101de6b_meta))).use { output ->
                input.copyTo(output)
            }
        }

        fileManager = FileManager(File(filesDir, "files").absolutePath)

        ResService.initOcPlatform(this)
        resourceService = ResService("io.noisyfox.resourcesharing", fileManager)
        resourceService.startService()

        resourceService.registerResource(TEST_FILE_1)
    }

    companion object {
        const val TEST_FILE_1 = "100MB.zip"

        lateinit var fileManager: FileManager
            private set

        lateinit var resourceService: ResService
            private set

        private fun Resources.getMetafileName(resid: Int): String {
            val value = TypedValue()
            getValue(resid, value, true)
            val n = File(value.string.toString()).name
            return n.replace("_meta", ".meta")
        }
    }
}