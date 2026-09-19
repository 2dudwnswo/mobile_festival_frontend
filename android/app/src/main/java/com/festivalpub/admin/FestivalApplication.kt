package com.festivalpub.admin

import android.app.Application
import com.festivalpub.admin.data.FirebaseConnection

class FestivalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseConnection.initialize(this)
    }
}
