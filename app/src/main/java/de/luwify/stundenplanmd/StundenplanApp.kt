package de.luwify.stundenplanmd

import android.app.Application

class StundenplanApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
