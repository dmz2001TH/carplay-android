package com.carplay.android.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import timber.log.Timber
import com.carplay.android.protocol.IAP2Constants

/**
 * H.264 Video Encoder
 *
 * Encodes screen frames (NV21/YUV420) to H.264 video stream
 * for transmission to the car's head unit via CarPlay protocol.
 *
 * Uses Android's hardware MediaCodec for efficient encoding.
 * Configured for low-latency real-time streaming:
 * - No B-frames (reduces latency)
 * - VBR bitrate mode
 * - Real-time priority
 */
class VideoEncoder {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC  // H.264
        private const val I_FRAME_INTERVAL = 1  // seconds between keyframes
        private const val FRAME_TIMEOUT_US = 10_000L // 10ms timeout for output polling
    }

    private var encoder: MediaCodec? = null
    private var outputThread: HandlerThread? = null
    private var outputHandler: Handler? = null
    private var isRunning = false
    private var frameCount = 0L

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

                // Use semi-planar YUV420 (NV12) — most hardware encoders support this
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)

                // Try VBR, fall back handled by encoder
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (e: Exception) {
                    Timber.w("VBR not supported, using default bitrate mode")
                }

                // Low latency settings
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // Real-time priority
                try {
                    setInteger("max-bframes", 0)  // No B-frames for low latency
                } catch (e: Exception) {
                    // Not all encoders support this
                }

                // Attempt CQ mode for better quality
                try {
                    setInteger("qmin", 10)
                    setInteger("qmax", 50)
                } catch (e: Exception) {
                    // Optional parameter
                }
            }

            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            Timber.d("H.264 encoder initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize encoder with SemiPlanar, trying Flexible")

            // Fallback: try YUV420Flexible
            try {
                encoder?.release()
                encoder = MediaCodec.createEncoderByType(MIME_TYPE)

                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }

                encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Timber.d("H.264 encoder initialized with Flexible format")
            } catch (e2: Exception) {
                Timber.e(e2, "Failed to initialize encoder completely")
                encoder = null
            }
        }
    }

    /**
     * Start encoding
     */
    fun start() {
        if (encoder == null) {
            Timber.e("Cannot start: encoder not initialized")
            return
        }

        Timber.d("Starting H.264 encoder")
        frameCount = 0
        encoder?.start()
        isRunning = true
        startOutputThread()
    }

    /**
     * Queue a raw NV21 frame for encoding
     * @param data NV21 formatted pixel data
     * @param timestampUs Presentation timestamp in microseconds
     */
    fun queueFrame(data: ByteArray, timestampUs: Long) {
        if (!isRunning || encoder == null) return

        try {
            val bufferIndex = encoder!!.dequeueInputBuffer(0)
            if (bufferIndex >= 0) {
                val inputBuffer = encoder!!.getInputBuffer(bufferIndex) ?: return
                inputBuffer.clear()

                // Ensure we don't overflow the buffer
                val bytesToWrite = minOf(data.size, inputBuffer.remaining())
                inputBuffer.put(data, 0, bytesToWrite)

                encoder!!.queueInputBuffer(
                    bufferIndex,
                    0,
                    bytesToWrite,
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
        if (!isRunning) return

        Timber.d("Stopping H.264 encoder (encoded $frameCount frames)")
        isRunning = false

        try {
            encoder?.stop()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping encoder")
        }

        try {
            encoder?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error releasing encoder")
        }

        try {
            outputThread?.quitSafely()
            outputThread?.join(1000)
        } catch (e: Exception) {
            Timber.e(e, "Error stopping output thread")
        }

        encoder = null
        outputThread = null
        outputHandler = null
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
        outputThread = HandlerThread("VideoEncoder-Output").apply { start() }
        outputHandler = Handler(outputThread!!.looper)

        Thread({
            Timber.d("Encoder output thread started")
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning) {
                try {
                    val enc = encoder ?: break
                    val bufferIndex = enc.dequeueOutputBuffer(bufferInfo, FRAME_TIMEOUT_US)

                    when {
                        bufferIndex >= 0 -> {
                            val outputBuffer = enc.getOutputBuffer(bufferIndex) ?: continue
                            val frameData = ByteArray(bufferInfo.size)
                            outputBuffer.get(frameData)

                            val isKeyframe = (bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            onEncodedFrame?.invoke(frameData, isKeyframe)
                            enc.releaseOutputBuffer(bufferIndex, false)

                            frameCount++
                            if (frameCount % 300 == 0L) {
                                Timber.d("Encoded $frameCount frames")
                            }
                        }
                        bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = enc.outputFormat
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

            Timber.d("Encoder output thread stopped (total: $frameCount frames)")
        }, "VideoEncoder-Output").start()
    }
}
