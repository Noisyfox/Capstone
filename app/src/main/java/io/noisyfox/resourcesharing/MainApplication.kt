package io.noisyfox.resourcesharing

import android.app.Application
import android.content.res.Resources
import android.util.TypedValue
import io.noisyfox.libfilemanager.FileManager
import io.noisyfox.resourcesharing.resmanager.ResService
import java.io.File
import java.io.FileOutputStream

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

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
        resourceService = ResService("io.noisyfox.resourcesharing", fileManager)
        resourceService.startService()

        resourceService.registerResource("100MB.zip")
    }

    companion object {
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