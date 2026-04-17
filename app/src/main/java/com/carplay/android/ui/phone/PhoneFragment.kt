package com.carplay.android.ui.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.carplay.android.databinding.FragmentPhoneBinding
import timber.log.Timber

/**
 * Phone Fragment — CarPlay Phone Interface (Modernized)
 *
 * Keypad dialer with call button.
 * Calls go through the phone's native dialer.
 */
class PhoneFragment : Fragment() {

    private var _binding: FragmentPhoneBinding? = null
    private val binding get() = _binding!!

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (!granted) {
            Toast.makeText(requireContext(), "Call permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhoneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDialpad()
        setupTabs()
        checkPermissions()
    }

    private fun setupDialpad() {
        val dialButtons = listOf(
            binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6,
            binding.btn7, binding.btn8, binding.btn9,
            binding.btnStar, binding.btn0, binding.btnHash
        )

        dialButtons.forEachIndexed { index, button ->
            val digit = when (index) {
                9 -> "*"
                10 -> "0"
                11 -> "#"
                else -> "${index + 1}"
            }
            button.setOnClickListener {
                val current = binding.txtPhoneNumber.text.toString()
                binding.txtPhoneNumber.text = current + digit
            }
        }

        // Call button
        binding.btnCall.setOnClickListener {
            val number = binding.txtPhoneNumber.text.toString()
            if (number.isNotEmpty()) {
                makeCall(number)
            }
        }

        // Backspace
        binding.btnBackspace.setOnClickListener {
            val current = binding.txtPhoneNumber.text.toString()
            if (current.isNotEmpty()) {
                binding.txtPhoneNumber.text = current.dropLast(1)
            }
        }

        // Long press backspace to clear all
        binding.btnBackspace.setOnLongClickListener {
            binding.txtPhoneNumber.text = ""
            true
        }
    }

    private fun setupTabs() {
        binding.tabKeypad.setOnClickListener {
            showKeypad()
            highlightTab(0)
        }
        binding.tabRecents.setOnClickListener {
            showKeypad() // For now, just show keypad
            highlightTab(1)
            Toast.makeText(requireContext(), "Recent calls — coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.tabContacts.setOnClickListener {
            showKeypad()
            highlightTab(2)
            // Open contacts in a way
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "vnd.android.cursor.dir/contact"
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Contacts — coming soon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun highlightTab(index: Int) {
        val tabs = listOf(binding.tabKeypad, binding.tabRecents, binding.tabContacts)
        tabs.forEachIndexed { i, tab ->
            tab.setTextColor(
                if (i == index) resources.getColor(com.carplay.android.R.color.carplay_blue, null)
                else resources.getColor(com.carplay.android.R.color.text_tertiary, null)
            )
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            phonePermissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
        }
    }

    private fun makeCall(number: String) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "Call permission needed", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            startActivity(intent)
            binding.txtPhoneNumber.text = ""
        } catch (e: Exception) {
            Timber.e(e, "Cannot make call: $number")
            // Fallback: open dialer
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Cannot make call", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showKeypad() {
        binding.dialpadLayout.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
