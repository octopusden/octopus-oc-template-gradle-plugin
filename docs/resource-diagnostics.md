# Namespace resource diagnostics

## Why

Functional tests occasionally fail in ways that *look* like OKD resource pressure — pods that can't initialize, pods killed mid-run — but logs alone can't prove the root cause. This feature captures structured, whole-namespace evidence around every FT run so each failure has a quick yes/no answer to "was this a resource problem?" backed by concrete signals.

It's diagnostic, not preventative: the collector never throws out, never blocks the build, and never fails it even if `oc adm top` or `oc get events` are denied.

## Architecture

Three layers:

1. **Lifecycle hooks** — `doFirst` on the existing `ocCreate` / `ocLogs` / `ocDelete` tasks. This covers both projects using `ocTemplate.isRequiredBy(ftTask)` and projects wiring `dependsOn(ocCreate) / finalizedBy(ocDelete)` manually, because every project goes through those tasks regardless.
2. **`OcDiagnosticsService`** — a Gradle `BuildService` keyed by namespace. Owns the daemon poller thread and idempotency (`AtomicBoolean` start/stop flags). One instance per distinct namespace per build.
3. **`DiagnosticsCollector` + `PostMortemAnalyzer`** — the collector shells out to `oc` and writes files. The analyzer is a pure function over those files that produces `summary.txt`. Splitting them makes the analyzer trivially unit-testable with fixture files.

## Lifecycle

```
ocCreate.doFirst   ──►  startCollection()                  (idempotent)
                         ├─ writeBeforeSnapshot()          (snapshot-before/*.json + pod-limits.jsonl)
                         └─ start daemon poller thread     (tick every diagnosticsPeriod)
ocCreate (main)    ──►  oc create + waitReadiness          (collection window covers readiness wait)
[FT task runs]
ocLogs.doFirst     ──►  stopCollection()                   (idempotent)
                         ├─ interrupt poller, join 5s
                         ├─ writeAfterSnapshot()           (snapshot-after/*.json)
                         ├─ writeMeta()                    (meta.json)
                         └─ PostMortemAnalyzer.analyzeAndWrite() (summary.txt)
ocLogs (main)      ──►  final log snapshots
ocDelete.doFirst   ──►  stopCollection()                   (no-op — already stopped)
ocDelete (main)    ──►  oc delete
BuildService.close() ►  stopCollection()                   (no-op — safety net)
```

The window deliberately starts at `ocCreate` and ends at the *first* finalizer (`ocLogs`) so the readiness-wait phase is included — that's where "can't initialize" failures show up.

## Artifact layout

```
build/<workDir>/diagnostics/
  ft-run-<yyyyMMdd-HHmmss>/        # one directory per FT run, UTC timestamp
    snapshot-before/
      resourcequota.json           # raw `oc get resourcequota -o json`
      limitrange.json
      pods.json
      events.json
    snapshot-after/                # same four files, taken at stop
    metrics.jsonl                  # oc adm top pods — per-tick
    pods.jsonl                     # pod phase + restart + last terminated reason/exit
    pod-limits.jsonl               # one-shot: mem/cpu limits per container
    quota.jsonl                    # per-tick quota hard/used
    events.jsonl                   # deduped-by-uid, new events since last tick
    meta.json                      # project, namespace, gitSha, ftStartTs, ftEndTs, schemaVersion
    degradation.log                # one line per source that became unavailable
    summary.txt                    # post-mortem written at stop
```

All streaming artifacts are **JSONL** (one flat JSON object per line, no nested structures). Timestamps are ISO-8601 UTC.

### JSONL schemas

All fields match the writers in `DiagnosticsCollector.kt`.

- **`metrics.jsonl`** — `{ts, pod, cpu_m, mem_bytes}`. `cpu_m` is millicores, `mem_bytes` is an integer byte count (parsed from `oc`'s quantity format — `120m`, `512Mi`, `1Gi`). Source: `oc adm top pods --no-headers`. (`DiagnosticsCollector.kt:154`)
- **`pods.jsonl`** — `{ts, pod, phase, restartCount, lastTerminatedReason, lastTerminatedExitCode}`. `restartCount` sums across containers. `lastTerminatedReason` is e.g. `OOMKilled` / `Error` / `Completed` — blank when no container has terminated yet. (`DiagnosticsCollector.kt:177`)
- **`pod-limits.jsonl`** — `{pod, container, node, memLimit, cpuLimit}`. Written once during `writeBeforeSnapshot`. Limits are the raw quantity strings (e.g. `"512Mi"`); the analyzer parses them on demand. (`DiagnosticsCollector.kt:118`)
- **`quota.jsonl`** — `{ts, name, resource, hard, used}`. One record per (quota × resource) per tick. (`DiagnosticsCollector.kt:218`)
- **`events.jsonl`** — `{ts, eventTs, type, reason, object, message}`. `object` is `Kind/Name`. Deduped by event UID (`seenEvents` set) so each event appears at most once across the whole run. (`DiagnosticsCollector.kt:270`)
- **`meta.json`** — `{schemaVersion, project, namespace, gitSha, ftStartTs, ftEndTs}`. `gitSha` is picked up from `GIT_COMMIT` or `BUILD_VCS_NUMBER`. `schemaVersion` is currently `1`. (`DiagnosticsCollector.kt:68`)

## Classification rules (`summary.txt`)

`PostMortemAnalyzer.analyze()` at `src/main/kotlin/org/octopusden/octopus/oc/template/plugins/gradle/service/diagnostics/PostMortemAnalyzer.kt:19` walks the JSONL files and collects a set of signals. The final line is:

- `Likely resource problem: YES — signals: <comma-separated signal names>` if any signal fired.
- `Likely resource problem: NO — no resource-pressure signals detected` otherwise.

Signals (any one of these flips the verdict to YES):

| Signal              | Trigger                                                                                                                                 |
|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `OOMKill`           | Any `pods.jsonl` row with `lastTerminatedReason == "OOMKilled"`. Peak-vs-limit % is computed from `metrics.jsonl` + `pod-limits.jsonl`. |
| `near-memory-limit` | A pod's peak `mem_bytes` in `metrics.jsonl` ≥ **90%** of its summed container mem limit from `pod-limits.jsonl`.                        |
| `quota-pressure`    | Any `quota.jsonl` sample where `used/hard ≥ 95%` for a `limits.memory` / `memory` / `requests.memory` / CPU equivalent resource.        |
| `FailedScheduling`  | Any `events.jsonl` row with `reason == "FailedScheduling"`.                                                                             |
| `Evicted`           | Any `events.jsonl` row with `reason == "Evicted"` or containing `"Evict"`.                                                              |

Thresholds are constants at `PostMortemAnalyzer.kt:16-17` (`QUOTA_PRESSURE_PCT = 95.0`, `MEMORY_NEAR_LIMIT_PCT = 90.0`).

The summary also always contains, regardless of verdict: header with project/namespace/window, pod restart deltas, near-limit details, quota start→end %, and up to 10 scheduling-failure events.

## Example `summary.txt` outputs

### Healthy run (NO)

```
FT run post-mortem
project:   vcs-facade
namespace: ft-vcs-facade
window:    2026-04-10T14:00:00Z → 2026-04-10T14:08:32Z (512s)

Namespace quota:
  - default/limits.memory: 45% → 52% (hard=10Gi)
  - default/limits.cpu: 30% → 35% (hard=8)

Likely resource problem: NO — no resource-pressure signals detected
```

No signals. Quota stayed well below thresholds.

### OOMKilled pod (YES)

```
FT run post-mortem
project:   vcs-facade
namespace: ft-vcs-facade
window:    2026-04-10T14:00:00Z → 2026-04-10T14:12:45Z (765s)

OOMKilled pods:
  - oc-template-ft-1-0-snapshot-opensearch (peak 489.2MiB / limit 512.0MiB = 96%)

Pods that restarted during the run:
  - oc-template-ft-1-0-snapshot-opensearch: +1 restart(s)

Pods peaked near their memory limit (≥90%):
  - oc-template-ft-1-0-snapshot-opensearch: 489.2MiB / 512.0MiB (96%)

Namespace quota:
  - default/limits.memory: 60% → 68% (hard=10Gi)

Likely resource problem: YES — signals: OOMKill, near-memory-limit
```

opensearch hit 96% of its 512Mi limit and got killed. Raise the limit or fix the leak.

### Can't schedule + quota full (YES)

```
FT run post-mortem
project:   vcs-facade
namespace: ft-vcs-facade
window:    2026-04-10T14:00:00Z → 2026-04-10T14:15:03Z (903s)

Namespace quota:
  - default/limits.memory: 92% → 98% (hard=10Gi)
  - default/limits.cpu: 88% → 96% (hard=8)

FailedScheduling events:
  - Pod/oc-template-ft-1-0-snapshot-bitbucket: 0/6 nodes are available: 3 Insufficient memory, 3 node(s) had taint
  - Pod/oc-template-ft-1-0-snapshot-vcs-facade: 0/6 nodes are available: 3 Insufficient memory, 3 node(s) had taint

Likely resource problem: YES — signals: quota-pressure, FailedScheduling
```

Two signals: namespace quota at 98% and pods couldn't schedule. This is a namespace capacity problem — raise quota or reduce concurrent services.

### OOMKill without near-limit signal (YES)

```
FT run post-mortem
project:   vcs-facade
namespace: ft-vcs-facade
window:    2026-04-10T14:00:00Z → 2026-04-10T14:09:17Z (557s)

OOMKilled pods:
  - oc-template-ft-1-0-snapshot-gitea (peak 450.7MiB / limit 512.0MiB = 88%)

Pods that restarted during the run:
  - oc-template-ft-1-0-snapshot-gitea: +2 restart(s)

Namespace quota:
  - default/limits.memory: 70% → 75% (hard=10Gi)

Likely resource problem: YES — signals: OOMKill
```

Peak was only 88% — below the 90% `near-memory-limit` threshold so that signal didn't fire. But `OOMKill` still did because the pod was killed. The peak metric might have missed the exact spike (10s sampling interval).

## Configuration

On the `ocTemplate { ... }` extension:

| Property             | Default     | Effect                                                                     |
|----------------------|-------------|----------------------------------------------------------------------------|
| `diagnosticsEnabled` | `true`      | Emergency off switch. Skips service registration entirely when `false`.    |
| `diagnosticsPeriod`  | `10_000` ms | Poller interval for streaming samples.                                    |

Enabled by default so every project gets diagnostics with zero config changes.

## Namespace deduplication

Registration happens in `OcTaskConfiguration.registerDiagnosticsService()` at `src/main/groovy/org/octopusden/octopus/oc/template/plugins/gradle/OcTaskConfiguration.groovy:52`:

```
buildServiceName = "ocDiagnosticsService_<project>_<sanitized-namespace>"
```

`project.gradle.sharedServices.registerIfAbsent(...)` is idempotent by name, so multiple nested `ocTemplate` groups sharing the same namespace (the common case — see `example-build.gradle.kts` with `giteaServices` + `bitbucketServices` under one namespace) all resolve to the *same* service instance → one `ft-run-*` bundle per FT, one `summary.txt`. Per-pod attribution is preserved inside the JSONL files (every sample row carries the pod name).

If different nested groups ever configure *distinct* namespaces, the dedup naturally produces one bundle per distinct namespace.

## Graceful degradation

Every `oc` call in `DiagnosticsCollector` goes through `markDegraded(source, detail)` on failure (`DiagnosticsCollector.kt:338`). That helper:

- adds the source name to a `Set<String>` so subsequent failures of the same source are silent,
- logs one `WARN` with the reason,
- appends one line to `degradation.log`.

Typical degradations:

- **`metrics`** — `oc adm top` needs metrics-server. If absent or slow, metrics are unavailable but everything else still runs.
- **`events`** / **`resourcequota`** / **`limitrange`** — rare; RBAC only.

The collector thread's own loop also has a blanket `catch (e: Exception)` around `tick()` in `OcDiagnosticsService.startCollection()` at `src/main/kotlin/org/octopusden/octopus/oc/template/plugins/gradle/service/OcDiagnosticsService.kt:71` — anything that escapes `tick` is debug-logged and swallowed.

## Reading a `summary.txt`

Trust order:

1. **`OOMKill`** or **`Evicted`** — essentially conclusive. Look at the associated peak-vs-limit % to decide whether to raise the limit or fix a leak.
2. **`quota-pressure` + `FailedScheduling`** — namespace is out of headroom, not a per-pod problem. Raise quota or reduce concurrent services.
3. **`near-memory-limit`** alone — a warning, not a diagnosis. The pod didn't die this run, but it's on the edge.
4. **`NO`** — trust it *only* if `degradation.log` is empty or only contains `metrics`. A `NO` with `pods`/`events` degraded means the analyzer was flying blind.

## Key code references

- Lifecycle hooks:
  - `OcCreateTask` `doFirst` → `startCollection()` — `src/main/groovy/.../tasks/OcCreateTask.groovy`
  - `OcLogsTask` `doFirst` → `stopCollection()` — `src/main/groovy/.../tasks/OcLogsTask.groovy`
  - `OcDeleteTask` `doFirst` → `stopCollection()` — `src/main/groovy/.../tasks/OcDeleteTask.groovy`
- `OcDiagnosticsService.startCollection` — `src/main/kotlin/.../service/OcDiagnosticsService.kt:44`
- `OcDiagnosticsService.stopCollection` — `OcDiagnosticsService.kt:88`
- `OcDiagnosticsService.close` (safety net) — `OcDiagnosticsService.kt:110`
- Service registration + namespace dedup — `OcTaskConfiguration.groovy:52` (`registerDiagnosticsService`)
- Collector entry points — `DiagnosticsCollector.writeBeforeSnapshot` (`DiagnosticsCollector.kt:57`), `tick` (`:87`), `writeAfterSnapshot` (`:63`), `writeMeta` (`:68`)
- Quantity parsers — `DiagnosticsCollector.parseCpuMillicores` / `parseMemoryBytes` (`:351`, `:361`)
- Analyzer — `PostMortemAnalyzer.analyze` (`PostMortemAnalyzer.kt:19`)
- Unit tests — `src/test/kotlin/.../service/diagnostics/PostMortemAnalyzerTest.kt`
- Integration test — `OcTemplatePluginTest.testDiagnosticsArtifactsProduced` (`OcTemplatePluginTest.kt:262`)
