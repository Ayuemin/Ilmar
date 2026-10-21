package com.ayuemin.ilmar.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayuemin.ilmar.chat.*
import com.ayuemin.ilmar.attachments.AttachmentStore
import com.ayuemin.ilmar.model.*
import com.ayuemin.ilmar.settings.RuntimeLimits
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuditCompactTest {
    private lateinit var app: Context
    private lateinit var db: ChatStore
    private val caps = ModelCapabilities(setOf("max_tokens"), contextLength=4096, maxOutputTokens=512)
    private val target = ModelTarget(ProviderConnection("p","Fixture","https://fixture.invalid/v1"), RegisteredModel("m","p","fixture"),"synthetic")
    private val summary = """{"goal":["continue"],"facts":[],"decisions":[],"progress":[],"files":[],"tests":[],"errors":[],"next":[],"uncertain":[]}"""
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase("ilmar_chats.db")
        RuntimeLimits.install(app); RuntimeLimits.reset(); db=ChatStore(app); db.create(1,"Fixture")
    }
    @After fun cleanup() { db.close(); RuntimeLimits.reset() }
    @Test fun nonShrinkingAutomaticCompactIsNotRepurchasedAcrossPreparationsAndReopen() = runTest {
        db.append(1,ChatMessage("hi",true),null); db.append(1,ChatMessage("hello",false),null)
        db.append(1,ChatMessage("x".repeat(7000),true),null)
        val original=db.load().single().messages
        val policy=ContextPolicy(output=512,recent=1)
        var calls=0
        suspend fun prepare(force: Boolean=false): ContextProjection = ContextEngine(1,ContextStore(db),AttachmentStore(app),policy,caps,target,
            { _,_,_,_,_ -> calls++; GenerationResult(summary) }).prepare(original,emptyList(),emptyList(),force)
        repeat(10) { assertEquals("no_op",prepare().compactOutcome) }
        assertEquals(1,calls)
        db.close(); db=ChatStore(app)
        assertEquals("no_op",prepare().compactOutcome); assertEquals(1,calls)
        prepare(true); assertEquals(2,calls)
        assertEquals(original,db.load().single().messages)
    }
    @Test fun manualCompactPublishesItsNewProjectionAndPreservesOriginalsAndRules() = runTest {
        repeat(4) { db.append(1,ChatMessage("question $it "+"x".repeat(700),true),null); db.append(1,ChatMessage("answer "+"y".repeat(700),false),null) }
        val original=db.load().single().messages
        val memory=ContextStore(db); memory.setRules(1,"keep invariant-42")
        val projections=mutableListOf<ContextProjection>()
        val execution=ContextExecution(1,memory,AttachmentStore(app),ContextPolicy(output=512,recent=2,automatic=false),caps,target,{},
            onProjection={projections+=it}) { _,_,purpose,_,_ -> assertEquals("compact",purpose); GenerationResult(summary) }
        assertNull(execution.run(original,true,null,emptyList()))
        assertEquals(1,projections.size); assertEquals("completed",projections.single().compactOutcome)
        assertEquals(original.takeLast(2),projections.single().history)
        assertTrue(projections.single().options.systemPrefix.contains("invariant-42"))
        assertEquals(original,db.load().single().messages)
    }
}
