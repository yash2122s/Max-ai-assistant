package com.example.voice.assistant

import android.content.Context
import com.example.viewmodel.ChatViewModel

object AssistantSessionManager {
    val viewModel: ChatViewModel
        get() = ChatViewModel.instance

    fun getEngine(context: Context): AssistantEngine =
        AssistantLifecycleManager.getOrCreateEngine(context)
}
