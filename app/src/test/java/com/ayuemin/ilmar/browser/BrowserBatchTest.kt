package com.ayuemin.ilmar.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.agent.*
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserBatchTest {
    private lateinit var app: Context
    private lateinit var page: Page
    private lateinit var modes: ChatModePreferences
    private val chat = 721L
    private class Page : BrowserPage {
        var url = "https://www.iana.org/"; var revision = 1; var clicks = 0; var reads = 0; var opens = 0; var shots = 0
        var afterOpen: (() -> Unit)? = null
        var matches = 1; var changeAfter = Int.MAX_VALUE; var crash = false; var human = false; var scrollError = false
        fun snapshot() = JSONObject().put("page_id", "page-$revision").put("url", url).put("title", "IANA")
            .put("text", "Example Domains: these domains are available for illustrative examples in documents.")
            .put("controls", JSONArray().put(JSONObject().put("ref", 1).put("label", "Reserved Domains")))
            .put("links", JSONArray().put(JSONObject().put("ref", 1).put("label", "Reserved Domains").put("url", "https://www.iana.org/domains/reserved")))
            .put("untrusted", true)
        override suspend fun open(url: String): JSONObject { opens++; this.url = url; revision++; afterOpen?.invoke(); return snapshot() }
        override suspend fun read(): JSONObject { reads++; if (reads == changeAfter) revision++; return snapshot() }
        override suspend fun find(query: String, exact: Boolean) = snapshot().apply {
            remove("text"); remove("controls"); remove("links")
            put("matches", JSONArray().apply { repeat(matches) { put(JSONObject().put("ref", it + 1).put("label", query)) } })
        }
        override suspend fun scroll(down: Boolean) = if (scrollError) JSONObject().put("error", "SCROLL_FAILED") else snapshot()
        override suspend fun act(command: String, args: JSONObject): JSONObject {
            require(args.getString("page_id") == "page-$revision" && args.getInt("ref") == 1)
            clicks++
            if (crash) throw CancellationException("Simulated death after click")
            if (human) return snapshot().put("requires_user", true)
            revision++; return snapshot()
        }
        override suspend fun screenshot(): ByteArray { shots++; return byteArrayOf(1) }
        override fun stop() = Unit
    }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); RuntimeLimits.install(app); RuntimeLimits.reset()
        modes = ChatModePreferences(app).apply {
            setEnabled(chat, "Агент", false); setEnabled(chat, "Поиск", true); setChoice(chat, "search_source", "browser")
        }
        page = Page(); BrowserRuntime.attach(page)
        Dispatchers.setMain(StandardTestDispatcher())
    }
    @After fun cleanup() { BrowserRuntime.detach(page); RuntimeLimits.reset(); Dispatchers.resetMain() }
    private fun tools(control: BrowserRunControl? = null) = BrowserTools.forChat(chat, modes, false, true, control = control)
    private suspend fun plan(json: String, tools: List<BoundTool> = tools(), runner: AgentLoopRunner = AgentLoopRunner()): Pair<Int, JSONObject> {
        var rounds = 0; var response = JSONObject()
        runner.run(tools) { history, _ ->
            if (rounds++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("batch", "browser_batch", json)))
            else { response = JSONObject(history.last().getString("content")); GenerationResult("Done") }
        }
        return rounds to response
    }
    @Test fun ianaKnownSequenceTakesTwoModelRequestsInsteadOfSixAndSendsOneFinalDom() = runTest {
        val sequential = listOf(
            "browser_search" to "{\"query\":\"IANA example domains\"}",
            "browser_open" to "{\"url\":\"https://www.iana.org/help/example-domains\"}",
            "browser_read" to "{}", "browser_click" to "{\"page_id\":\"page-3\",\"ref\":1}",
            "browser_scroll" to "{\"direction\":\"down\"}")
        val sequentialInputs = JSONArray(); var rounds = 0
        AgentLoopRunner().run(tools()) { history, schemas ->
            sequentialInputs.put(JSONObject().put("history", JSONArray(history)).put("schemas", JSONArray(schemas.map { JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters) })))
            val index = rounds++
            if (index < sequential.size) GenerationResult("", toolCalls = listOf(ModelToolCall("step-$index", sequential[index].first, sequential[index].second))) else GenerationResult("Done")
        }
        assertEquals(6, rounds); assertEquals(1, page.clicks)
        BrowserRuntime.detach(page); page = Page(); BrowserRuntime.attach(page)
        val args = """{"steps":[{"action":"search","query":"IANA example domains"},{"action":"open","url":"https://www.iana.org/help/example-domains"},{"action":"read"},{"action":"click","target":"Reserved Domains"},{"action":"scroll","direction":"down"}]}"""
        val batchedInputs = JSONArray(); var batchRounds = 0; var result = JSONObject()
        val visible = mutableListOf<Boolean>()
        val monitored = tools(BrowserRunControl(chat, false, event = { code, data -> if (code == "BROWSER_RESULT") visible += data.getBoolean("model_visible") }))
        AgentLoopRunner().run(monitored) { history, schemas ->
            batchedInputs.put(JSONObject().put("history", JSONArray(history)).put("schemas", JSONArray(schemas.map { JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters) })))
            if (batchRounds++ == 0) GenerationResult("", toolCalls = listOf(ModelToolCall("batch", "browser_batch", args)))
            else { result = JSONObject(history.last().getString("content")); GenerationResult("Done") }
        }
        assertEquals(2, batchRounds); assertEquals(5, result.getInt("completed_steps")); assertEquals(7, result.getInt("local_tool_calls"))
        assertEquals(1, visible.count { it }); assertEquals(7, visible.count { !it })
        assertEquals(0, result.getInt("model_requests_inside")); assertEquals(1, page.clicks); assertEquals(0, page.shots)
        assertTrue(result.getJSONObject("observation").getString("text").contains("Example Domains"))
        assertEquals(1, batchedInputs.getJSONObject(1).getJSONArray("history").let { h -> (0 until h.length()).count { h.getJSONObject(it).optString("role") == "tool" } })
        java.io.File("build/reports/browser-rounds-comparison.json").apply { parentFile!!.mkdirs(); writeText(JSONObject()
            .put("fixture", "offline IANA-shaped fixed page; no paid model or live website")
            .put("sequential_requests", rounds).put("batch_requests", batchRounds)
            .put("sequential_inputs", sequentialInputs).put("batch_inputs", batchedInputs).toString(2)) }
    }
    @Test fun unchangedWaitPollsLocallyAndChangedPageStopsEarly() = runTest {
        BrowserRuntime.command(chat, "open", "https://www.iana.org/")
        val unchanged = JSONObject(BrowserRuntime.command(chat, "wait", details = JSONObject().put("timeout_ms", 600)))
        assertFalse(unchanged.getBoolean("wait_changed")); assertEquals(4, unchanged.getInt("local_polls"))
        assertTrue(unchanged.getBoolean("text_unchanged")); assertEquals(600L, unchanged.getLong("wait_elapsed_ms"))
        page.changeAfter = page.reads + 2
        val changed = JSONObject(BrowserRuntime.command(chat, "wait", details = JSONObject().put("timeout_ms", 1000)))
        assertTrue(changed.getBoolean("wait_changed")); assertEquals(2, changed.getInt("local_polls"))
        assertTrue(changed.has("text")); assertEquals(0, page.shots)
    }
    @Test fun ambiguousTargetAndInvalidTailNeverExecuteWrites() = runTest {
        page.matches = 2
        val (_, result) = plan("""{"steps":[{"action":"open","url":"https://www.iana.org/"},{"action":"click","target":"Reserved Domains"},{"action":"open","url":"https://example.com/"}]}""")
        assertEquals("TARGET_NOT_UNIQUE", result.getString("error")); assertEquals(0, page.clicks); assertEquals(1, page.opens)
        for (action in listOf("screenshot", "batch", "javascript")) {
            val (_, denied) = plan("""{"steps":[{"action":"open","url":"https://example.com/"},{"action":"$action"}]}""")
            assertEquals("INVALID_ARGUMENTS", denied.getString("error")); assertEquals(1, page.opens)
        }
    }
    @Test fun autoDenialAndMidChainBudgetDenialStopBeforeNextEffectOrModel() = runTest {
        modes.setChoice(chat, "search_source", "auto")
        var requests = 0
        val denied = runCatching { plan("""{"steps":[{"action":"open","url":"https://www.iana.org/"}]}""", tools(BrowserRunControl(chat, true, ask = { _, _, _ -> requests++; false }))) }.exceptionOrNull() as AgentFailure
        assertEquals("BROWSER_CANCELLED", denied.code); assertEquals(1, requests); assertEquals(0, page.opens)
        modes.setChoice(chat, "search_source", "browser")
        var generations = 0
        val limited = tools(BrowserRunControl(chat, false, ask = { _, _, _ -> false }, actionLimit = 1))
        val failure = runCatching { AgentLoopRunner().run(limited) { _, _ -> generations++; GenerationResult("", toolCalls = listOf(ModelToolCall("b", "browser_batch", """{"steps":[{"action":"open","url":"https://www.iana.org/"},{"action":"click","target":"Reserved Domains"}]}"""))) } }.exceptionOrNull() as AgentFailure
        assertEquals("BROWSER_CANCELLED", failure.code); assertEquals(1, generations); assertEquals(1, page.opens); assertEquals(0, page.clicks)
    }
    @Test fun modeRevocationMidChainStopsBeforeNextStepAndBatchNeedsJournal() = runTest {
        val noJournal = runCatching { BrowserBatch.execute(JSONObject("""{"steps":[{"action":"open","url":"https://www.iana.org/"}]}"""), BrowserPolicy()) }.exceptionOrNull() as AgentFailure
        assertEquals("LOCAL_EXECUTOR_REQUIRED", noJournal.code); assertEquals(0, page.opens)
        page.afterOpen = { modes.setChoice(chat, "search_source", "provider") }
        val revoked = runCatching { plan("""{"steps":[{"action":"open","url":"https://www.iana.org/"},{"action":"click","target":"Reserved Domains"}]}""") }.exceptionOrNull() as AgentFailure
        assertEquals("PERMISSION_REVOKED", revoked.code); assertEquals(1, page.opens); assertEquals(0, page.clicks)
    }
    @Test fun handoffStopsChainWithoutFinalReadOrSecondModel() = runTest {
        page.human = true; var generations = 0
        val failure = runCatching { AgentLoopRunner().run(tools()) { _, _ -> generations++; GenerationResult("", toolCalls = listOf(ModelToolCall("b", "browser_batch", """{"steps":[{"action":"open","url":"https://www.iana.org/"},{"action":"click","target":"Reserved Domains"},{"action":"read"}]}"""))) } }.exceptionOrNull()
        assertTrue(failure is com.ayuemin.ilmar.tools.ClarificationRequired); assertEquals(1, generations)
        assertEquals(1, page.clicks); assertEquals(0, page.reads); assertEquals(0, page.shots)
    }
    @Test fun successfulWriteSurvivesRestartAndUnknownWriteBlocksFreshRefsAndDifferentPlan() = runTest {
        app.deleteDatabase("ilmar_chats.db")
        var db = ChatStore(app); db.create(chat, "Batch"); db.append(chat, ChatMessage("Inspect IANA domains", true), null)
        fun runner(session: TaskSession) = AgentLoopRunner(
            onCheckpoint = { trace, messages -> DurableTaskStore(db).checkpoint(session, trace, messages) },
            blockUnresolved = { id, name, args -> db.unresolvedExecution(chat, id, name, args) },
            replayConfirmed = { name, args -> DurableTaskStore(db).confirmed(session, name, args) })
        fun begin() = DurableTaskStore(db).begin(chat, db.load().single().messages)
        val args = """{"steps":[{"action":"open","url":"https://www.iana.org/"},{"action":"click","target":"Reserved Domains"},{"action":"scroll","direction":"down"}]}"""
        try {
            page.scrollError = true
            val first = begin(); val (_, failed) = plan(args, runner = runner(first)); DurableTaskStore(db).finish(first, "FAILED")
            assertEquals("SCROLL_FAILED", failed.getString("error")); assertEquals(1, page.clicks)
            db.close(); db = ChatStore(app); DurableTaskStore(db).interruptOpen()
            page.scrollError = false
            val second = begin(); val (_, replay) = plan(args, runner = runner(second)); DurableTaskStore(db).finish(second, "COMPLETED")
            assertEquals("CONFIRMED_STEP_ALREADY_EXECUTED", replay.getString("halt_reason")); assertEquals(1, page.clicks)
            page.crash = true
            val third = begin()
            val different = """{"steps":[{"action":"open","url":"https://www.iana.org/"},{"action":"click","target":"Reserved Domains"}]}"""
            assertTrue(runCatching { plan(different, runner = runner(third)) }.exceptionOrNull() is CancellationException)
            assertEquals(2, page.clicks)
            db.close(); db = ChatStore(app); DurableTaskStore(db).interruptOpen(); page.crash = false
            val fourth = begin()
            val freshPlan = """{"steps":[{"action":"read"},{"action":"click","target":"Reserved Domains"}]}"""
            val error = runCatching { plan(freshPlan, runner = runner(fourth)) }.exceptionOrNull() as AgentFailure
            assertEquals("UNRESOLVED_TOOL", error.code); assertEquals(2, page.clicks)
            val direct = tools().single { it.schema.name == "browser_click" }
            val blocked = runCatching { runner(fourth).run(listOf(direct)) { _, _ -> GenerationResult("", toolCalls = listOf(ModelToolCall("d", "browser_click", """{"page_id":"page-${page.revision}","ref":1}"""))) } }.exceptionOrNull() as AgentFailure
            assertEquals("UNRESOLVED_TOOL", blocked.code); assertEquals(2, page.clicks)
            assertTrue(JSONObject(BrowserRuntime.command(chat, "read")).getString("text").isNotBlank())
        } finally { db.close() }
    }
}
