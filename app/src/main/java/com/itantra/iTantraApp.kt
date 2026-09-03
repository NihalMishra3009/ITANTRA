package com.itantra

import android.app.Application
import android.util.Log
import com.itantra.orchestrator.PipelineOrchestrator

class iTantraApp : Application() {

    /** Shared orchestration state so secondary screens (network map, diagnostics)
     *  can read live transport / routing / delivery status without owning engines. */
    var orchestrator: PipelineOrchestrator? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "iTantra Application Initialized - Offline Multilingual Neural Transceiver")
    }

    companion object {
        const val TAG = "iTantra"
    }
}
