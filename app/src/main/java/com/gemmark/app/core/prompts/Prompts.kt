package com.gemmark.app.core.prompts

import com.gemmark.app.core.model.PromptGroup

/**
 * Prompt corpus v1.
 *
 * Spec requirements:
 *  - Text is frozen as v1 for the whole shooting window; do not edit in place —
 *    any change requires bumping [PROMPT_VERSION] and re-running affected groups.
 *  - Self-authored corpus (no copyrighted or likely-training-set text).
 *  - Input token targets are approximate until the Gemma 4 tokenizer is wired
 *    on device; calibrate the texts then (TODO(device): calibrate token counts).
 */
data class PromptSpec(
    val group: PromptGroup,
    /** Index within the group (G5 has three language variants). */
    val variant: Int,
    val language: String,
    val text: String,
    /** Whether the output must parse as JSON (group 4). */
    val validateJson: Boolean = false,
)

object PromptRepository {

    const val PROMPT_VERSION = "v1"

    /** All prompts of a group. Empty when the group is not yet implemented (G6). */
    fun prompts(group: PromptGroup): List<PromptSpec> = when (group) {
        PromptGroup.SHORT_IN_LONG_OUT -> listOf(PromptSpec(group, 0, "en", GROUP1_DECODE))
        PromptGroup.LONG_IN_SHORT_OUT -> listOf(PromptSpec(group, 0, "en", GROUP2_PREFILL))
        PromptGroup.FIXED_256 -> listOf(PromptSpec(group, 0, "en", GROUP3_MAIN))
        PromptGroup.JSON_TASK -> listOf(PromptSpec(group, 0, "en", GROUP4_JSON, validateJson = true))
        PromptGroup.TRILINGUAL -> listOf(
            PromptSpec(group, 0, "zh", GROUP5_ZH),
            PromptSpec(group, 1, "en", GROUP5_EN),
            PromptSpec(group, 2, "ja", GROUP5_JA),
        )
        PromptGroup.IMAGE -> listOf(PromptSpec(group, 0, "en", GROUP6_IMAGE))
        PromptGroup.THINKING -> listOf(
            PromptSpec(group, 0, "en", GROUP7_THINKING_A),
            PromptSpec(group, 1, "en", GROUP7_THINKING_B),
        )
        PromptGroup.COMPARE -> listOf(PromptSpec(group, 0, "en", GROUP8_COMPARE))
    }

    /** Prompt for a given round: groups with several variants rotate round-robin. */
    fun promptForRound(group: PromptGroup, roundIndex: Int): PromptSpec? {
        val all = prompts(group)
        if (all.isEmpty()) return null
        return all[roundIndex % all.size]
    }

    // ---------------------------------------------------------------------
    // Group 1 — short input (~32 tok), long output (512): decode throughput.
    // ---------------------------------------------------------------------
    private val GROUP1_DECODE = """
        Write a detailed field guide entry for an imaginary bird called the copper-crested
        dune wren. Describe its appearance, habitat, diet, song, nesting habits, migration
        pattern, and one surprising behavior. Use plain descriptive prose.
    """.trimIndent().collapse()

    // ---------------------------------------------------------------------
    // Group 2 — long input (~2048 tok), short output (32): prefill throughput.
    // A self-authored technical essay followed by a one-line summary request.
    // TODO(device): calibrate length to ~2048 Gemma tokens with the real tokenizer.
    // ---------------------------------------------------------------------
    private val GROUP2_PREFILL: String
        get() = GROUP2_DOCUMENT + "\n\nSummarize the document above in exactly one sentence."

    // ---------------------------------------------------------------------
    // Group 3 — fixed 256 in / 256 out: the main leaderboard group.
    // TODO(device): calibrate to exactly 256 Gemma tokens with the real tokenizer.
    // ---------------------------------------------------------------------
    private val GROUP3_MAIN = """
        The town of Miravel sits in a shallow valley where two seasonal rivers cross, and
        for two hundred years its people have built their houses on stilts of black cedar.
        Every spring the rivers rise, the streets become channels, and the market moves
        onto flat-bottomed boats that tie up to iron rings set into the chimneys. Children
        learn to row before they learn to ride bicycles. The town hall keeps a ledger of
        flood marks going back six generations, and the highest line, carved in the year
        of the double storm, is still half a hand above any flood since. Engineers from
        the capital once proposed a dam upstream, but the council voted it down, arguing
        that the floods carry the silt that keeps their orchards rich and their wells
        sweet. Instead they raised the stilts, widened the drains, and planted willows
        along both banks. Visitors often ask whether living with water is exhausting.
        The ferryman gives the same answer every time: the river is a neighbor, not an
        enemy, and neighbors are easier to live with when you learn their habits.

        Based on the passage above, explain how Miravel's approach to flooding differs
        from conventional flood control, what trade-offs the council accepted, and what
        lessons other towns could draw from it. Write approximately two hundred and fifty
        words of continuous prose.
    """.trimIndent().collapse()

    // ---------------------------------------------------------------------
    // Group 4 — JSON structured extraction (~128 in / 128 out).
    // Output is validated for JSON legality by the runner.
    // ---------------------------------------------------------------------
    private val GROUP4_JSON = """
        Extract the order details from the following message and return ONLY a JSON object
        with the keys "customer", "items" (array of objects with "name", "quantity",
        "unit_price"), "currency", "delivery_date", and "express" (boolean). No prose,
        no code fences.

        Message: Hi, this is Ren Okabe. Please send 3 boxes of jasmine tea at 12.50 each
        and 2 ceramic teapots at 34 each to my studio. I need them delivered by March 14,
        and yes please use express shipping. Everything in euros as usual.
    """.trimIndent().collapse()

    // ---------------------------------------------------------------------
    // Group 5 — same instruction in zh / en / ja (~64 tok each, 128 out).
    // ---------------------------------------------------------------------
    private val GROUP5_ZH = """
        请解释为什么高铁列车进入隧道时车内乘客会感到耳朵不适，并说明车头的流线型设计
        如何缓解这一现象。请用大约一百字回答，面向没有工程背景的读者。
    """.trimIndent().collapse()

    private val GROUP5_EN = """
        Explain why passengers on a high-speed train feel pressure in their ears when the
        train enters a tunnel, and how the streamlined nose of the train reduces this
        effect. Answer in about one hundred words for readers without an engineering
        background.
    """.trimIndent().collapse()

    private val GROUP5_JA = """
        高速鉄道の列車がトンネルに入るとき、乗客が耳の圧迫感を覚えるのはなぜですか。
        また、先頭車両の流線型デザインがこの現象をどのように緩和するのか説明してください。
        工学の知識がない読者向けに、約百字で答えてください。
    """.trimIndent().collapse()

    // ---------------------------------------------------------------------
    // Group 6 — multimodal: fixed 512×512 bundled scene + short question.
    // The image (res/drawable/gemmark_test_scene) is deterministic and
    // self-authored: house, tree, sun, clouds, black cat.
    // ---------------------------------------------------------------------
    private val GROUP6_IMAGE = """
        Describe this image in two or three sentences, mentioning every object
        you can identify.
    """.trimIndent().collapse()

    // ---------------------------------------------------------------------
    // Group 7 — thinking mode (Nano v4+): deterministic multi-step puzzles.
    // Answers are unique so reasoning quality is verifiable by eye; the
    // benchmark only scores window throughput, never correctness.
    // ---------------------------------------------------------------------
    private val GROUP7_THINKING_A = """
        Ana, Bo and Cy each keep exactly one pet: a cat, a dog, or a parrot.
        Ana never touches animals with fur. Bo walks his pet on a leash twice
        a day. Work out who owns which pet. Think through the constraints step
        by step, then state the three owner–pet pairs in one final line.
    """.trimIndent().collapse()

    private val GROUP7_THINKING_B = """
        A tank holds 240 liters and currently contains 60. Pump A adds 8 liters
        per minute; drain B removes 3 liters per minute. Both run continuously.
        Compute how many minutes until the tank is exactly full, checking your
        arithmetic step by step, then state the answer as a single number of
        minutes on the final line.
    """.trimIndent().collapse()

    // ---------------------------------------------------------------------
    // Group 8 — two-image comparison (day scene vs night variant).
    // ---------------------------------------------------------------------
    private val GROUP8_COMPARE = """
        Compare these two images and list every difference you can find
        between them, one difference per line.
    """.trimIndent().collapse()

    // ---------------------------------------------------------------------
    // Group 2 document body (self-authored).
    // ---------------------------------------------------------------------
    private val GROUP2_DOCUMENT = """
        A Practical History of the Rooftop Water Tank

        For most of the twentieth century, the skyline of any dense city was punctuated by
        squat wooden barrels standing on steel legs above the rooflines. The rooftop water
        tank is one of those pieces of infrastructure so ordinary that it becomes
        invisible, yet the reasoning behind it is a compact lesson in engineering
        economics, and the story of how it survived a century of newer technology explains
        a great deal about why cities keep old solutions alive.

        The problem the tank solves is pressure. Municipal water mains deliver water at a
        pressure sufficient to climb perhaps five or six storeys. Above that height, the
        column of water in the building's own pipes weighs more than the main can push
        against, and the taps on the upper floors slow to a trickle. The obvious remedy is
        a pump, but a pump sized to serve a morning rush of showers on forty floors would
        be large, expensive, and idle for most of the day. Worse, it would need a backup,
        because a stuck pump on a summer afternoon is not an inconvenience but an
        emergency.

        The tank reframes the problem. Instead of pumping water when people demand it, the
        building pumps water slowly and steadily into a reservoir on the roof, using a
        small motor that runs a few hours a day. Gravity then does the delivery work.
        Every apartment below the tank enjoys steady pressure that no burst of demand can
        disturb, because the tank holds a buffer measured in hours, not seconds. The pump
        can fail quietly at midnight and be repaired at leisure the next morning while the
        residents shower, none the wiser. In accounting terms, the tank converts a peak
        load problem into a base load problem, and base load is always cheaper.

        Wood seems a strange material for the twenty-first century, but the choice is
        less sentimental than it appears. A wooden tank is assembled on the roof from
        staves light enough to carry up a stairwell, needs no crane, no welding permit,
        and no corrosion protection. Kept wet, the staves swell against their steel hoops
        and seal themselves. A steel tank lasts longer on paper, but it arrives as a
        single heavy object, demands structural reinforcement, and rusts from the inside
        unless it is lined, inspected, and relined on a schedule that few building
        managers keep. The wooden tank's fifteen-year lifespan is not a defect; it is a
        maintenance schedule that enforces itself, because a tank that must be rebuilt is
        a tank that gets inspected.

        The economics of the rooftop tank also shaped the buildings beneath them. Because
        gravity pressure depends only on height difference, the floors directly under the
        tank get the weakest showers, and older buildings often reserved those floors for
        storage or staff. When penthouses became fashionable, plumbers added small
        booster pumps for the top two floors alone, a hybrid that kept the tank's
        economics intact while flattering the rent roll. Fire codes noticed the tanks
        too: a reservoir on the roof is a ready supply for standpipes, and many cities
        wrote the tanks into their fire regulations, which is one reason they persisted
        even as electric pumps became cheap and reliable.

        Critics of the tanks point, fairly, at water quality. An unsealed or poorly
        maintained tank can admit dust, insects, and worse, and periodic scandals about
        neglected tanks have pushed cities toward stricter inspection regimes rather
        than replacement, because the alternative — pressurized systems with variable
        speed pumps — trades a visible, inspectable risk for an invisible, electrical
        one. Modern pressure systems are excellent, and most new towers use them, but
        they bind the building's water supply to its electrical supply. A blackout that
        stops the elevators also stops the showers, exactly when the stairwells make
        every trip upstairs count double. The old tank, indifferent to the grid, keeps
        delivering for hours on stored gravity alone.

        The deeper lesson of the rooftop tank is that infrastructure survives when its
        failure modes are gentle. The tank fails slowly, visibly, and partially; the
        pump fails suddenly, invisibly, and completely. Engineers call this graceful
        degradation, and city dwellers call it nothing at all, because infrastructure
        that degrades gracefully never makes the evening news. When you next see a
        wooden barrel on a rooftop, consider that it embodies a century of quiet
        arithmetic about peaks and averages, about the price of pressure, and about the
        virtue of failing softly — arithmetic that still holds every time someone on the
        fourteenth floor turns a tap and expects, without thinking, that water will come.
    """.trimIndent()

    /**
     * Source formatting is hard-wrapped for readability; the frozen prompt is the
     * collapsed form — single newlines become spaces, blank lines stay paragraph breaks.
     */
    private fun String.collapse(): String =
        split("\n\n").joinToString("\n\n") { paragraph ->
            paragraph.lines().joinToString(" ") { it.trim() }.trim()
        }
}
