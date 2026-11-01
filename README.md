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

## Controls

| Action | Touch | Keyboard / gamepad |
|---|---|---|
| Move | hold left / right third of screen, or swipe sideways in the middle | ← → · A D · 4 6 · D-pad / left stick |
| Jump | tap middle, or swipe up anywhere | ↑ · W · Space · 2 5 · A/B button |
| Pause | tap the score bar · Back | Enter · P · Start |
| Mute | | M |

Move and jump can be combined with two fingers.

## Rules

- Cranes run along the rail and drop crates on random columns.
- Walk, jump one crate high, push a single crate if nothing is on top of it
  and the cell beyond is free.
- A full bottom row clears and the stack drops by one.
- A crate on your head, or any column reaching the rail, ends the game.
- Every 5 rows: level up (faster cranes and crates, up to 3 cranes at once).

Scoring: landed crate +1, push +2, row clear +100 × level.

## Layout

```
app/src/main/java/it/bitwire/craterush/
  game/Engine.kt    rules, physics, state machine, drawing to a 1-bit framebuffer (no Android deps)
  game/Gfx.kt       framebuffer, 3x5 font, sprites
  LcdRenderer.kt    LCD look: pixel ghosting, grid, integer scaling, bezel
  Beeper.kt         square-wave sound via AudioTrack
  GameView.kt       game loop thread, touch/key/gamepad input
  MainActivity.kt   immersive mode, back handling, hi-score persistence
```

Logical resolution is 100×88; the 12×8 playfield uses 8×8 px cells.
