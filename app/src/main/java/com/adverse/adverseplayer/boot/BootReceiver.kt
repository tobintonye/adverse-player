package com.adverse.adverseplayer.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adverse.adverseplayer.MainActivity


/**
 * No one is driving out to a billboard site to tap "reopen app" after a
 * power blip. This is non-negotiable for unattended outdoor hardware.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
