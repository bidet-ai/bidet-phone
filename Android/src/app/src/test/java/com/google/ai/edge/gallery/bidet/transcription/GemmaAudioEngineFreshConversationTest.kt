/*
 * Copyright 2026 bidet-ai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.bidet.transcription

import com.google.ai.edge.litertlm.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-05-09 regression test for the "chunks 1+ silently empty" bug.
 *
 * Bug being pinned:
 *  Prior to this fix, [GemmaAudioEngine] cached one [com.google.ai.edge.litertlm.Conversation]
 *  in `conversationRef` at [GemmaAudioEngine.initialize] time and reused it across every
 *  [GemmaAudioEngine.transcribe] call via `conv.sendMessageAsync(...)`. LiteRT-LM
 *  Conversations are stateful: by chunk 1 the conversation already contained
 *  `[system, audio_0, text_0, audio_1, "Transcribe..."]` and the model treated the second
 *  send as a continuation, emitting stop tokens immediately. Live symptom: chunk 0
 *  transcribed correctly, chunks 1+ silently produced empty text.
 *
 * Fix invariant this test pins:
 *  Each `transcribe()` call must use a FRESH Conversation, used once, closed after. The
 *  shared Engine remains warm across calls (cheap to re-use, expensive to rebuild — 3.6 GB
 *  model load) so we cache the [com.google.ai.edge.litertlm.Engine] reference, not the
 *  Conversation.
 *
 * Why this is a structural test rather than a behavioral test:
 *  [com.google.ai.edge.litertlm.Engine] and [com.google.ai.edge.litertlm.Conversation] are
 *  final library classes that require 3.6 GB of model weights to instantiate; we cannot
 *  build a stand-in in a JVM unit test (no mockito/mockk on this module either — see
 *  app/build.gradle.kts testImplementation list). The closest pure-state test we can write
 *  without booting LiteRT-LM is to pin that the engine no longer holds a long-lived
 *  Conversation field. If a future refactor reintroduces one ("just cache it once, it's
 *  cheaper") the field will reappear and this test will fail before the live bug does.
 *
 * No timing-coroutine assertions: we deliberately avoid the brittle PR-26-era pattern of
 * driving suspendCancellableCoroutine through fakes. The structural assertion below catches
 * the regression class without touching the suspending state machine.
 */
class GemmaAudioEngineFreshConversationTest {

    @Test
    fun gemmaAudioEngine_doesNotHold_aLongLived_ConversationField() {
        // Walk the declared fields of GemmaAudioEngine (not its supertypes) and assert that
        // none of them are typed as Conversation. The prior bug was a single
        // `AtomicReference<Conversation?>` cached at initialize() time; if that field comes
        // back, this assertion fires.
        val cls = GemmaAudioEngine::class.java
        val conversationFields = cls.declaredFields.filter { f ->
            // Direct Conversation? field (the original bug shape pre-AtomicReference) OR a
            // generic container (AtomicReference<Conversation>, etc.) parameterised on
            // Conversation. We check both the raw type and the generic type signature.
            val typeIsConversation = Conversation::class.java.isAssignableFrom(f.type)
            val genericMentionsConversation =
                f.genericType.typeName.contains(Conversation::class.java.name)
            typeIsConversation || genericMentionsConversation
        }
        assertTrue(
            "GemmaAudioEngine must not hold any field typed as (or containing) " +
                "com.google.ai.edge.litertlm.Conversation. The fresh-conversation-per-chunk " +
                "fix moved Conversation lifecycle to be local to transcribe(); a long-lived " +
                "field is the exact bug shape we are guarding against (chunk 0 transcribes, " +
                "chunks 1+ silently empty due to LiteRT-LM Conversation accumulating history " +
                "across sendMessageAsync calls). Found offending field(s): " +
                conversationFields.joinToString { "${it.name}: ${it.genericType.typeName}" },
            conversationFields.isEmpty(),
        )
    }

    @Test
    fun gemmaAudioEngine_holds_anEngineReference_forWarmReuse() {
        // Counter-assertion: the engine reference IS expected to be held — closing the
        // shared engine on every chunk would force a 3.6 GB model reload per chunk and
        // defeat the purpose of [com.google.ai.edge.gallery.bidet.llm.BidetSharedLiteRtEngineProvider].
        // This assertion documents the kept-state alongside the dropped-state above so a
        // future refactor that drops the Engine handle by accident is also caught.
        //
        // We match on the exact FQN "com.google.ai.edge.litertlm.Engine<" or
        // "com.google.ai.edge.litertlm.Engine?" or "com.google.ai.edge.litertlm.Engine>"
        // (i.e. the type appears as a generic parameter or trailing token) so the
        // BidetSharedLiteRtEngineProvider field — which contains the substring "Engine" —
        // does NOT match.
        val cls = GemmaAudioEngine::class.java
        val litertlmEnginePackage = "com.google.ai.edge.litertlm.Engine"
        val engineFields = cls.declaredFields.filter { f ->
            val name = f.genericType.typeName
            // Match the exact LiteRT-LM Engine class as a generic argument (preceded by
            // '<' or ',' or whitespace) — never as a substring of another class name.
            Regex("""[<,\s?]${Regex.escape(litertlmEnginePackage)}[>,?\s]""")
                .containsMatchIn(name)
        }
        assertEquals(
            "GemmaAudioEngine must hold exactly one field referencing the LiteRT-LM Engine " +
                "(an AtomicReference<Engine?> is the production shape) so transcribe() can " +
                "create a fresh Conversation per chunk without re-acquiring through the " +
                "shared provider mutex. Found: " +
                engineFields.joinToString { "${it.name}: ${it.genericType.typeName}" },
            1,
            engineFields.size,
        )
    }
}
