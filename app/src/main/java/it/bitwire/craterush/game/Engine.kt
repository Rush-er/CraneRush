package it.bitwire.craterush.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.random.Random

enum class Difficulty(
    val label: String,
    val speed: Float,         // crane / boss speed multiplier
    val fall: Float,          // crate fall speed multiplier
    val spawn: Float,         // crane spawn interval multiplier
    val craneBonus: Int,      // extra simultaneous cranes
    val craneCap: Int,        // max simultaneous cranes
    val linesPerLevel: Int,
    val bossHp: Int,          // added to each boss' base HP
    val special: Float,       // steel / bomb crate chance multiplier
) {
    EASY("EASY", 0.8f, 0.8f, 1.35f, 0, 2, 4, -1, 0.6f),
    NORMAL("NORMAL", 1f, 1f, 1f, 0, 3, 5, 0, 1f),
    HARD("HARD", 1.2f, 1.15f, 0.8f, 0, 4, 5, 1, 1.3f),
    INSANE("INSANE", 1.4f, 1.3f, 0.65f, 1, 5, 6, 2, 1.6f),
}

enum class Mode(val label: String) {
    CAMPAIGN("CAMPAIGN"),     // 20 levels with bosses
    BOMB_RUSH("BOMB RUSH"),   // survival: mostly bomb crates, speeds up over time
    VERSUS("CRANE VS"),       // you are the crane, a CPU worker plays against you
}

/** Warehouse (playfield) size. */
enum class Warehouse(val label: String, val cols: Int, val rows: Int) {
    SMALL("SMALL", 12, 8),
    MEDIUM("MEDIUM", 14, 10),
    LARGE("LARGE", 16, 11),
    HUGE("HUGE", 20, 12),
}

class Settings(
    var mode: Mode = Mode.CAMPAIGN,
    var difficulty: Difficulty = Difficulty.NORMAL,
    var warehouse: Warehouse = Warehouse.LARGE,
    var startLevel: Int = 1,
    var sound: Boolean = true,
    var ghosting: Boolean = true,
    var buttons: Boolean = true,      // on-screen buttons
)

enum class BossKind(val label: String, val baseHp: Int) {
    FOREMAN("FOREMAN", 3),
    TWIN("TWIN HOOK", 4),
    STEELER("STEELER", 4),
    MEGA("MEGA CRANE", 6),
}

/**
 * Crate-stacking arcade engine. Grid-based, logical resolution depends on the warehouse size,
 * no Android deps.
 *
 * Rules:
 *  - Cranes travel along the top rail and drop crates on random columns.
 *  - The worker walks, jumps one crate high, and pushes a single crate
 *    (only if nothing sits on it, the cell beyond is free and it isn't steel).
 *  - A complete bottom row is cleared and everything shifts down.
 *  - Bomb crates explode a few seconds after landing, destroying the crates around them.
 *  - Every 5th level is a boss: each cleared row damages it.
 *  - A crate landing on the worker, a blast hitting him, or a column reaching the rail ends the game.
 */
class Engine(private val rng: Random = Random.Default) {

    companion object {
        const val CELL = 8
        const val FIELD_X = 2
        const val RAIL_Y = 16
        const val TOP = 32                  // playfield top (y)

        const val STEP_TIME = 0.11f
        const val RISE_SPEED = 9f           // rows/s
        const val HANG_TIME = 0.17f
        const val PLAYER_FALL = 10f         // rows/s
        const val CLEAR_TIME = 0.5f
        const val DIE_TIME = 1.6f
        const val BANNER_TIME = 1.8f
        const val BOMB_FUSE = 4f
        const val BLAST_TIME = 0.35f
        const val BOSS_DEATH_TIME = 1.8f
        const val MAX_LEVEL = 20
        const val BOSS_EVERY = 5
        const val STEEL_FROM = 4
        const val BOMB_FROM = 8

        const val EMPTY = 0
        const val WOOD = 1
        const val STEEL = 2
        const val BOMB = 3

        const val RUSH_FUSE = 3f
        const val RUSH_LEVEL_TIME = 30f      // s per speed level in bomb rush
        const val VS_TIME = 90f
        const val VS_RELOAD = 1.2f
        const val VS_CRANE_SPEED = 60f
        const val VS_BOMB_EVERY = 5

        const val TITLE_Y = 38
        const val SET_Y = 17
        const val SET_STEP = 10
        const val MENU_STEP = 10
        const val SETTINGS_ITEMS = 7
        private val SETTING_LABELS = listOf("DIFFICULTY", "WAREHOUSE", "START LEVEL", "SOUND", "GHOSTING", "BUTTONS", "BACK")

        private const val EPS = 1e-4f
        private val PLUS = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)

        fun isBossLevel(l: Int) = l % BOSS_EVERY == 0
    }

    enum class State { TITLE, SETTINGS, PLAYING, PAUSED, BANNER, CLEARING, DYING, GAME_OVER, WIN }
    enum class Sfx { START, JUMP, PUSH, LAND, CLEAR, LEVEL, DEATH, MENU, BOSS, HIT, BOSS_DOWN, BOOM, WIN, SMASH }
    private enum class Phase { GROUND, RISE, HANG, FALL }
    private enum class BPhase { MOVE, AIM, COOL, DEAD }

    class Loose(var col: Int, var r: Float, val fromCol: Int, var slideT: Float, val type: Int, val fuse: Float)
    class Crane(var x: Float, val dir: Int, val target: Int, val type: Int, var carrying: Boolean = true)
    private class Blast(val c: Int, val r: Int, var t: Float = BLAST_TIME)
    private class Boss(val kind: BossKind, var hp: Int) {
        val maxHp = hp
        var x = -2f * CELL
        var anchor = 0
        val drops = IntArray(2)
        var phase = BPhase.MOVE
        var t = 0f
        var count = 0
        var hitT = 0f
    }

    // ---- settings & persistence ----
    val settings = Settings()
    /** Indexed by [hiIndex]. */
    val hiScores = IntArray(Mode.entries.size * Difficulty.entries.size)
    var bestLevel = 1
    /** Set when settings / hi scores / best level change; the host saves and clears it. */
    var dirty = false
    val hiScore get() = hiScores[hiIndex(settings.mode, settings.difficulty)]
    fun hiIndex(m: Mode, d: Difficulty) = m.ordinal * Difficulty.entries.size + d.ordinal
    private val mode get() = settings.mode
    private val diff get() = settings.difficulty

    // ---- public state ----
    var state = State.TITLE; private set
    var score = 0; private set
    var lines = 0; private set
    var level = 1; private set
    val events = ArrayDeque<Sfx>()

    // ---- dimensions (depend on warehouse size) ----
    var cols = 0; private set
    var rows = 0; private set
    var w = 0; private set
    var h = 0; private set
    private val crateHangR get() = rows + 0.5f   // crate bottom in rows while hanging under a crane

    // ---- input (set by the view) ----
    var leftHeld = false
    var rightHeld = false
    private var queuedStep = 0
    private var queuedJump = false

    // ---- world ----
    private var grid = Array(0) { IntArray(0) }
    private var fuse = Array(0) { FloatArray(0) }
    private var heights = IntArray(0)
    private val loose = ArrayList<Loose>()
    private val cranes = ArrayList<Crane>()
    private val blasts = ArrayList<Blast>()
    private val toExplode = ArrayList<Pair<Int, Int>>()
    private var boss: Boss? = null
    private var spawnT = 0f
    private var levelLines = 0
    private var pendingLevelUp = false
    private var elapsed = 0f

    // ---- versus: the player's crane and the CPU worker ----
    private var vsX = 0f
    private var vsReload = 0f
    private var vsDrops = 0
    private var vsDrop = false
    private var vsResult = ""
    private var vsReason = ""
    private var aiT = 0f
    private var aiTarget = 0
    private var aiTrapped = false
    private val aiSeen = HashSet<Loose>()
    private val danger = FloatArray(32)
    private val vsCol get() = ((vsX - FIELD_X + CELL / 2f) / CELL).toInt().coerceIn(0, cols - 1)
    private val vsNextType get() = if ((vsDrops + 1) % VS_BOMB_EVERY == 0) BOMB else WOOD
    private val vsRowsToWin get() = when (diff) { Difficulty.EASY -> 6; Difficulty.NORMAL -> 5; else -> 4 }
    private val aiReaction get() = when (diff) {
        Difficulty.EASY -> 0.75f; Difficulty.NORMAL -> 0.4f; Difficulty.HARD -> 0.22f; Difficulty.INSANE -> 0.1f
    }
    private val aiSmash get() = when (diff) {
        Difficulty.EASY -> 0.1f; Difficulty.NORMAL -> 0.4f; Difficulty.HARD -> 0.7f; Difficulty.INSANE -> 0.95f
    }
    private val aiMistake get() = when (diff) {
        Difficulty.EASY -> 0.25f; Difficulty.NORMAL -> 0.1f; Difficulty.HARD -> 0.03f; Difficulty.INSANE -> 0f
    }
    private val fuseTime get() = if (mode == Mode.BOMB_RUSH) RUSH_FUSE else BOMB_FUSE

    // ---- player ----
    private var px = 0
    private var py = 0f
    private var phase = Phase.GROUND
    private var riseTo = 0f
    private var hangT = 0f
    private var stepping = false
    private var stepFrom = 0
    private var stepT = 0f
    private var facing = 1
    private var walkFrame = 0

    // ---- ui ----
    private var timer = 0f       // state timer
    private var blinkT = 0f
    private var cursor = 0
    private var pausedFrom = State.PLAYING
    private var bannerLines = listOf<String>()

    // ---- level tuning ----
    private val craneSpeed get() = (22f + level * 3f) * diff.speed                       // px/s
    private val crateFall get() = (5.5f + level * 0.45f) * diff.fall                      // rows/s
    private val spawnEvery get() = max(0.55f, (3.4f - level * 0.15f) * diff.spawn)        // s
    private val maxCranes get() = min(diff.craneCap, 1 + (level - 1) / 5 + (cols - 12) / 4 + diff.craneBonus)

    init { applySize() }

    /** Re-allocates the world for the current warehouse size. */
    fun applySize() {
        val wh = settings.warehouse
        cols = wh.cols; rows = wh.rows
        w = FIELD_X * 2 + cols * CELL
        h = TOP + rows * CELL
        grid = Array(cols) { IntArray(rows + 4) }
        fuse = Array(cols) { FloatArray(rows + 4) }
        heights = IntArray(cols)
        loose.clear(); cranes.clear(); blasts.clear(); boss = null
        px = cols / 2; py = 0f
    }

    // ================= input API =================

    val isRunning get() = state == State.PLAYING || state == State.BANNER || state == State.CLEARING || state == State.DYING
    val inMenu get() = !isRunning

    fun pressStep(dir: Int) { if (state == State.PLAYING && mode != Mode.VERSUS) queuedStep = dir }
    fun pressJump() {
        if (state != State.PLAYING) return
        if (mode == Mode.VERSUS) vsDrop = true else queuedJump = true
    }

    fun togglePause() {
        when (state) {
            State.PLAYING, State.BANNER -> pause()
            State.PAUSED -> resume()
            else -> {}
        }
    }

    fun pause() {
        if (state == State.PLAYING || state == State.BANNER) {
            pausedFrom = state; state = State.PAUSED; cursor = 0
        }
    }

    private fun resume() { if (state == State.PAUSED) state = pausedFrom }

    /** Back button. Returns false when the app should close. */
    fun back(): Boolean {
        when (state) {
            State.TITLE -> return false
            State.PLAYING, State.BANNER -> pause()
            State.PAUSED, State.SETTINGS, State.GAME_OVER, State.WIN -> toTitle()
            State.CLEARING, State.DYING -> {}
        }
        return true
    }

    fun toggleSound() { settings.sound = !settings.sound; dirty = true }

    private val menuSize get() = when (state) {
        State.TITLE -> 3
        State.PAUSED, State.GAME_OVER -> 2
        State.WIN -> 1
        State.SETTINGS -> SETTINGS_ITEMS
        else -> 0
    }

    fun menuMove(d: Int) {
        val n = menuSize
        if (n == 0) return
        cursor = (cursor + d + n) % n
        events.addLast(Sfx.MENU)
    }

    fun menuAdjust(d: Int) {
        when (state) {
            State.SETTINGS -> adjustSetting(cursor, d)
            State.TITLE -> if (cursor == 1) cycleMode(d) else menuMove(d)
            State.PAUSED, State.GAME_OVER -> menuMove(d)
            else -> {}
        }
    }

    fun menuSelect() {
        when (state) {
            State.TITLE -> when (cursor) { 0 -> newGame(); 1 -> cycleMode(1); else -> openSettings() }
            State.SETTINGS -> if (cursor == SETTINGS_ITEMS - 1) toTitle() else adjustSetting(cursor, 1)
            State.PAUSED -> if (cursor == 0) resume() else toTitle()
            State.GAME_OVER -> if (timer > 0.8f) { if (cursor == 0) newGame() else toTitle() }
            State.WIN -> if (timer > 1.5f) toTitle()
            else -> {}
        }
    }

    /** A tap in logical screen coordinates while a menu is shown. */
    fun tap(x: Float, y: Float) {
        when (state) {
            State.TITLE -> {
                if (abs(x - w / 2f) > titleButtonW / 2f + 2) return
                when (itemAt(y, TITLE_Y, MENU_STEP, 3)) {
                    0 -> { cursor = 0; newGame() }
                    1 -> { cursor = 1; cycleMode(if (x < w / 2f) -1 else 1) }
                    2 -> { cursor = 2; openSettings() }
                }
            }
            State.SETTINGS -> {
                val i = itemAt(y, SET_Y, SET_STEP, SETTINGS_ITEMS)
                if (i < 0) return
                cursor = i
                val v = settingValue(i)
                if (v == null) { menuSelect(); return }
                // tapping the left half of the "< value >" control steps backwards
                val vw = FrameBuffer.textWidth("< $v >")
                val vx = w - 5 - vw
                adjustSetting(i, if (x >= vx - 2 && x < vx + vw / 2f) -1 else 1)
            }
            State.PAUSED -> when (itemAt(y, pauseTop + 13, MENU_STEP, 2)) {
                0 -> resume()
                1 -> toTitle()
            }
            State.GAME_OVER -> {
                if (itemAt(y, overTop + 26, MENU_STEP, 1) == 0 && abs(x - w / 2f) <= 36) {
                    cursor = if (x < w / 2f) 0 else 1
                    menuSelect()
                }
            }
            State.WIN -> if (itemAt(y, overTop + 34, MENU_STEP, 1) == 0) menuSelect()
            else -> {}
        }
    }

    private fun itemAt(y: Float, y0: Int, step: Int, n: Int): Int {
        val i = floor((y - y0 + 2) / step).toInt()
        return if (i in 0 until n) i else -1
    }

    private fun toTitle() { state = State.TITLE; cursor = 0; events.addLast(Sfx.MENU) }
    private fun openSettings() { state = State.SETTINGS; cursor = 0; events.addLast(Sfx.MENU) }

    private fun <T> cycle(list: List<T>, cur: T, d: Int) = list[(list.indexOf(cur) + d + list.size) % list.size]

    private fun cycleMode(d: Int) {
        settings.mode = cycle(Mode.entries, settings.mode, d)
        dirty = true
        events.addLast(Sfx.MENU)
    }

    private fun adjustSetting(i: Int, d: Int) {
        val s = settings
        when (i) {
            0 -> s.difficulty = cycle(Difficulty.entries, s.difficulty, d)
            1 -> { s.warehouse = cycle(Warehouse.entries, s.warehouse, d); applySize() }
            2 -> s.startLevel = ((s.startLevel - 1 + d + bestLevel) % bestLevel) + 1
            3 -> s.sound = !s.sound
            4 -> s.ghosting = !s.ghosting
            5 -> s.buttons = !s.buttons
            else -> return
        }
        dirty = true
        events.addLast(Sfx.MENU)
    }

    // ================= game flow =================

    fun newGame() {
        applySize()
        score = 0; lines = 0
        phase = Phase.GROUND
        stepping = false; facing = 1
        queuedStep = 0; queuedJump = false
        spawnT = 1.0f
        elapsed = 0f
        events.addLast(Sfx.START)
        when (mode) {
            Mode.CAMPAIGN -> startLevel(settings.startLevel.coerceIn(1, bestLevel))
            Mode.BOMB_RUSH -> {
                level = 1; boss = null
                banner("BOMB RUSH", "SURVIVE!")
            }
            Mode.VERSUS -> {
                level = 3; boss = null
                vsX = colX(0).toFloat(); vsReload = 0.5f; vsDrops = 0; vsDrop = false
                vsResult = ""; vsReason = ""
                aiT = 0f; aiTarget = px; aiSeen.clear()
                banner("CRANE VS WORKER", "YOU ARE THE CRANE", "JUMP: DROP")
            }
        }
    }

    private fun startLevel(l: Int) {
        level = l
        levelLines = 0
        pendingLevelUp = false
        if (level > bestLevel) { bestLevel = level; dirty = true }
        if (isBossLevel(l)) {
            val kind = BossKind.entries[(l / BOSS_EVERY - 1).coerceIn(0, BossKind.entries.size - 1)]
            boss = Boss(kind, max(2, kind.baseHp + diff.bossHp)).also { planNext(it) }
            banner("LEVEL $l", "WARNING!", kind.label)
            events.addLast(Sfx.BOSS)
        } else {
            boss = null
            val hint = when (l) {
                1 -> listOf("GET READY")
                STEEL_FROM -> listOf("STEEL CRATES", "NO PUSH OR SMASH")
                BOMB_FROM -> listOf("BOMB CRATES", "STAND CLEAR!")
                else -> emptyList()
            }
            banner(*(listOf("LEVEL $l") + hint).toTypedArray())
            if (l > 1) events.addLast(Sfx.LEVEL)
        }
    }

    private fun advanceLevel() {
        if (level >= MAX_LEVEL) {
            state = State.WIN; timer = 0f; cursor = 0
            recordHi()
            events.addLast(Sfx.WIN)
        } else startLevel(level + 1)
    }

    private fun banner(vararg l: String) {
        bannerLines = l.toList()
        state = State.BANNER; timer = 0f
    }

    private fun recordHi() {
        val i = hiIndex(mode, diff)
        if (score > hiScores[i]) { hiScores[i] = score; dirty = true }
    }

    fun update(dtIn: Float) {
        val dt = min(dtIn, 0.05f)
        blinkT += dt
        timer += dt
        if (state != State.PAUSED) updateBlasts(dt)
        when (state) {
            State.PLAYING -> tick(dt)
            State.BANNER -> if (timer >= BANNER_TIME) state = State.PLAYING
            State.CLEARING -> if (timer >= CLEAR_TIME) finishClear()
            State.DYING -> if (timer >= DIE_TIME || (mode == Mode.VERSUS && timer >= 1f)) {
                state = State.GAME_OVER; timer = 0f; cursor = 0
                recordHi()
            }
            else -> {}
        }
    }

    private fun tick(dt: Float) {
        elapsed += dt
        when (mode) {
            Mode.BOMB_RUSH -> {
                val l = min(MAX_LEVEL, 1 + (elapsed / RUSH_LEVEL_TIME).toInt())
                if (l != level) { level = l; events.addLast(Sfx.LEVEL) }
            }
            Mode.VERSUS -> {
                updateVsCrane(dt)
                updateAi(dt)
                if (elapsed >= VS_TIME) { workerWins("TIME UP"); return }
            }
            else -> {}
        }
        updateCranes(dt)
        updatePlayer(dt)
        updateLoose(dt)
        updateBombs(dt)
        if (state == State.PLAYING) checkCrush()
        if (state == State.PLAYING) checkRow()
        if (state == State.PLAYING) updateBoss(dt)
    }

    // ================= cranes =================

    private fun colX(c: Int) = FIELD_X + c * CELL

    private fun updateCranes(dt: Float) {
        spawnT -= dt
        if (boss == null && mode != Mode.VERSUS && spawnT <= 0f && cranes.size < maxCranes) {
            spawnCrane()
            spawnT = spawnEvery * (0.7f + rng.nextFloat() * 0.6f)
        }
        val it = cranes.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.x += c.dir * craneSpeed * dt
            if (c.carrying) {
                val tx = colX(c.target).toFloat()
                if ((c.dir > 0 && c.x >= tx) || (c.dir < 0 && c.x <= tx)) {
                    c.carrying = false
                    loose.add(Loose(c.target, crateHangR, c.target, 1f, c.type, fuseTime))
                }
            }
            if (c.x < -CELL - 2 || c.x > w + 2) it.remove()
        }
    }

    private fun spawnCrane() {
        val pending = cranes.filter { it.carrying }.map { it.target }.toSet()
        val candidates = (0 until cols).filter { heights[it] < rows && it !in pending }
            .ifEmpty { (0 until cols).filter { heights[it] < rows } }
        if (candidates.isEmpty()) return
        val target = candidates[rng.nextInt(candidates.size)]
        val dir = if (rng.nextBoolean()) 1 else -1
        cranes.add(Crane(if (dir > 0) -CELL.toFloat() else w.toFloat(), dir, target, craneCrateType()))
    }

    private fun craneCrateType(): Int {
        val s = diff.special
        if (mode == Mode.BOMB_RUSH) {
            val r = rng.nextFloat()
            return when { r < 0.5f -> BOMB; r < 0.5f + 0.08f * s -> STEEL; else -> WOOD }
        }
        val bomb = if (level >= BOMB_FROM) min(0.10f, 0.03f + 0.005f * (level - BOMB_FROM)) * s else 0f
        val steel = if (level >= STEEL_FROM) min(0.22f, 0.05f + 0.012f * (level - STEEL_FROM)) * s else 0f
        val r = rng.nextFloat()
        return when {
            r < bomb -> BOMB
            r < bomb + steel -> STEEL
            else -> WOOD
        }
    }

    // ================= boss =================

    private val bossSpeed get() = (45f + level * 1.5f) * diff.speed * (if (boss?.kind == BossKind.MEGA) 1.4f else 1f)

    private fun updateBoss(dt: Float) {
        val b = boss ?: return
        b.hitT = max(0f, b.hitT - dt)
        when (b.phase) {
            BPhase.MOVE -> {
                val tx = colX(b.anchor).toFloat()
                val step = bossSpeed * dt
                if (abs(tx - b.x) <= step) {
                    b.x = tx; b.phase = BPhase.AIM; b.t = aimTime(b)
                } else b.x += sign(tx - b.x) * step
            }
            BPhase.AIM -> {
                b.t -= dt
                if (b.t <= 0f) {
                    for (i in 0..1) {
                        val type = b.drops[i]
                        if (type == EMPTY) continue
                        loose.add(Loose(b.anchor + i, crateHangR, b.anchor + i, 1f, type, BOMB_FUSE))
                        b.drops[i] = EMPTY
                    }
                    b.phase = BPhase.COOL; b.t = coolTime(b)
                }
            }
            BPhase.COOL -> {
                b.t -= dt
                if (b.t <= 0f) { planNext(b); b.phase = BPhase.MOVE }
            }
            BPhase.DEAD -> {
                b.t -= dt
                if (b.t <= 0f) { boss = null; advanceLevel() }
            }
        }
    }

    private fun aimTime(b: Boss): Float = when (b.kind) {
        BossKind.FOREMAN -> 0.75f
        BossKind.TWIN -> 0.6f
        BossKind.STEELER -> 0.5f
        BossKind.MEGA -> if (b.count % 4 == 1) 0.5f else 0.3f
    } / diff.speed

    private fun coolTime(b: Boss): Float = when (b.kind) {
        BossKind.FOREMAN -> 0.5f
        BossKind.TWIN -> 0.6f
        BossKind.STEELER -> 0.45f
        BossKind.MEGA -> if (b.count % 4 == 0) 1.0f else 0.15f
    } / diff.speed

    private fun planNext(b: Boss) {
        b.count++
        when (b.kind) {
            BossKind.FOREMAN ->
                if (b.count % 4 == 0) setRandom(b, WOOD, WOOD) else setAimed(b, WOOD)
            BossKind.TWIN ->
                if (b.count % 3 == 0) setAimedPair(b) else setRandom(b, WOOD, WOOD)
            BossKind.STEELER -> when (b.count % 3) {
                0 -> setAimed(b, STEEL)
                1 -> setRandom(b, if (rng.nextBoolean()) BOMB else WOOD, EMPTY)
                else -> setRandom(b, WOOD, STEEL)
            }
            BossKind.MEGA ->
                if (b.count % 4 == 1) setAimed(b, megaType())
                else setRandom(b, megaType(), if (rng.nextBoolean()) megaType() else EMPTY)
        }
    }

    private fun megaType(): Int {
        val r = rng.nextFloat()
        return when { r < 0.15f -> BOMB; r < 0.35f -> STEEL; else -> WOOD }
    }

    private fun setAimed(b: Boss, type: Int) {
        val hook = when (px) { 0 -> 0; cols - 1 -> 1; else -> rng.nextInt(2) }
        b.anchor = px - hook
        b.drops[hook] = type
        b.drops[1 - hook] = EMPTY
    }

    private fun setAimedPair(b: Boss) {
        b.anchor = (px - rng.nextInt(2)).coerceIn(0, cols - 2)
        b.drops[0] = WOOD; b.drops[1] = WOOD
    }

    private fun setRandom(b: Boss, t0: Int, t1: Int) {
        var l = t0; var r = t1
        if (r == EMPTY && rng.nextBoolean()) { r = l; l = EMPTY }
        val ok = (0..cols - 2).filter { a ->
            (l == EMPTY || heights[a] <= rows - 3) && (r == EMPTY || heights[a + 1] <= rows - 3)
        }
        b.anchor = if (ok.isNotEmpty()) ok[rng.nextInt(ok.size)]
        else (0..cols - 2).minBy { heights[it] + heights[it + 1] }
        b.drops[0] = l; b.drops[1] = r
    }

    private fun hitBoss() {
        val b = boss ?: return
        if (b.phase == BPhase.DEAD) return
        b.hp--
        b.hitT = 0.5f
        if (b.hp <= 0) {
            b.phase = BPhase.DEAD; b.t = BOSS_DEATH_TIME
            b.drops.fill(EMPTY)
            score += 500 * level
            events.addLast(Sfx.BOSS_DOWN)
        } else events.addLast(Sfx.HIT)
    }

    // ================= world queries =================

    /** Is cell (c,row) blocked by a settled or loose crate? */
    private fun occupied(c: Int, row: Int): Boolean {
        if (c !in 0 until cols || row < 0) return true
        if (row < grid[c].size && grid[c][row] != EMPTY) return true
        for (l in loose) if (l.col == c && l.r < row + 1 - EPS && l.r + 1 > row + EPS) return true
        return false
    }

    private fun isIntegral(v: Float) = abs(v - v.roundToInt()) < EPS

    // ================= player =================

    private fun updatePlayer(dt: Float) {
        // horizontal
        if (stepping) {
            stepT += dt / STEP_TIME
            if (stepT >= 1f) { stepping = false; stepT = 1f }
        }
        if (!stepping) {
            var dir = 0
            val buffered = queuedStep != 0
            if (buffered) { dir = queuedStep; queuedStep = 0 }
            else if (leftHeld != rightHeld) dir = if (leftHeld) -1 else 1
            if (dir != 0) {
                facing = dir
                // A sideways tap during the rise (or together with a jump) is kept until it can be
                // taken at the apex, e.g. to push a crate sitting on top of another one.
                if (!tryStep(dir) && buffered && (phase == Phase.RISE || queuedJump)) queuedStep = dir
            }
        }

        // jump
        if (queuedJump) {
            queuedJump = false
            if (phase == Phase.GROUND) {
                val fy = py.roundToInt()
                if (!occupied(px, fy + 2)) {
                    phase = Phase.RISE; riseTo = fy + 1f
                    events.addLast(Sfx.JUMP)
                }
            }
        }

        // vertical
        when (phase) {
            Phase.GROUND -> if (py > heights[px] + EPS) phase = Phase.FALL
            Phase.RISE -> {
                val ny = min(riseTo, py + RISE_SPEED * dt)
                val headRow = floor(ny + 2f - EPS).toInt()
                if (headRow < grid[px].size && grid[px][headRow] != EMPTY) {
                    phase = Phase.FALL
                } else {
                    py = ny
                    if (py >= riseTo - EPS) { py = riseTo; phase = Phase.HANG; hangT = HANG_TIME }
                }
            }
            Phase.HANG -> { hangT -= dt; if (hangT <= 0f) phase = Phase.FALL }
            Phase.FALL -> {
                val floorY = heights[px].toFloat()
                val ny = py - PLAYER_FALL * dt
                if (ny <= floorY) { py = floorY; phase = Phase.GROUND } else py = ny
            }
        }
    }

    private fun tryStep(dir: Int): Boolean {
        val nx = px + dir
        if (nx !in 0 until cols) return false
        val fy = floor(py + EPS).toInt()
        val topRow = floor(py + 2f - EPS).toInt()
        val blocked = (fy..topRow).any { occupied(nx, it) }
        if (!blocked) { beginStep(nx); return true }

        // push: feet level with the top crate of the next column (standing, or at the top of
        // a jump, which is how a crate sitting on another one is reached), space beyond, not steel
        if ((phase != Phase.GROUND && phase != Phase.HANG) || !isIntegral(py)) return false
        val nx2 = nx + dir
        if (nx2 !in 0 until cols) return false
        val type = grid[nx][fy]
        if (type == EMPTY || type == STEEL || heights[nx] != fy + 1) return false
        if (occupied(nx, fy + 1) || occupied(nx2, fy)) return false
        grid[nx][fy] = EMPTY
        heights[nx]--
        loose.add(Loose(nx2, fy.toFloat(), nx, 0f, type, fuse[nx][fy]))
        fuse[nx][fy] = 0f
        score += 2
        events.addLast(Sfx.PUSH)
        beginStep(nx)
        return true
    }

    private fun beginStep(nx: Int) {
        stepFrom = px; px = nx; stepT = 0f; stepping = true
        walkFrame = walkFrame xor 1
    }

    // ================= loose crates =================

    private fun updateLoose(dt: Float) {
        if (loose.isEmpty()) return
        loose.sortBy { it.r }
        val landed = ArrayList<Loose>()
        for (l in loose) {
            if (l.slideT < 1f) { l.slideT = min(1f, l.slideT + dt / STEP_TIME); continue }
            val floorR = heights[l.col].toFloat()
            var sup = floorR
            for (o in loose) if (o !== l && o.col == l.col && o.r < l.r) sup = max(sup, o.r + 1f)
            l.r = max(sup, l.r - crateFall * dt)
            if (l.r <= floorR + EPS && sup == floorR) landed.add(l)
        }
        loose.removeAll(landed.toSet())
        for (l in landed) land(l)
    }

    private fun land(l: Loose) {
        val col = l.col
        val r = heights[col]
        if (r < grid[col].size) {
            grid[col][r] = l.type
            fuse[col][r] = if (l.type == BOMB) (if (l.fuse > 0f) l.fuse else fuseTime) else 0f
        }
        heights[col]++
        score += 1
        events.addLast(Sfx.LAND)
        if (heights[col] >= rows) { if (mode == Mode.VERSUS) workerWins("OVERFLOW") else die() }
    }

    private fun checkCrush() {
        val it = loose.iterator()
        while (it.hasNext()) {
            val l = it.next()
            if (l.col == px && l.slideT >= 1f && l.r < py + 2f - 0.05f && l.r + 1f > py + 0.05f) {
                // Jumping into a falling crate smashes it with the head (not steel).
                if ((phase == Phase.RISE || phase == Phase.HANG) && l.type != STEEL && l.r > py + 1f) {
                    it.remove()
                    blasts.add(Blast(px, floor(l.r + 0.5f).toInt()))
                    score += 5
                    phase = Phase.FALL
                    events.addLast(Sfx.SMASH)
                    continue
                }
                die(); return
            }
        }
    }

    // ================= bombs =================

    private fun updateBombs(dt: Float) {
        toExplode.clear()
        for (c in 0 until cols) for (r in 0 until heights[c]) {
            if (grid[c][r] != BOMB) continue
            fuse[c][r] -= dt
            if (fuse[c][r] <= 0f) toExplode.add(c to r)
        }
        if (toExplode.isEmpty()) return
        for ((c, r) in toExplode) if (grid[c][r] == BOMB) explode(c, r)
        settleColumns()
        events.addLast(Sfx.BOOM)
    }

    private fun explode(c: Int, r: Int) {
        val pr0 = floor(py + EPS).toInt()
        val pr1 = floor(py + 2f - EPS).toInt()
        for ((dc, dr) in PLUS) {
            val cc = c + dc; val rr = r + dr
            if (cc !in 0 until cols || rr < 0 || rr >= grid[cc].size) continue
            blasts.add(Blast(cc, rr))
            if (cc == px && rr in pr0..pr1) die()
            val t = grid[cc][rr]
            if (t == EMPTY) continue
            if (t == BOMB && (dc != 0 || dr != 0)) {
                fuse[cc][rr] = min(fuse[cc][rr], 0.15f)   // chain reaction
                continue
            }
            grid[cc][rr] = EMPTY
            fuse[cc][rr] = 0f
            score += 3
        }
    }

    /** After a blast: crates above a gap become loose and fall. */
    private fun settleColumns() {
        for (c in 0 until cols) {
            var k = 0
            while (k < grid[c].size && grid[c][k] != EMPTY) k++
            heights[c] = k
            for (r in k + 1 until grid[c].size) {
                if (grid[c][r] == EMPTY) continue
                loose.add(Loose(c, r.toFloat(), c, 1f, grid[c][r], fuse[c][r]))
                grid[c][r] = EMPTY
                fuse[c][r] = 0f
            }
        }
        if (phase == Phase.GROUND && py > heights[px] + EPS) phase = Phase.FALL
    }

    private fun updateBlasts(dt: Float) {
        if (blasts.isEmpty()) return
        for (b in blasts) b.t -= dt
        blasts.removeAll { it.t <= 0f }
    }

    // ================= rows =================

    private fun checkRow() {
        if ((0 until cols).all { grid[it][0] != EMPTY }) {
            state = State.CLEARING; timer = 0f
            events.addLast(Sfx.CLEAR)
        }
    }

    private fun finishClear() {
        for (c in 0 until cols) {
            val g = grid[c]; val f = fuse[c]
            for (r in 0 until g.size - 1) { g[r] = g[r + 1]; f[r] = f[r + 1] }
            g[g.size - 1] = EMPTY; f[f.size - 1] = 0f
            heights[c]--
        }
        py = max(0f, py - 1f)
        if (phase == Phase.RISE) riseTo -= 1f
        if (phase == Phase.GROUND && py > heights[px] + EPS) phase = Phase.FALL
        lines++
        levelLines++
        score += 100 * level
        when (mode) {
            Mode.CAMPAIGN ->
                if (boss != null) hitBoss()
                else if (!isBossLevel(level) && levelLines >= diff.linesPerLevel) pendingLevelUp = true
            Mode.VERSUS -> if (lines >= vsRowsToWin) { workerWins("ROWS CLEARED"); return }
            Mode.BOMB_RUSH -> {}
        }
        state = State.PLAYING
        checkRow() // chain clears
        if (state == State.PLAYING && pendingLevelUp) {
            pendingLevelUp = false
            advanceLevel()
        }
    }

    private fun die() {
        if (state == State.DYING) return
        state = State.DYING; timer = 0f
        if (mode == Mode.VERSUS) {
            vsResult = "YOU WIN!"; vsReason = "WORKER CRUSHED"
            score = 1000 + ((VS_TIME - elapsed).coerceAtLeast(0f) * 10).toInt() + vsDrops
            events.addLast(Sfx.WIN)
        } else events.addLast(Sfx.DEATH)
    }

    private fun workerWins(reason: String) {
        vsResult = "WORKER WINS"; vsReason = reason
        score = 0
        state = State.GAME_OVER; timer = 0f; cursor = 0
        events.addLast(Sfx.DEATH)
    }

    // ================= versus: player crane =================

    private fun updateVsCrane(dt: Float) {
        // Human input drives the crane; the worker's input is produced by the AI below.
        val dir = (if (rightHeld) 1 else 0) - (if (leftHeld) 1 else 0)
        val minX = colX(0).toFloat(); val maxX = colX(cols - 1).toFloat()
        if (dir != 0) vsX = (vsX + dir * VS_CRANE_SPEED * dt).coerceIn(minX, maxX)
        else {
            val tx = colX(vsCol).toFloat()                  // glide onto the nearest column
            val step = VS_CRANE_SPEED * dt
            vsX = if (abs(tx - vsX) <= step) tx else vsX + sign(tx - vsX) * step
        }
        vsReload = max(0f, vsReload - dt)
        if (vsDrop) {
            vsDrop = false
            if (vsReload <= 0f && heights[vsCol] < rows) {
                val c = vsCol
                loose.add(Loose(c, crateHangR, c, 1f, vsNextType, fuseTime))
                vsDrops++
                vsReload = VS_RELOAD
                events.addLast(Sfx.PUSH)
            }
        }
    }

    // ================= versus: CPU worker =================

    private fun updateAi(dt: Float) {
        aiT -= dt
        if (aiT <= 0f) {
            aiT = aiReaction * (0.7f + rng.nextFloat() * 0.6f)
            aiTarget = aiChooseTarget()
            if (rng.nextFloat() < aiMistake) aiTarget = (px + rng.nextInt(5) - 2).coerceIn(0, cols - 1)
        }
        leftHeld = false; rightHeld = false

        // A crate about to hit the head: maybe smash it (decided once per crate).
        for (l in loose) {
            if (l.col != px || l.slideT < 1f || l.type == STEEL || l.r <= py + 1f) continue
            if (l.r - (py + 2f) < 1.6f && aiSeen.add(l) && phase == Phase.GROUND &&
                ((aiTrapped && diff != Difficulty.EASY) || rng.nextFloat() < aiSmash)) {
                queuedJump = true
            }
        }
        aiSeen.retainAll(loose.toSet())

        if (stepping || aiTarget == px) return
        val dir = if (aiTarget > px) 1 else -1
        val nx = px + dir
        val feet = floor(py + EPS).toInt()
        when {
            phase == Phase.GROUND && heights[nx] == feet + 1 -> queuedJump = true      // climb: jump first...
            phase == Phase.RISE || phase == Phase.HANG -> queuedStep = dir             // ...then step at the apex
            dir < 0 -> leftHeld = true
            else -> rightHeld = true
        }
    }

    private fun aiChooseTarget(): Int {
        // Earliest time a falling crate reaches head height in each column (for a worker standing on top of it).
        val hit = danger
        hit.fill(Float.MAX_VALUE)
        val fall = crateFall
        for (l in loose) {
            if (l.slideT < 1f) continue
            val t = (l.r - (heights[l.col] + 2f)) / fall
            if (t < hit[l.col]) hit[l.col] = t
        }
        // Bomb blasts (plus shape) that would reach a worker standing on top of a column.
        val bombNear = BooleanArray(cols)
        fun markBlast(c: Int, r: Int) {
            for (k in max(0, c - 1)..min(cols - 1, c + 1)) {
                val feet = heights[k]
                val reach = if (k == c) (r - 1)..(r + 1) else r..r
                if (feet in reach || feet + 1 in reach) bombNear[k] = true
            }
        }
        for (c in 0 until cols) for (r in 0 until heights[c]) {
            if (grid[c][r] == BOMB && fuse[c][r] < 2.5f) markBlast(c, r)
        }
        for (l in loose) if (l.type == BOMB) markBlast(l.col, heights[l.col])
        val cc = vsCol
        val craneReady = vsReload < 0.4f

        // columns reachable by walking / jumping one crate up
        var lo = px
        while (lo > 0 && heights[lo - 1] <= heights[lo] + 1) lo--
        var hi = px
        while (hi < cols - 1 && heights[hi + 1] <= heights[hi] + 1) hi++

        var best = px
        var bestScore = Float.MAX_VALUE
        if (lo == hi) aiTrapped = true
        for (c in lo..hi) {
            var sc = 0f
            // Walking there: don't pass under a crate that arrives while we're in that column.
            val dir = if (c > px) 1 else -1
            var t = 0f
            var k = px
            while (k != c) {
                val prev = k
                k += dir
                t += STEP_TIME * 1.2f + (if (heights[k] > heights[prev]) 0.3f else 0f)
                if (k != c && hit[k] < t + STEP_TIME + 0.15f) sc += 60f
            }
            // a crate will land on this column: deadly if it arrives before we could leave again
            if (hit[c] < t + 0.45f) sc += 100f else if (hit[c] < Float.MAX_VALUE) sc += 15f
            if (c == 0 || c == cols - 1) sc += 1.5f           // walls are easy to get cornered against
            if (bombNear[c]) sc += 50f
            if (c == cc) sc += if (craneReady) 12f else 5f    // right under the crane
            else if (abs(c - cc) == 1) sc += 2f
            sc += abs(c - px) * 0.8f - min(abs(c - cc), 5) * 0.6f + heights[c] * 0.3f
            // pits (both neighbours 2+ higher) are traps
            val l = if (c > 0) heights[c - 1] else 99
            val r = if (c < cols - 1) heights[c + 1] else 99
            if (l - heights[c] >= 2 && r - heights[c] >= 2) sc += 8f
            if (sc < bestScore) { bestScore = sc; best = c }
        }
        aiTrapped = lo == hi || bestScore >= 60f
        return best
    }

    // ================= test hooks =================

    internal fun testPlace(c: Int, type: Int, fuseLeft: Float = BOMB_FUSE) {
        val r = heights[c]
        grid[c][r] = type
        fuse[c][r] = if (type == BOMB) fuseLeft else 0f
        heights[c]++
    }
    internal fun testHeight(c: Int) = heights[c]
    internal fun testCell(c: Int, r: Int) = grid[c][r]
    internal val testBossHp get() = boss?.hp
    internal fun testSkipBanner() { if (state == State.BANNER) state = State.PLAYING }
    internal fun testPlayerCol() = px
    internal fun testDrop(c: Int, r: Float, type: Int = WOOD) { loose.add(Loose(c, r, c, 1f, type, BOMB_FUSE)) }
    internal val testLooseCount get() = loose.size
    internal fun testVsCol() = vsCol
    internal val testVsReason get() = vsReason

    // ================= drawing =================

    private fun crateY(r: Float) = (h - (r + 1f) * CELL).roundToInt()
    private val pauseTop get() = h / 2 - 17
    private val overTop get() = TOP + (h - TOP - 46) / 2
    private val titleButtonW get() = min(w - 16, 76)

    private fun settingValue(i: Int): String? {
        val s = settings
        return when (i) {
            0 -> s.difficulty.label
            1 -> s.warehouse.label
            2 -> s.startLevel.toString()
            3 -> if (s.sound) "ON" else "OFF"
            4 -> if (s.ghosting) "ON" else "OFF"
            5 -> if (s.buttons) "ON" else "OFF"
            else -> null
        }
    }

    fun draw(fb: FrameBuffer) {
        fb.clear()
        when (state) {
            State.TITLE -> { drawTitle(fb); return }
            State.SETTINGS -> { drawSettings(fb); return }
            else -> {}
        }
        drawWorld(fb)
        drawHud(fb)
        when (state) {
            State.PAUSED -> {
                val pw = 60; val x = (w - pw) / 2; val y = pauseTop
                fb.panel(x, y, pw, 35)
                fb.textCentered("PAUSED", y + 3)
                listOf("RESUME", "QUIT").forEachIndexed { i, s ->
                    fb.button(x + 6, y + 13 + i * MENU_STEP, pw - 13, s, i == cursor)
                }
            }
            State.BANNER -> {
                val tw = bannerLines.maxOf { FrameBuffer.textWidth(it) } + 12
                val ph = bannerLines.size * 7 + 5
                val y = TOP + (h - TOP - ph) / 2
                fb.panel((w - tw) / 2, y, tw, ph)
                bannerLines.forEachIndexed { i, s ->
                    if (s == "WARNING!" && (blinkT % 0.4f) > 0.25f) return@forEachIndexed
                    fb.textCentered(s, y + 3 + i * 7)
                }
            }
            State.GAME_OVER -> {
                val y = overTop
                fb.panel((w - 80) / 2, y, 80, 37)
                when (mode) {
                    Mode.VERSUS -> {
                        if (vsResult != "YOU WIN!" || (blinkT % 0.6f) < 0.4f) fb.textCentered(vsResult, y + 3)
                    }
                    Mode.BOMB_RUSH -> fb.textCentered("OVER  ${clock(elapsed)}", y + 3)
                    Mode.CAMPAIGN -> fb.textCentered("GAME OVER", y + 3)
                }
                if (mode == Mode.VERSUS) {
                    fb.textCentered(vsReason, y + 11)
                    fb.textCentered("SCORE ${score.toString().padStart(6, '0')}", y + 17)
                } else {
                    fb.textCentered("SCORE ${score.toString().padStart(6, '0')}", y + 11)
                    fb.textCentered("HI    ${hiScore.toString().padStart(6, '0')}", y + 17)
                }
                if (timer > 0.8f) {
                    fb.button(w / 2 - 35, y + 26, 33, "RETRY", cursor == 0)
                    fb.button(w / 2 + 1, y + 26, 33, "MENU", cursor == 1)
                }
            }
            State.WIN -> {
                val y = overTop
                fb.panel((w - 84) / 2, y, 84, 46)
                if ((blinkT % 0.6f) < 0.4f) fb.textCentered("YOU WIN!", y + 3)
                fb.textCentered("WAREHOUSE CLEAR", y + 10)
                fb.textCentered("SCORE ${score.toString().padStart(6, '0')}", y + 18)
                fb.textCentered("HI    ${hiScore.toString().padStart(6, '0')}", y + 24)
                if (timer > 1.5f) fb.button(w / 2 - 18, y + 34, 35, "MENU", true)
            }
            else -> {}
        }
    }

    private fun clock(t: Float): String {
        val s = t.toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun drawHud(fb: FrameBuffer) {
        if (mode == Mode.VERSUS) {
            fb.text("ROWS ${min(lines, vsRowsToWin)}/$vsRowsToWin", 1, 1)
            val t = clock((VS_TIME - elapsed).coerceAtLeast(0f))
            fb.text(t, w - FrameBuffer.textWidth(t) - 1, 1)
            return
        }
        fb.text(score.toString().padStart(6, '0'), 1, 1)
        if (mode == Mode.BOMB_RUSH) {
            val t = clock(elapsed)
            fb.text(t, w / 2 - FrameBuffer.textWidth(t) / 2, 1)
            val lv = "LV$level"
            fb.text(lv, w - FrameBuffer.textWidth(lv) - 1, 1)
            return
        }
        val lv = "LV$level"
        fb.text(lv, w - FrameBuffer.textWidth(lv) - 1, 1)
        val b = boss
        if (b != null) {
            if (b.hitT > 0f && (b.hitT * 12).toInt() % 2 == 0) return
            val bw = 16 + b.maxHp * 4 - 1
            val x0 = (w - bw) / 2
            fb.text("BOSS", x0, 1)
            for (i in 0 until b.maxHp) {
                val x = x0 + 16 + i * 4
                if (i < b.hp) fb.fill(x, 2, 3, 3, true) else { fb.set(x, 2); fb.set(x + 2, 2); fb.set(x, 4); fb.set(x + 2, 4) }
            }
        } else if (!isBossLevel(level)) {
            val s = "${min(levelLines, diff.linesPerLevel)}/${diff.linesPerLevel}"
            fb.text(s, w / 2 - FrameBuffer.textWidth(s) / 2, 1)
        }
    }

    private fun drawCrate(fb: FrameBuffer, type: Int, x: Int, y: Int, fuseLeft: Float) {
        when (type) {
            STEEL -> fb.blit(Art.steel, x, y)
            BOMB -> {
                val lit = fuseLeft < 1.5f && (blinkT * (if (fuseLeft < 0.6f) 16 else 8)).toInt() % 2 == 0
                fb.blit(Art.bomb, x, y, invert = lit)
            }
            else -> fb.blit(Art.crate, x, y)
        }
    }

    private fun drawWorld(fb: FrameBuffer) {
        // rail and side walls
        fb.hline(0, w - 1, RAIL_Y)
        fb.vline(0, TOP, h - 1)
        fb.vline(w - 1, TOP, h - 1)

        // settled crates (bottom row blinks while clearing)
        val hideBottom = state == State.CLEARING && ((timer * 10).toInt() % 2 == 0)
        for (c in 0 until cols) for (r in 0 until heights[c]) {
            if (r == 0 && hideBottom) continue
            if (r < rows) drawCrate(fb, grid[c][r], colX(c), crateY(r.toFloat()), fuse[c][r])
        }

        // loose crates
        for (l in loose) {
            val t = l.slideT
            val x = (colX(l.fromCol) + (colX(l.col) - colX(l.fromCol)) * t).roundToInt()
            drawCrate(fb, l.type, x, crateY(l.r), BOMB_FUSE)
        }

        // cranes
        for (c in cranes) {
            val x = c.x.roundToInt()
            fb.blit(Art.crane, x, RAIL_Y + 1)
            if (c.carrying) drawCrate(fb, c.type, x, RAIL_Y + 4, BOMB_FUSE)
        }

        drawBoss(fb)
        if (mode == Mode.VERSUS) drawVsCrane(fb)

        // blasts
        for (b in blasts) if ((b.t * 20).toInt() % 2 == 0) fb.blit(Art.blast, colX(b.c), crateY(b.r.toFloat()))

        // player
        val dying = state == State.DYING || (state == State.GAME_OVER && vsResult != "WORKER WINS")
        if (state == State.DYING && (timer * 8).toInt() % 2 == 1 && timer < 1f) return
        val sx = if (stepping) colX(stepFrom) + ((colX(px) - colX(stepFrom)) * stepT).roundToInt() else colX(px)
        val sy = (h - (py + 2f) * CELL).roundToInt()
        val sprite = when {
            dying -> Art.playerSquashed
            phase != Phase.GROUND -> Art.playerJump
            stepping && walkFrame == 1 -> Art.playerWalk
            else -> Art.playerStand
        }
        fb.blit(sprite, sx, sy, flip = facing < 0)
    }

    private fun drawVsCrane(fb: FrameBuffer) {
        val x = vsX.roundToInt()
        // trolley above the rail marks the player's crane
        fb.fill(x + 1, RAIL_Y - 3, 6, 3, true)
        fb.set(x + 2, RAIL_Y - 1, false); fb.set(x + 5, RAIL_Y - 1, false)
        fb.blit(Art.crane, x, RAIL_Y + 1)
        if (vsReload <= 0f) drawCrate(fb, vsNextType, x, RAIL_Y + 4, BOMB_FUSE)
        if ((blinkT % 0.5f) < 0.35f) fb.blit(Art.arrow, colX(vsCol) + 1, TOP - 4)
    }

    private fun drawBoss(fb: FrameBuffer) {
        val b = boss ?: return
        val dead = b.phase == BPhase.DEAD
        if (dead && (b.t * 10).toInt() % 2 == 0) return
        var bx = b.x.roundToInt()
        if (dead || b.phase == BPhase.AIM) bx += if ((blinkT * 30).toInt() % 2 == 0) 1 else -1
        val flash = b.hitT > 0f && (b.hitT * 12).toInt() % 2 == 0
        fb.blit(Art.bosses[b.kind.ordinal], bx, RAIL_Y - 7, invert = flash)
        for (i in 0..1) if (b.drops[i] != EMPTY) drawCrate(fb, b.drops[i], bx + i * CELL, RAIL_Y + 4, BOMB_FUSE)
        if (b.phase == BPhase.AIM && (blinkT % 0.25f) < 0.15f) {
            for (i in 0..1) if (b.drops[i] != EMPTY) fb.blit(Art.arrow, colX(b.anchor + i) + 1, TOP - 4)
        }
    }

    private fun drawTitle(fb: FrameBuffer) {
        fb.hline(0, w - 1, RAIL_Y)
        // decorative crane carrying a crate across the rail
        val cx = ((blinkT * 20f) % (w + 16)).roundToInt() - 8
        fb.blit(Art.crane, cx, RAIL_Y + 1)
        fb.blit(Art.crate, cx, RAIL_Y + 4)

        fb.textCentered("CRATE RUSH", 24)
        fb.hline(w / 2 - 22, w / 2 + 21, 30)

        val bw = titleButtonW
        val bx = (w - bw) / 2
        listOf("PLAY", mode.label, "SETTINGS").forEachIndexed { i, s ->
            val y = TITLE_Y + i * MENU_STEP
            fb.button(bx, y, bw, s, i == cursor)
            if (i == 1) {                                   // the mode button cycles: show arrows
                fb.text("<", bx + 3, y, i != cursor)
                fb.text(">", bx + bw - 6, y, i != cursor)
            }
        }
        fb.textCentered("HI ${hiScore.toString().padStart(6, '0')}", 68)
        fb.textCentered("${diff.label} - ${settings.warehouse.label}", 74)

        // little stack scene
        for ((c, hh) in listOf(0 to 2, 1 to 1, cols - 2 to 1, cols - 1 to 3)) for (r in 0 until hh) {
            fb.blit(Art.crate, colX(c), crateY(r.toFloat()))
        }
        fb.blit(Art.playerStand, colX(cols / 2 - 1), h - 16)
        fb.blit(Art.crate, colX(cols / 2), crateY(0f))
        if (h >= 112) {
            val hint = when (mode) {
                Mode.CAMPAIGN -> "20 LEVELS - 4 BOSSES"
                Mode.BOMB_RUSH -> "BOMBS! SURVIVE"
                Mode.VERSUS -> "BE THE CRANE"
            }
            fb.textCentered(hint, 84)
        }
    }

    private fun drawSettings(fb: FrameBuffer) {
        fb.textCentered("SETTINGS", 4)
        fb.hline(w / 2 - 20, w / 2 + 19, 10)
        val s = settings
        for (i in 0 until SETTINGS_ITEMS) {
            val y = SET_Y + i * SET_STEP
            val sel = i == cursor
            val v = settingValue(i)
            if (v == null) {                                // BACK
                fb.button((w - 40) / 2, y, 39, SETTING_LABELS[i], sel)
                continue
            }
            fb.button(2, y, w - 5, "", sel)
            fb.text(SETTING_LABELS[i], 5, y, !sel)
            val vs = "< $v >"
            fb.text(vs, w - 5 - FrameBuffer.textWidth(vs), y, !sel)
        }
        val hint = when (cursor) {
            0 -> "HI ${hiScore.toString().padStart(6, '0')}"
            1 -> "${cols}X$rows FLOOR"
            2 -> "BEST LV $bestLevel"
            5 -> if (s.buttons) "ON-SCREEN PAD" else "TOUCH ZONES"
            SETTINGS_ITEMS - 1 -> "BACK TO TITLE"
            else -> "TAP < > TO CHANGE"
        }
        fb.hline(4, w - 5, h - 10)
        fb.textCentered(hint, h - 7)
    }
}
