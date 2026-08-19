package com.adverse.adverseplayer

import android.app.Application
import com.adverse.adverseplayer.sync.KeepAliveWorker

class AdverseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KeepAliveWorker.schedule(this)
    }
}
