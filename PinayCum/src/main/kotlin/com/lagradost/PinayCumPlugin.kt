package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PinayCumPlugin : Plugin() {
    override fun load(context: Context) {
        // Registers your main API class and passes the required Android context down to it
        registerMainAPI(PinayCum(context))
    }
}