package com.carplay.android.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import timber.log.Timber
import com.carplay.android.protocol.IAP2Constants

/**
 * Screen Capture Service
 *
 * Captures the phone screen using MediaProjection API
 * and feeds frames to the VideoEncoder for H.264 encoding.
 *
 * Used to display the Android screen on the car's head unit
 * via the CarPlay video stream.
 */
class ScreenCaptureService {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val VIRTUAL_DISPLAY_NAME = "CarPlay-ScreenCapture"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var isCapturing = false
    private var frameCount = 0L

    // Video encoder reference
    var videoEncoder: VideoEncoder? = null

    // Capture config
    var width = IAP2Constants.VIDEO_WIDTH
    var height = IAP2Constants.VIDEO_HEIGHT
    var dpi = 320

    // Callback for raw frames (Bitmap)
    var onFrameCaptured: ((Bitmap) -> Unit)? = null

    /**
     * Start media projection (requires user permission)
     * Call this from Activity with result from MediaProjectionManager
     */
    fun startProjection(context: Context, resultCode: Int, data: Intent) {
        Timber.d("Starting media projection")

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.d("Media projection stopped")
                isCapturing = false
            }
        }, captureHandler)

        startCapture(context)
    }

    /**
     * Request screen capture permission
     * Call this from your Activity
     */
    fun requestCapturePermission(context: Context): Intent? {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    /**
     * Start capturing frames
     */
    private fun startCapture(context: Context) {
        Timber.d("Starting screen capture: ${width}x${height} @ ${dpi}dpi")

        // Create capture thread
        captureThread = HandlerThread("ScreenCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        // Create ImageReader for frame capture
        imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888,
            2  // Double buffer
        )

        // Set listener for new frames
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isCapturing) return@setOnImageAvailableListener

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                // Convert Image to Bitmap
                val bitmap = imageToBitmap(image)

                // Feed to video encoder
                val timestampUs = System.nanoTime() / 1000
                val yuvData = bitmapToNV21(bitmap)
                videoEncoder?.queueFrame(yuvData, timestampUs)

                // Callback for UI preview
                onFrameCaptured?.invoke(bitmap)

                frameCount++
                if (frameCount % 100 == 0L) {
                    Timber.d("Captured $frameCount frames")
                }

            } catch (e: Exception) {
                Timber.e(e, "Frame capture error")
            } finally {
                image.close()
            }
        }, captureHandler)

        // Create virtual display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, captureHandler
        )

        isCapturing = true
        Timber.d("Screen capture started")
    }

    /**
     * Stop capturing
     */
    fun stop() {
        Timber.d("Stopping screen capture (captured $frameCount frames)")
        isCapturing = false

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        captureThread?.quitSafely()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        captureThread = null
        captureHandler = null
        frameCount = 0
    }

    /**
     * Check if currently capturing
     */
    fun isCapturing() = isCapturing

    // ── Private Converters ─────────────────────────────────

    /**
     * Convert Android Image to Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop to exact size if padding exists
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }

    /**
     * Convert Bitmap to NV21 format (YUV420sp) for MediaCodec
     */
    private fun bitmapToNV21(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val yuv = ByteArray(w * h * 3 / 2)
        var yIndex = 0
        var uvIndex = w * h

        for (j in 0 until h) {
            for (i in 0 until w) {
                val pixel = pixels[j * w + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // RGB to Y
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                // RGB to UV (subsampled 2x2)
                if (j % 2 == 0 && i % 2 == 0) {
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val u2 = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u2.coerceIn(0, 255).toByte()
                }
            }
        }

        return yuv
    }
}
