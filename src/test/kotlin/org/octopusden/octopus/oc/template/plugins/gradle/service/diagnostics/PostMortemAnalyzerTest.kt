package org.octopusden.octopus.oc.template.plugins.gradle.service.diagnostics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PostMortemAnalyzerTest {

    @Test
    fun `classifies clean run as no resource problem`(@TempDir dir: File) {
        writeMeta(dir, "proj-a", "ns-a")
        dir.resolve("pod-limits.jsonl").writeText(
            """{"pod":"svc-1","container":"app","node":"worker-1","memLimit":"512Mi","cpuLimit":"1"}""" + "\n"
        )
        dir.resolve("metrics.jsonl").writeText(
            """{"ts":"2026-04-09T14:03:12Z","pod":"svc-1","cpu_m":120,"mem_bytes":104857600}""" + "\n"
        )
        dir.resolve("pods.jsonl").writeText(
            """{"ts":"2026-04-09T14:03:12Z","pod":"svc-1","phase":"Running","restartCount":0,"lastTerminatedReason":"","lastTerminatedExitCode":""}""" + "\n"
        )
        dir.resolve("quota.jsonl").writeText(
            """{"ts":"2026-04-09T14:03:12Z","name":"default","resource":"limits.memory","hard":"10Gi","used":"2Gi"}""" + "\n"
        )
        dir.resolve("events.jsonl").writeText("")

        val summary = PostMortemAnalyzer.analyze(dir)
        assertThat(summary).contains("Likely resource problem: NO")
        assertThat(summary).doesNotContain("OOMKilled")
    }

    @Test
    fun `detects OOMKilled pod and classifies as likely resource problem`(@TempDir dir: File) {
        writeMeta(dir, "proj-b", "ns-b")
        dir.resolve("pod-limits.jsonl").writeText(
            """{"pod":"bad-svc","container":"app","node":"worker-3","memLimit":"512Mi","cpuLimit":"1"}""" + "\n"
        )
        // 498 MiB peak usage against a 512 MiB limit.
        dir.resolve("metrics.jsonl").writeText(
            """{"ts":"2026-04-09T14:03:12Z","pod":"bad-svc","cpu_m":120,"mem_bytes":522190848}""" + "\n"
        )
        dir.resolve("pods.jsonl").writeText(
            """{"ts":"2026-04-09T14:03:12Z","pod":"bad-svc","phase":"Running","restartCount":0,"lastTerminatedReason":"","lastTerminatedExitCode":""}""" + "\n" +
            """{"ts":"2026-04-09T14:07:22Z","pod":"bad-svc","phase":"Running","restartCount":1,"lastTerminatedReason":"OOMKilled","lastTerminatedExitCode":"137"}""" + "\n"
        )
        dir.resolve("quota.jsonl").writeText("")
        dir.resolve("events.jsonl").writeText("")

        val summary = PostMortemAnalyzer.analyze(dir)
        assertThat(summary).contains("Likely resource problem: YES")
        assertThat(summary).contains("OOMKill")
        assertThat(summary).contains("bad-svc")
        assertThat(summary).contains("+1 restart")
    }

    @Test
    fun `detects quota pressure and FailedScheduling`(@TempDir dir: File) {
        writeMeta(dir, "proj-c", "ns-c")
        dir.resolve("pod-limits.jsonl").writeText("")
        dir.resolve("metrics.jsonl").writeText("")
        dir.resolve("pods.jsonl").writeText("")
        dir.resolve("quota.jsonl").writeText(
            """{"ts":"2026-04-09T14:00:00Z","name":"default","resource":"limits.memory","hard":"10Gi","used":"9.8Gi"}""" + "\n"
        )
        dir.resolve("events.jsonl").writeText(
            """{"ts":"2026-04-09T14:01:00Z","eventTs":"2026-04-09T14:01:00Z","type":"Warning","reason":"FailedScheduling","object":"Pod/pending-svc","message":"0/6 nodes insufficient memory"}""" + "\n"
        )

        val summary = PostMortemAnalyzer.analyze(dir)
        assertThat(summary).contains("Likely resource problem: YES")
        assertThat(summary).contains("quota-pressure")
        assertThat(summary).contains("FailedScheduling")
    }

    @Test
    fun `detects CPU quota pressure`(@TempDir dir: File) {
        writeMeta(dir, "proj-cpu", "ns-cpu")
        dir.resolve("pod-limits.jsonl").writeText("")
        dir.resolve("metrics.jsonl").writeText("")
        dir.resolve("pods.jsonl").writeText("")
        dir.resolve("quota.jsonl").writeText(
            """{"ts":"2026-04-09T14:00:00Z","name":"default","resource":"limits.cpu","hard":"4","used":"3900m"}""" + "\n"
        )
        dir.resolve("events.jsonl").writeText("")

        val summary = PostMortemAnalyzer.analyze(dir)
        assertThat(summary).contains("Likely resource problem: YES")
        assertThat(summary).contains("quota-pressure")
    }

    @Test
    fun `parseFlatJsonObject handles strings and numbers`() {
        val parsed = PostMortemAnalyzer.parseFlatJsonObject(
            """{"pod":"abc","cpu_m":120,"mem_bytes":104857600,"reason":""}"""
        )
        assertThat(parsed["pod"]).isEqualTo("abc")
        assertThat(parsed["cpu_m"]).isEqualTo("120")
        assertThat(parsed["mem_bytes"]).isEqualTo("104857600")
        assertThat(parsed["reason"]).isEqualTo("")
    }

    @Test
    fun `parseCpuMillicores and parseMemoryBytes handle common formats`() {
        assertThat(DiagnosticsCollector.parseCpuMillicores("120m")).isEqualTo(120L)
        assertThat(DiagnosticsCollector.parseCpuMillicores("2")).isEqualTo(2000L)
        assertThat(DiagnosticsCollector.parseMemoryBytes("512Mi")).isEqualTo(512L * 1024 * 1024)
        assertThat(DiagnosticsCollector.parseMemoryBytes("1Gi")).isEqualTo(1024L * 1024 * 1024)
        assertThat(DiagnosticsCollector.parseMemoryBytes("")).isEqualTo(0L)
    }

    private fun writeMeta(dir: File, project: String, ns: String) {
        dir.resolve("meta.json").writeText(
            """{"schemaVersion":1,"project":"$project","namespace":"$ns","gitSha":"","ftStartTs":"2026-04-09T14:00:00Z","ftEndTs":"2026-04-09T14:10:00Z"}"""
        )
    }
}
