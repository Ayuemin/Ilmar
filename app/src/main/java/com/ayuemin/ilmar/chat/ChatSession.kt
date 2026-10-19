package com.ayuemin.ilmar.chat

data class ChatSession(val id: Long, val title: String, val messages: List<ChatMessage> = emptyList())
