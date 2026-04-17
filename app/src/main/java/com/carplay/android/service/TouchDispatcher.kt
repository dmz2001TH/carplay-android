package com.carplay.android.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.view.Display
import android.view.WindowManager
import timber.log.Timber

/**
 * Touch Input Dispatcher
 *
 * Converts touch events received from the CarPlay head unit
 * into Android input events.
 *
 * The head unit sends touch coordinates in its display space
 * (typically 800x480 for NissanConnect). These need to be mapped
 * to the phone's display coordinates.
 *
 * Two dispatch modes:
 * 1. AccessibilityService (requires user to enable accessibility service)
 * 2. Instrumentation (requires ADB shell, for dev/testing)
 *
 * Touch mapping:
 * - Head unit reports: x=[0..headWidth], y=[0..headHeight]
 * - Phone expects: x=[0..phoneWidth], y=[0..phoneHeight]
 * - Linear mapping with configurable scaling and offset
 */
class TouchDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "TouchDispatcher"

        // Default NissanConnect display dimensions
        const val HEAD_UNIT_WIDTH = 800
        const val HEAD_UNIT_HEIGHT = 480

        // Touch action codes from iAP2
        const val ACTION_DOWN: Byte = 0x01
        const val ACTION_UP: Byte = 0x02
        const val ACTION_MOVE: Byte = 0x03
    }

    // Phone display dimensions (auto-detected)
    private var phoneWidth: Int = 0
    private var phoneHeight: Int = 0

    // Head unit display dimensions (configurable)
    var headUnitWidth = HEAD_UNIT_WIDTH
    var headUnitHeight = HEAD_UNIT_HEIGHT

    // Touch calibration offsets (for fine-tuning alignment)
    var offsetX = 0f
    var offsetY = 0f
    var scaleX = 1f
    var scaleY = 1f

    // Accessibility service reference (if available)
    private var accessibilityService: AccessibilityService? = null

    // Track active pointer for gesture completion
    private var lastX = 0f
    private var lastY = 0f

    init {
        detectPhoneDisplay()
    }

    /**
     * Detect phone display dimensions
     */
    private fun detectPhoneDisplay() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        @Suppress("DEPRECATION")
        display.getSize(size)
        phoneWidth = size.x
        phoneHeight = size.y
        Timber.d("Phone display: ${phoneWidth}x${phoneHeight}")
    }

    /**
     * Set accessibility service for touch dispatch
     */
    fun setAccessibilityService(service: AccessibilityService?) {
        this.accessibilityService = service
        Timber.d("Accessibility service ${if (service != null) "set" else "cleared"}")
    }

    /**
     * Dispatch a touch event from head unit to Android
     *
     * @param rawX X coordinate in head unit space (0..headUnitWidth)
     * @param rawY Y coordinate in head unit space (0..headUnitHeight)
     * @param action Touch action (DOWN, UP, MOVE)
     */
    fun dispatchTouch(rawX: Int, rawY: Int, action: Byte) {
        // Map head unit coordinates to phone coordinates
        val mappedX = mapCoordinateX(rawX)
        val mappedY = mapCoordinateY(rawY)

        Timber.v("Touch: raw=($rawX,$rawY) → mapped=($mappedX,$mappedY) action=$action")

        lastX = mappedX
        lastY = mappedY

        // Try accessibility service first, fall back to injection
        if (accessibilityService != null) {
            dispatchViaAccessibility(mappedX, mappedY, action)
        } else {
            dispatchViaInjection(mappedX, mappedY, action)
        }
    }

    /**
     * Map head unit X coordinate to phone X coordinate
     */
    private fun mapCoordinateX(rawX: Int): Float {
        val normalized = rawX.toFloat() / headUnitWidth
        val scaled = normalized * scaleX + offsetX
        return (scaled * phoneWidth).coerceIn(0f, phoneWidth.toFloat())
    }

    /**
     * Map head unit Y coordinate to phone Y coordinate
     */
    private fun mapCoordinateY(rawY: Int): Float {
        val normalized = rawY.toFloat() / headUnitHeight
        val scaled = normalized * scaleY + offsetY
        return (scaled * phoneHeight).coerceIn(0f, phoneHeight.toFloat())
    }

    /**
     * Dispatch touch via AccessibilityService gesture dispatch
     * This is the most reliable method but requires user to enable the service
     */
    private fun dispatchViaAccessibility(x: Float, y: Float, action: Byte) {
        val service = accessibilityService ?: return

        try {
            when (action) {
                ACTION_DOWN -> {
                    val path = Path().apply { moveTo(x, y) }
                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 50
                    )
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()
                    service.dispatchGesture(gesture, null, null)
                }
                ACTION_MOVE -> {
                    // For moves, dispatch a new stroke from last position to current
                    val path = Path().apply {
                        moveTo(lastX, lastY)
                        lineTo(x, y)
                    }
                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 50
                    )
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()
                    service.dispatchGesture(gesture, null, null)
                }
                ACTION_UP -> {
                    // Touch up — gesture ends naturally
                    Timber.v("Touch UP at ($x, $y)")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Accessibility dispatch error")
        }
    }

    /**
     * Dispatch touch via input injection (requires shell permissions)
     * Used as fallback for development/testing
     */
    private fun dispatchViaInjection(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") action: Byte) {
        try {
            // Use Runtime.exec to call input tap/sendevent
            // Note: This requires root or shell permissions
            val process = Runtime.getRuntime().exec(
                arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString())
            )
            process.waitFor()
            Timber.v("Input injection: tap($x, $y)")
        } catch (e: Exception) {
            // Expected to fail without root — just log
            Timber.v("Input injection failed (no root?): ${e.message}")
        }
    }

    /**
     * Configure touch calibration for specific head unit
     *
     * @param headWidth Head unit display width
     * @param headHeight Head unit display height
     * @param offsetX X offset (0.0 to 1.0)
     * @param offsetY Y offset (0.0 to 1.0)
     * @param scaleX X scale factor
     * @param scaleY Y scale factor
     */
    fun calibrate(
        headWidth: Int = HEAD_UNIT_WIDTH,
        headHeight: Int = HEAD_UNIT_HEIGHT,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        this.headUnitWidth = headWidth
        this.headUnitHeight = headHeight
        this.offsetX = offsetX
        this.offsetY = offsetY
        this.scaleX = scaleX
        this.scaleY = scaleY

        Timber.d("Touch calibrated: head=${headWidth}x${headHeight}, " +
                "offset=($offsetX,$offsetY), scale=($scaleX,$scaleY)")
    }
}
