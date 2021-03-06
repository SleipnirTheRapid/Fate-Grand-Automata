package com.mathewsachin.fategrandautomata.ui.prefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.mathewsachin.fategrandautomata.prefs.core.PrefsCore
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class MainSettingsViewModel @Inject constructor(
    prefs: PrefsCore
) : ViewModel() {
    val gameServer = prefs
        .gameServer
        .asFlow()
        .asLiveData()

    val refillRepetitions = prefs
        .refill
        .repetitions
        .asFlow()
        .asLiveData()

    val refillMessage = combine(
        prefs.refill.enabled.asFlow(),
        prefs.refill.resource.asFlow(),
        prefs.refill.repetitions.asFlow()
    ) { enabled, resource, repetitions ->
        if (enabled)
            "$resource x${repetitions}"
        else "OFF"
    }
        .asLiveData()
}