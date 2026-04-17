package com.carplay.android.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.carplay.android.R
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
        // App grid click handlers — navigate to corresponding fragments
        binding.cardMaps.setOnClickListener {
            findNavController().navigate(R.id.mapsFragment)
        }
        binding.cardMusic.setOnClickListener {
            findNavController().navigate(R.id.musicFragment)
        }
        binding.cardPhone.setOnClickListener {
            findNavController().navigate(R.id.phoneFragment)
        }
        binding.cardMessages.setOnClickListener {
            // Messages not implemented yet
        }
        binding.cardSettings.setOnClickListener {
            // Settings not implemented yet
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
