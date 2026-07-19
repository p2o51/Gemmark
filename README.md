# Gemmark

On-device LLM benchmark app (Android · Kotlin · Jetpack Compose · Material 3).
Implements the workflow frozen in **“03 · Gemmark — Gemini Nano 4 Benchmark App”**
and **“Gemmark 测试规范 v1”** (Notion).

## What it measures

Model load, TTFT, prefill, decode tok/s, E2E tok/s, sustained decay (thermal
drop), battery temperature, thermal status, and power draw (1 Hz
CURRENT_NOW × VOLTAGE, CHARGE_COUNTER cross-check).

## Standard Test (standard-v3, ML Kit R41)

One button, no configuration: the fixed suite runs on **both** Gemma-4
targets back-to-back — Preview·Fast first, then (after a thermal cooldown
of 20–75 s, released early once the battery reads ≤ 38 °C) Preview·Full —
inside a single session, live results on screen, ~12 minutes end-to-end.
After both suites, a **SWITCH LOAD phase** alternately loads each model four
times (Fast→Full→Fast→Full). AICore exposes no model-eviction API — `close()`
only unbinds the client (verified by bytecode against genai-prompt beta3).
Real-device data then falsified the displacement hypothesis: switch loads
measure ~400 ms on Tensor G5 and ~40 ms on Dimensity 9500 (non-blocking
warmup) — consistent with Fast/Full being **nested MatFormer variants
sharing one set of weights** (Gemma-3n-style E2B⊂E4B), so no gigabyte read
ever happens. The Load dimension is therefore *conditional*: samples under
500 ms are recorded as reference but excluded from scoring
(`MIN_VALID_SWITCH_MS`); it only scores if a device exhibits true
displacement loads. **Device Score = weighted geometric mean of Fast 45% ·
Full 45% · Load 10%**, missing dimensions renormalize (in practice today:
plain Fast/Full geomean). "Complete" coverage requires a *score* from both
models — a needs_retest segment yields `partial` with no Device Score.
On non-AICore devices a simulated demo runs instead (`mock`, never
comparable).

| Phase | Workload | Rounds | Measures |
|---|---|---|---|
| PREFILL | 2048 → 32 tok | 2 | long-context ingestion |
| DECODE | 32 → 512 tok | 2 | long-form generation |
| MAIN | 256 → 256 tok | 5 | balanced chat turn |
| STRUCTURED | typed extraction (@Generable) | 3 | constrained decoding, e2e (R41) |
| THINKING | logic puzzles, thinking on | 3 | reasoning window throughput (v4+) |
| IMAGE | 512×512 scene → 128 tok | 2 | vision |
| COMPARE | two scenes, spot differences | 1 | multi-image input (R41) |
| SUSTAINED | 256/256 continuous | ≥2 min wall (≤14) | thermal decay |

BUSY retries prefer the runtime's `retryDelay` hint over blind exponential
backoff; a round whose first attempt returns an empty response is retried
once (classed `busy_retried`, so it never enters the clean-TTFT basis).
IMAGE/COMPARE/STRUCTURED rounds do not feed the composite score; they
appear in the workload breakdown. The suite targets the Gemma-4 generation
only: Preview·Fast and Preview·Full are the two fixed engines.

Protocol per round: greedy sampling (temp 0 / top-k 1), 3 s interval,
BUSY → exponential backoff, quota → abort. Round status:
`ok` / `busy_retried` (valid) · `short` (<80 % target) · `fallback` (kept,
separate) · `error` (dropped). < 80 % valid rounds → **needs retest**.
Stats bases: decode/stability = MAIN+SUSTAINED series; prefill = PREFILL
clean rounds; response = clean-round TTFT of short-input workloads.
Export: one JSON per run (v1 field names + `workload_summary`) + CSV.

## Gemmark Score

Composite device-AI score for **G3 (256/256) completed runs** — the raw
sub-metrics and the spec's pure decode-median leaderboard are unaffected.

- **Device Score** (the headline, `device-v2`): weighted geometric mean of
  the Fast and Full per-model totals (45% each) and the model-switch Load
  sub-score (10%) from one dual-model session.
- Per-model sub-scores, each `1000 × measured ÷ anchor`:
  **Decode** (G3 decode median) · **Prefill** (clean-round prefill median) ·
  **Response** (clean-round TTFT median, inverted) · **Stability**
  (min(thermal_drop,1) × (1 − CV of decode))
- Total = weighted geometric mean: decode 35 %, reasoning 20 %, prefill/response/stability 15 % each; missing dimensions renormalize.
- **Anchor v2** (`gemmark-anchor-v2`, =1000 pts): a published constant spec,
  not a device — `10 tok/s decode · 500 tok/s prefill · 500 ms TTFT ·
  10 tok/s reasoning · zero decay`. Anchoring to constants keeps scores
  meaningful across devices/models/time; a device's headline is the
  Device Score of a complete dual-model session. Reasoning = thinking-mode WINDOW throughput
  (thought+answer tokens over the generation window), so thought/answer
  length ratios cannot distort the hardware rate.
- TTFT/prefill use **clean rounds only** (`status == ok`, zero retries):
  AICore may evict the model while BUSY, so retried rounds measure cold
  starts (observed on nano-v4-full).
- First formal v3 result (Pixel 10 Pro · nano-v4-fast · 2026-07-17):
  **3,267** — decode 3866 · prefill 4508 · response 2531 · stability 976 ·
  reasoning 5729 (thinking-window throughput 57 tok/s, ~47 % above plain
  decode on Tensor G5). Sustained held 38.7 tok/s with zero decay at
  thermal SEVERE / 42 °C.

Model variants are selected via the Prompt API's `ModelConfig`
(`releaseStage = STABLE | PREVIEW`, `preference = FAST | FULL`); on the current
AICore build these resolve to nano-v3 / nano-v4-fast / nano-v4-full.

## Architecture

```
core/       models (spec JSON schema), Statistics, PromptRepository (v1 corpus), TokenCounter
engine/     InferenceEngine abstraction
            ├─ MockInferenceEngine   — simulated streaming/BUSY/short/fallback/decay (works anywhere)
            └─ AiCoreInferenceEngine — Track A seam, WIRED ON DEVICE (see below)
telemetry/  TelemetryMonitor (battery/thermal), PowerSampler (1 Hz), PreflightChecker (固定条件)
runner/     BenchmarkRunner (round state machine) + BenchmarkSessionManager (survives navigation)
data/       RunRepository (one JSON file per run) + Exporters (JSON/CSV share sheet)
ui/         Multi-activity (Main/Run/Result/Welcome/Settings) · Canvas charts · GSF/GS Code · tonal M3
```

Manual DI in `di/AppContainer.kt`. Unit tests cover Statistics and the runner
state machine (`app/src/test`).

## Remaining tracks

1. **Track B** — LiteRT-LM Gemma engine for cross-vendor comparison.
2. **Gemma tokenizer** — optional offline recount (AICore native countTokens
   already used for all on-device runs).
3. Play closed-test packaging: release signing, versioning, store listing.

## Build

```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Requires the Android SDK (see `local.properties`). Min SDK 31, target 36.
