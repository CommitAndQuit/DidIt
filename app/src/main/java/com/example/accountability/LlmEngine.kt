package com.example.accountability

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlmEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val modelPath = "gemma_270m.task"

    suspend fun initialize() {
        if (llmInference != null) return
        withContext(Dispatchers.IO) {
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                Log.d("LlmEngine", "LLM Initialized successfully")
            } catch (e: Exception) {
                Log.e("LlmEngine", "Error initializing LLM", e)
                // For dummy model, this will likely fail initialization.
                // Catching to prevent crash in demo.
            }
        }
    }

    suspend fun generateSarcasticPrompt(userProfile: String, eventName: String): String {
        return withContext(Dispatchers.IO) {
            if (llmInference == null) {
                // Return a fallback response for dummy/failed initializations
                return@withContext "You were supposed to do '$eventName'. Based on your profile ($userProfile), I doubt you actually did it. Did you slack off again?"
            }

            val prompt = """
                You are a sarcastic, harsh accountability partner.
                The user's profile is: $userProfile.
                The user scheduled $eventName on their calendar.
                Write exactly 2 short, biting sentences asking if they actually did the task or slacked off.
                Make them feel guilty based on their profile. Do not offer solutions or encouragement.
            """.trimIndent()

            try {
                val response = llmInference?.generateResponse(prompt) ?: "Error generating text."
                response
            } catch (e: Exception) {
                Log.e("LlmEngine", "Inference error", e)
                "Failed to generate guilt trip. But you probably skipped '$eventName' anyway."
            }
        }
    }
}
