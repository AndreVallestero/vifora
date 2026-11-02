package com.vifora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.vifora.app.ui.ViforaScreen
import com.vifora.app.vm.CompressionViewModel

class MainActivity : ComponentActivity() {
    private val vm: CompressionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    ViforaScreen(vm = vm, activity = this)
                }
            }
        }
    }
}
