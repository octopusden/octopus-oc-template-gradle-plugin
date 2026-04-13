package org.octopusden.octopus.oc.template.plugins.gradle.service.diagnostics

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit


class DiagnosticsCollector(
    private val namespace: String,
    val diagnosticsDir: File,
    private val projectName: String,
    private val gitSha: String,
    private val schemaVersion: Int = 1,
    private val ocRunner: OcRunner = DefaultOcRunner()
) {

    private val logger: Logger = LoggerFactory.getLogger(DiagnosticsCollector::class.java)

    private val beforeDir: File = diagnosticsDir.resolve("snapshot-before")
    private val afterDir: File = diagnosticsDir.resolve("snapshot-after")

    private val metricsFile: File = diagnosticsDir.resolve("metrics.jsonl")
    private val podsFile: File = diagnosticsDir.resolve("pods.jsonl")
    private val podLimitsFile: File = diagnosticsDir.resolve("pod-limits.jsonl")
    private val quotaFile: File = diagnosticsDir.resolve("quota.jsonl")
    private val eventsFile: File = diagnosticsDir.resolve("events.jsonl")
    private val metaFile: File = diagnosticsDir.resolve("meta.json")
    private val degradationLog: File = diagnosticsDir.resolve("degradation.log")

    private val startTs: Instant = Instant.now()
    @Volatile private var endTs: Instant? = null

    private val degraded = mutableSetOf<String>()

    private val seenEvents = mutableSetOf<String>()

    init {
        diagnosticsDir.mkdirs()
    }


    fun writeBeforeSnapshot() {
        writeSnapshotInto(beforeDir)
        // Capture pod resource limits once, so the analyzer can compute peak-vs-limit later.
        capturePodLimits()
    }

    fun writeAfterSnapshot() {
        writeSnapshotInto(afterDir)
        endTs = Instant.now()
    }

    fun writeMeta() {
        val end = endTs ?: Instant.now()
        val json = buildString {
            append("{")
            append(jsonField("schemaVersion", schemaVersion)).append(",")
            append(jsonField("project", projectName)).append(",")
            append(jsonField("namespace", namespace)).append(",")
            append(jsonField("gitSha", gitSha)).append(",")
            append(jsonField("ftStartTs", startTs.toString())).append(",")
            append(jsonField("ftEndTs", end.toString()))
            append("}")
        }
        metaFile.writeText(json)
    }

    fun tick() {
        val ts = Instant.now().toString()
        sampleMetrics(ts)
        samplePods(ts)
        sampleQuota(ts)
        sampleEvents(ts)
    }

    private fun writeSnapshotInto(dir: File) {
        dir.mkdirs()
        writeOcJsonTo(dir.resolve("resourcequota.json"), listOf("get", "resourcequota", "-n", namespace, "-o", "json"), "resourcequota")
        writeOcJsonTo(dir.resolve("limitrange.json"), listOf("get", "limitrange", "-n", namespace, "-o", "json"), "limitrange")
        writeOcJsonTo(dir.resolve("pods.json"), listOf("get", "pods", "-n", namespace, "-o", "json"), "pods")
        writeOcJsonTo(dir.resolve("events.json"), listOf("get", "events", "-n", namespace, "-o", "json"), "events")
    }

    private fun writeOcJsonTo(target: File, args: List<String>, source: String) {
        val result = ocRunner.run(args, DEFAULT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            markDegraded(source, "exit=${result.exitCode}: ${result.stderr.take(200)}")
            return
        }
        target.writeText(result.stdout)
    }

    private fun capturePodLimits() {
        val jsonpath = "{range .items[*]}{.metadata.name}{\"\\t\"}{.spec.nodeName}{\"\\t\"}" +
            "{range .spec.containers[*]}{.name}{\"=\"}{.resources.limits.memory}{\",\"}{.resources.limits.cpu}{\";\"}{end}{\"\\n\"}{end}"
        val result = ocRunner.run(listOf("get", "pods", "-n", namespace, "-o", "jsonpath=$jsonpath"), DEFAULT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            markDegraded("pod-limits", "exit=${result.exitCode}")
            return
        }
        val sb = StringBuilder()
        result.stdout.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split("\t")
            if (parts.size < 3) return@forEach
            val pod = parts[0]
            val node = parts[1]
            parts[2].split(";").forEach { c ->
                if (c.isBlank()) return@forEach
                val eq = c.indexOf('=')
                if (eq <= 0) return@forEach
                val containerName = c.substring(0, eq)
                val limits = c.substring(eq + 1).split(",")
                val memLimit = limits.getOrNull(0).orEmpty()
                val cpuLimit = limits.getOrNull(1).orEmpty()
                sb.append("{")
                    .append(jsonField("pod", pod)).append(",")
                    .append(jsonField("container", containerName)).append(",")
                    .append(jsonField("node", node)).append(",")
                    .append(jsonField("memLimit", memLimit)).append(",")
                    .append(jsonField("cpuLimit", cpuLimit))
                    .append("}\n")
            }
        }
        podLimitsFile.writeText(sb.toString())
    }


    private fun sampleMetrics(ts: String) {
        val result = ocRunner.run(listOf("adm", "top", "pods", "-n", namespace, "--no-headers"), DEFAULT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            markDegraded("metrics", "oc adm top failed (metrics-server unavailable?): exit=${result.exitCode}")
            return
        }
        val sb = StringBuilder()
        result.stdout.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) return@forEach
            val pod = parts[0]
            val cpuM = parseCpuMillicores(parts[1])
            val memBytes = parseMemoryBytes(parts[2])
            sb.append("{")
                .append(jsonField("ts", ts)).append(",")
                .append(jsonField("pod", pod)).append(",")
                .append(jsonField("cpu_m", cpuM)).append(",")
                .append(jsonField("mem_bytes", memBytes))
                .append("}\n")
        }
        appendText(metricsFile, sb.toString())
    }

    private fun samplePods(ts: String) {
        val jsonpath = "{range .items[*]}{.metadata.name}{\"\\t\"}{.status.phase}{\"\\t\"}" +
            "{range .status.containerStatuses[*]}{.restartCount}{\",\"}{.lastState.terminated.reason}{\",\"}{.lastState.terminated.exitCode}{\";\"}{end}{\"\\n\"}{end}"
        val result = ocRunner.run(listOf("get", "pods", "-n", namespace, "-o", "jsonpath=$jsonpath"), DEFAULT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            markDegraded("pods", "exit=${result.exitCode}")
            return
        }
        val sb = StringBuilder()
        result.stdout.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split("\t")
            if (parts.size < 2) return@forEach
            val pod = parts[0]
            val phase = parts[1]
            var totalRestarts = 0
            var lastReason = ""
            var lastExit = ""
            if (parts.size >= 3) {
                parts[2].split(";").forEach { c ->
                    if (c.isBlank()) return@forEach
                    val fields = c.split(",")
                    totalRestarts += fields.getOrNull(0)?.toIntOrNull() ?: 0
                    val reason = fields.getOrNull(1).orEmpty()
                    if (reason.isNotEmpty()) lastReason = reason
                    val exit = fields.getOrNull(2).orEmpty()
                    if (exit.isNotEmpty()) lastExit = exit
                }
            }
            sb.append("{")
                .append(jsonField("ts", ts)).append(",")
                .append(jsonField("pod", pod)).append(",")
                .append(jsonField("phase", phase)).append(",")
                .append(jsonField("restartCount", totalRestarts)).append(",")
                .append(jsonField("lastTerminatedReason", lastReason)).append(",")
                .append(jsonField("lastTerminatedExitCode", lastExit))
                .append("}\n")
        }
        appendText(podsFile, sb.toString())
    }

    private fun sampleQuota(ts: String) {
        val result = ocRunner.run(listOf("get", "resourcequota", "-n", namespace, "-o", "json"), DEFAULT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            markDegraded("quota", "exit=${result.exitCode}")
            return
        }
        val sb = StringBuilder()
        val json = result.stdout
        // Parse each item's metadata.name, status.hard, and status.used from the JSON list.
        // Structure: {"items":[{"metadata":{"name":"..."},"status":{"hard":{...},"used":{...}}},...]}
        fun indexOf(target: String, from: Int): Int = json.indexOf(target, from)

        val itemsIdx = indexOf("\"items\"", 0)
        if (itemsIdx < 0) return

        var searchFrom = itemsIdx
        while (true) {
            val metaIdx = indexOf("\"metadata\"", searchFrom)
            if (metaIdx < 0) break

            val nameKeyIdx = indexOf("\"name\"", metaIdx)
            if (nameKeyIdx < 0) break
            val quotaName = extractJsonStringValue(json, nameKeyIdx)

            val statusIdx = indexOf("\"status\"", nameKeyIdx)
            if (statusIdx < 0) break

            val nextMetaIdx = indexOf("\"metadata\"", statusIdx)
            val itemEnd = if (nextMetaIdx > 0) nextMetaIdx else json.length

            val hardIdx = indexOf("\"hard\"", statusIdx)
            val usedIdx = indexOf("\"used\"", statusIdx)
            val hard = if (hardIdx in statusIdx until itemEnd) extractFlatMap(json, hardIdx) else emptyMap()
            val used = if (usedIdx in statusIdx until itemEnd) extractFlatMap(json, usedIdx) else emptyMap()

            val keys = hard.keys + used.keys
            keys.forEach { k ->
                sb.append("{")
                    .append(jsonField("ts", ts)).append(",")
                    .append(jsonField("name", quotaName)).append(",")
                    .append(jsonField("resource", k)).append(",")
                    .append(jsonField("hard", hard[k].orEmpty())).append(",")
                    .append(jsonField("used", used[k].orEmpty()))
                    .append("}\n")
            }

            searchFrom = if (nextMetaIdx > 0) nextMetaIdx else break
        }
        appendText(quotaFile, sb.toString())
    }

    private fun extractJsonStringValue(json: String, keyIdx: Int): String {
        val colonIdx = json.indexOf(':', keyIdx + 1)
        if (colonIdx < 0) return ""
        val quoteStart = json.indexOf('"', colonIdx + 1)
        if (quoteStart < 0) return ""
        val quoteEnd = json.indexOf('"', quoteStart + 1)
        if (quoteEnd < 0) return ""
        return json.substring(quoteStart + 1, quoteEnd)
    }

    private fun extractFlatMap(json: String, keyIdx: Int): Map<String, String> {
        val braceStart = json.indexOf('{', keyIdx)
        if (braceStart < 0) return emptyMap()

        var depth = 0
        var braceEnd = -1
        for (i in braceStart until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { braceEnd = i; break } }
            }
        }
        if (braceEnd < 0) return emptyMap()
        val block = json.substring(braceStart, braceEnd + 1)

        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < block.length) {
            val qs = block.indexOf('"', i)
            if (qs < 0) break
            val qe = block.indexOf('"', qs + 1)
            if (qe < 0) break
            val key = block.substring(qs + 1, qe)
            val colon = block.indexOf(':', qe + 1)
            if (colon < 0) break

            val vs = block.indexOf('"', colon + 1)
            if (vs < 0) break
            val ve = block.indexOf('"', vs + 1)
            if (ve < 0) break
            result[key] = block.substring(vs + 1, ve)
            i = ve + 1
        }
        return result
    }

    private fun sampleEvents(ts: String) {
        val jsonpath = "{range .items[*]}{.metadata.uid}{\"\\t\"}{.lastTimestamp}{\"\\t\"}{.type}{\"\\t\"}{.reason}{\"\\t\"}{.involvedObject.kind}{\"/\"}{.involvedObject.name}{\"\\t\"}{.message}{\"\\n\"}{end}"
        val result = ocRunner.run(listOf("get", "events", "-n", namespace, "-o", "jsonpath=$jsonpath"), DEFAULT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            markDegraded("events", "exit=${result.exitCode}")
            return
        }
        val sb = StringBuilder()
        result.stdout.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split("\t", limit = 6)
            if (parts.size < 6) return@forEach
            val uid = parts[0]
            if (!seenEvents.add(uid)) return@forEach
            sb.append("{")
                .append(jsonField("ts", ts)).append(",")
                .append(jsonField("eventTs", parts[1])).append(",")
                .append(jsonField("type", parts[2])).append(",")
                .append(jsonField("reason", parts[3])).append(",")
                .append(jsonField("object", parts[4])).append(",")
                .append(jsonField("message", parts[5]))
                .append("}\n")
        }
        appendText(eventsFile, sb.toString())
    }

    private fun appendText(file: File, text: String) {
        if (text.isEmpty()) return
        try {
            file.appendText(text)
        } catch (e: Exception) {
            logger.debug("Failed to append to ${file.name}: ${e.message}")
        }
    }

    private fun markDegraded(source: String, detail: String) {
        if (degraded.add(source)) {
            val msg = "Diagnostics source '$source' unavailable: $detail"
            logger.warn(msg)
            try {
                degradationLog.appendText("${Instant.now()}\t$source\t$detail\n")
            } catch (_: Exception) { /* ignore */ }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        internal fun parseCpuMillicores(s: String): Long {
            val t = s.trim()
            if (t.isEmpty()) return 0
            return if (t.endsWith("m")) {
                t.dropLast(1).toLongOrNull() ?: 0
            } else {
                (t.toDoubleOrNull()?.times(1000.0))?.toLong() ?: 0
            }
        }

        internal fun parseMemoryBytes(s: String): Long {
            val t = s.trim()
            if (t.isEmpty()) return 0
            val suffixes = listOf("Ki" to 1024L, "Mi" to 1024L * 1024, "Gi" to 1024L * 1024 * 1024,
                "Ti" to 1024L * 1024 * 1024 * 1024, "K" to 1000L, "M" to 1_000_000L,
                "G" to 1_000_000_000L, "T" to 1_000_000_000_000L)
            for ((suffix, mult) in suffixes) {
                if (t.endsWith(suffix)) {
                    return (t.dropLast(suffix.length).toDoubleOrNull()?.times(mult))?.toLong() ?: 0
                }
            }
            return t.toLongOrNull() ?: 0
        }

        internal fun jsonField(key: String, value: String): String =
            "\"${escape(key)}\":\"${escape(value)}\""

        internal fun jsonField(key: String, value: Number): String =
            "\"${escape(key)}\":$value"

        private fun escape(s: String): String {
            val sb = StringBuilder(s.length + 2)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            return sb.toString()
        }
    }

    data class OcResult(val exitCode: Int, val stdout: String, val stderr: String)

    interface OcRunner {
        fun run(args: List<String>, timeoutMs: Long): OcResult
    }

    class DefaultOcRunner : OcRunner {
        override fun run(args: List<String>, timeoutMs: Long): OcResult {
            return try {
                val pb = ProcessBuilder(listOf("oc") + args).redirectErrorStream(false)
                val p = pb.start()
                val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                    p.inputStream.bufferedReader().readText()
                }
                val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                    p.errorStream.bufferedReader().readText()
                }
                val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    stdoutFuture.cancel(true)
                    stderrFuture.cancel(true)
                    return OcResult(-1, "", "timeout after ${timeoutMs}ms")
                }
                OcResult(p.exitValue(), stdoutFuture.get(), stderrFuture.get())
            } catch (e: Exception) {
                OcResult(-1, "", e.message.orEmpty())
            }
        }
    }
}
