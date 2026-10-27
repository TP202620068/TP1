package com.example.tp1

import android.graphics.Color
import android.os.Bundle
import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.tp1.browser.BrowserViewModel
import com.example.tp1.chat.ChatViewModel
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourcesViewModel
import com.example.tp1.sources.sourcePickerIntent
import com.example.tp1.ui.SourceFlow
import com.example.tp1.ui.theme.TP1Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sources: SourcesViewModel by viewModels()
    private val browser: BrowserViewModel by viewModels()
    private val chat: ChatViewModel by viewModels()
    private val documents = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        receiveSources(it, SourceKind.Document)
    }
    private val tree = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        receiveSources(it, SourceKind.Tree)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
        setContent {
            TP1Theme {
                SourceFlow(sources, browser, chat, onPick = { kind ->
                    try {
                        (if (kind == SourceKind.Tree) tree else documents).launch(sourcePickerIntent(kind))
                    } catch (_: ActivityNotFoundException) {
                        sources.pickerUnavailable()
                    }
                }, onOpen = { entry -> browser.open(entry) { startActivity(it) } },
                    onRefresh = { refreshBrowser() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!sources.state.value.restoring) refreshBrowser()
    }

    override fun onStart() {
        super.onStart()
        chat.setForeground(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) chat.setForeground(false)
        super.onStop()
    }

    private fun refreshBrowser() = lifecycleScope.launch {
        sources.refresh().join()
        browser.setSources(sources.state.value.sources, refresh = true)
    }

    private fun receiveSources(result: ActivityResult, kind: SourceKind) {
        if (result.resultCode != Activity.RESULT_OK) return
        val data = result.data ?: return
        val uris = buildList {
            data.data?.let { add(it.toString()) }
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { add(it.toString()) }
            }
        }
        sources.add(uris, kind, data.flags)
    }
}
