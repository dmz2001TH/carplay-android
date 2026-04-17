package com.carplay.android.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import timber.log.Timber
import com.carplay.android.protocol.IAP2Constants

/**
 * H.264 Video Encoder
 *
 * Encodes the phone screen to H.264 video stream for transmission
 * to the car's head unit via CarPlay protocol.
 *
 * Uses Android's hardware MediaCodec for efficient encoding.
 */
class VideoEncoder {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC  // H.264
        private const val I_FRAME_INTERVAL = 1  // seconds
        private const val BITRATE_MODE = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    }

    private var encoder: MediaCodec? = null
    private var inputThread: HandlerThread? = null
    private var inputHandler: Handler? = null
    private var isRunning = false

    // Callback for encoded frames
    var onEncodedFrame: ((ByteArray, Boolean) -> Unit)? = null

    // Configurable parameters
    var width = IAP2Constants.VIDEO_WIDTH       // 800
    var height = IAP2Constants.VIDEO_HEIGHT     // 480
    var bitrate = IAP2Constants.VIDEO_BITRATE   // 2 Mbps
    var frameRate = IAP2Constants.VIDEO_FPS     // 30

    /**
     * Initialize the H.264 encoder
     */
    fun init() {
        Timber.d("Initializing H.264 encoder: ${width}x${height} @ ${frameRate}fps, ${bitrate}bps")

        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BITRATE_MODE, BITRATE_MODE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // Real-time priority

                // Low latency for CarPlay
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)  // No B-frames for low latency
            }

            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Start output thread
            inputThread = HandlerThread("VideoEncoder-Output").apply { start() }
            inputHandler = Handler(inputThread!!.looper)

            Timber.d("H.264 encoder initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize encoder")
        }
    }

    /**
     * Start encoding
     */
    fun start() {
        Timber.d("Starting H.264 encoder")
        encoder?.start()
        isRunning = true
        startOutputThread()
    }

    /**
     * Queue a raw frame (NV21/YUV420) for encoding
     */
    fun queueFrame(data: ByteArray, timestampUs: Long) {
        if (!isRunning) return

        try {
            val bufferIndex = encoder?.dequeueInputBuffer(0) ?: return
            if (bufferIndex >= 0) {
                val inputBuffer = encoder?.getInputBuffer(bufferIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(data)

                encoder?.queueInputBuffer(
                    bufferIndex,
                    0,
                    data.size,
                    timestampUs,
                    0  // No flags for regular frames
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error queuing frame")
        }
    }

    /**
     * Request a keyframe (I-frame)
     */
    fun requestKeyframe() {
        Timber.d("Requesting keyframe")
        try {
            val format = MediaFormat().apply {
                setInteger(MediaFormat.KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(format)
        } catch (e: Exception) {
            Timber.e(e, "Error requesting keyframe")
        }
    }

    /**
     * Stop encoding
     */
    fun stop() {
        Timber.d("Stopping H.264 encoder")
        isRunning = false

        try {
            encoder?.stop()
            encoder?.release()
            inputThread?.quitSafely()
            inputThread?.join(1000)
        } catch (e: Exception) {
            Timber.e(e, "Error stopping encoder")
        }

        encoder = null
        inputThread = null
        inputHandler = null
    }

    /**
     * Release all resources
     */
    fun release() {
        stop()
    }

    // ── Private ────────────────────────────────────────────

    /**
     * Output thread that polls for encoded frames
     */
    private fun startOutputThread() {
        Thread({
            Timber.d("Encoder output thread started")
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning) {
                try {
                    val bufferIndex = encoder?.dequeueOutputBuffer(bufferInfo, 10_000) ?: -1

                    when {
                        bufferIndex >= 0 -> {
                            val outputBuffer = encoder?.getOutputBuffer(bufferIndex) ?: continue
                            val frameData = ByteArray(bufferInfo.size)
                            outputBuffer.get(frameData)

                            val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            onEncodedFrame?.invoke(frameData, isKeyframe)

                            encoder?.releaseOutputBuffer(bufferIndex, false)
                        }
                        bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = encoder?.outputFormat
                            Timber.d("Encoder output format changed: $newFormat")
                        }
                        bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output available yet, continue
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Timber.e(e, "Encoder output error")
                    }
                }
            }

            Timber.d("Encoder output thread stopped")
        }, "VideoEncoder-Output").start()
    }
}
