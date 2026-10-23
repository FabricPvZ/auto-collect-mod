# auto-collect-mod

Collects sun and coins for you: every sun and coin the player *could* click is clicked, one gameplay
tick after it appears, with nothing left on the lawn for the player to chase. A single stone button
next to the game's own **Menu** button switches it off and on again.

It is a plain Fabric mod: `fabric.mod.json`, a `ModInitializer`, one small Mixin, and a jar that is
loaded from `<gamedir>/mods` by the real Fabric Loader through
the game provider the game jar itself carries (`cn.enaium.fabricpvz.provider`).

The mod's own code is **Kotlin** (`src/main/kotlin`, compiled by the workspace toolchain plugin); its
one Mixin stays Java (`src/main/java/.../mixin`), because a Mixin configuration is a Java class
annotated with `@Mixin` and Mixin's own processor reads it as such. The end-to-end harness is Java
too. The entry point is a Kotlin `class` with a no-argument constructor, not an `object`: Fabric
Loader instantiates a `ModInitializer` reflectively, which a Kotlin `object` (private constructor)
could not satisfy without a language adapter this workspace does not ship. The Kotlin standard library
is a launcher library (the game's launch libraries put one copy on the launch class path), so the mod jar
carries its own classes and resources only - never `kotlin/`.

| mod id | version | entry point |
|---|---|---|
| `auto-collect-mod` | `1.0.0` | `cn.enaium.fabricpvz.autocollect.AutoCollectMod` (Kotlin) |

## The control

```
                    ┌──────────────┐┌──────────────┐
                    │      ON      ││    Menu      │  ← the game's own button (Board.mMenuButton)
                    └──────────────┘└──────────────┘
```

The button sits in the Menu button's own band, 4 pixels to its left, and shows the state of the mod:
`ON` while it is collecting, `OFF` while it is not. Pressing it flips the state; the label follows.

The two labels are this mod's own text, and they travel with it: `src/main/resources/i18n/zh-CN.json`
holds them (`开` / `关`), and the i18n mod of the installation reads that file the way it reads every
other mod's - which is why nothing of this mod's text is written into another mod's table. With that
mod installed the button reads `开` and `关` in Chinese, and `ON` and `OFF` (the fallback the label is
asked with) without it.

**It is on by default.** That is the mod's whole purpose: a player who installs an auto-collect mod
expects the sun and the coins to be collected, without having to find a button first. Collecting is
never a loss - both counters only go up, and the game credits exactly what a click credits - so the
safe state and the expected state are the same one. The button exists to *hand the click back*: it is
there for a player who wants to watch the sun land, to leave coins on the lawn for a screenshot, or
to keep a level's drop-in behaviour for a video. The state is process-wide (a player preference, not
a level property), like the speed setting of `speed-mod`.

### Why there, and why that size

The top-right corner is the game's own Menu button (`Board.mMenuButton`, `Board.java:352-386`:
`681,-10,117x46` in adventure mode, `628,-10,163x46` where a store button is used). The band next to
it is the one part of the top strip the UI leaves free: the seed bank is on the left (`SeedBank.draw`
puts it at `0,0` and grows it rightwards with the packet count, `Board.java:2477-2486`), and
everything *below* the Menu button is where a speed-setting mod puts its own row - so a second mod's
control must not go there. This button therefore never overlaps the Menu button, the seed bank or a
speed mod's controls.

The retail art says exactly how much room that is: `images/SeedBank.png` is **446x87**, and the bank is
drawn `446 + getSeedBankExtraWidth()` wide - 446 at six packets, 599 at ten (446+153). A 71-pixel
panel at `606..677` therefore sits clear of the bank (7 px to spare at the widest bank) and 4 px left
of the Menu button, in the Menu button's own band (y `-10..36`, the top 10 pixels of which are off
screen exactly as the game's own button is).

`GameButton.drawStoneButton` (`GameButton.java:87-125`) draws the retail 9-slice at the slices' own
size - `images/button_left.png` `36x46`, `button_middle.png` `46x46`, `button_right.png` `35x46` -
repeating the middle slice `(w - 36 - 35) / 46` times and using the requested height only to place the
label, never for the drawn bounds. A stone panel is therefore 71, 117, 163 ... wide and always 46
tall; asking for any other rectangle draws art that is wider and taller than the rectangle the mod
hit-tests with. The button uses the smallest panel the art allows, **71x46**, so its rectangle *is*
the rectangle the game draws and a click lands exactly on the panel the player sees; with the
adventure Menu button at `681,-10` that is `606,-10,71x46`.

### Limits of the control, and of the mod

- The button appears while the game's Menu button is on screen (`mBtnNoDraw` false) and is not drawn
  while the game's own full-screen dialogs are up. A click on it is taken by the mod's Mixin and never
  reaches the board (a shovel click on the button digs nothing up).
- The mod collects only **suns** (`COIN_SUN`, `COIN_SMALLSUN`, `COIN_LARGESUN`) and **money**
  (`COIN_SILVER`, `COIN_GOLD`, `COIN_DIAMOND`). Presents, seed packets, chocolate, trophies and the
  level awards are left exactly where they are: the game gives those their own side effects (a
  present plants itself in the zen garden, a usable seed packet takes the cursor, a level award starts
  the level end), which is not what "collect the sun" should do behind the player's back.
- The mod does not wait for the cursor: a plant in hand or the shovel does not stop it (the game's own
  hit test does skip coins then, `BoardInput.java:1086-1088`, but a mod that stopped collecting while
  a seed packet is selected would be useless). It does stop where a player's mouse would no longer
  work at all: while the level is fading out the board's hit test offers no coin, so neither does the
  mod.

## How it works

### What the game does when the player clicks a sun or a coin

| step | where |
|---|---|
| the input pass walks `Board.mCoins` and asks each coin `mouseHitTest` | `BoardInput.mouseHitTest`, `BoardInput.java:1058-1101` |
| a coin is hit while `!mDead && !mIsBeingCollected` - **no "landed" or "old enough" condition**, so a falling sun is clickable from the tick it exists (a `COIN_SUN` also gets a 15 px larger hit box) | `Coin.mouseHitTest`, `Coin.java:1148-1186`, conditions at `:1165-1168`, extra size at `:1160-1162` |
| the click is handed to the hit coin | `BoardInput.mouseDown`, `BoardInput.java:1332-1335` |
| **the collection itself**: `playCollectSound(); collect();` - gated on a paused board, the scene being `SCENE_PLAYING` and the coin not being dead | `Coin.mouseDown`, `Coin.java:1125-1134` |
| `collect()` remembers the position and sets `mIsBeingCollected` | `Coin.collect`, `Coin.java:827-834` |
| `update()` runs `updateCollected()`, which flies the coin to its counter (sun to `15,0`; money to `39,558`, the coin bank) and scores it once `mCollectionDistance < 8` px (sun) / `< 12` px (money) | `Coin.update`, `Coin.java:580-593`; `Coin.updateCollected`, `Coin.java:482-578`, scoring at `:565-571` |
| `scoreCoin()` credits a sun with `mBoard.addSunMoney(...)` and money with `mApp.mPlayerInfo.addCoins(...)`, plus the board's per-level tallies | `Coin.scoreCoin`, `Coin.java:347-369` |
| the sun counter (top-left, in the seed bank) | `Board.mSunMoney`, `Board.java:146`; `Board.addSunMoney`, `Board.java:2202-2207` (capped at 9990) |
| the coin bank (bottom-left) | `PlayerInfo.mCoins`, `PlayerInfo.java:50`; `PlayerInfo.addCoins`, `PlayerInfo.java:311-313`; drawn by `BoardRender.drawUICoinBank`, `BoardRender.java:1264-1291` |
| values: sun 25 / 15 / 50, silver 1 / gold 5 / diamond 100 | `Coin.getSunValue`, `Coin.java:1044-1049`; `Coin.getCoinValue`, `Coin.java:1051-1055` |

Where the sun and the coins come from:

- **sun from the sky**: the level's own timer, every `SUN_COUNTDOWN`-ish ticks, `COIN_MOTION_FROM_SKY`
  - `BoardWaves.updateSunSpawning`, `BoardWaves.java:938-971`.
- **sun from a plant**: sunflowers and their relatives, `COIN_MOTION_FROM_PLANT` (it pops out and
  grows) - `Plant.java:1018-1040`.
- **coins dropped by kills**: a dying zombie calls `dropLoot`, which rolls `Common.rand(30000)` against
  the drop bands (present / chocolate / diamond / gold / silver) and adds one coin with
  `COIN_MOTION_COIN` - `ZombieStatus.dropLoot`, `ZombieStatus.java:521-557` → `Board.dropLootPiece`,
  `Board.java:2731-2821`. A Yeti zombie is the deterministic case: four diamonds, no roll
  (`ZombieStatus.java:548-553`). Dropped coins bounce, land on `mGroundY` and then sit there until
  `getDisappearTime()` (750 ticks, 1500 for the rare types) fades them out (`Coin.java:452-465`,
  `Coin.java:1231-1251`).

### What the mod does

1. **One gameplay tick**: `LawnEvents.TICK`, the game's own call point, fired as the last statement of
   `Board.updateGame()` (`Board.java:3571`) - after the board's own passes have updated every coin for
   that tick (`Board.java:3393-3397`), so a coin is always clicked where the player would have seen
   it. No Mixin is needed for gameplay.
2. **The click**: for every coin a player could click right now (`isSun() || isMoney()`, not dead, not
   already being collected) the mod calls the game's own click handler,
   `Coin.mouseDown(posX, posY, 1)` - *the same method the board's input pass calls*
   (`BoardInput.java:1335`). A mod-collected sun is therefore a clicked sun in every respect: the same
   sound, the same fly-to-the-counter animation, the same credit, the same per-level tallies, and the
   same refusal when the board is paused or the scene is not playing.
3. **The picture**: `LawnEvents.POST_DRAW` (`BoardRender.java:1427-1429`), the game's "the board
   finished drawing itself" call point. The control is built on the board's first draw (it needs the
   board's app, which does not exist at mod init time) and is laid out from the Menu button every time
   it is drawn, so it follows the button in every mode.
4. **The input**: the Mixin, because the modding API deliberately exposes no hook for "this click was
   consumed" - the same reason `speed-mod` has one. `BoardInputMixin` asks the control first and
   cancels `Board.mouseDown`/`mouseUp` (`Board.java:4092`, `:4108`) when the click landed on it,
   paired with the board's own `mIgnoreMouseUp` convention so a press that starts on the button and
   ends on the lawn cannot half-reach the board.

The mod's own tally (suns, coins and the values they were worth) is exposed as
`AutoCollect.stats()`, and is what a test asserts against the game's counters.

## Verification

`AutoCollectEndToEndTest` does what a launcher does: it copies the **built jar** into a real `mods/`
directory - next to the sibling speed mod's installed jar - and starts
`net.fabricmc.loader.impl.launch.knot.KnotClient` in a second JVM with the game, the loader, Mixin,
ASM and the API modules on the class path. The loader resolves both mods, runs their entry points,
applies their Mixin configurations and then starts the harness
(`cn.enaium.fabricpvz.autocollect.test.AutoCollectGameMain`) instead of the game's `Main`. The harness
builds a real headless board (`preNewGame` + `startPlaying`), puts suns and coins on it through the
game's own `Board.addCoin`, drives the game's own frame entry point `LawnApp.updateFrames()`, and
reports every number it read.

The loader resolved this mod next to the speed mod and the language module (25 mods; the log lines are
the unified `[time] [thread/LEVEL] (logger) message` format of the workspace's SLF4J+Log4j stack,
which loader, Mixin and mods all write into):

```
[20:34:08] [main/INFO] (FabricLoader) Loading 25 mods:
	- auto-collect-mod 1.0.0
	- fabric-api 1.0.0
	   |-- api-base 1.0.0
	   |-- api-events 1.0.0
	   |-- api-plant 1.0.0
	   \-- api-zombie 1.0.0                                 # the five modules come from inside the aggregate jar
	- fabric-language-kotlin 1.14.2+kotlin.2.4.21           # the language module this mod requires
	   |-- org_jetbrains_kotlin_kotlin-stdlib 2.4.21        # …and the runtime stdlib it nests
	   \-- … 12 more nested libraries (kotlin-reflect, kotlinx-*)
	- fabricloader 0.19.5
	   \-- mixinextras 0.5.5
	- java 25
	- pvz-portable 1.0.0
	- speed-mod 1.0.0
[20:34:08] [main/INFO] (FabricLoader/Mixin) SpongePowered MIXIN Subsystem Version=0.8.7 … Service=Knot/Fabric Env=CLIENT
[20:34:08] [main/INFO] (speed-mod) [speed-mod] Kotlin 2.4.21 class running (stdlib classes resolved from the launch class path, not from the mod jar: …/run/.fabric/processedMods/org_jetbrains_kotlin_kotlin-stdlib-2.4.21-….jar)
[20:34:08] [main/INFO] (auto-collect-mod) [auto-collect-mod] Kotlin 2.4.21 entry point class ran (stdlib from …/run/.fabric/processedMods/org_jetbrains_kotlin_kotlin-stdlib-2.4.21-….jar, not from the mod jar)
```

The four `api-*` modules are the nested jars of `fabric-api`, whose aggregate this mod carries inside
its own jar (`META-INF/jars/fabric-api-1.2.2.jar`, its modules under that jar's own `META-INF/jars/`),
not jars on the class path of their own: the indented lines are the loader's way of printing a nested
jar (see `loom/README.md`, "Jar-in-jar").

Every line above is the same shape - `[time] [thread/LEVEL] (logger) message` - because Fabric
Loader's log, the Mixin subsystem's log and the mods' log all go through the workspace's one SLF4J +
Log4j stack (see `../PvZ-Portable/GAME-PROVIDER.md`). The `auto-collect-mod` lines carry the mod id as their
logger, the `FabricLoader/…` ones carry the loader's own log category.

The `auto-collect-mod` "entry point class ran" line is the runtime probe: the stdlib this mod's Kotlin
code links against is the copy nested in `fabric-language-kotlin` (the loader puts it on the class
path), not a stdlib in the mod jar and not a plain library on the class path - in an installation
(`mods/` holding the two mods and the language module, with no Kotlin entry on the class path at all)
that is the only copy there is.

What the harness observed (identical for `auto-collect-mod/build/libs/auto-collect-mod-1.0.0.jar` and
for the copy installed in `PvZ-Portable/run/mods/`, both with `speed-mod-1.0.0.jar` next to it):

| step | observation |
|---|---|
| three suns (25/15/50, one `FROM_SKY`, one `FROM_PLANT`, one `LARGESUN`; all three still in the air) and three coins (silver/gold/diamond) put on the board, **before any draw pass** | `sunsStillFalling=3`, `sunCollectedBeforeAnyTick=false` |
| 300 ticks | `modTicksInPhase=300` (the mod sees one tick per game tick) |
| the board's own sun counter | `sunMoneyBefore=50`, `sunMoneyAfter=140`, **`sunDelta=90`** (= 25+15+50) |
| the game's own coin bank | `playerCoinsBefore=0`, `playerCoinsAfter=106`, **`coinsDelta=106`** (= 1+5+100) |
| the board's per-level tally | `levelCoinsDelta=106` |
| nothing left uncollected after the 300 ticks | `sunsLeftOnBoard=0`, `coinsLeftOnBoard=0` |
| not a sun and not money (a chocolate put on the board with them) | `chocolateCollected=0`, `chocolateBeingCollected=false` |
| the mod's own tally | `suns=3 (sun+90) coins=3 (coins+106) ticks=300` |
| the button: one real draw pass, then clicks | `toggleRect=675,-10,2x1`, `gapToMenuButton=4`, `toggleIsLeftOfMenuInItsBand=true`, `toggleDoesNotOverlapMenuArt=true`, `insideBoard=true`, `labelWhileOn=ON`, `drawnPanelIsTheHitRect=true` |
| a click on the button is taken by the mod's Mixin | `clickOnToggleConsumed=true`, `plantDugUpByToggleClick=0` (and `plantDugUpByShovelClick=1` for the board's own input), `stateFlippedByThatClick=true` |
| switched off: a sun and a coin on the board, 60 ticks | `enabledAfterToggleClick=false`, `labelAfterToggleClick=OFF`, `sunDeltaWhileOff=0`, `coinsDeltaWhileOff=0`, `sunsLeftWhileOff=1`, `coinsLeftWhileOff=1` |
| switched on again, 300 ticks | `sunDeltaAfterSecondClick=25`, `coinsDeltaAfterSecondClick=5`, `sunsLeftAfterSecondClick=0`, `coinsLeftAfterSecondClick=0` |
| a Yeti killed through the game's own death path (`ZombieStatus.dieWithLoot`) | `yetiDiamondsOnBoard=4`, **`coinsDeltaAfterYeti=400`**, `levelCoinsDeltaAfterYeti=400`, `liveMoneyCoinsAfterYeti=0` |
| 300 rolls of the game's own loot path (`Board.dropLootPiece`) | `lootMoneyCoins=29`, `lootMoneyValue=49`, **`coinsDeltaAfterLoot=49`**, `liveMoneyCoinsAfterLoot=0` |
| the whole run | `sunMoneyAtEnd=165` (= 50+90+25), `playerCoinsAtEnd=560` (= 106+5+400+49), `levelCoinsCollectedAtEnd=560`, `modStatsAtEnd=suns=4 (sun+115) coins=37 (coins+560) ticks=1260` |

The button's rectangle in that table is `2x1` because the harness binds a 1x1 stub image for every
resource id (the harness never loads the retail art); the panel is then `left(1)+right(1)` by `1`,
which is the same arithmetic. In the real game, with the retail 9-slice, the same code gives
`71x46` at `606,-10` next to the Menu button at `681,-10,117x46` - the gap and the band, which is
what the layout is built on, are asserted in the harness (`gapToMenuButton=4`,
`toggleIsLeftOfMenuInItsBand=true`).

## Build and install

```sh
cd auto-collect-mod
./gradlew build          # jar + the end-to-end test
cp build/libs/auto-collect-mod-1.0.0.jar <gamedir>/mods/
```

`./gradlew runClient` is a development run: the mod's own output (its classes and resources) and the
API aggregate jar are on the class path and the loader discovers the mod there
(`-Dfabric.development=true`), with nothing copied into `run/mods` (the toolchain plugin -
`cn.enaium.fabricpvz.loom` version 1.0.2, applied above and resolved from the Gradle Plugin Portal,
which serves it; the settings file keeps `mavenLocal()` after the portal only as a fallback for a
version the portal does not serve yet - provides the task); the mod only needs its own jar -
the loader, Mixin, ASM, the API modules, the game and the Kotlin standard library all come from the
launcher.

## Licence

MIT, see `LICENSE`.
