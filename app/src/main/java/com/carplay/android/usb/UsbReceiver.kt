package com.carplay.android.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import timber.log.Timber

/**
 * USB Broadcast Receiver
 *
 * Handles USB device attach/detach events system-wide.
 * Registered in AndroidManifest.xml
 */
class UsbReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                device?.let {
                    Timber.d("USB attached: VID=0x${it.vendorId.toString(16)}, " +
                            "PID=0x${it.productId.toString(16)}, name=${it.deviceName}")
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                device?.let {
                    Timber.d("USB detached: VID=0x${it.vendorId.toString(16)}, " +
                            "PID=0x${it.productId.toString(16)}")
                }
            }
        }
    }
}
