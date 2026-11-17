package com.example.tp1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.tp1.chat.ChatViewModel
import com.example.tp1.search.SearchViewModel
import com.example.tp1.indexing.RetrievalResult
import kotlinx.coroutines.awaitCancellation
import org.junit.rules.ExternalResource

class OfflineChatRule internal constructor(
    private val searchRetrieve: suspend (String) -> RetrievalResult = { error("Navigation test must not load retrieval weights") }
) : ExternalResource() {
    private val indexing = OfflineIndexingRule()
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) {
            // Acquisition is replaced before launch; install the fake native owner before onStart.
            ViewModelProvider(activity, viewModelFactory {
                initializer {
                    ChatViewModel(activity.application, createSavedStateHandle(),
                        downloadModel = { awaitCancellation() },
                        openModel = { error("Navigation test must not load a real model") },
                        retrieve = { error("Navigation test must not load retrieval weights") })
                }
            })[ChatViewModel::class.java]
            ViewModelProvider(activity, viewModelFactory {
                initializer {
                    SearchViewModel(activity.application, createSavedStateHandle(), retrieve = searchRetrieve, checkContext = {})
                }
            })[SearchViewModel::class.java]
        }
    }

    override fun before() {
        indexing.install()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
    }

    override fun after() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
        indexing.uninstall()
    }
}
