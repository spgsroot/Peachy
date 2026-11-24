package ru.spgsroot.peachy.nsfw

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether NSFW features may run. Null means the stored row has not been read yet, and that
 * state stays closed: nothing adult is shown or started before the setting is known.
 */
@Singleton
class NsfwGate @Inject constructor() {
    private val state = MutableStateFlow<Boolean?>(null)
    val enabled: StateFlow<Boolean?> = state.asStateFlow()

    fun publish(stored: Boolean?) {
        state.value = stored
    }

    fun allows(): Boolean = state.value == true
}
