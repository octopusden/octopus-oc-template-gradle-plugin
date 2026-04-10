# Background pod log streaming

## Why

`oc logs <pod>` fails (or returns nothing) once the pod is gone. Before this feature, any pod that was OOMKilled, evicted, or force-deleted mid-run would leave behind an empty `.log` file — exactly the runs where the logs mattered most.

The fix: as soon as a managed pod appears, start `oc logs -f` in a background thread and pipe its output straight into the final log file. If the pod dies before `OcLogsTask` runs, the file is already populated and we simply don't overwrite it.

(Feature added in commit `922f37e`.)

## Lifecycle

All logic lives in `OcTemplateService` (one instance per managed service).

1. **Start** — `startLogStreaming()` at `src/main/kotlin/org/octopusden/octopus/oc/template/plugins/gradle/service/OcTemplateService.kt:285`.
   - Called from `create()` (after `oc create`) and repeatedly from `waitReadiness()` on every poll — so newly-appearing pods get picked up during rolling updates / slow startup.
   - Spawns one daemon thread per pod, named `log-stream-<pod>`. Tracked in `streamingProcesses` and `streamingThreads` (`ConcurrentHashMap`, keyed by pod name).
   - Idempotent per pod: `if (streamingProcesses.containsKey(podName)) return@forEach`.

2. **Stream** — `streamLogForPod()` at `OcTemplateService.kt:301`.
   - Runs `oc logs -f <pod> --all-containers -n <ns>` via `ProcessBuilder.redirectErrorStream(true)`.
   - Pipes stdout directly into the pod's `.log` file via `input.copyTo(output)`.
   - Retry loop: up to **10 attempts**, **3s** between attempts. Retries cover the gap between pod creation and containers actually being ready to stream, plus transient `oc` failures.
   - Every attempt checks `Thread.currentThread().isInterrupted` so stop is prompt.
   - Gives up silently (debug log only) after 10 failed attempts — streaming must never fail the build.

3. **Stop** — `stopLogStreaming()` at `OcTemplateService.kt:336`.
   - `destroyForcibly()` on every process, then `interrupt()` + `join(5000)` on every thread.
   - Called from three places:
     - `create()` at the very start (clean slate before a new run)
     - `logs()` at the very start (flush streaming before the final snapshot fetch)
     - `close()` (BuildService shutdown — safety net)

4. **Final snapshot** — `logs()` at `OcTemplateService.kt:264`.
   - Runs a one-shot `oc logs -n <ns> <pod>` per managed pod.
   - **If** the one-shot succeeds with non-empty output → overwrite the file with that snapshot (authoritative final view of a healthy pod).
   - **Else if** the streaming log file already exists and is non-empty → leave it alone and log `"oc logs failed or empty for '<pod>', keeping streaming log"`. This is the branch the `testKilledPodPreservesStreamingLog` integration test asserts on.
   - **Else** → write whatever the one-shot returned (possibly empty) so the file at least exists.

## File layout

```
build/<workDir>/logs/
  <deployment-prefix>-<service>.log   # one file per managed pod
```

The file path is computed in the `init` block at `OcTemplateService.kt:56-65` (`logs = dir("logs")`) and in `startLogStreaming` / `logs()` as `logs.file("$podName.log")`.

## Failure modes

| Scenario                               | Behavior                                                                 |
|----------------------------------------|--------------------------------------------------------------------------|
| Pod OOMKilled mid-run                  | Streaming thread's `oc logs -f` exits; final `logs()` falls through to "keeping streaming log" branch. |
| `oc logs -f` dies transiently          | Retried up to 10×, 3s apart.                                              |
| Pod never becomes ready                | `waitReadiness` fails the build; `stopLogStreaming()` runs via `close()`. Whatever was streamed so far (e.g. init/crashloop output) is preserved. |
| Build cancelled                        | `close()` runs on every BuildService → threads interrupted, processes `destroyForcibly`'d. |
| Streaming thread throws                | Caught in `streamLogForPod`'s per-attempt `catch (e: Exception)` → debug log, retry. |

## Key code references

- `startLogStreaming` — `OcTemplateService.kt:285`
- `streamLogForPod` (retry loop) — `OcTemplateService.kt:301`
- `stopLogStreaming` — `OcTemplateService.kt:336`
- `logs()` (final snapshot + "keeping streaming log" branch) — `OcTemplateService.kt:264`
- `close()` safety-net stop — `OcTemplateService.kt:380`
- Integration test covering the kill scenario — `OcTemplatePluginTest.kt:238` (`testKilledPodPreservesStreamingLog`)
