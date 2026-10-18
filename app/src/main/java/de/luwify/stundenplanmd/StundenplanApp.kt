package de.luwify.stundenplanmd

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class StundenplanApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Update check whenever the app comes to the foreground (throttled, if on). Not per activity: rotations
        // and switching between activities don't count.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.updates.onForeground()
        })
    }
}
