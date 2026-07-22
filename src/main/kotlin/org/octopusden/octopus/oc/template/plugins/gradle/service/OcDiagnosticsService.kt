package org.octopusden.octopus.oc.template.plugins.gradle.service

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.octopusden.octopus.oc.template.plugins.gradle.service.diagnostics.DiagnosticsCollector
import org.octopusden.octopus.oc.template.plugins.gradle.service.diagnostics.PostMortemAnalyzer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Namespace-scoped BuildService that runs whole-namespace resource diagnostics
 * during an FT run. Started by [OcCreateTask] (doFirst) and stopped by
 * [OcLogsTask] or [OcDeleteTask] (whichever runs first). [close] is a final
 * safety net at build end.
 *
 * Idempotent: multiple start/stop calls are no-ops. Never throws out of
 * start/stop — diagnostics must not fail the build.
 */
abstract class OcDiagnosticsService :
    BuildService<OcDiagnosticsService.Parameters>,
    AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val namespace: Property<String>
        val workDir: DirectoryProperty
        val diagnosticsPeriod: Property<Long>
        val projectName: Property<String>
        val gitSha: Property<String>
    }

    private val logger: Logger = LoggerFactory.getLogger(OcDiagnosticsService::class.java)

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    @Volatile private var collector: DiagnosticsCollector? = null

    @Volatile private var pollerThread: Thread? = null

    fun startCollection() {
        if (!started.compareAndSet(false, true)) return
        try {
            val workDir = parameters.workDir.get().asFile
            val namespace = parameters.namespace.get()
            val runDirName = "ft-run-" + TIMESTAMP_FMT.format(Instant.now())
            val runDir = File(workDir, "diagnostics/$runDirName")
            val c = DiagnosticsCollector(
                namespace = namespace,
                diagnosticsDir = runDir,
                projectName = parameters.projectName.getOrElse("unknown"),
                gitSha = parameters.gitSha.getOrElse(""),
            )
            collector = c
            logger.info("Starting OKD diagnostics collection for namespace '$namespace' into ${runDir.absolutePath}")
            safely("before-snapshot") { c.writeBeforeSnapshot() }

            val period = parameters.diagnosticsPeriod.getOrElse(10_000L)
            val t = Thread {
                // Run the first tick immediately so that even short-lived FT
                // runs produce at least one set of JSONL samples.
                try {
                    c.tick()
                } catch (e: Exception) {
                    logger.debug("diagnostics tick failed: ${e.message}")
                }
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(period)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    try {
                        c.tick()
                    } catch (e: Exception) {
                        logger.debug("diagnostics tick failed: ${e.message}")
                    }
                }
            }.apply {
                isDaemon = true
                name = "oc-diagnostics-$namespace"
                start()
            }
            pollerThread = t
        } catch (e: Exception) {
            logger.warn("Failed to start diagnostics collection: ${e.message}")
        }
    }

    fun stopCollection() {
        if (!stopped.compareAndSet(false, true)) return
        val t = pollerThread
        val c = collector
        try {
            t?.interrupt()
            try {
                t?.join(5_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (c != null) {
                safely("after-snapshot") { c.writeAfterSnapshot() }
                safely("meta") { c.writeMeta() }
                safely("post-mortem") {
                    val summary = PostMortemAnalyzer.analyzeAndWrite(c.diagnosticsDir)
                    logger.info("OKD diagnostics summary: ${summary.absolutePath}")
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to stop diagnostics collection: ${e.message}")
        } finally {
            pollerThread = null
        }
    }

    override fun close() {
        // Safety-net: ensure we stop even if neither OcLogsTask nor OcDeleteTask ran.
        if (started.get() && !stopped.get()) {
            stopCollection()
        }
    }

    private inline fun safely(
        step: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn("Diagnostics step '$step' failed: ${e.message}")
        }
    }

    companion object {
        private val TIMESTAMP_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
