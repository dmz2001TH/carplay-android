package com.carplay.android.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.carplay.android.databinding.FragmentDashboardBinding

/**
 * Dashboard Fragment - CarPlay Home Screen
 *
 * Displays CarPlay-style grid of app icons:
 * - Maps, Music, Phone, Messages, Settings
 * - Now Playing widget
 * - Quick info (time, connection status)
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

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
        setupCarPlayGrid()
    }

    private fun setupCarPlayGrid() {
        // App grid click handlers
        binding.cardMaps.setOnClickListener {
            // Navigate to Maps
        }
        binding.cardMusic.setOnClickListener {
            // Navigate to Music
        }
        binding.cardPhone.setOnClickListener {
            // Navigate to Phone
        }
        binding.cardMessages.setOnClickListener {
            // Navigate to Messages
        }
        binding.cardSettings.setOnClickListener {
            // Open settings
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
