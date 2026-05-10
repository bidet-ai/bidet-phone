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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.bidet.a11y.A11yPreferences
import com.google.ai.edge.gallery.bidet.a11y.CleanFontChoice
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
    var cleanFontChoice by remember { mutableStateOf(A11yPreferences.DEFAULT_CLEAN_FONT_CHOICE) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val prefs = context.bidetDataStore.data.first()
        promptReceptiveOverride =
            prefs[BidetTabsViewModel.promptOverrideKey(SupportAxis.RECEPTIVE)] ?: ""
        promptExpressiveOverride =
            prefs[BidetTabsViewModel.promptOverrideKey(SupportAxis.EXPRESSIVE)] ?: ""
        webhookUrl = prefs[KEY_WEBHOOK_URL] ?: ""
        webhookKey = prefs[KEY_WEBHOOK_KEY] ?: ""
        cleanFontChoice = A11yPreferences.getCleanFontChoice(context)
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

        // bidet-ai a11y (2026-05-10): "Font for cleaned text" radio picker.
        // v0.3 — replaces the v0.2 single OpenDyslexic switch. Default flips to Atkinson
        // Hyperlegible per the 2026-05-10 a11y audit. Persisted via [A11yPreferences]
        // (DataStore-backed). Flipping a radio button instantly updates the rendering on
        // BOTH Clean tabs because they observe the same flow. RAW transcript is unaffected
        // by design — verbatim text is the source of truth, not a piece of UX to skin.
        Text(stringResource(R.string.bidet_settings_a11y_section))
        Text(stringResource(R.string.bidet_settings_clean_font_label))
        Text(
            stringResource(R.string.bidet_settings_clean_font_helper),
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        )
        // The radio group. Order matches the visual hierarchy in the spec:
        //   1. System default
        //   2. Atkinson Hyperlegible (DEFAULT)
        //   3. OpenDyslexic
        //   4. Andika
        // Each row is the entire RadioButton + Column(label + per-font helper) so a tap
        // anywhere on the row selects (touch-target accessibility).
        for (choice in CleanFontChoice.values()) {
            val labelRes = when (choice) {
                CleanFontChoice.SYSTEM_DEFAULT -> R.string.bidet_settings_clean_font_system
                CleanFontChoice.ATKINSON_HYPERLEGIBLE ->
                    R.string.bidet_settings_clean_font_atkinson
                CleanFontChoice.OPEN_DYSLEXIC -> R.string.bidet_settings_clean_font_opendyslexic
                CleanFontChoice.ANDIKA -> R.string.bidet_settings_clean_font_andika
            }
            val helperRes = when (choice) {
                CleanFontChoice.SYSTEM_DEFAULT ->
                    R.string.bidet_settings_clean_font_system_helper
                CleanFontChoice.ATKINSON_HYPERLEGIBLE ->
                    R.string.bidet_settings_clean_font_atkinson_helper
                CleanFontChoice.OPEN_DYSLEXIC ->
                    R.string.bidet_settings_clean_font_opendyslexic_helper
                CleanFontChoice.ANDIKA -> R.string.bidet_settings_clean_font_andika_helper
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = cleanFontChoice == choice,
                    onClick = {
                        cleanFontChoice = choice
                        scope.launch {
                            A11yPreferences.setCleanFontChoice(context, choice)
                            status = "Font: ${choice.storageKey}"
                        }
                    },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(labelRes))
                    Text(stringResource(helperRes))
                }
            }
        }

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
