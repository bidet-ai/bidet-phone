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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The "you are recording right now" header.
 *
 * Bug B (2026-05-09 fix): the only existing stop affordance was a tiny mic/stop icon in the
 * `BidetTabsScreen` TopAppBar — easy to miss, and not visible at all when the binding race
 * kept the welcome screen rendering instead of the tabs. This header ships everywhere the
 * recording-active UI renders: a pulsing red dot, a live mm:ss timer driven by
 * [System.currentTimeMillis] − [startedAtMs], and a big visible STOP button.
 *
 * @param startedAtMs the wall-clock time when recording began (from
 *   `RecordingService.statusFlow.value.startedAtMs`). The timer ticks against the live clock
 *   so it stays correct even if the Composable recomposes mid-second.
 * @param onStop click handler — invokes [RecordingService.stopIntent] via the Composable.
 * @param prominent if true, renders larger (used on the "Starting recording…" screen where
 *   it's the only thing on screen).
 */
@Composable
fun RecordingHeader(
    startedAtMs: Long,
    onStop: () -> Unit,
    prominent: Boolean = false,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250) // 4 Hz — smooth enough for mm:ss
        }
    }

    val elapsedSec = (((nowMs - startedAtMs).coerceAtLeast(0L)) / 1000L).toInt()
    val timer = "%d:%02d".format(elapsedSec / 60, elapsedSec % 60)

    val infinite = rememberInfiniteTransition(label = "recording-pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = if (prominent) 16.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pulsing red dot
        Spacer(
            modifier = Modifier
                .size(if (prominent) 16.dp else 12.dp)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(Color(0xFFE53935)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Recording",
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (prominent) 18.sp else 16.sp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = timer,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = if (prominent) 18.sp else 16.sp,
        )
        Spacer(modifier = Modifier.width(0.dp).weight(1f))
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("STOP", fontWeight = FontWeight.Bold)
        }
    }
}

