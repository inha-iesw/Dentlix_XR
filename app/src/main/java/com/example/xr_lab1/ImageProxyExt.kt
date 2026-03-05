package com.example.xr_lab1

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

fun ImageProxy.toBitmap(): Bitmap {
    val plane = planes.first()
    val width = this.width
    val height = this.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    require(pixelStride == 4) { "Expected RGBA_8888 input, got pixelStride=$pixelStride" }

    val src = plane.buffer
    src.rewind()
    val tight = ByteBuffer.allocateDirect(width * height * 4)
    val row = ByteArray(width * 4)
    for (y in 0 until height) {
        src.position(y * rowStride)
        src.get(row, 0, row.size)
        tight.put(row)
    }
    tight.rewind()

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.copyPixelsFromBuffer(tight)
    }
}
