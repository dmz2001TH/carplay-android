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
 * Also handles audio output decoding (received from car's FM/radio).
 */
class AudioEncoder {

    companion object {
        private const val TAG = "AudioEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    // ── Encoder ────────────────────────────────────────────
    private var encoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var isEncoding = false

    // Configurable
    var sampleRate = IAP2Constants.AUDIO_SAMPLE_RATE  // 44100
    var channels = IAP2Constants.AUDIO_CHANNELS        // 2
    var bitrate = IAP2Constants.AUDIO_BITRATE          // 128kbps

    // Callback for encoded audio
    var onEncodedAudio: ((ByteArray) -> Unit)? = null

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
     */
    fun start() {
        Timber.d("Starting audio capture and encoding")
        isEncoding = true

        audioRecord?.startRecording()
        encoder?.start()

        // Start encoding thread
        Thread({
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val buffer = ByteArray(bufferSize)

            while (isEncoding) {
                try {
                    // Read from microphone
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                    if (bytesRead > 0) {
                        // Queue to encoder
                        val bufferIndex = encoder?.dequeueInputBuffer(0) ?: -1
                        if (bufferIndex >= 0) {
                            val inputBuffer = encoder?.getInputBuffer(bufferIndex) ?: continue
                            inputBuffer.clear()
                            inputBuffer.put(buffer, 0, bytesRead)

                            val timestampUs = System.nanoTime() / 1000
                            encoder?.queueInputBuffer(
                                bufferIndex, 0, bytesRead, timestampUs, 0
                            )
                        }

                        // Get encoded output
                        val bufferInfo = MediaCodec.BufferInfo()
                        val outputIndex = encoder?.dequeueOutputBuffer(bufferInfo, 0) ?: -1

                        if (outputIndex >= 0) {
                            val outputBuffer = encoder?.getOutputBuffer(outputIndex) ?: continue
                            val encodedData = ByteArray(bufferInfo.size)
                            outputBuffer.get(encodedData)

                            onEncodedAudio?.invoke(encodedData)

                            encoder?.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                } catch (e: Exception) {
                    if (isEncoding) {
                        Timber.e(e, "Audio encoding error")
                    }
                }
            }

            Timber.d("Audio encoding thread stopped")
        }, "AudioEncoder").start()
    }

    /**
     * Stop audio capture and encoding
     */
    fun stop() {
        Timber.d("Stopping audio encoder")
        isEncoding = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio encoder")
        }

        audioRecord = null
        encoder = null
    }

    /**
     * Release all resources
     */
    fun release() {
        stop()
    }
}
