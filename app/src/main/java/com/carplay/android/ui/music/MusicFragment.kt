package com.carplay.android.ui.music

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.carplay.android.databinding.FragmentMusicBinding

/**
 * Music Fragment - CarPlay Music Player
 *
 * CarPlay-style music player with:
 * - Now Playing view with album art
 * - Playback controls (prev/play-pause/next)
 * - Progress bar / seek bar
 * - Source selection (Spotify, Apple Music, Local)
 * - Queue / playlist view
 */
class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

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
        setupControls()
    }

    private fun setupControls() {
        // Play/Pause toggle
        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            binding.btnPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }

        // Previous track
        binding.btnPrev.setOnClickListener {
            // TODO: Previous track
        }

        // Next track
        binding.btnNext.setOnClickListener {
            // TODO: Next track
        }

        // Shuffle
        binding.btnShuffle.setOnClickListener {
            // TODO: Toggle shuffle
        }

        // Repeat
        binding.btnRepeat.setOnClickListener {
            // TODO: Toggle repeat
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
