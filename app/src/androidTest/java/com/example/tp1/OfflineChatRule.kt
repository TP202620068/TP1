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
import kotlinx.coroutines.awaitCancellation
import org.junit.rules.ExternalResource

class OfflineChatRule : ExternalResource() {
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) {
            // Install before onStart so navigation tests never download model weights.
            ViewModelProvider(activity, viewModelFactory {
                initializer {
                    ChatViewModel(activity.application, createSavedStateHandle(),
                        downloadModel = { awaitCancellation() },
                        openModel = { error("Navigation test must not load a real model") })
                }
            })[ChatViewModel::class.java]
        }
    }

    override fun before() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
    }

    override fun after() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
    }
}
