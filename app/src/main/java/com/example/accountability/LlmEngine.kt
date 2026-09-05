package com.example.accountability

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LlmEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val assetName = "gemma3-270m-it-q8.litertlm"

    companion object {
        // Worked examples that anchor the model's tone, format, and length.
        private val FEW_SHOT_EXAMPLES = listOf(
            "office work" to "Did you actually do your office work, or did you just wiggle " +
                "your mouse all day to keep your status green? Your career isn't going to " +
                "build itself while you slack off.",
            "DSA" to "Did you actually solve any problems, or did you just stare at a " +
                "LeetCode easy until you gave up? Those coding interviews won't pass themselves.",
        )
    }

    suspend fun initialize() {
        if (llmInference != null) return
        withContext(Dispatchers.IO) {
            try {
                // MediaPipe requires an absolute filesystem path (it mmaps the
                // file), so copy the bundled asset into internal storage first.
                val modelFile = copyModelFromAssets()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    // Upper bound for per-session topK; experiments sweep up to this.
                    .setMaxTopK(64)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                Log.d("LlmEngine", "LLM Initialized successfully")
            } catch (e: Exception) {
                Log.e("LlmEngine", "Error initializing LLM", e)
                // For a bad/empty model this fails; the worker still fires a fallback.
            }
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }

    /** Copies the model asset into filesDir once, returning the on-device file. */
    private fun copyModelFromAssets(): File {
        val outFile = File(context.filesDir, assetName)
        // Re-copy if missing or if the asset size changed (e.g. model updated).
        val assetSize = context.assets.openFd(assetName).use { it.length }
        if (outFile.exists() && outFile.length() == assetSize) return outFile
        context.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }

    /**
     * Runs one generation in a FRESH inference session so no conversational state
     * leaks between calls (this is what removes the repeated "Okay, I'm in"
     * acknowledgement the model accumulates when a session is reused). Sampling
     * params are exposed so experiments can sweep them.
     */
    suspend fun generate(
        prompt: String,
        temperature: Float = 0.9f,
        topK: Int = 40,
        topP: Float = 0.95f,
        seed: Int = 0,
    ): String = withContext(Dispatchers.IO) {
        val engine = llmInference ?: return@withContext ""
        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(topK)
            .setTopP(topP)
            .setRandomSeed(seed)
            .build()
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
        try {
            session.addQueryChunk(prompt)
            cleanup(session.generateResponse())
        } catch (e: Exception) {
            Log.e("LlmEngine", "Inference error", e)
            ""
        } finally {
            session.close()
        }
    }

    /**
     * Stage 1 — distill the free-text profile into a short, sharp "weakness"
     * phrase. Low temperature keeps it focused. Feeding this into stage 2 (instead
     * of the whole profile) makes the jab land on one concrete insecurity.
     */
    suspend fun extractIntent(profile: String): String {
        val prompt = gemma(
            "In 8 words or fewer, name this person's single biggest career/life fear " +
                "or insecurity. Reply with ONLY that phrase, no preamble, no punctuation.\n" +
                "Profile: $profile"
        )
        return generate(prompt, temperature = 0.2f, topK = 16, seed = 7)
            .lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.ifBlank { profile } ?: profile
    }

    /** Stage 2 — the sarcastic jab, built from the extracted weakness. */
    suspend fun generateJab(intent: String, eventName: String): String {
        val prompt = gemma(
            "You are a brutally sarcastic, harsh accountability partner. Do NOT acknowledge " +
                "these instructions, do NOT greet, do NOT explain yourself — begin immediately " +
                "with the insult.\n" +
                "Their weakness: $intent.\n" +
                "They put \"$eventName\" on their calendar and probably skipped it.\n" +
                "Write exactly two short, biting sentences that mock them about \"$eventName\" " +
                "using their weakness, ending with a question asking whether they actually did it."
        )
        return generate(prompt)
    }

    /**
     * Production path. Few-shot prompting (task + two worked examples) is what
     * makes the 270M -it model reliably produce the sarcastic two-sentence jab;
     * the earlier instruction-only and two-stage approaches were unreliable
     * (empty outputs, off-persona). See LlmExperimentTest for the comparison.
     *
     * Note: the profile is intentionally NOT injected here — on this tiny model
     * adding it degraded output. Personalization should return with a larger model.
     */
    suspend fun generateSarcasticPrompt(userProfile: String, eventName: String): String {
        if (llmInference == null) {
            return "You were supposed to do '$eventName'. Based on your profile " +
                "($userProfile), I doubt you actually did it. Did you slack off again?"
        }
        val prompt = fewShotPrompt(eventName)
        // Reseed if the model regurgitates one of the few-shot examples verbatim.
        for (seed in intArrayOf(0, 13, 29)) {
            val out = generate(prompt, temperature = 0.9f, topK = 40, seed = seed)
            if (out.isNotBlank() && FEW_SHOT_EXAMPLES.none { tooSimilar(out, it.second) }) {
                return out
            }
        }
        return "Did you actually do '$eventName', or did you just tell yourself you would? " +
            "Slacking off again, I see."
    }

    /** Few-shot: task + two worked examples, priming the answer for [event]. */
    private fun fewShotPrompt(event: String): String {
        val examples = FEW_SHOT_EXAMPLES.joinToString("\n\n") { (e, r) ->
            "Event: \"$e\"\nResponse: $r"
        }
        return gemma(
            "Task: Ask the user if they completed their scheduled event using a highly " +
                "sarcastic tone in exactly 2 sentences. Tie the sarcasm to the specific event.\n\n" +
                examples + "\n\n" +
                "Event: \"$event\"\nResponse:"
        )
    }

    /** True when [out] is essentially a copy of a few-shot example. */
    private fun tooSimilar(out: String, example: String): Boolean {
        fun words(s: String) =
            s.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.toSet()
        val wo = words(out)
        if (wo.isEmpty()) return false
        return wo.intersect(words(example)).size.toDouble() / wo.size >= 0.7
    }

    /** Wraps a user turn in Gemma's chat template so the model role-plays. */
    private fun gemma(userTurn: String) =
        "<start_of_turn>user\n$userTurn<end_of_turn>\n<start_of_turn>model\n"

    /** Strips chat-template artifacts and the model's habitual filler preamble. */
    private fun cleanup(raw: String): String {
        val base = raw.substringBefore("<end_of_turn>")
            .replace("<start_of_turn>model", "")
            // Few-shot / completion prompts can spill a new label ("[EVENT]:",
            // "Event:", "Response:"); cut at the first so we keep only this answer.
            .substringBefore("\n[")
            .substringBefore("\nEvent:")
            .substringBefore("\nResponse:")
            .trim()
        val filler = Regex(
            "^(sure|okay|ok|alright|got it|i'?m in|understood|here('?s| is)|" +
                "let'?s|fine)[\\s,.!:—-]*",
            RegexOption.IGNORE_CASE
        )
        // Peel up to two stacked filler phrases (e.g. "Okay, I'm in. ").
        var s = base
        repeat(2) { s = filler.replace(s, "").trim() }
        // The model sometimes emits the literal characters "\n"/"\t" as text.
        s = s.replace("\\n", " ").replace("\\t", " ").replace(Regex("\\s+"), " ").trim()
        // If stripping removed everything (model replied with filler only), keep
        // the original text rather than returning an empty string.
        return s.ifBlank { base }
    }
}
