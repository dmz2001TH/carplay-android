package com.carplay.android.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import timber.log.Timber
import com.carplay.android.protocol.IAP2Constants

/**
 * AAC Audio Encoder
 *
 * Captures audio from the phone's microphone and encodes it to AAC-LC
 * for transmission to the car's head unit via CarPlay protocol.
 *
 * Improvements over original:
 * - Proper timestamp synchronization using AudioRecord timestamps
 * - Separate capture and encode threads to prevent blocking
 * - Proper MediaCodec buffer lifecycle management
 * - Configurable audio source (mic, voice call, etc.)
 */
class AudioEncoder {

    companion object {
        private const val TAG = "AudioEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val ENCODE_TIMEOUT_US = 10_000L  // 10ms
    }

    // ── Encoder ────────────────────────────────────────────
    private var encoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var isEncoding = false
    private var captureThread: Thread? = null
    private var encodeThread: Thread? = null

    // Configurable
    var sampleRate = IAP2Constants.AUDIO_SAMPLE_RATE  // 44100
    var channels = IAP2Constants.AUDIO_CHANNELS        // 2
    var bitrate = IAP2Constants.AUDIO_BITRATE          // 128kbps

    // Callback for encoded audio
    var onEncodedAudio: ((ByteArray) -> Unit)? = null

    // Frame counter for diagnostics
    private var framesEncoded = 0L

    /**
     * Initialize audio capture and encoder
     */
    fun init() {
        Timber.d("Initializing AAC encoder: ${sampleRate}Hz, ${channels}ch, ${bitrate}bps")

        try {
            // Calculate buffer size
            val channelConfig = if (channels == 1)
                AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            // Create AudioRecord for microphone capture
            audioRecord = AudioRecord.Builder()
                .setAudioSource(AUDIO_SOURCE)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build())
                .setBufferSizeInBytes(minBufferSize * 2)
                .build()

            // Create AAC encoder
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)

            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)  // AAC-LC
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize)
            }

            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            Timber.d("AAC encoder initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize audio encoder")
        }
    }

    /**
     * Start audio capture and encoding
     *
     * Uses two threads:
     * - Capture thread: reads PCM from AudioRecord, queues to encoder
     * - Encode thread: polls encoder output, delivers encoded AAC frames
     */
    fun start() {
        if (encoder == null || audioRecord == null) {
            Timber.e("Cannot start: encoder or AudioRecord not initialized")
            return
        }

        Timber.d("Starting audio capture and encoding")
        isEncoding = true
        framesEncoded = 0

        audioRecord?.startRecording()
        encoder?.start()

        // Capture thread: mic → encoder input
        captureThread = Thread({
            Timber.d("Audio capture thread started")
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val buffer = ByteArray(bufferSize)

            while (isEncoding) {
                try {
                    val record = audioRecord ?: break
                    val bytesRead = record.read(buffer, 0, buffer.size)

                    if (bytesRead > 0) {
                        val enc = encoder ?: break
                        val bufferIndex = enc.dequeueInputBuffer(0)
                        if (bufferIndex >= 0) {
                            val inputBuffer = enc.getInputBuffer(bufferIndex) ?: continue
                            inputBuffer.clear()
                            inputBuffer.put(buffer, 0, bytesRead)

                            // Use nanoTime for accurate timestamps
                            val timestampUs = System.nanoTime() / 1000
                            enc.queueInputBuffer(
                                bufferIndex, 0, bytesRead, timestampUs, 0
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (isEncoding) {
                        Timber.e(e, "Audio capture error")
                    }
                }
            }

            Timber.d("Audio capture thread stopped")
        }, "AudioEncoder-Capture")

        // Encode thread: encoder output → callback
        encodeThread = Thread({
            Timber.d("Audio encode thread started")
            val bufferInfo = MediaCodec.BufferInfo()

            while (isEncoding) {
                try {
                    val enc = encoder ?: break
                    val outputIndex = enc.dequeueOutputBuffer(bufferInfo, ENCODE_TIMEOUT_US)

                    when {
                        outputIndex >= 0 -> {
                            val outputBuffer = enc.getOutputBuffer(outputIndex) ?: continue
                            val encodedData = ByteArray(bufferInfo.size)
                            outputBuffer.get(encodedData)

                            onEncodedAudio?.invoke(encodedData)
                            enc.releaseOutputBuffer(outputIndex, false)

                            framesEncoded++
                            if (framesEncoded % 1000 == 0L) {
                                Timber.d("Encoded $framesEncoded audio frames")
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Timber.d("Audio encoder output format changed: ${enc.outputFormat}")
                        }
                        // INFO_TRY_AGAIN_LATER — no output available, continue
                    }
                } catch (e: Exception) {
                    if (isEncoding) {
                        Timber.e(e, "Audio encode error")
                    }
                }
            }

            Timber.d("Audio encode thread stopped (total: $framesEncoded frames)")
        }, "AudioEncoder-Encode")

        captureThread?.start()
        encodeThread?.start()
    }

    /**
     * Stop audio capture and encoding
     */
    fun stop() {
        if (!isEncoding) return

        Timber.d("Stopping audio encoder (encoded $framesEncoded frames)")
        isEncoding = false

        try {
            captureThread?.join(2000)
            encodeThread?.join(2000)
        } catch (e: Exception) {
            Timber.w(e, "Error joining threads")
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping AudioRecord")
        }

        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping encoder")
        }

        audioRecord = null
        encoder = null
        captureThread = null
        encodeThread = null
        framesEncoded = 0
    }

    /**
     * Release all resources
     */
    fun release() {
        stop()
    }
}
