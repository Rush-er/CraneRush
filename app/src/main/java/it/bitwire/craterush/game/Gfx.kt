package it.bitwire.craterush.game

/** 1-bit framebuffer at the original monochrome LCD scale. */
class FrameBuffer(val w: Int, val h: Int) {
    val px = BooleanArray(w * h)

    fun clear() = px.fill(false)

    fun set(x: Int, y: Int, on: Boolean = true) {
        if (x in 0 until w && y in 0 until h) px[y * w + x] = on
    }

    fun hline(x0: Int, x1: Int, y: Int, on: Boolean = true) { for (x in x0..x1) set(x, y, on) }
    fun vline(x: Int, y0: Int, y1: Int, on: Boolean = true) { for (y in y0..y1) set(x, y, on) }

    fun fill(x: Int, y: Int, w: Int, h: Int, on: Boolean) {
        for (yy in y until y + h) for (xx in x until x + w) set(xx, yy, on)
    }

    /** Framed panel: cleared interior with a 1px border. */
    fun panel(x: Int, y: Int, w: Int, h: Int) {
        fill(x, y, w, h, false)
        hline(x, x + w - 1, y); hline(x, x + w - 1, y + h - 1)
        vline(x, y, y + h - 1); vline(x + w - 1, y, y + h - 1)
    }

    /** Draws a sprite; with [invert] the sprite's box is filled and its set pixels are cleared. */
    fun blit(s: Sprite, x: Int, y: Int, flip: Boolean = false, invert: Boolean = false) {
        for (sy in 0 until s.h) for (sx in 0 until s.w) {
            val on = s.bits[sy * s.w + (if (flip) s.w - 1 - sx else sx)]
            if (invert) set(x + sx, y + sy, !on) else if (on) set(x + sx, y + sy)
        }
    }

    fun text(str: String, x: Int, y: Int) {
        var cx = x
        for (c in str.uppercase()) {
            Font.glyph(c)?.let { g ->
                for (i in 0 until 15) if (g[i]) set(cx + i % 3, y + i / 3)
            }
            cx += 4
        }
    }

    fun textCentered(str: String, y: Int) = text(str, (w - textWidth(str)) / 2, y)

    companion object {
        fun textWidth(str: String) = if (str.isEmpty()) 0 else str.length * 4 - 1
    }
}

class Sprite(rows: List<String>) {
    val w = rows[0].length
    val h = rows.size
    init { require(rows.all { it.length == w }) { "ragged sprite" } }
    val bits = BooleanArray(w * h) { rows[it / w][it % w] == '#' }
}

/** Original 3x5 pixel font. */
object Font {
    // Rows separated by spaces, 3 px each, 5 rows.
    private val raw = mapOf(
        '0' to "### #.# #.# #.# ###", '1' to ".#. ##. .#. .#. ###", '2' to "### ..# ### #.. ###",
        '3' to "### ..# .## ..# ###", '4' to "#.# #.# ### ..# ..#", '5' to "### #.. ### ..# ###",
        '6' to "### #.. ### #.# ###", '7' to "### ..# ..# .#. .#.", '8' to "### #.# ### #.# ###",
        '9' to "### #.# ### ..# ###",
        'A' to ".#. #.# ### #.# #.#", 'B' to "##. #.# ##. #.# ##.", 'C' to ".## #.. #.. #.. .##",
        'D' to "##. #.# #.# #.# ##.", 'E' to "### #.. ##. #.. ###", 'F' to "### #.. ##. #.. #..",
        'G' to ".## #.. #.# #.# .##", 'H' to "#.# #.# ### #.# #.#", 'I' to "### .#. .#. .#. ###",
        'J' to "..# ..# ..# #.# .#.", 'K' to "#.# #.# ##. #.# #.#", 'L' to "#.. #.. #.. #.. ###",
        'M' to "#.# ### ### #.# #.#", 'N' to "##. #.# #.# #.# #.#", 'O' to ".#. #.# #.# #.# .#.",
        'P' to "##. #.# ##. #.. #..", 'Q' to ".#. #.# #.# ##. .##", 'R' to "##. #.# ##. #.# #.#",
        'S' to ".## #.. .#. ..# ##.", 'T' to "### .#. .#. .#. .#.", 'U' to "#.# #.# #.# #.# ###",
        'V' to "#.# #.# #.# #.# .#.", 'W' to "#.# #.# ### ### #.#", 'X' to "#.# #.# .#. #.# #.#",
        'Y' to "#.# #.# .#. .#. .#.", 'Z' to "### ..# .#. #.. ###",
        ' ' to "... ... ... ... ...", ':' to "... .#. ... .#. ...", '-' to "... ... ### ... ...",
        '!' to ".#. .#. .#. ... .#.", '.' to "... ... ... ... .#.", '/' to "..# ..# .#. #.. #..",
        '<' to "..# .#. #.. .#. ..#", '>' to "#.. .#. ..# .#. #.."
    )

    private val glyphs: Map<Char, BooleanArray> = raw.mapValues { (c, s) ->
        val p = s.replace(" ", "")
        require(p.length == 15) { "bad glyph '$c'" }
        BooleanArray(15) { p[it] == '#' }
    }

    fun glyph(c: Char) = glyphs[c]
}

/** Original sprite set, drawn in the spirit of early-2000s monochrome phone games. */
object Art {
    val crate = Sprite(listOf(
        "########",
        "##....##",
        "#.#..#.#",
        "#..##..#",
        "#..##..#",
        "#.#..#.#",
        "##....##",
        "########",
    ))

    private val head = listOf(
        "..####..",
        ".######.",
        "..#.....",
        "..#.##..",
        "..####..",
        "...##...",
    )

    val playerStand = Sprite(head + listOf(
        ".######.",
        "#.####.#",
        "#.####.#",
        "#.####.#",
        "..####..",
        "..#..#..",
        "..#..#..",
        "..#..#..",
        "..#..#..",
        ".##..##.",
    ))

    val playerWalk = Sprite(head + listOf(
        ".######.",
        "#.####.#",
        "#.####.#",
        ".#####.#",
        "..####..",
        "..#..#..",
        ".#....#.",
        ".#....#.",
        "#......#",
        "##....##",
    ))

    val playerJump = Sprite(head.take(5) + listOf(
        "#..##..#",
        "########",
        "..####..",
        "..####..",
        "..####..",
        "..####..",
        ".#....#.",
        ".#....#.",
        "..#..#..",
        "..#..#..",
        "........",
    ))

    val playerSquashed = Sprite(listOf(
        "........", "........", "........", "........",
        "........", "........", "........", "........",
        "........", "........", "........", "........",
        "#.#..#.#",
        ".######.",
        "########",
        "##.##.##",
    ))

    val crane = Sprite(listOf(
        ".######.",
        "#.#..#.#",
        "...##...",
    ))

    val steel = Sprite(listOf(
        "########",
        "#.####.#",
        "##.##.##",
        "###..###",
        "###..###",
        "##.##.##",
        "#.####.#",
        "########",
    ))

    val bomb = Sprite(listOf(
        "########",
        "#....#.#",
        "#..##..#",
        "#.####.#",
        "#.####.#",
        "#..##..#",
        "#......#",
        "########",
    ))

    val blast = Sprite(listOf(
        "#..##..#",
        ".#.##.#.",
        "..####..",
        "########",
        "########",
        "..####..",
        ".#.##.#.",
        "#..##..#",
    ))

    val arrow = Sprite(listOf(
        "######",
        ".####.",
        "..##..",
    ))

    /** Bosses, 16x11: a cab above the rail, the rail row, then two hooks over two columns. */
    val bosses = listOf(
        // FOREMAN: hard hat and frown
        Sprite(listOf(
            "....########....",
            "...#.######.#...",
            "..############..",
            "..#.##....##.#..",
            "..#....##....#..",
            "..#..######..#..",
            "...##########...",
            "################",
            "...##......##...",
            "...##......##...",
            "..####....####..",
        )),
        // TWIN HOOK: two heads
        Sprite(listOf(
            ".######..######.",
            "#.#..#.##.#..#.#",
            "#......##......#",
            "#.####.##.####.#",
            "#......##......#",
            ".######..######.",
            "..#..#....#..#..",
            "################",
            "...##......##...",
            "..#..#....#..#..",
            "..#..#....#..#..",
        )),
        // STEELER: robot
        Sprite(listOf(
            "..############..",
            "..#..........#..",
            "..#.###..###.#..",
            "..#.#.#..#.#.#..",
            "..#.###..###.#..",
            "..#..######..#..",
            "..############..",
            "################",
            "...##......##...",
            "..####....####..",
            "..#..#....#..#..",
        )),
        // MEGA CRANE: skull
        Sprite(listOf(
            "#.############.#",
            ".##############.",
            "##..##....##..##",
            "##..##....##..##",
            "######.##.######",
            ".####......####.",
            "..#.#.#..#.#.#..",
            "################",
            "..####....####..",
            "..#..#....#..#..",
            "..#..#....#..#..",
        )),
    )
}
