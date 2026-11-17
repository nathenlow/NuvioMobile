package com.nuvio.app.features.player.audiosync

import android.content.Context
import android.net.ConnectivityManager

/** Whether the active network is free to use heavily (not mobile data or another metered link). */
internal object NetworkMetering {
    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return !manager.isActiveNetworkMetered
    }
}
