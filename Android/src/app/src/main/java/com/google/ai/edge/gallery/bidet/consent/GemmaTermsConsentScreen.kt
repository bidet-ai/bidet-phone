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

package com.google.ai.edge.gallery.bidet.consent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.ui.bidetDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First-launch Gemma Terms of Use consent. Brief §9.
 *
 * Behaviour:
 *  - Shown ONCE before model download. Persists [KEY_CONSENT] in DataStore.
 *  - Two buttons: [Decline] (closes the app) | [Accept & Download] (sets the consent flag).
 *  - Two clickable links open the user's browser to:
 *      - https://ai.google.dev/gemma/terms
 *      - https://ai.google.dev/gemma/prohibited_use_policy
 *  - Re-prompt only when the URLs change (we version the consent value below).
 */
@Composable
fun GemmaTermsConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.bidet_consent_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = stringResource(R.string.bidet_consent_body))

        val termsUrl = stringResource(R.string.bidet_consent_terms_link)
        val pupUrl = stringResource(R.string.bidet_consent_pup_link)

        Text(
            text = stringResource(R.string.bidet_consent_terms_label),
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable { openUrl(context, termsUrl) },
        )
        Text(
            text = stringResource(R.string.bidet_consent_pup_label),
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable { openUrl(context, pupUrl) },
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.bidet_consent_decline))
            }
            Button(onClick = onAccept) {
                Text(stringResource(R.string.bidet_consent_accept))
            }
        }
    }
}

/** Persist consent (called by the caller after the user taps Accept). */
suspend fun recordGemmaConsent(context: Context) {
    context.bidetDataStore.edit { prefs ->
        prefs[KEY_CONSENT] = CONSENT_VERSION
    }
}

/** True if the user has previously accepted the current Gemma Terms version. */
suspend fun hasGemmaConsent(context: Context): Boolean {
    val prefs = context.bidetDataStore.data.first()
    val accepted = prefs[KEY_CONSENT] ?: ""
    return accepted == CONSENT_VERSION
}

/** Hostable wrapper that flips state when the user accepts. */
@Composable
fun GemmaTermsConsentGate(
    context: Context = LocalContext.current,
    onConsented: @Composable () -> Unit,
    onDeclined: () -> Unit,
) {
    var ready by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        ready = hasGemmaConsent(context)
    }
    when (ready) {
        true -> onConsented()
        false -> GemmaTermsConsentScreen(
            onAccept = {
                scope.launch {
                    recordGemmaConsent(context)
                    ready = true
                }
            },
            onDecline = onDeclined,
        )
        null -> Unit // loading
    }
}

private val KEY_CONSENT = androidx.datastore.preferences.core.stringPreferencesKey("bidet_gemma_consent_version")

/** Bump this when the Gemma Terms URL or text changes. */
private const val CONSENT_VERSION: String = "v1-2026-05-07"

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
