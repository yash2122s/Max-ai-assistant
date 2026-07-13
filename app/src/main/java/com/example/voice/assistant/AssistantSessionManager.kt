package com.example.voice.assistant

import com.example.viewmodel.ChatViewModel

object AssistantSessionManager {
    val viewModel: ChatViewModel
        get() = ChatViewModel.instance
}
