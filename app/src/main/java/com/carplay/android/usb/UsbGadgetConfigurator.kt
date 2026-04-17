package com.carplay.android.usb

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * USB Gadget Configurator (ConfigFS)
 *
 * Based on OkcarOS approach — configures the Android phone to present
 * as an Apple iPhone USB device to the car's head unit.
 *
 * This requires ROOT access and a kernel with USB gadget support.
 *
 * What it does:
 * 1. Sets USB VID=0x05AC (Apple), PID=0x12A8 (iPhone)
 * 2. Configures USB string descriptors (manufacturer="Apple Inc.", product="iPhone")
 * 3. Sets up USB function configurations:
 *    - Config 1: PTP (Image transfer)
 *    - Config 2: iPod USB Interface (Audio + HID)
 *    - Config 3: PTP + Apple Mobile Device (iAP2 control)
 *    - Config 4: PTP + Apple Mobile Device + USB Ethernet (NCM for video/audio)
 *
 * Reference: OkcarOS init.okcar.rc and init.qcom.okcar.rc
 *
 * Prerequisites:
 * - Rooted device
 * - Kernel compiled with: CONFIG_USB_CONFIGFS, CONFIG_USB_F_MTP, CONFIG_USB_F_ACM,
 *   CONFIG_USB_CONFIGFS_F_HID, custom okcar gadget drivers OR standard NCM gadget
 * - USB ConfigFS mounted at /config/usb_gadget/
 */
class UsbGadgetConfigurator(private val context: Context) {

    companion object {
        private const val TAG = "UsbGadgetConfig"

        // Apple device identifiers
        const val APPLE_VID = "0x05AC"
        const val APPLE_PID = "0x12A8"
        const val APPLE_BCD_DEVICE = "0x1003"
        const val APPLE_BCD_USB = "0x0200"

        // Apple device strings
        const val APPLE_PRODUCT = "iPhone"
        const val APPLE_MANUFACTURER = "Apple Inc."
        const val APPLE_SERIAL = "d9ccb8ebdb3e6d6db237d513894b43390e0afec1"

        // ConfigFS paths
        const val GADGET_PATH = "/config/usb_gadget/g1"
        const val UDC_PATH = "$GADGET_PATH/UDC"

        // Log file for debugging
        const val DEBUG_LOG_PATH = "/sdcard/carplay_debug.log"
    }

    private val debugLog = StringBuilder()

    // ── Public API ─────────────────────────────────────────

    /**
     * Check if we can configure the USB gadget
     */
    fun canConfigure(): Boolean {
        val gadgetDir = File(GADGET_PATH)
        return gadgetDir.exists() && isRootAvailable()
    }

    /**
     * Full setup: configure USB gadget as Apple CarPlay device
     *
     * @param withAdb Keep ADB active alongside CarPlay
     * @return true if successful
     */
    fun configureAsAppleDevice(withAdb: Boolean = true): Boolean {
        logDebug("Starting USB Gadget configuration (ADB=$withAdb)")

        if (!canConfigure()) {
            logError("Cannot configure: no root or no USB gadget support")
            return false
        }

        try {
            // Step 1: Detach UDC (disable USB temporarily)
            writeToFile(UDC_PATH, "none")
            logDebug("UDC detached")

            // Step 2: Remove existing function bindings
            cleanupExistingFunctions()

            // Step 3: Set Apple device identifiers
            writeToFile("$GADGET_PATH/bDeviceClass", "0")
            writeToFile("$GADGET_PATH/bDeviceSubClass", "0")
            writeToFile("$GADGET_PATH/bDeviceProtocol", "0")
            writeToFile("$GADGET_PATH/idVendor", APPLE_VID)
            writeToFile("$GADGET_PATH/idProduct", APPLE_PID)
            writeToFile("$GADGET_PATH/bcdDevice", APPLE_BCD_DEVICE)
            writeToFile("$GADGET_PATH/bcdUSB", APPLE_BCD_USB)
            logDebug("Device IDs set: VID=$APPLE_VID PID=$APPLE_PID")

            // Step 4: Set string descriptors
            writeToFile("$GADGET_PATH/strings/0x409/product", APPLE_PRODUCT)
            writeToFile("$GADGET_PATH/strings/0x409/manufacturer", APPLE_MANUFACTURER)
            writeToFile("$GADGET_PATH/strings/0x409/serialnumber", APPLE_SERIAL)
            logDebug("String descriptors set")

            // Step 5: Create USB configurations
            setupConfigurations()

            // Step 6: Attach UDC
            val udcController = findUdcController()
            if (udcController != null) {
                writeToFile(UDC_PATH, udcController)
                logDebug("UDC attached: $udcController")
            } else {
                logError("No UDC controller found")
                return false
            }

            logDebug("USB Gadget configured as Apple device successfully!")
            flushDebugLog()
            return true

        } catch (e: Exception) {
            logError("Failed to configure USB gadget: ${e.message}")
            Timber.e(e, "USB gadget configuration failed")
            flushDebugLog()
            return false
        }
    }

    /**
     * Reset USB gadget to default Android configuration
     */
    fun resetToDefault(): Boolean {
        logDebug("Resetting USB gadget to default")
        return try {
            writeToFile(UDC_PATH, "none")
            writeToFile("/sys/class/android_usb/android0/enable", "1")
            writeToFile("/sys/class/android_usb/android0/idVendor", "18D1")
            writeToFile("/sys/class/android_usb/android0/idProduct", "4EE2")
            logDebug("USB gadget reset to default")
            flushDebugLog()
            true
        } catch (e: Exception) {
            logError("Failed to reset: ${e.message}")
            false
        }
    }

    // ── Configuration Setup ────────────────────────────────

    private fun setupConfigurations() {
        // Config 1: PTP (Image transfer)
        execRoot("mkdir -p $GADGET_PATH/configs/b.1")
        writeToFile("$GADGET_PATH/configs/b.1/bmAttributes", "0xC0")
        writeToFile("$GADGET_PATH/configs/b.1/strings/0x409/configuration", "PTP")
        linkFunction("mtp.gs0", "b.1", "f1")
        logDebug("Config 1: PTP setup")

        // Config 2: iPod USB Interface (Audio + HID for touch)
        execRoot("mkdir -p $GADGET_PATH/configs/b.2")
        execRoot("mkdir -p $GADGET_PATH/configs/b.2/strings/0x409")
        writeToFile("$GADGET_PATH/configs/b.2/MaxPower", "500")
        writeToFile("$GADGET_PATH/configs/b.2/bmAttributes", "0xC0")
        writeToFile("$GADGET_PATH/configs/b.2/strings/0x409/configuration", "iPod USB Interface")
        linkFunction("audio_source.gs3", "b.2", "f1")
        linkFunction("hid.gs0", "b.2", "f2")
        logDebug("Config 2: iPod Interface setup")

        // Config 3: PTP + Apple Mobile Device (iAP2 control channel)
        execRoot("mkdir -p $GADGET_PATH/configs/b.3")
        execRoot("mkdir -p $GADGET_PATH/configs/b.3/strings/0x409")
        writeToFile("$GADGET_PATH/configs/b.3/MaxPower", "500")
        writeToFile("$GADGET_PATH/configs/b.3/bmAttributes", "0xC0")
        writeToFile("$GADGET_PATH/configs/b.3/strings/0x409/configuration", "PTP + Apple Mobile Device")
        linkFunction("mtp.gs1", "b.3", "f1")
        linkFunction("acm.gs0", "b.3", "f2")
        logDebug("Config 3: PTP + Apple Mobile Device setup")

        // Config 4: PTP + Apple Mobile Device + USB Ethernet (NCM for video/audio)
        execRoot("mkdir -p $GADGET_PATH/configs/b.4")
        execRoot("mkdir -p $GADGET_PATH/configs/b.4/strings/0x409")
        writeToFile("$GADGET_PATH/configs/b.4/MaxPower", "500")
        writeToFile("$GADGET_PATH/configs/b.4/bmAttributes", "0xC0")
        writeToFile("$GADGET_PATH/configs/b.4/strings/0x409/configuration",
            "PTP + Apple Mobile Device + Apple USB Ethernet")
        linkFunction("mtp.gs2", "b.4", "f1")
        linkFunction("acm.gs1", "b.4", "f2")
        linkFunction("ncm.gs0", "b.4", "f3")
        logDebug("Config 4: NCM setup complete")
    }

    /**
     * Alternative configuration using standard Android gadget functions
     * (for devices that don't have okcar custom functions)
     */
    fun configureWithStandardFunctions(): Boolean {
        logDebug("Configuring with standard gadget functions")

        if (!canConfigure()) return false

        try {
            writeToFile(UDC_PATH, "none")
            cleanupExistingFunctions()

            // Set Apple identifiers
            writeToFile("$GADGET_PATH/idVendor", APPLE_VID)
            writeToFile("$GADGET_PATH/idProduct", APPLE_PID)
            writeToFile("$GADGET_PATH/bcdUSB", APPLE_BCD_USB)
            writeToFile("$GADGET_PATH/strings/0x409/product", APPLE_PRODUCT)
            writeToFile("$GADGET_PATH/strings/0x409/manufacturer", APPLE_MANUFACTURER)
            writeToFile("$GADGET_PATH/strings/0x409/serialnumber", APPLE_SERIAL)

            // Use standard ACM (serial) for iAP2 channel
            execRoot("mkdir -p $GADGET_PATH/configs/b.1")
            writeToFile("$GADGET_PATH/configs/b.1/bmAttributes", "0xC0")
            writeToFile("$GADGET_PATH/configs/b.1/strings/0x409/configuration", "CarPlay")

            // Try to link available functions
            linkFunction("acm.gs0", "b.1", "f1")
            linkFunction("mtp.gs0", "b.1", "f2")

            // Re-attach
            val udc = findUdcController()
            if (udc != null) {
                writeToFile(UDC_PATH, udc)
                logDebug("Standard gadget configured: $udc")
                flushDebugLog()
                return true
            }
        } catch (e: Exception) {
            logError("Standard config failed: ${e.message}")
        }

        flushDebugLog()
        return false
    }

    // ── Network (NCM) Setup ───────────────────────────────

    /**
     * Set up NCM network interface for CarPlay video/audio stream
     * Based on OkcarOS approach: cdc_ncm kernel module + IP routing
     */
    fun setupNcmNetwork(): Boolean {
        logDebug("Setting up NCM network")

        try {
            // Load cdc_ncm kernel module
            execRoot("modprobe -a -d /vendor/lib/modules cdc_ncm 2>/dev/null || " +
                     "insmod /vendor/lib/modules/cdc_ncm.ko 2>/dev/null || true")

            // Configure NCM interface parameters
            execRoot("chown system system /sys/class/net/usb0/cdc_ncm/min_tx_pkt 2>/dev/null || true")
            execRoot("chown system system /sys/class/net/usb0/cdc_ncm/ndp_to_end 2>/dev/null || true")
            execRoot("chown system system /sys/class/net/usb0/cdc_ncm/rx_max 2>/dev/null || true")
            execRoot("chown system system /sys/class/net/usb0/cdc_ncm/tx_max 2>/dev/null || true")
            execRoot("chown system system /sys/class/net/usb0/cdc_ncm/tx_timer_usecs 2>/dev/null || true")

            // Set up IPv6 routing (CarPlay uses link-local IPv6)
            execRoot("ip -6 route add fe80::/64 dev usb0 metric 256 2>/dev/null || true")
            execRoot("ip -6 rule add from all lookup main pref 10400 2>/dev/null || true")

            logDebug("NCM network setup complete")
            return true
        } catch (e: Exception) {
            logError("NCM setup failed: ${e.message}")
            return false
        }
    }

    // ── HID Setup ──────────────────────────────────────────

    /**
     * Set up HID gadget for touch input from head unit
     */
    fun setupHidGadget(): Boolean {
        logDebug("Setting up HID gadget")

        try {
            // Check if HID gadget exists
            val hidDir = File("$GADGET_PATH/functions/hid.gs0")
            if (!hidDir.exists()) {
                execRoot("mkdir -p $GADGET_PATH/functions/hid.gs0")
            }

            // Configure HID
            writeToFile("$GADGET_PATH/functions/hid.gs0/subclass", "0")
            writeToFile("$GADGET_PATH/functions/hid.gs0/protocol", "0")
            writeToFile("$GADGET_PATH/functions/hid.gs0/report_length", "64")
            writeToFile("$GADGET_PATH/functions/hid.gs0/no_out_endpoint", "1")

            // Copy HID report descriptor
            val reportDesc = File("/vendor/etc/init/hw/okcar.hidreport.bin")
            if (reportDesc.exists()) {
                execRoot("cp /vendor/etc/init/hw/okcar.hidreport.bin " +
                         "$GADGET_PATH/functions/hid.gs0/report_desc")
            }

            logDebug("HID gadget setup complete")
            return true
        } catch (e: Exception) {
            logError("HID setup failed: ${e.message}")
            return false
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private fun cleanupExistingFunctions() {
        for (i in 1..9) {
            val link = File("$GADGET_PATH/configs/b.1/f$i")
            if (link.exists()) {
                execRoot("rm $GADGET_PATH/configs/b.1/f$i")
            }
        }
    }

    private fun linkFunction(functionName: String, config: String, slotName: String) {
        val funcPath = "$GADGET_PATH/functions/$functionName"
        val configPath = "$GADGET_PATH/configs/$config/$slotName"

        // Only link if function exists
        if (File(funcPath).exists()) {
            execRoot("ln -sf $funcPath $configPath")
            logDebug("Linked $functionName → $config/$slotName")
        } else {
            logDebug("Function $functionName not available, skipping")
        }
    }

    private fun findUdcController(): String? {
        val udcDir = File("/sys/class/udc")
        if (udcDir.exists()) {
            val controllers = udcDir.listFiles()?.map { it.name }
            if (!controllers.isNullOrEmpty()) {
                return controllers.first()
            }
        }
        return null
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0") || output.contains("root")
        } catch (e: Exception) {
            false
        }
    }

    private fun writeToFile(path: String, content: String) {
        execRoot("echo '$content' > $path")
    }

    private fun execRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotEmpty()) {
                logDebug("exec error: $error")
            }
            output
        } catch (e: Exception) {
            logDebug("exec failed: ${e.message}")
            ""
        }
    }

    // ── Debug Logging ──────────────────────────────────────

    private fun logDebug(msg: String) {
        Timber.d(msg)
        debugLog.appendLine("[${System.currentTimeMillis()}] D: $msg")
    }

    private fun logError(msg: String) {
        Timber.e(msg)
        debugLog.appendLine("[${System.currentTimeMillis()}] E: $msg")
    }

    /**
     * Flush debug log to /sdcard/carplay_debug.log
     */
    private fun flushDebugLog() {
        try {
            val file = File(DEBUG_LOG_PATH)
            file.appendText(debugLog.toString())
            debugLog.clear()
            Timber.d("Debug log flushed to $DEBUG_LOG_PATH")
        } catch (e: Exception) {
            Timber.e(e, "Failed to flush debug log")
        }
    }

    /**
     * Get the full debug log content
     */
    fun getDebugLog(): String {
        return try {
            File(DEBUG_LOG_PATH).readText()
        } catch (e: Exception) {
            "No debug log available"
        }
    }

    /**
     * Clear debug log
     */
    fun clearDebugLog() {
        try {
            File(DEBUG_LOG_PATH).writeText("")
            debugLog.clear()
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear debug log")
        }
    }
}
