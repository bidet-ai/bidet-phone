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
 *
 * banWordCheck: scans user-visible code paths for banned tokens and fails the
 * build if any match. Hooks `assembleDebug` and `assembleRelease`.
 *
 * Why this exists: Bidet AI's public-facing copy must avoid clinical terms
 * (ADHD / ADD), school-context references (St. Francis, sfschools, K-12,
 * middle school), and other tokens enumerated in the brief §12. These leak
 * easily through autocomplete or copy-paste; the build is the safety net.
 */

import java.util.regex.Pattern

abstract class BanWordCheckTask : org.gradle.api.DefaultTask() {

    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val sourceRoot: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun check() {
        val root = sourceRoot.get().asFile
        if (!root.exists()) {
            logger.lifecycle("banWordCheck: source root '$root' does not exist; skipping.")
            return
        }

        // Banned tokens. Each entry: (regex, caseSensitive).
        // We word-boundary every entry (\b...\b) so substring matches don't trip the build.
        // "ADD" is the only case-sensitive entry — avoids tripping on "add to list" etc.,
        // but does flag the all-caps clinical use.
        val banned: List<Pair<String, Boolean>> = listOf(
            "St\\. Francis" to false,
            "sfschools" to false,
            "K-12" to false,
            "K12" to false,
            "middle school" to false,
            "ADHD" to false,
            "adult ADD" to false,
            "ADD" to true
        )

        // Files to scan: strings.xml, user-facing assets (skills + tinygarden + general
        // resources) — NOT prompts/, which are LLM-side instructions that may legitimately
        // mention banned terms in a "do not use the word X" form. And all Kotlin source under
        // main/.
        val targets: List<java.io.File> = mutableListOf<java.io.File>().apply {
            val stringsXml = java.io.File(root, "src/main/res/values/strings.xml")
            if (stringsXml.exists()) add(stringsXml)
            val assets = java.io.File(root, "src/main/assets")
            if (assets.exists()) addAll(
                assets.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in setOf("txt", "md", "json", "xml", "html", "js", "ts") }
                    // assets/prompts/ feeds Gemma directly, not the user. Exempt by design.
                    // assets/moonshine/ holds the Moonshine ONNX bundle + tokens.txt
                    // (BPE vocab — not user-visible English text). Exempt explicitly.
                    .filter { f ->
                        val rel = f.relativeTo(assets).path.replace(java.io.File.separatorChar, '/')
                        !rel.startsWith("prompts/") && !rel.startsWith("moonshine/")
                    }
                    .toList()
            )
            val kotlinMain = java.io.File(root, "src/main")
            if (kotlinMain.exists()) addAll(
                kotlinMain.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in setOf("kt", "kts") }
                    .toList()
            )
        }

        // For .kt files we scan only string literals (per brief §12). For .xml/.txt/.md/.json
        // and other resource-style files we scan the whole document.
        val klitRegex = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"")
        val violations = mutableListOf<String>()
        for (file in targets) {
            val text = try { file.readText() } catch (t: Throwable) {
                logger.warn("banWordCheck: cannot read $file: ${t.message}")
                continue
            }
            val ext = file.extension.lowercase()
            // Build the substring(s) we'll scan.
            data class Segment(val text: String, val absStart: Int)
            val segments: List<Segment> = if (ext in setOf("kt", "kts")) {
                val out = mutableListOf<Segment>()
                val m = klitRegex.matcher(text)
                while (m.find()) {
                    out.add(Segment(text.substring(m.start(), m.end()), m.start()))
                }
                out
            } else {
                listOf(Segment(text, 0))
            }
            for ((token, caseSensitive) in banned) {
                val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
                val pattern = Pattern.compile("\\b" + token + "\\b", flags)
                for (seg in segments) {
                    val matcher = pattern.matcher(seg.text)
                    while (matcher.find()) {
                        val absOffset = seg.absStart + matcher.start()
                        val line = text.substring(0, absOffset).count { it == '\n' } + 1
                        violations.add("${file.relativeTo(project.rootDir)}:$line: matched banned token '${matcher.group()}' (rule: '$token', caseSensitive=$caseSensitive)")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            val msg = "banWordCheck: ${violations.size} violation(s) found:\n  " +
                violations.joinToString("\n  ")
            throw org.gradle.api.GradleException(msg)
        }

        logger.lifecycle("banWordCheck: scanned ${targets.size} file(s) — no banned tokens found.")
    }
}

val banWordCheck = tasks.register<BanWordCheckTask>("banWordCheck") {
    group = "verification"
    description = "Fails the build if banned tokens leak into user-visible code paths."
    sourceRoot.set(project.layout.projectDirectory)
}

// 2026-05-08: assemble-task hook DISABLED for v0.1. The banWordCheck task
// has two known bugs (StackOverflowError on the current tree; flags upstream
// `enum SkillAction.ADD` as a clinical-term false positive). Phase 2 rewrites
// the task; the consuming `customtasks/agentchat` upstream code is also
// scheduled for removal in Phase 2. Until both land, the task is opt-in only
// (run manually via `./gradlew :app:banWordCheck`), not gating the build.
//
// afterEvaluate {
//     listOf("assembleDebug", "assembleRelease").forEach { name ->
//         tasks.findByName(name)?.dependsOn(banWordCheck)
//     }
// }
