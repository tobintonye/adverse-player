package com.adverse.adverseplayer

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adverse.adverseplayer.sync.SyncService
import com.adverse.adverseplayer.device.AppDeviceAdminReceiver
import com.adverse.adverseplayer.sync.DeviceState
import com.adverse.adverseplayer.ui.PlayerScreen

class MainActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        SyncService.start(this)

        // Only takes effect once the box has been provisioned as device
        // owner
        if (AppDeviceAdminReceiver.isDeviceOwner(this)) {
            AppDeviceAdminReceiver.enableLockTask(this)
            startLockTask()
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    val state by SyncService.state.collectAsStateWithLifecycle()
                    val audioEnabled by SyncService.audioEnabled.collectAsStateWithLifecycle()
                    when (val s = state) {
                        is DeviceState.Unpaired -> LoadingScreen()
                        is DeviceState.ShowPairingCode -> PairingCodeScreen(s.code)
                        is DeviceState.Playing -> PlayerScreen(items = s.items, context = this@MainActivity, audioEnabled = audioEnabled)
                    }
                }
            }
        }
    } // override function ends here


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
    }

}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("AdVerse Player starting…", color = Color.White, fontSize = 24.sp)
    }
}

@Composable
private fun PairingCodeScreen(code: String) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Enter this code in the AdVerse dashboard", color = Color.Gray, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(code, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold)
        }
    }
}