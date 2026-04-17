package com.carplay.android.ui.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.carplay.android.databinding.FragmentMusicBinding
import timber.log.Timber

/**
 * Music Fragment — Media Hub
 *
 * Launches YouTube, YouTube Music, Spotify.
 * Shows now-playing info from active MediaSession.
 * Provides playback controls for any active media player.
 */
class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private var audioManager: AudioManager? = null
    private var activeController: MediaController? = null
    private var isPlaying = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupControls()
        setupVolumeControl()
        setupSourceButtons()
        observeMediaSession()
    }

    // ── Playback Controls ──────────────────────────────────

    private fun setupControls() {
        binding.btnPlayPauseLarge.setOnClickListener {
            togglePlayPause()
        }

        binding.btnPrevLarge.setOnClickListener {
            activeController?.transportControls?.skipToPrevious()
                ?: dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        binding.btnNextLarge.setOnClickListener {
            activeController?.transportControls?.skipToNext()
                ?: dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        binding.btnShuffle.setOnClickListener {
            activeController?.transportControls?.setShuffleMode(1)
            Toast.makeText(requireContext(), "Shuffle toggled", Toast.LENGTH_SHORT).show()
        }

        binding.btnRepeatLarge.setOnClickListener {
            activeController?.transportControls?.setRepeatMode(1)
            Toast.makeText(requireContext(), "Repeat toggled", Toast.LENGTH_SHORT).show()
        }

        binding.seekBarNowPlaying.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val metadata = activeController?.metadata
                    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                    if (duration > 0) {
                        val position = (progress.toLong() * duration) / 100
                        activeController?.transportControls?.seekTo(position)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun togglePlayPause() {
        if (activeController != null) {
            if (isPlaying) {
                activeController?.transportControls?.pause()
            } else {
                activeController?.transportControls?.play()
            }
        } else {
            // No active media — open YouTube Music
            openYouTubeMusic()
        }
    }

    // ── Volume Control ─────────────────────────────────────

    private fun setupVolumeControl() {
        val am = audioManager ?: return
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        binding.volumeSeekBar.max = maxVol
        binding.volumeSeekBar.progress = currentVol

        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ── Source Buttons ─────────────────────────────────────

    private fun setupSourceButtons() {
        binding.btnOpenYouTube.setOnClickListener {
            openYouTube()
        }

        binding.btnOpenYTMusic.setOnClickListener {
            openYouTubeMusic()
        }

        binding.btnOpenSpotify.setOnClickListener {
            openSpotify()
        }
    }

    private fun openYouTube() {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (intent != null) {
                startActivity(intent)
            } else {
                // Open in browser
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")))
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot open YouTube")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")))
        }
    }

    private fun openYouTubeMusic() {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
            if (intent != null) {
                startActivity(intent)
            } else {
                // Open Play Store
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.youtube.music")))
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot open YouTube Music")
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.youtube.music")))
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "YouTube Music not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSpotify() {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage("com.spotify.music")
            if (intent != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.spotify.music")))
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot open Spotify")
        }
    }

    // ── MediaSession Observation ───────────────────────────

    private fun observeMediaSession() {
        try {
            val mediaSessionManager = requireContext().getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

            // Note: getActiveSessions requires notification listener permission
            // For now, we'll try and handle failure gracefully
            val component = ComponentName(requireContext(),
                "com.carplay.android.service.MediaNotificationListener")

            val controllers = mediaSessionManager.getActiveSessions(component)
            if (controllers.isNotEmpty()) {
                setActiveController(controllers.first())
            }
        } catch (e: SecurityException) {
            Timber.w("Need notification listener permission for media session observation")
            binding.txtNoMedia.visibility = View.VISIBLE
        } catch (e: Exception) {
            Timber.w("MediaSession observation not available: ${e.message}")
        }
    }

    private fun setActiveController(controller: MediaController) {
        activeController = controller
        activeController?.registerCallback(mediaCallback)

        // Update UI immediately
        updateMediaUI(controller.metadata, controller.playbackState)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMediaUI(metadata, activeController?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMediaUI(activeController?.metadata, state)
        }
    }

    private fun updateMediaUI(metadata: MediaMetadata?, playbackState: PlaybackState?) {
        if (_binding == null) return

        activity?.runOnUiThread {
            if (metadata != null) {
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Track"
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                binding.txtNowPlayingTitle.text = title
                binding.txtNowPlayingArtist.text = if (album.isNotEmpty()) "$artist · $album" else artist
                binding.txtNoMedia.visibility = View.GONE

                // Duration
                if (duration > 0) {
                    binding.txtTotalTime.text = formatTime(duration)
                }
            }

            if (playbackState != null) {
                isPlaying = playbackState.state == PlaybackState.STATE_PLAYING
                binding.btnPlayPauseLarge.setImageResource(
                    if (isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )

                // Position
                val position = playbackState.position
                val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                binding.txtCurrentTime.text = formatTime(position)

                if (duration > 0) {
                    val progress = ((position * 100) / duration).toInt()
                    binding.seekBarNowPlaying.progress = progress
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val instrumentation = android.app.Instrumentation()
            instrumentation.sendKeyDownUpSync(keyCode)
        } catch (e: Exception) {
            try {
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            } catch (e2: Exception) {
                Timber.e(e2, "Cannot dispatch media key")
            }
        }
    }

    override fun onDestroyView() {
        activeController?.unregisterCallback(mediaCallback)
        _binding = null
        super.onDestroyView()
    }
}
