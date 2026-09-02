package com.itantra

import android.app.Application
import android.util.Log

class iTantraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "iTantra Application Initialized - Offline Multilingual Neural Transceiver")
    }

    companion object {
        const val TAG = "iTantra"
    }
}
