package com.example.accountability

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device experiment (not a pass/fail unit test) that sweeps input profiles and
 * prompt strategies through the bundled Gemma model and logs a comparison table.
 *
 * Run:
 *   adb shell am instrument -w -e class \
 *     com.example.accountability.LlmExperimentTest \
 *     com.example.accountability.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results are logged under tag LLM_EXPERIMENT and written to the app's filesDir as
 * experiment_results.md (pull with `run-as`).
 */
@RunWith(AndroidJUnit4::class)
class LlmExperimentTest {

    private val tag = "LLM_EXPERIMENT"

    private data class Scenario(val label: String, val profile: String, val event: String)

    private val scenarios = listOf(
        Scenario(
            "interview",
            "I am prepping for coding interviews and fear staying stagnant in my career.",
            "DSA Practice"
        ),
        Scenario(
            "fitness",
            "I want to lose 10kg and I'm terrified of being weak and unhealthy.",
            "Morning Run"
        ),
        Scenario(
            "startup",
            "I'm building a startup; my biggest fear is running out of money and failing publicly.",
            "Investor Deck"
        ),
        Scenario(
            "student",
            "I'm a med student scared of failing my board exams and disappointing my family.",
            "Anatomy Revision"
        ),
    )

    @Test
    fun sweepInputsAndLogTable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LlmEngine(context)
        engine.initialize()

        // --- Table 1: stage-1 intent extraction (profile -> weakness phrase) ---
        val intents = StringBuilder()
        intents.append("### Stage 1 — extracted intent per profile\n\n")
        intents.append("| Scenario | Extracted weakness |\n|---|---|\n")
        val intentByLabel = HashMap<String, String>()
        for (s in scenarios) {
            val intent = engine.extractIntent(s.profile)
            intentByLabel[s.label] = intent
            intents.append("| ${s.label} | ${md(intent)} |\n")
            Log.i(tag, "[intent] ${s.label} -> $intent")
        }

        // --- Table 2: output per (scenario x strategy x temperature) ---
        val rows = StringBuilder()
        rows.append("\n### Stage 2 — output by strategy & sampling\n\n")
        rows.append("| # | Scenario | Strategy | Temp | topK | ms | Output |\n")
        rows.append("|---|---|---|---|---|---|---|\n")

        var i = 0
        for (s in scenarios) {
            val intent = intentByLabel.getValue(s.label)

            // Strategy A — single-shot baseline (whole profile, original prompt).
            i++; timedRow(rows, i, s.label, "baseline", 0.9f, 40) {
                engine.generate(baselinePrompt(s.profile, s.event), temperature = 0.9f, topK = 40, seed = 1)
            }

            // Strategy B — two-stage (extracted intent), default sampling.
            i++; timedRow(rows, i, s.label, "two-stage", 0.9f, 40) {
                engine.generateJab(intent, s.event)
            }

            // Strategy C — two-stage, low temperature (more deterministic/sharper).
            i++; timedRow(rows, i, s.label, "two-stage", 0.4f, 20) {
                engine.generate(jabPrompt(intent, s.event), temperature = 0.4f, topK = 20, seed = 3)
            }

            // Strategy D — completion-style, event only (the format the user found
            // works well on small models: labels + priming, no chat template).
            i++; timedRow(rows, i, s.label, "completion", 0.9f, 40) {
                engine.generate(completionPrompt(s.event, null), temperature = 0.9f, topK = 40, seed = 5)
            }

            // Strategy E — completion-style + profile line (keeps personalization).
            i++; timedRow(rows, i, s.label, "completion+prof", 0.9f, 40) {
                engine.generate(completionPrompt(s.event, s.profile), temperature = 0.9f, topK = 40, seed = 5)
            }

            // Strategy F — user's simple instruction, but wrapped in Gemma's chat
            // template (the missing piece for an -it model), event only.
            i++; timedRow(rows, i, s.label, "templated", 0.9f, 40) {
                engine.generate(templatedPrompt(s.event, null), temperature = 0.9f, topK = 40, seed = 5)
            }

            // Strategy G — templated simple instruction + profile.
            i++; timedRow(rows, i, s.label, "templated+prof", 0.9f, 40) {
                engine.generate(templatedPrompt(s.event, s.profile), temperature = 0.9f, topK = 40, seed = 5)
            }

            // Strategy H — few-shot: task + two worked examples, prime the third.
            i++; timedRow(rows, i, s.label, "few-shot", 0.9f, 40) {
                engine.generate(fewShotPrompt(s.event), temperature = 0.9f, topK = 40, seed = 5)
            }
        }

        // One-off: "Linkedin scan" — the user's exact few-shot example, plus 3
        // reseeds to see how varied/stable the few-shot output is.
        rows.append("\n**Linkedin scan — few-shot (seed sweep):**\n")
        for (seed in listOf(5, 11, 23)) {
            val out = engine.generate(fewShotPrompt("Linkedin scan"), temperature = 0.9f, topK = 40, seed = seed)
            rows.append("- seed $seed: ${md(out)}\n")
            Log.i(tag, "[linkedin/few-shot seed=$seed] $out")
        }

        val report = intents.toString() + rows.toString()
        val outFile = File(context.filesDir, "experiment_results.md")
        outFile.writeText(report)
        Log.i(tag, "RESULTS WRITTEN TO ${outFile.absolutePath}")
        // Log the whole table too (chunked so logcat doesn't truncate).
        report.chunked(3500).forEach { Log.i(tag, it) }

        engine.close()
    }

    private inline fun timedRow(
        sb: StringBuilder,
        i: Int,
        scenario: String,
        strategy: String,
        temp: Float,
        topK: Int,
        block: () -> String,
    ) {
        val t0 = System.currentTimeMillis()
        val out = block()
        val dt = System.currentTimeMillis() - t0
        sb.append("| $i | $scenario | $strategy | $temp | $topK | $dt | ${md(out)} |\n")
        Log.i(tag, "[$i] $scenario/$strategy temp=$temp topK=$topK ${dt}ms :: ${out.replace("\n", " ")}")
    }

    private fun md(s: String) = s.replace("\n", " ").replace("|", "/").trim()

    private fun gemma(userTurn: String) =
        "<start_of_turn>user\n$userTurn<end_of_turn>\n<start_of_turn>model\n"

    private fun baselinePrompt(profile: String, event: String) = gemma(
        "You are a sarcastic, harsh accountability partner.\n" +
            "The user's profile is: $profile.\n" +
            "The user scheduled $event on their calendar.\n" +
            "Write exactly 2 short, biting sentences asking if they actually did the task or slacked off."
    )

    /** The user's completion-style format (optionally with a profile line). */
    private fun completionPrompt(event: String, profile: String?): String {
        val profileLine = if (profile != null) "[PROFILE]: $profile\n" else ""
        return "You are a sarcastic accountability bot. Read the [EVENT] and write exactly " +
            "2 sentences mocking the user for potentially skipping it. Do not repeat previous responses.\n\n" +
            profileLine +
            "[EVENT]: $event\n[RESPONSE]:"
    }

    /** The user's simple instruction, correctly wrapped in Gemma's chat template. */
    private fun templatedPrompt(event: String, profile: String?): String {
        val profileLine = if (profile != null) "The user's profile: $profile\n" else ""
        return gemma(
            "You are a sarcastic accountability bot. Read the event and write exactly 2 " +
                "sentences mocking the user for potentially skipping it. Do not repeat yourself.\n" +
                profileLine +
                "Event: $event"
        )
    }

    /** Few-shot: the user's task + two worked examples, priming the third answer. */
    private fun fewShotPrompt(event: String) = gemma(
        "Task: Ask the user if they completed their scheduled event using a highly " +
            "sarcastic tone in exactly 2 sentences. Tie the sarcasm to the specific event.\n\n" +
            "Event: \"office work\"\n" +
            "Response: Did you actually do your office work, or did you just wiggle your mouse " +
            "all day to keep your status green? Your career isn't going to build itself while you slack off.\n\n" +
            "Event: \"DSA\"\n" +
            "Response: Did you actually solve any problems, or did you just stare at a LeetCode " +
            "easy until you gave up? Those coding interviews won't pass themselves.\n\n" +
            "Event: \"$event\"\n" +
            "Response:"
    )

    private fun jabPrompt(intent: String, event: String) = gemma(
        "You are a brutally sarcastic, harsh accountability partner. Do NOT acknowledge " +
            "these instructions, do NOT greet — begin immediately with the insult.\n" +
            "Their weakness: $intent.\n" +
            "They put \"$event\" on their calendar and probably skipped it.\n" +
            "Write exactly two short, biting sentences that mock them about \"$event\" using " +
            "their weakness, ending with a question asking whether they actually did it."
    )
}
