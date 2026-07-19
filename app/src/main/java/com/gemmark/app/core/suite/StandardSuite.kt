package com.gemmark.app.core.suite

import com.gemmark.app.core.model.PromptGroup

/**
 * The Gemmark Standard Test: a fixed, versioned suite of representative
 * workloads. This IS the benchmark — users never pick prompts or rounds;
 * they press Run and watch live results, like any established benchmark.
 *
 * v3 (ML Kit R41): built for the Gemma-4 generation only (nano-v4-fast /
 * nano-v4-full are the two fixed targets). Adds constrained decoding
 * (STRUCTURED), thinking-mode reasoning throughput (THINKING), and a
 * two-image comparison round (COMPARE). Budget stays 7–8 minutes on
 * nano-v4-full; the sustained phase is bounded by WALL TIME so every device
 * receives comparable thermal stress.
 */
data class SuitePhase(
    /** Stable id recorded into each round's `workload` field. */
    val id: String,
    /** Short label for the run screen ("PREFILL", "DECODE"…). */
    val label: String,
    /** Human sentence for the log/result breakdown. */
    val description: String,
    val group: PromptGroup,
    /** Rounds to run; the sustained phase may extend past this (see minWallMs). */
    val rounds: Int,
    /** Keep issuing rounds until the phase has consumed at least this wall time. */
    val minWallMs: Long = 0,
    /** Hard cap so a very fast engine cannot run away during timed phases. */
    val maxRounds: Int = rounds,
    /**
     * Whether the "<80 % of max tokens → short" rule applies. Extraction-,
     * description- and reasoning-style workloads legitimately stop early;
     * their success criterion is content, not length.
     */
    val applyShortRule: Boolean = true,
    /** Number of bundled test images attached to each request (0, 1 or 2). */
    val imageCount: Int = 0,
    /** R41 thinking mode on for this phase's requests. */
    val thinking: Boolean = false,
    /** Constrained decoding via the typed (structured output) API. */
    val structured: Boolean = false,
    /**
     * If the first round fails (capability missing on this model), skip the
     * rest of the phase and exclude it from validity instead of failing the run.
     */
    val optional: Boolean = false,
)

object StandardSuite {

    const val VERSION = "standard-v3"

    /** Warm-up: excluded from all statistics. */
    const val WARMUP_ROUNDS = 1

    /** Fixed pause between rounds (spec v1 kept at 3 s). */
    const val ROUND_INTERVAL_MS = 3_000L

    val phases: List<SuitePhase> = listOf(
        SuitePhase(
            id = "prefill",
            label = "PREFILL",
            description = "Long context ingestion (2048 → 32 tok)",
            group = PromptGroup.LONG_IN_SHORT_OUT,
            rounds = 2,
        ),
        SuitePhase(
            id = "decode",
            label = "DECODE",
            description = "Long-form generation (32 → 512 tok)",
            group = PromptGroup.SHORT_IN_LONG_OUT,
            rounds = 2,
        ),
        SuitePhase(
            id = "main",
            label = "MAIN",
            description = "Balanced chat turn (256 → 256 tok)",
            group = PromptGroup.FIXED_256,
            rounds = 5,
        ),
        SuitePhase(
            id = "structured",
            label = "STRUCTURED",
            description = "Constrained decoding into a typed schema (R41)",
            group = PromptGroup.JSON_TASK,
            rounds = 3,
            applyShortRule = false,
            structured = true,
            optional = true,
        ),
        SuitePhase(
            id = "thinking",
            label = "THINKING",
            description = "Multi-step reasoning with thinking mode (Nano v4+)",
            group = PromptGroup.THINKING,
            rounds = 3,
            applyShortRule = false,
            thinking = true,
            optional = true,
        ),
        SuitePhase(
            id = "image",
            label = "IMAGE",
            description = "Vision: describe a fixed 512×512 scene (→ 128 tok)",
            group = PromptGroup.IMAGE,
            rounds = 2,
            applyShortRule = false,
            imageCount = 1,
            optional = true,
        ),
        SuitePhase(
            id = "compare",
            label = "COMPARE",
            description = "Vision: spot differences between two scenes",
            group = PromptGroup.COMPARE,
            rounds = 1,
            applyShortRule = false,
            imageCount = 2,
            optional = true,
        ),
        SuitePhase(
            id = "sustained",
            label = "SUSTAINED",
            description = "Continuous 256/256 load for thermal behaviour (≥2 min)",
            group = PromptGroup.FIXED_256,
            rounds = 3,
            minWallMs = 120_000,
            maxRounds = 14,
        ),
    )

    /** Planned measured rounds (sustained counted at its minimum). */
    val plannedRounds: Int = phases.sumOf { it.rounds }

    /** Workloads whose rounds feed the decode/stability series (all 256/256). */
    val DECODE_BASIS = setOf("main", "sustained")

    /** Workloads whose clean-round TTFT feeds the response score (short inputs). */
    val RESPONSE_BASIS = setOf("main", "sustained")

    /** Workload whose rounds feed the prefill score. */
    const val PREFILL_BASIS = "prefill"

    /** Workload whose window throughput feeds the reasoning score. */
    const val REASONING_BASIS = "thinking"
}
