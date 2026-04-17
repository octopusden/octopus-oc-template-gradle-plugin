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

All streaming files use **JSONL** (JSON Lines) format — one valid JSON object per line, separated by newlines. This is append-friendly (no need to manage array brackets) and can be read line-by-line without loading the entire file into memory.

All fields match the writers in `DiagnosticsCollector.kt`.

#### `metrics.jsonl` — live CPU and memory usage per pod

```json
{"ts":"2026-04-10T14:03:12Z","pod":"postgres-abc","cpu_m":120,"mem_bytes":522190848}
```

| Key | Meaning |
|-----|---------|
| `ts` | Timestamp (ISO-8601 UTC) when this sample was taken |
| `pod` | Pod name in the namespace |
| `cpu_m` | Actual CPU usage in **millicores** (1000m = 1 full CPU core). Example: `120` means 0.12 cores |
| `mem_bytes` | Actual memory usage in **bytes**. Example: `522190848` = ~498 MiB |

Source: `oc adm top pods --no-headers`, which queries the cluster's metrics-server for real-time resource consumption. (`DiagnosticsCollector.kt:154`)

**What to look for:** memory trending upward toward the limit over time suggests a memory leak or an undersized limit.

#### `pods.jsonl` — pod status, restart count, termination reason

```json
{"ts":"2026-04-10T14:03:12Z","pod":"postgres-abc","phase":"Running","restartCount":1,"lastTerminatedReason":"OOMKilled","lastTerminatedExitCode":"137"}
```

| Key | Meaning |
|-----|---------|
| `ts` | Timestamp when this sample was taken |
| `pod` | Pod name |
| `phase` | Current pod phase: `Pending`, `Running`, `Succeeded`, `Failed`, or `Unknown` |
| `restartCount` | Total restart count summed across all containers in the pod. A rising value means containers are crashing and restarting |
| `lastTerminatedReason` | Why the last container was terminated: `OOMKilled` (out of memory), `Error` (non-zero exit), `Completed` (exited 0). Blank if no container has terminated yet |
| `lastTerminatedExitCode` | Exit code of the last terminated container. `137` = killed by SIGKILL (typical for OOMKill), `143` = SIGTERM, `1` = application error |

Source: `oc get pods` with jsonpath. (`DiagnosticsCollector.kt:177`)

**What to look for:** `OOMKilled` means Kubernetes killed the pod for exceeding its memory limit. A rising `restartCount` means the pod crashed and restarted during the FT run.

#### `quota.jsonl` — namespace resource quota usage

```json
{"ts":"2026-04-10T14:03:12Z","name":"default","resource":"limits.memory","hard":"10Gi","used":"9.8Gi"}
```

| Key | Meaning |
|-----|---------|
| `ts` | Timestamp when this sample was taken |
| `name` | Name of the ResourceQuota object (e.g. `default`, `compute-resources`) |
| `resource` | Which resource this row describes: `limits.memory`, `limits.cpu`, `requests.memory`, `requests.cpu`, `pods`, etc. |
| `hard` | The maximum allowed by the quota (the namespace's total budget for this resource) |
| `used` | How much is currently consumed across all pods in the namespace |

Source: `oc get resourcequota` with jsonpath. One record per (quota × resource) per tick. (`DiagnosticsCollector.kt:218`)

**What to look for:** if `used/hard > 95%`, the namespace is nearly full and new pods may fail to create. Compare `used` across ticks to see if consumption is growing during the FT run.

#### `events.jsonl` — Kubernetes events (deduplicated)

```json
{"ts":"2026-04-10T14:03:12Z","eventTs":"2026-04-10T14:02:50Z","type":"Warning","reason":"FailedScheduling","object":"Pod/my-pod","message":"0/6 nodes available: insufficient memory"}
```

| Key | Meaning |
|-----|---------|
| `ts` | Timestamp when the collector captured this event |
| `eventTs` | Timestamp when Kubernetes generated the event (may be earlier than `ts`) |
| `type` | Event severity: `Normal` (informational) or `Warning` (something went wrong) |
| `reason` | Machine-readable cause: `FailedScheduling`, `Evicted`, `Killing`, `Created`, `Started`, etc. |
| `object` | The Kubernetes object involved, as `Kind/Name` (e.g. `Pod/my-pod`, `ReplicaSet/my-rs`) |
| `message` | Human-readable description of what happened |

Source: `oc get events` with jsonpath. Deduped by event UID so each event appears at most once across the whole run. (`DiagnosticsCollector.kt:270`)

**What to look for:** `FailedScheduling` means no node had enough resources to place the pod. `Evicted` means Kubernetes evicted the pod to reclaim node resources.

#### `pod-limits.jsonl` — memory/CPU limits per container (captured once)

```json
{"pod":"postgres-abc","container":"postgres","node":"worker-3","memLimit":"512Mi","cpuLimit":"1"}
```

| Key | Meaning |
|-----|---------|
| `pod` | Pod name |
| `container` | Container name within the pod (a pod can have multiple containers) |
| `node` | The cluster node this pod is running on |
| `memLimit` | Maximum memory this container is allowed to use (e.g. `512Mi`). If exceeded, Kubernetes OOMKills it |
| `cpuLimit` | Maximum CPU this container is allowed to use (e.g. `1` = 1 core, `500m` = 0.5 core). If exceeded, the container is throttled (not killed) |

Source: `oc get pods` with jsonpath. Written once during `writeBeforeSnapshot`. (`DiagnosticsCollector.kt:118`)

**What to look for:** combine with `metrics.jsonl` to calculate peak usage as a percentage of the limit. A pod peaking at 90%+ of its memory limit is at risk of being OOMKilled. Note: CPU over-limit only causes throttling, but memory over-limit causes a kill.

#### `meta.json` — run metadata

```json
{"schemaVersion":1,"project":"vcs-facade","namespace":"ft-vcs-facade","gitSha":"abc123","ftStartTs":"2026-04-10T14:00:00Z","ftEndTs":"2026-04-10T14:08:32Z"}
```

| Key | Meaning |
|-----|---------|
| `schemaVersion` | Format version (currently `1`). Bumped if the file structure changes in the future |
| `project` | Gradle project name |
| `namespace` | OKD/Kubernetes namespace the FT ran in |
| `gitSha` | Git commit hash, picked up from env var `GIT_COMMIT` or `BUILD_VCS_NUMBER`. Empty if neither is set |
| `ftStartTs` | When diagnostics collection started (ISO-8601 UTC) |
| `ftEndTs` | When diagnostics collection stopped (ISO-8601 UTC) |

Source: written by `DiagnosticsCollector.writeMeta()`. (`DiagnosticsCollector.kt:68`)

## How `summary.txt` reaches its conclusion

`PostMortemAnalyzer.analyze()` (`PostMortemAnalyzer.kt:19`) reads all the JSONL files and follows these steps to produce the verdict.

### Step 1: Build lookup maps

The analyzer first builds two maps that are used throughout the analysis:

- **`memLimitByPod`** — from `pod-limits.jsonl`, sums each container's `memLimit` per pod. This is the pod's total memory budget.
- **`peakMemByPod`** — from `metrics.jsonl`, takes the maximum `mem_bytes` across all ticks per pod. This is the worst observed memory usage.

These two maps let the analyzer compute: "this pod used X% of its memory limit at peak."

### Step 2: Check for signals

The analyzer checks 5 independent conditions. Each one that fires adds a signal to the list.

#### Signal 1: `OOMKill` — was any pod killed for exceeding its memory limit?

- Scans `pods.jsonl` for any row with `lastTerminatedReason == "OOMKilled"`
- For each OOMKilled pod, computes `peakMemByPod / memLimitByPod` to show how close it was to the limit before being killed
- This is the strongest signal — the pod was definitively killed by Kubernetes for using too much memory

#### Signal 2: `near-memory-limit` — was any pod dangerously close to its memory limit?

- For each pod, computes `peak mem_bytes / total memLimit`
- If the ratio is **≥ 90%** (`MEMORY_NEAR_LIMIT_PCT`), the signal fires
- The pod didn't die this run, but it's on the edge — next run with slightly more load could trigger an OOMKill

#### Signal 3: `quota-pressure` — is the namespace running out of its resource budget?

- Scans `quota.jsonl` for memory and CPU resources (`limits.memory`, `requests.memory`, `limits.cpu`, `requests.cpu`, etc.)
- If any sample has `used / hard ≥ 95%` (`QUOTA_PRESSURE_PCT`), the signal fires
- This means the namespace quota is nearly exhausted — new pods may fail to be created because there's no room left

#### Signal 4: `FailedScheduling` — could Kubernetes not place a pod on any node?

- Scans `events.jsonl` for any event with `reason == "FailedScheduling"`
- This typically means no node in the cluster had enough available CPU or memory to run the pod
- The `message` field usually explains why (e.g. "0/6 nodes are available: 3 Insufficient memory")

#### Signal 5: `Evicted` — was any pod forcefully removed by Kubernetes?

- Scans `events.jsonl` for any event with `reason == "Evicted"` or reason containing `"Evict"`
- Eviction happens when a node is under resource pressure and Kubernetes needs to free up resources by killing pods

### Step 3: Verdict

Simple rule — **any signal fires → YES, no signals → NO**:

```
Likely resource problem: YES — signals: OOMKill, near-memory-limit
```
or
```
Likely resource problem: NO — no resource-pressure signals detected
```

The verdict is intentionally conservative (one signal = YES) because this is a triage tool: it's better to investigate a false positive than to miss a real resource problem.

### Step 4: Context sections (always included)

Regardless of the verdict, the summary always includes these sections when data is available:

- **Header** — project name, namespace, time window, duration
- **OOMKilled pods** — each pod with its peak-vs-limit percentage
- **Pods that restarted** — restart count delta during the run (not absolute count — so it only shows restarts that happened during this FT, not before)
- **Near-limit pods** — pods that peaked ≥ 90% of their memory limit
- **Namespace quota** — start→end usage percentage per resource (shows whether quota was filling up during the run)
- **FailedScheduling events** — up to 10 events with their messages
- **Eviction events** — up to 10 events with their messages

### Thresholds

| Constant | Value | Used by |
|----------|-------|---------|
| `MEMORY_NEAR_LIMIT_PCT` | 90% | `near-memory-limit` signal |
| `QUOTA_PRESSURE_PCT` | 95% | `quota-pressure` signal |

Defined at `PostMortemAnalyzer.kt:16-17`.

### Caveats

- **`NO` doesn't always mean "definitely not resource"** — check `degradation.log`. If `metrics` was degraded (metrics-server unavailable), the analyzer couldn't compute peak memory, so `near-memory-limit` could never fire. If `events` was degraded, `FailedScheduling` and `Evicted` could never fire. A `NO` with degraded sources means lower confidence.
- **`metrics.jsonl` can miss spikes** — samples are taken every 10s, and metrics-server itself updates every ~15s. A memory spike lasting 2 seconds that triggers an OOMKill may never appear in the metrics. That's why the analyzer flags `OOMKill` from `pods.jsonl` independently, regardless of what `metrics.jsonl` shows.
- **Restart delta, not absolute count** — `restartCount` shows `max - min` during the run window. A pod that already had 5 restarts before the FT started won't be flagged unless it restarts again during the run.

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
