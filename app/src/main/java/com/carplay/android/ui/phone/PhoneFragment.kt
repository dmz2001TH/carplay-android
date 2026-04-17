package com.carplay.android.ui.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.carplay.android.databinding.FragmentPhoneBinding
import timber.log.Timber

/**
 * Phone Fragment - CarPlay Phone Interface
 *
 * CarPlay-style phone interface with:
 * - Keypad dialer
 * - Recent calls list
 * - Contacts
 * - Active call display
 */
class PhoneFragment : Fragment() {

    private var _binding: FragmentPhoneBinding? = null
    private val binding get() = _binding!!

    // Permission launcher
    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            loadContacts()
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
        setupPhone()
        checkPermissions()
    }

    private fun setupPhone() {
        // Dialpad buttons
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

        // Tab switching
        binding.tabKeypad.setOnClickListener { showKeypad() }
        binding.tabRecents.setOnClickListener { showRecents() }
        binding.tabContacts.setOnClickListener { showContacts() }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )

        if (permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }) {
            loadContacts()
        } else {
            phonePermissionLauncher.launch(permissions)
        }
    }

    private fun loadContacts() {
        // TODO: Load contacts from phone
        Timber.d("Loading contacts...")
    }

    private fun makeCall(number: String) {
        // TODO: Initiate call via Bluetooth HFP
        Timber.d("Calling: $number")
    }

    private fun showKeypad() {
        binding.dialpadLayout.visibility = View.VISIBLE
        binding.recentsLayout.visibility = View.GONE
        binding.contactsLayout.visibility = View.GONE
    }

    private fun showRecents() {
        binding.dialpadLayout.visibility = View.GONE
        binding.recentsLayout.visibility = View.VISIBLE
        binding.contactsLayout.visibility = View.GONE
    }

    private fun showContacts() {
        binding.dialpadLayout.visibility = View.GONE
        binding.recentsLayout.visibility = View.GONE
        binding.contactsLayout.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
