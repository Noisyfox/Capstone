package io.noisyfox.resourcesharing.resmanager

import io.noisyfox.libfilemanager.MarkedFile
import io.noisyfox.libfilemanager.getSHA256HexString
import org.iotivity.base.OcResourceHandle

internal class ResContext(
        service: ResService,
        val file: MarkedFile
) {
    val uri: String = "${service.baseUri}/${file.metadata.name.getSHA256HexString()}"
    var resourceHandler: OcResourceHandle? = null

    fun registerResource() {
        if (resourceHandler != null) {
            return
        }


    }
}
