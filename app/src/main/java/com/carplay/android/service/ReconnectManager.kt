package com.carplay.android.service

import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Auto-Reconnect Manager
 *
 * Handles automatic reconnection with exponential backoff
 * when the USB connection drops unexpectedly.
 *
 * Backoff strategy:
 * - Attempt 1: 1s delay
 * - Attempt 2: 2s delay
 * - Attempt 3: 4s delay
 * - Attempt 4: 8s delay
 * - Max delay capped at 30s
 * - Max 10 retries before giving up
 */
class ReconnectManager(
    private val onReconnect: () -> Unit,
    private val onReconnectFailed: () -> Unit
) {

    companion object {
        private const val TAG = "ReconnectManager"
        private const val INITIAL_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L
        private const val MAX_RETRIES = 10
        private const val BACKOFF_MULTIPLIER = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var currentDelay = INITIAL_DELAY_MS
    private var isReconnecting = false
    private var isEnabled = true

    /**
     * Start the reconnection process
     */
    fun startReconnect() {
        if (!isEnabled) {
            Timber.d("Reconnect disabled, skipping")
            return
        }

        if (isReconnecting) {
            Timber.d("Reconnect already in progress")
            return
        }

        if (retryCount >= MAX_RETRIES) {
            Timber.e("Max retries ($MAX_RETRIES) reached, giving up")
            onReconnectFailed()
            return
        }

        isReconnecting = true
        retryCount++
        val delay = currentDelay

        Timber.d("Reconnect attempt $retryCount/$MAX_RETRIES in ${delay}ms")

        handler.postDelayed({
            isReconnecting = false
            onReconnect()

            // Increase delay for next attempt
            currentDelay = (currentDelay * BACKOFF_MULTIPLIER).coerceAtMost(MAX_DELAY_MS)
        }, delay)
    }

    /**
     * Reset reconnect state (call after successful connection)
     */
    fun reset() {
        Timber.d("Resetting reconnect state (had $retryCount attempts)")
        handler.removeCallbacksAndMessages(null)
        retryCount = 0
        currentDelay = INITIAL_DELAY_MS
        isReconnecting = false
    }

    /**
     * Cancel any pending reconnection
     */
    fun cancel() {
        Timber.d("Cancelling reconnect")
        handler.removeCallbacksAndMessages(null)
        isReconnecting = false
    }

    /**
     * Enable/disable auto-reconnect
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) cancel()
    }

    /**
     * Check if currently attempting reconnection
     */
    fun isReconnecting() = isReconnecting

    /**
     * Get current retry count
     */
    fun getRetryCount() = retryCount
}
