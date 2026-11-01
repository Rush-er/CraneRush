package it.bitwire.craterush.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EngineTest {

    /** Plays with random input for a while, drawing every frame. */
    private fun play(e: Engine, rng: Random, seconds: Float): Set<Engine.State> {
        val fb = FrameBuffer(e.w, e.h)
        val seen = HashSet<Engine.State>()
        var t = 0f
        while (t < seconds) {
            when (rng.nextInt(12)) {
                0 -> e.pressJump()
                1 -> e.pressStep(-1)
                2 -> e.pressStep(1)
                3 -> { e.leftHeld = rng.nextBoolean(); e.rightHeld = !e.leftHeld }
                4 -> { e.leftHeld = false; e.rightHeld = false }
            }
            e.update(1f / 60f)
            e.draw(fb)
            e.events.clear()
            seen += e.state
            if (e.state == Engine.State.GAME_OVER || e.state == Engine.State.WIN) break
            t += 1f / 60f
        }
        return seen
    }

    @Test fun allSizesDifficultiesAndLevels() {
        for (wh in Warehouse.entries) for (d in Difficulty.entries) for (start in listOf(1, 4, 5, 8, 10, 15, 20)) {
            repeat(3) { seed ->
                val e = Engine(Random(seed * 31 + start))
                e.settings.warehouse = wh
                e.settings.difficulty = d
                e.bestLevel = Engine.MAX_LEVEL
                e.settings.startLevel = start
                e.newGame()
                assertEquals(start, e.level)
                assertEquals(wh.cols * Engine.CELL + 4, e.w)
                play(e, Random(seed), 240f)
            }
        }
    }

    @Test fun menusNavigateAndChangeSettings() {
        val e = Engine(Random(1))
        val fb = FrameBuffer(e.w, e.h)
        assertEquals(Engine.State.TITLE, e.state)
        e.menuMove(1); e.menuSelect()
        assertEquals(Engine.State.SETTINGS, e.state)
        e.menuAdjust(1)                       // difficulty -> HARD
        assertEquals(Difficulty.HARD, e.settings.difficulty)
        e.menuMove(1); e.menuAdjust(-1)       // warehouse LARGE -> MEDIUM
        assertEquals(Warehouse.MEDIUM, e.settings.warehouse)
        assertEquals(Warehouse.MEDIUM.cols * Engine.CELL + 4, e.w)
        assertTrue(e.dirty)
        e.draw(FrameBuffer(e.w, e.h))
        assertTrue(e.back())
        assertEquals(Engine.State.TITLE, e.state)
        e.tap(e.w / 2f, Engine.TITLE_Y.toFloat())
        assertTrue(e.isRunning)
        e.pause()
        assertEquals(Engine.State.PAUSED, e.state)
        assertTrue(e.back())
        assertEquals(Engine.State.TITLE, e.state)
        assertTrue(!e.back())
        e.draw(fb)
    }

    @Test fun bossTakesDamageFromRows() {
        // Lazy player on a boss level: the game must end in GAME_OVER or WIN without crashing,
        // and passes through the boss banner.
        val e = Engine(Random(7))
        e.bestLevel = 20; e.settings.startLevel = 5
        e.newGame()
        assertEquals(Engine.State.BANNER, e.state)
        val seen = play(e, Random(3), 600f)
        assertTrue(Engine.State.PLAYING in seen)
    }

    private fun clearRow(e: Engine) {
        for (c in 0 until e.cols) e.testPlace(c, Engine.WOOD)
        e.update(0.001f)                                  // detect the row
        assertEquals(Engine.State.CLEARING, e.state)
        repeat(40) { e.update(1f / 60f) }                 // finish the clear
    }

    @Test fun rowsDamageBossAndAdvanceLevel() {
        val e = Engine(Random(2))
        e.bestLevel = 20; e.settings.startLevel = 5
        e.newGame(); e.testSkipBanner()
        val hp = e.testBossHp!!
        assertEquals(BossKind.FOREMAN.baseHp, hp)
        clearRow(e)
        assertEquals(hp - 1, e.testBossHp)
        repeat(hp - 1) { clearRow(e) }
        assertEquals(0, e.testBossHp)
        repeat(200) { e.update(1f / 60f) }               // death animation
        assertEquals(6, e.level)
        assertEquals(null, e.testBossHp)
    }

    @Test fun beatingFinalBossWins() {
        val e = Engine(Random(4))
        e.settings.difficulty = Difficulty.EASY
        e.bestLevel = 20; e.settings.startLevel = 20
        e.newGame(); e.testSkipBanner()
        repeat(e.testBossHp!!) { clearRow(e) }
        repeat(200) { e.update(1f / 60f) }
        assertEquals(Engine.State.WIN, e.state)
        assertTrue(e.hiScore > 0)
    }

    @Test fun normalLevelAdvancesAfterLines() {
        val e = Engine(Random(5))
        e.newGame(); e.testSkipBanner()
        repeat(Difficulty.NORMAL.linesPerLevel) { clearRow(e) }
        assertEquals(2, e.level)
        assertEquals(Engine.State.BANNER, e.state)
    }

    @Test fun bombDestroysNeighboursAndCratesFall() {
        val e = Engine(Random(6))
        e.newGame(); e.testSkipBanner()
        // column 3: wood, bomb, wood, wood ; columns 2 and 4: two wood each
        e.testPlace(2, Engine.WOOD); e.testPlace(2, Engine.WOOD)
        e.testPlace(4, Engine.WOOD); e.testPlace(4, Engine.WOOD)
        e.testPlace(3, Engine.WOOD); e.testPlace(3, Engine.BOMB, 0.001f)
        e.testPlace(3, Engine.WOOD); e.testPlace(3, Engine.WOOD)
        e.update(1f / 60f)
        assertEquals(1, e.testHeight(2))
        assertEquals(1, e.testHeight(4))
        assertEquals(0, e.testHeight(3))                  // (3,0) destroyed, the crate on top is now loose
        repeat(120) { e.update(1f / 60f) }
        assertEquals(1, e.testHeight(3))                  // ...and has landed
        assertEquals(Engine.WOOD, e.testCell(3, 0))
    }

    @Test fun bombKillsAdjacentPlayer() {
        val e = Engine(Random(8))
        e.newGame(); e.testSkipBanner()
        val p = e.testPlayerCol()
        e.testPlace(p + 1, Engine.BOMB, 0.001f)
        e.update(1f / 60f)
        assertEquals(Engine.State.DYING, e.state)
    }

    @Test fun jumpPushesCrateStackedOnAnother() {
        val e = Engine(Random(9))
        e.newGame(); e.testSkipBanner()
        val p = e.testPlayerCol()
        e.testPlace(p + 1, Engine.WOOD); e.testPlace(p + 1, Engine.WOOD)
        e.pressJump(); e.pressStep(1)                     // tap jump, then right while rising
        repeat(30) { e.update(1f / 60f) }
        assertEquals(1, e.testHeight(p + 1))              // top crate pushed off the stack...
        assertEquals(1, e.testHeight(p + 2))              // ...and landed in the next column
        assertEquals(p + 1, e.testPlayerCol())
    }

    @Test fun holdingDirectionAndJumpingPushesStackedCrate() {
        val e = Engine(Random(10))
        e.newGame(); e.testSkipBanner()
        val p = e.testPlayerCol()
        e.testPlace(p + 1, Engine.WOOD); e.testPlace(p + 1, Engine.WOOD)
        e.rightHeld = true; e.pressJump()
        repeat(30) { e.update(1f / 60f) }
        assertEquals(1, e.testHeight(p + 1))
    }

    @Test fun steelOnStackIsNotPushedFromJump() {
        val e = Engine(Random(11))
        e.newGame(); e.testSkipBanner()
        val p = e.testPlayerCol()
        e.testPlace(p + 1, Engine.WOOD); e.testPlace(p + 1, Engine.STEEL)
        e.pressJump(); e.pressStep(1)
        repeat(30) { e.update(1f / 60f) }
        assertEquals(2, e.testHeight(p + 1))
        assertEquals(p, e.testPlayerCol())
    }

    @Test fun jumpingIntoFallingCrateSmashesIt() {
        val e = Engine(Random(12))
        e.newGame(); e.testSkipBanner()
        val p = e.testPlayerCol()
        e.testDrop(p, 4f)
        e.pressJump()
        repeat(40) { e.update(1f / 60f) }
        assertEquals(Engine.State.PLAYING, e.state)
        assertEquals(0, e.testHeight(p))
        assertEquals(0, e.testLooseCount)
        assertTrue(e.score >= 5)
    }

    @Test fun fallingCrateStillKillsStandingPlayer() {
        val e = Engine(Random(13))
        e.newGame(); e.testSkipBanner()
        e.testDrop(e.testPlayerCol(), 4f)
        repeat(40) { e.update(1f / 60f) }
        assertEquals(Engine.State.DYING, e.state)
    }

    @Test fun steelCrateKillsJumpingPlayer() {
        val e = Engine(Random(14))
        e.newGame(); e.testSkipBanner()
        e.testDrop(e.testPlayerCol(), 4f, Engine.STEEL)
        e.pressJump()
        repeat(40) { e.update(1f / 60f) }
        assertEquals(Engine.State.DYING, e.state)
    }
}
