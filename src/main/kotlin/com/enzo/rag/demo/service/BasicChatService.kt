package com.enzo.rag.demo.service

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service

@Service
class BasicChatService(
    private val chatModel: ChatModel
) {
    
    fun chat(message: String): String {
        return try {
            val prompt = Prompt(message)
            val result = chatModel.call(prompt)
            // 簡化響應處理
            result.toString()
        } catch (e: Exception) {
            "抱歉，無法連接到聊天模型：${e.message}"
        }
    }

    fun directChat(message: String): String {
        return chat(message) // 這將使用SimpleChatModel中的繁體中文系統提示
    }
    
    fun chatWithContext(message: String, context: String = ""): String {
        val fullMessage = if (context.isNotEmpty()) {
            """
            上下文：$context
            
            問題：$message
            
            請基於上下文回答問題：
            """.trimIndent()
        } else {
            message
        }
        
        return chat(fullMessage)
    }
}