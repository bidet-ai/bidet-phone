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

        // Files to scan: strings.xml, the entire assets dir, and Kotlin source under main/.
        val targets: List<java.io.File> = mutableListOf<java.io.File>().apply {
            val stringsXml = java.io.File(root, "src/main/res/values/strings.xml")
            if (stringsXml.exists()) add(stringsXml)
            val assets = java.io.File(root, "src/main/assets")
            if (assets.exists()) addAll(
                assets.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in setOf("txt", "md", "json", "xml", "html", "js", "ts") }
                    .toList()
            )
            val kotlinMain = java.io.File(root, "src/main")
            if (kotlinMain.exists()) addAll(
                kotlinMain.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in setOf("kt", "kts") }
                    .toList()
            )
        }

        val violations = mutableListOf<String>()
        for (file in targets) {
            val text = try { file.readText() } catch (t: Throwable) {
                logger.warn("banWordCheck: cannot read $file: ${t.message}")
                continue
            }
            for ((token, caseSensitive) in banned) {
                val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
                // Word boundary on both sides — \b only treats letters/digits/_ as word chars,
                // which is fine for our list (multi-word entries like "middle school" still
                // get bounded on the outer letters).
                val pattern = Pattern.compile("\\b" + token + "\\b", flags)
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    // Compute line number of the match for friendly reporting.
                    val before = text.substring(0, matcher.start())
                    val line = before.count { it == '\n' } + 1
                    violations.add("${file.relativeTo(project.rootDir)}:$line: matched banned token '${matcher.group()}' (rule: '$token', caseSensitive=$caseSensitive)")
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

afterEvaluate {
    listOf("assembleDebug", "assembleRelease").forEach { name ->
        tasks.findByName(name)?.dependsOn(banWordCheck)
    }
}
