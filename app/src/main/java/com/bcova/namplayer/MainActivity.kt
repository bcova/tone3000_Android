package com.bcova.namplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.bcova.namplayer.audio.PedalController
import com.bcova.namplayer.t3k.AuthCallback
import com.bcova.namplayer.ui.AppRoot
import com.bcova.namplayer.ui.AmpfieldTheme
import com.bcova.namplayer.ui.T3kViewModel

class MainActivity : ComponentActivity() {

    private val t3k: T3kViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the screen on while playing.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // App was started by TONE3000's redirect (e.g. Android closed it while the browser was open).
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AmpfieldTheme {
                AppRoot(t3k = t3k)
            }
        }
    }

    // TONE3000 redirects to com.bcova.namplayer://callback after sign-in / picking a tone.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Page-turner footswitches type keys (Page Down, arrows…). Mapped keys control the rig.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && PedalController.get(this).onKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (AuthCallback.isCallback(uri)) {
            t3k.handleCallback(uri!!)
            setIntent(Intent(intent).setData(null)) // don't handle it twice
        }
    }
}
