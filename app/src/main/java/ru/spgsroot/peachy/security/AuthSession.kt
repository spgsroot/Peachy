package ru.spgsroot.peachy.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthSession @Inject constructor() {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /**
     * Mirrors [AuthManager] so the activity lifecycle can decide synchronously whether leaving the
     * app has to lock anything. Without a password the session has nothing to protect.
     */
    @Volatile
    private var passwordConfigured = false

    fun setPasswordConfigured(configured: Boolean) {
        passwordConfigured = configured
    }

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }

    /** Locks a password-protected session; returns true when it actually locked. */
    fun lockIfProtected(): Boolean {
        if (!passwordConfigured) return false
        lock()
        return true
    }
}
