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

package com.google.ai.edge.gallery.bidet.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.ingest.Tp3Sender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-only settings screen. Brief §11.
 *
 * Three sections:
 *  1. Prompt-template editor — writes to DataStore (NOT bundled assets) so that prompt edits
 *     in debug builds don't accidentally ship in release.
 *  2. TP3 webhook URL + X-AG-KEY — DataStore-backed.
 *  3. "Send to TP3" button — POSTs the current four-tab state to the configured webhook.
 *
 * The screen is gated to debug builds via the navigation graph (BuildConfig.DEBUG check at the
 * call site); this composable is permission-blind and trusts the caller to enforce the gate.
 */
@Composable
fun BidetSettingsScreen(
    sessionId: String,
    rawText: String,
    cleanText: String,
    analysisText: String,
    foraiText: String,
    durationSeconds: Long,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v0.2 (2026-05-09): per-axis overrides replace the v0.1 per-tab overrides.
    var promptReceptiveOverride by remember { mutableStateOf("") }
    var promptExpressiveOverride by remember { mutableStateOf("") }
    var webhookUrl by remember { mutableStateOf("") }
    var webhookKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val prefs = context.bidetDataStore.data.first()
        promptReceptiveOverride =
            prefs[BidetTabsViewModel.promptOverrideKey(SupportAxis.RECEPTIVE)] ?: ""
        promptExpressiveOverride =
            prefs[BidetTabsViewModel.promptOverrideKey(SupportAxis.EXPRESSIVE)] ?: ""
        webhookUrl = prefs[KEY_WEBHOOK_URL] ?: ""
        webhookKey = prefs[KEY_WEBHOOK_KEY] ?: ""
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.bidet_settings_title))
        Divider()

        Text(stringResource(R.string.bidet_settings_prompt_section))
        OutlinedTextField(
            value = promptReceptiveOverride,
            onValueChange = { promptReceptiveOverride = it },
            label = { Text("Receptive (Clean for me) prompt override (blank = use tab pref)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 12,
        )
        OutlinedTextField(
            value = promptExpressiveOverride,
            onValueChange = { promptExpressiveOverride = it },
            label = { Text("Expressive (Clean for others) prompt override (blank = use tab pref)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 12,
        )
        Button(
            onClick = {
                scope.launch {
                    context.bidetDataStore.edit { prefs ->
                        prefs[BidetTabsViewModel.promptOverrideKey(SupportAxis.RECEPTIVE)] =
                            promptReceptiveOverride
                        prefs[BidetTabsViewModel.promptOverrideKey(SupportAxis.EXPRESSIVE)] =
                            promptExpressiveOverride
                    }
                    status = "Prompt overrides saved."
                }
            }
        ) { Text("Save prompts") }

        Spacer(Modifier.height(12.dp))
        Divider()

        Text(stringResource(R.string.bidet_settings_webhook_section))
        OutlinedTextField(
            value = webhookUrl,
            onValueChange = { webhookUrl = it },
            label = { Text(stringResource(R.string.bidet_settings_webhook_url_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = webhookKey,
            onValueChange = { webhookKey = it },
            label = { Text(stringResource(R.string.bidet_settings_webhook_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                scope.launch {
                    context.bidetDataStore.edit { prefs ->
                        prefs[KEY_WEBHOOK_URL] = webhookUrl
                        prefs[KEY_WEBHOOK_KEY] = webhookKey
                    }
                    status = "Webhook saved."
                }
            }
        ) { Text("Save webhook") }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    if (webhookUrl.isBlank()) {
                        status = "Webhook URL is empty."
                        return@launch
                    }
                    val rc = Tp3Sender(context).send(
                        url = webhookUrl,
                        agKey = webhookKey,
                        sessionId = sessionId,
                        raw = rawText,
                        clean = cleanText,
                        analysis = analysisText,
                        forai = foraiText,
                        durationSeconds = durationSeconds,
                    )
                    status = "Send to TP3: $rc"
                }
            }
        ) { Text(stringResource(R.string.bidet_settings_send_to_tp3)) }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status)
        }
    }
}

private val KEY_WEBHOOK_URL = stringPreferencesKey("bidet_tp3_webhook_url")
private val KEY_WEBHOOK_KEY = stringPreferencesKey("bidet_tp3_webhook_key")

/** Read currently-saved webhook config (used by callers outside the screen). */
suspend fun loadTp3WebhookConfig(context: Context): Pair<String, String> {
    val prefs = context.bidetDataStore.data.first()
    return (prefs[KEY_WEBHOOK_URL] ?: "") to (prefs[KEY_WEBHOOK_KEY] ?: "")
}
