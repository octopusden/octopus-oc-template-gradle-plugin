package org.octopusden.octopus.oc.template.plugins.gradle.service.diagnostics

import groovy.json.JsonSlurper
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Reads the JSONL artifacts produced by [DiagnosticsCollector] and writes a
 * human-readable `summary.txt` that tries to answer: was this FT run likely
 * affected by OKD resource pressure?
 *
 * Pure logic; no I/O beyond the directory passed in. Intended to be unit-testable
 * with hand-crafted fixture files.
 */
object PostMortemAnalyzer {
    private const val QUOTA_PRESSURE_PCT = 95.0
    private const val MEMORY_NEAR_LIMIT_PCT = 90.0

    fun analyze(diagnosticsDir: File): String {
        val meta = parseMetaJson(diagnosticsDir.resolve("meta.json"))
        val podLimits = readJsonlLines(diagnosticsDir.resolve("pod-limits.jsonl"))
        val metrics = readJsonlLines(diagnosticsDir.resolve("metrics.jsonl"))
        val pods = readJsonlLines(diagnosticsDir.resolve("pods.jsonl"))
        val quota = readJsonlLines(diagnosticsDir.resolve("quota.jsonl"))
        val events = readJsonlLines(diagnosticsDir.resolve("events.jsonl"))

        val signals = mutableListOf<String>()
        val lines = mutableListOf<String>()

        val project = meta["project"] ?: "(unknown)"
        val namespace = meta["namespace"] ?: "(unknown)"
        val startTs = meta["ftStartTs"] ?: ""
        val endTs = meta["ftEndTs"] ?: ""
        val duration = runCatching {
            if (startTs.isNotEmpty() && endTs.isNotEmpty()) {
                Duration.between(Instant.parse(startTs), Instant.parse(endTs)).seconds
            } else {
                -1L
            }
        }.getOrDefault(-1L)

        lines += "FT run post-mortem"
        lines += "project:   $project"
        lines += "namespace: $namespace"
        lines += "window:    $startTs → $endTs${if (duration >= 0) " (${duration}s)" else ""}"
        lines += ""

        val limitsByPodContainer = podLimits.mapNotNull { m ->
            val pod = m["pod"] ?: return@mapNotNull null
            val container = m["container"] ?: return@mapNotNull null
            val memLimit = DiagnosticsCollector.parseMemoryBytes(m["memLimit"] ?: "")
            Triple(pod, container, memLimit)
        }
        val memLimitByPod: Map<String, Long> = limitsByPodContainer
            .groupBy { it.first }
            .mapValues { entry -> entry.value.sumOf { it.third } }

        val peakMemByPod: Map<String, Long> = metrics
            .groupBy { it["pod"].orEmpty() }
            .mapValues { entry -> entry.value.maxOfOrNull { (it["mem_bytes"] ?: "0").toLongOrNull() ?: 0L } ?: 0L }
            .filterKeys { it.isNotEmpty() }

        val oomKilledPods = pods
            .filter { it["lastTerminatedReason"] == "OOMKilled" }
            .mapNotNull { it["pod"] }
            .toSet()

        val restartedPods = pods
            .groupBy { it["pod"].orEmpty() }
            .mapNotNull { (pod, samples) ->
                if (pod.isEmpty()) return@mapNotNull null
                val counts = samples.mapNotNull { (it["restartCount"] ?: "").toIntOrNull() }
                val delta = (counts.maxOrNull() ?: 0) - (counts.minOrNull() ?: 0)
                if (delta > 0) pod to delta else null
            }.toMap()

        if (oomKilledPods.isNotEmpty()) {
            signals += "OOMKill"
            lines += "OOMKilled pods:"
            oomKilledPods.forEach { pod ->
                val peak = peakMemByPod[pod] ?: 0L
                val limit = memLimitByPod[pod] ?: 0L
                val pct = if (limit > 0) (peak * 100.0 / limit) else -1.0
                val pctStr = if (pct >= 0) " (peak ${humanBytes(peak)} / limit ${humanBytes(limit)} = ${"%.0f".format(pct)}%)" else ""
                lines += "  - $pod$pctStr"
            }
            lines += ""
        }

        if (restartedPods.isNotEmpty()) {
            lines += "Pods that restarted during the run:"
            restartedPods.forEach { (pod, delta) -> lines += "  - $pod: +$delta restart(s)" }
            lines += ""
        }

        val nearLimitPods = peakMemByPod.mapNotNull { (pod, peak) ->
            val limit = memLimitByPod[pod] ?: return@mapNotNull null
            if (limit <= 0) return@mapNotNull null
            val pct = peak * 100.0 / limit
            if (pct >= MEMORY_NEAR_LIMIT_PCT) Triple(pod, peak, limit) else null
        }
        if (nearLimitPods.isNotEmpty()) {
            signals += "near-memory-limit"
            lines += "Pods peaked near their memory limit (≥${MEMORY_NEAR_LIMIT_PCT.toInt()}%):"
            nearLimitPods.forEach { (pod, peak, limit) ->
                val pct = peak * 100.0 / limit
                lines += "  - $pod: ${humanBytes(peak)} / ${humanBytes(limit)} (${"%.0f".format(pct)}%)"
            }
            lines += ""
        }

        // Quota
        val memQuotaSamples = quota.filter {
            val r = it["resource"] ?: ""
            r == "limits.memory" || r == "memory" || r == "requests.memory"
        }
        val cpuQuotaSamples = quota.filter {
            val r = it["resource"] ?: ""
            r == "limits.cpu" || r == "cpu" || r == "requests.cpu"
        }
        val memQuotaPressure = memQuotaSamples.any { sample ->
            val used = DiagnosticsCollector.parseMemoryBytes(sample["used"] ?: "")
            val hard = DiagnosticsCollector.parseMemoryBytes(sample["hard"] ?: "")
            hard > 0 && (used * 100.0 / hard) >= QUOTA_PRESSURE_PCT
        }
        val cpuQuotaPressure = cpuQuotaSamples.any { sample ->
            val used = DiagnosticsCollector.parseCpuMillicores(sample["used"] ?: "")
            val hard = DiagnosticsCollector.parseCpuMillicores(sample["hard"] ?: "")
            hard > 0 && (used * 100.0 / hard) >= QUOTA_PRESSURE_PCT
        }
        if (memQuotaPressure || cpuQuotaPressure) signals += "quota-pressure"

        if (memQuotaSamples.isNotEmpty() || cpuQuotaSamples.isNotEmpty()) {
            lines += "Namespace quota:"
            (memQuotaSamples + cpuQuotaSamples)
                .groupBy { (it["name"] ?: "") + "::" + (it["resource"] ?: "") }
                .forEach { (_, samples) ->
                    val first = samples.first()
                    val last = samples.last()
                    val resource = first["resource"] ?: "?"
                    val name = first["name"] ?: "?"
                    val hard = first["hard"] ?: "?"
                    val isCpu = resource.contains("cpu")

                    fun parse(v: String): Long =
                        if (isCpu) DiagnosticsCollector.parseCpuMillicores(v) else DiagnosticsCollector.parseMemoryBytes(v)

                    fun pct(
                        used: String,
                        hard: String,
                    ): String {
                        val u = parse(used)
                        val h = parse(hard)
                        return if (h > 0) "${"%.0f".format(u * 100.0 / h)}%" else "?"
                    }
                    val startPct = pct(first["used"] ?: "", hard)
                    val endPct = pct(last["used"] ?: "", hard)
                    lines += "  - $name/$resource: $startPct → $endPct (hard=$hard)"
                }
            lines += ""
        }

        // Events
        val schedulingFailures = events.filter { it["reason"] == "FailedScheduling" }
        val evictions = events.filter { it["reason"] == "Evicted" || it["reason"]?.contains("Evict") == true }
        if (schedulingFailures.isNotEmpty()) {
            signals += "FailedScheduling"
            lines += "FailedScheduling events:"
            schedulingFailures.take(10).forEach { e ->
                lines += "  - ${e["object"]}: ${e["message"]}"
            }
            if (schedulingFailures.size > 10) lines += "  ... and ${schedulingFailures.size - 10} more"
            lines += ""
        }
        if (evictions.isNotEmpty()) {
            signals += "Evicted"
            lines += "Eviction events:"
            evictions.take(10).forEach { e ->
                lines += "  - ${e["object"]}: ${e["message"]}"
            }
            lines += ""
        }

        // Classification
        val verdict = if (signals.isEmpty()) {
            "NO — no resource-pressure signals detected"
        } else {
            "YES — signals: ${signals.toSet().joinToString(
                ", ",
            )}"
        }
        lines += "Likely resource problem: $verdict"

        return lines.joinToString("\n") + "\n"
    }

    fun analyzeAndWrite(diagnosticsDir: File): File {
        val summary = analyze(diagnosticsDir)
        val out = diagnosticsDir.resolve("summary.txt")
        out.writeText(summary)
        return out
    }

    private val slurper = JsonSlurper()

    internal fun readJsonlLines(file: File): List<Map<String, String>> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<Map<String, String>>()
        file.useLines { seq ->
            seq.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("{")) return@forEach
                result += parseJsonLine(trimmed)
            }
        }
        return result
    }

    internal fun parseMetaJson(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return parseJsonLine(file.readText().trim())
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseJsonLine(src: String): Map<String, String> {
        return try {
            val parsed = slurper.parseText(src) as? Map<String, Any?> ?: return emptyMap()
            parsed.mapValues { (_, v) -> v?.toString().orEmpty() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return "0"
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
        var b = bytes.toDouble()
        var u = 0
        while (b >= 1024.0 && u < units.size - 1) {
            b /= 1024.0
            u++
        }
        return "${"%.1f".format(b)}${units[u]}"
    }
}
