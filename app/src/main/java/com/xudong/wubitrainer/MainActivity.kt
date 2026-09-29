package com.xudong.wubitrainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xudong.wubitrainer.ui.WubiRoot
import com.xudong.wubitrainer.ui.theme.WubiTheme

/**
 * The only activity.
 *
 * It declares the config changes it can absorb itself (see the manifest) so that a rotation or
 * a keyboard being attached mid-answer does not recreate it and lose the half-typed buffer.
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = AppController(this)
        app.start()
        setContent {
            WubiTheme {
                WubiRoot(app)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Progress is already on disk up to the debounce window; closing the last window is
        // the moment to make that window zero.
        app.flushNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.dispose()
    }
}
