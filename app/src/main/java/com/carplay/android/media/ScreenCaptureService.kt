package com.carplay.android.media

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
 * Flow:
 * 1. Activity requests MediaProjection permission from user
 * 2. Activity gets MediaProjection object
 * 3. Activity passes MediaProjection to CarPlayService
 * 4. CarPlayService calls startProjection() here
 * 5. VirtualDisplay is created, frames flow to ImageReader
 * 6. ImageReader converts to NV21, queues to VideoEncoder
 * 7. VideoEncoder outputs H.264 frames → USB → Head Unit
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
    private var videoEncoder: VideoEncoder? = null

    // Capture config
    var width = IAP2Constants.VIDEO_WIDTH
    var height = IAP2Constants.VIDEO_HEIGHT
    var dpi = 320

    // Callback for raw frames (Bitmap) — for UI preview
    var onFrameCaptured: ((Bitmap) -> Unit)? = null

    /**
     * Set the video encoder to feed frames to
     */
    fun setVideoEncoder(encoder: VideoEncoder) {
        this.videoEncoder = encoder
    }

    /**
     * Request screen capture permission intent
     * Call this from your Activity
     */
    fun requestCapturePermission(context: Context): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    /**
     * Start projection with MediaProjection object
     * Call this from CarPlayService after receiving MediaProjection from Activity
     */
    fun startProjection(context: Context, projection: MediaProjection) {
        if (isCapturing) {
            Timber.w("Already capturing, stopping first")
            stop()
        }

        Timber.d("Starting media projection")
        mediaProjection = projection

        // Register callback for projection stop
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.d("Media projection stopped by system")
                isCapturing = false
            }
        }, null)

        startCapture(context)
    }

    /**
     * Set MediaProjection without starting capture yet
     */
    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
    }

    /**
     * Start capturing frames (requires MediaProjection to be set)
     */
    fun startCapture(context: Context) {
        val projection = mediaProjection
        if (projection == null) {
            Timber.e("Cannot start capture: no MediaProjection set")
            return
        }

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

                // Convert to NV21 and feed to video encoder
                val timestampUs = System.nanoTime() / 1000
                val yuvData = bitmapToNV21(bitmap)
                videoEncoder?.queueFrame(yuvData, timestampUs)

                // Callback for UI preview (if anyone is listening)
                onFrameCaptured?.invoke(bitmap)

                frameCount++
                if (frameCount % 300 == 0L) {
                    Timber.d("Captured $frameCount frames")
                }

                // Recycle bitmap to avoid OOM
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }

            } catch (e: Exception) {
                Timber.e(e, "Frame capture error")
            } finally {
                image.close()
            }
        }, captureHandler)

        // Create virtual display
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, captureHandler
        )

        isCapturing = true
        Timber.d("Screen capture started successfully")
    }

    /**
     * Stop capturing
     */
    fun stop() {
        if (!isCapturing && virtualDisplay == null && mediaProjection == null) {
            return // Already stopped
        }

        Timber.d("Stopping screen capture (captured $frameCount frames)")
        isCapturing = false

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing virtual display")
        }

        try {
            imageReader?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing image reader")
        }

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping media projection")
        }

        try {
            captureThread?.quitSafely()
            captureThread?.join(1000)
        } catch (e: Exception) {
            Timber.w(e, "Error stopping capture thread")
        }

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        captureThread = null
        captureHandler = null
        frameCount = 0

        Timber.d("Screen capture stopped")
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
     * NV21 is the preferred format for Android H.264 hardware encoder
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

                // RGB to Y (luminance)
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                // RGB to UV (chrominance, subsampled 2x2 → NV21)
                if (j % 2 == 0 && i % 2 == 0) {
                    // NV21: V then U
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }

        return yuv
    }
}
