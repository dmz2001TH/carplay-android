package com.carplay.android.ui.dashboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.carplay.android.R
import com.carplay.android.databinding.FragmentDashboardBinding
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard Fragment — CarPlay Home Screen (Modern)
 *
 * App launcher grid + now playing widget.
 * Launches YouTube, YouTube Music, Google Maps, Spotify, etc.
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateClock()
        setupAppGrid()
        setupNowPlayingControls()
        observeMediaSession()
    }

    // ── Clock ──────────────────────────────────────────────

    private fun updateClock() {
        val now = Date()
        binding.txtTime.text = timeFormat.format(now)
        binding.txtDate.text = dateFormat.format(now)

        // Update every minute
        binding.txtTime.postDelayed({ updateClock() }, 60_000)
    }

    // ── App Grid ───────────────────────────────────────────

    private fun setupAppGrid() {
        // Google Maps
        binding.tileMaps.setOnClickListener {
            openAppOrFallback(
                packageName = "com.google.android.apps.maps",
                fallback = { findNavController().navigate(R.id.mapsFragment) }
            )
        }

        // YouTube
        binding.tileYouTube.setOnClickListener {
            openApp("com.google.android.youtube")
        }

        // YouTube Music
        binding.tileYouTubeMusic.setOnClickListener {
            openApp("com.google.android.apps.youtube.music")
        }

        // Phone
        binding.tilePhone.setOnClickListener {
            findNavController().navigate(R.id.phoneFragment)
        }

        // Settings
        binding.tileSettings.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Timber.e(e, "Cannot open settings")
            }
        }

        // Spotify
        binding.tileSpotify.setOnClickListener {
            openAppOrFallback(
                packageName = "com.spotify.music",
                fallback = {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.spotify.music")))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Install Spotify from Play Store", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // Telegram
        binding.tileTelegram.setOnClickListener {
            openApp("org.telegram.messenger")
        }
    }

    // ── Now Playing Controls ───────────────────────────────

    private fun setupNowPlayingControls() {
        binding.btnPlayPause.setOnClickListener {
            dispatchMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        binding.btnPrev.setOnClickListener {
            dispatchMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        binding.btnNext.setOnClickListener {
            dispatchMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        binding.nowPlayingCard.setOnClickListener {
            findNavController().navigate(R.id.musicFragment)
        }
    }

    // ── MediaSession Observer ──────────────────────────────

    private fun observeMediaSession() {
        try {
            val mediaSessionManager = requireContext().getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

            // Get active media controllers
            val controllers = mediaSessionManager.getActiveSessions(
                ComponentName(requireContext(), android.service.notification.NotificationListenerService::class.java)
            )

            if (controllers.isNotEmpty()) {
                val controller = controllers.first()
                updateNowPlaying(controller)
            }
        } catch (e: SecurityException) {
            Timber.w("Notification listener permission not granted for MediaSession observation")
        } catch (e: Exception) {
            Timber.e(e, "Error observing media sessions")
        }
    }

    private fun updateNowPlaying(controller: MediaController) {
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        if (metadata != null) {
            val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
            val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"

            binding.txtSongTitle.text = title
            binding.txtArtist.text = artist

            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
            binding.btnPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private fun openApp(packageName: String) {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "App not installed: $packageName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot open app: $packageName")
            Toast.makeText(requireContext(), "Cannot open app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppOrFallback(packageName: String, fallback: () -> Unit) {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                fallback()
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot open app: $packageName")
            fallback()
        }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        try {
            val instrumentation = android.app.Instrumentation()
            instrumentation.sendKeyDownUpSync(keyCode)
        } catch (e: Exception) {
            // Fallback: use MediaSession API
            Timber.w("Instrumentation failed, trying Runtime exec")
            try {
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            } catch (e2: Exception) {
                Timber.e(e2, "Cannot dispatch media key")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
