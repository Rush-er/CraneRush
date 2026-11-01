# Crate Rush

Crate-stacking arcade game in the style of early-2000s monochrome phone games.
Kotlin + SurfaceView, no game framework, targets Android 17 (API 37), minSdk 26.

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio. If the API 37 platform isn't installed,
set `compileSdk`/`targetSdk` to 36 in `app/build.gradle.kts`. If your AGP
version rejects API 37, bump the plugin in `build.gradle.kts`.

## Modes

Pick the mode on the title screen.

| Mode | |
|---|---|
| CAMPAIGN | 20 levels with steel and bomb crates, a boss every 5th level. Beat the Mega Crane to win. |
| BOMB RUSH | Survival. Half the crates are bombs with a 3 s fuse. The speed goes up every 30 s. Score and time survived count. |
| CRANE VS | You are the crane against a CPU worker. ◀ ▶ move the crane, JUMP drops a crate (1.2 s reload, every 5th crate is a bomb). Crush the worker to win. The worker wins if he clears enough rows, survives 90 s, or you overfill a column. Difficulty sets how smart he is: reaction time, how often he smashes crates with his head, mistakes. |

## Controls

| Action | Touch | Keyboard / gamepad |
|---|---|---|
| Move | hold left / right third of screen, or swipe sideways in the middle | ← → · A D · 4 6 · D-pad / left stick |
| Jump | tap middle, or swipe up anywhere | ↑ · W · Space · 2 5 · A/B button |
| Pause | tap the score bar · Back | Enter · P · Start |
| Mute | | M |
| Menus | tap an item, or ◀ ▶ / JUMP on the on-screen pad | ↑ ↓ select · ← → change · Space/Enter/A confirm · B/Back return |

With **BUTTONS** on (default) an on-screen pad is shown under the LCD (portrait) or beside it
(landscape): ◀ ▶ for the left thumb (slide between them), a big JUMP for the right thumb, and ‖ to pause.
The tap zones above keep working on the LCD.

Move and jump can be combined with two fingers.

## Rules

- Cranes run along the rail and drop crates on random columns.
- Walk, jump one crate high, push a single crate if nothing is on top of it
  and the cell beyond is free. At the top of a jump you can push the top crate of a 2-high stack.
- Jump into a falling crate to smash it with your head (+5). Not steel.
- A full bottom row clears and the stack drops by one.
- A crate on your head, a bomb blast, or any column reaching the rail ends the game.
- 20 levels. Clear the required rows (shown in the HUD) to advance.
- **Steel crates** (from level 4) can't be pushed or smashed.
- **Bomb crates** (from level 8) blink and explode 4 s after landing, destroying the
  crates next to them (and you, if you're in the blast). Crates above fall down.
- **Bosses** every 5th level replace the cranes. Every row you clear costs the boss 1 HP:
  - LV5 FOREMAN: aims single crates at you.
  - LV10 TWIN HOOK: drops pairs of crates.
  - LV15 STEELER: steel and bomb crates.
  - LV20 MEGA CRANE: fast volleys of mixed crates. Beat it to win.

Scoring: landed crate +1, push +2, crate destroyed by a bomb +3, smashed crate +5, row clear +100 × level,
boss defeated +500 × level.

## Settings

| Setting | Options |
|---|---|
| Difficulty | EASY · NORMAL · HARD · INSANE: crane/crate speed, cranes at once, rows per level, boss HP, special crate rate, CPU worker skill in CRANE VS. Separate hi score for each mode and difficulty. |
| Warehouse | SMALL 12×8 (original) · MEDIUM 14×10 · LARGE 16×11 (default) · HUGE 20×12 |
| Start level | any level you have reached |
| Sound | on / off |
| Ghosting | LCD pixel ghosting effect on / off |
| Buttons | on-screen pad on / off |

Settings, hi scores and best level are saved.

## Layout

```
app/src/main/java/it/bitwire/craterush/
  game/Engine.kt    rules, physics, bosses, menus, drawing to a 1-bit framebuffer (no Android deps)
  game/Gfx.kt       framebuffer, 3x5 font, sprites
  LcdRenderer.kt    LCD look: pixel ghosting, grid, integer scaling, bezel
  Beeper.kt         square-wave sound via AudioTrack
  GameView.kt       game loop thread, touch/key/gamepad input
  Controls.kt       on-screen button pad layout, hit testing, drawing
  MainActivity.kt   immersive mode, back handling, hi-score persistence
```

Playfield cells are 8×8 px; the logical resolution depends on the warehouse size
(4 + cols×8 by 32 + rows×8, e.g. 132×120 for LARGE).

Tests: `./gradlew testDebugUnitTest` (engine simulation, no device needed).
