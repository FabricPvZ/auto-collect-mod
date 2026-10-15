package cn.enaium.fabricpvz.autocollect

import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.Coin

/**
 * What the mod does: walk the board's coins and collect the suns and the money, by replaying the
 * game's own click handler for each of them.
 *
 * ## What the game does when the player clicks a sun or a coin
 *
 * The board's input pass hit-tests every coin and routes the click into the coin itself:
 *
 * - `BoardInput.mouseHitTest` (`BoardInput.java:1058-1101`) walks `Board.mCoins` and asks each coin
 *   `Coin.mouseHitTest` (`Coin.java:1148-1186`). A coin answers "hit" while `!mDead &&
 *   !mIsBeingCollected` (`Coin.java:1165-1168`) - there is no "has landed yet" or "is old enough"
 *   condition, so a sun is clickable from the tick it exists, whether it is still falling from the
 *   sky (`BoardWaves.java:963-971`, `COIN_MOTION_FROM_SKY`) or still popping out of a plant
 *   (`Plant.java:1018-1040`, `COIN_MOTION_FROM_PLANT`). A `COIN_SUN` gets a 15 px larger hit box
 *   than the other coins (`Coin.java:1160-1162`).
 * - `BoardInput.mouseDown` (`BoardInput.java:1281-1335`) hands the click to the hit coin:
 *   `aCoin.mouseDown(x, y, theClickCount)` (`BoardInput.java:1335`).
 * - `Coin.mouseDown` (`Coin.java:1125-1134`) is the whole player-facing collection:
 *   `playCollectSound(); collect();`, gated on the board not being paused, the scene being
 *   `SCENE_PLAYING` and the coin not being dead (`Coin.java:1126-1128`) - and it is *this* method the
 *   mod calls, so a mod-collected sun is a clicked sun in every respect, sound and advice included.
 * - `Coin.collect` (`Coin.java:827-834`) remembers where the coin was (`mCollectX/mCollectY`) and
 *   sets `mIsBeingCollected`. From then on `Coin.update` (`Coin.java:580-593`) runs
 *   `Coin.updateCollected` (`Coin.java:482-578`), which flies the coin to its counter (sun: `(15, 0)`;
 *   money: `(39, 558)`, the coin bank) and calls `Coin.scoreCoin` (`Coin.java:347-369`) as soon as
 *   `mCollectionDistance` is below 8 px (sun) or 12 px (money) (`Coin.java:565-571`).
 * - `Coin.scoreCoin` credits: a sun with `mBoard.addSunMoney(getSunValue())` (`Coin.java:351-353`,
 *   `Board.java:2202-2207`, capped at 9990), money with `mApp.mPlayerInfo.addCoins(value)` plus the
 *   board's own tallies `Board.mCoinsCollected` and `Board.mLevelCoinsCollected` (`Coin.java:354-366`).
 *   `getSunValue` is 25 / 15 / 50 for `COIN_SUN` / `COIN_SMALLSUN` / `COIN_LARGESUN`
 *   (`Coin.java:1044-1049`), `getCoinValue` is 1 / 5 / 100 for `COIN_SILVER` / `COIN_GOLD` /
 *   `COIN_DIAMOND` (`Coin.java:1051-1055`).
 *
 * So "collect" is exactly one call, and the counters the player sees are `Board.mSunMoney`
 * (`Board.java:146`; drawn in the seed bank) and `PlayerInfo.mCoins` (`PlayerInfo.java:50`; drawn as
 * the coin bank, `BoardRender.java:1270-1291`).
 *
 * ## What this mod does
 *
 * On every gameplay tick it looks for a coin that a player could click right now and clicks it:
 * `isSun() || isMoney()`, not dead, not already being collected - the same set the game's own
 * hit test would offer the mouse - and it stops where a player's mouse stops too: while the level's
 * fade-out is running the board's hit test answers "nothing here" (`BoardInput.java:1059` checks
 * `mBoardFadeOutCounter >= 0`), so [collect] does nothing then either.
 *
 * Only suns and money are touched. Presents, seed packets, chocolate, trophies and the level awards
 * are left exactly where they are: the game gives those their own side effects (a present plants
 * itself in the zen garden, a usable seed packet takes the cursor, a level award starts the level
 * end), which is not what "collect the sun" should do behind the player's back.
 *
 * This is a Kotlin `object`, so its single instance is the way in from everywhere: Kotlin calls
 * `AutoCollect.tick(board)`, the Java side (the end-to-end harness) calls
 * `AutoCollect.INSTANCE.tick(board)` and `AutoCollect.INSTANCE.getSunsCollected()`. Nothing is
 * exposed with `@JvmStatic`/`@JvmField`, so the object is never reachable as a set of Java statics.
 */
object AutoCollect {

    /** Whether the mod is collecting. On by default; see [AutoCollectToggle]. */
    private var collecting: Boolean = true

    /** Suns collected by this mod since the last [reset]. */
    var sunsCollected: Int = 0

    /** Coins collected by this mod since the last [reset]. */
    var coinsCollected: Int = 0

    /** Sun credited by this mod (the sum of the suns' values). */
    var sunValue: Int = 0

    /** Money credited by this mod (the sum of the coins' values). */
    var coinValue: Int = 0

    /** Ticks the mod was called on since the last [reset]. */
    var ticks: Int = 0

    /** True while the mod collects. */
    fun isEnabled(): Boolean = collecting

    /** Turns collection on or off; the toggle button calls this. */
    fun setEnabled(theEnabled: Boolean) {
        collecting = theEnabled
    }

    /** Flips collection; returns the new state. */
    fun toggle(): Boolean {
        collecting = !collecting
        return collecting
    }

    /** The mod's own tally, for a log line or an end-to-end report. */
    fun stats(): String =
        "suns=$sunsCollected (sun+$sunValue) coins=$coinsCollected (coins+$coinValue) ticks=$ticks"

    /** Zeroes the tally (not the on/off state); a level change or a test starts from here. */
    fun resetStats() {
        sunsCollected = 0
        coinsCollected = 0
        sunValue = 0
        coinValue = 0
        ticks = 0
    }

    /**
     * One gameplay tick: collect everything on the board the player could have clicked.
     *
     * Called from the mod's `LawnEvents.TICK` listener, which the game fires as the last statement of
     * `Board.updateGame()` (`Board.java:3571`) - after the board's own object passes have updated
     * every coin for this tick (`Board.java:3393-3397`), so a coin this mod collects is collected
     * from the position the player saw it at.
     */
    fun tick(theBoard: Board) {
        ticks++
        if (collecting) {
            collect(theBoard)
        }
    }

    /**
     * Clicks every collectible sun and coin on the board the way the game's own input pass would.
     *
     * @return how many coins the game accepted the click for
     */
    fun collect(theBoard: Board): Int {
        if (theBoard.mBoardFadeOutCounter >= 0) {
            // The level is fading out: the board's own hit test offers no coin to the mouse
            // (BoardInput.java:1059), so neither does the mod.
            return 0
        }

        var aCollected = 0
        for (aCoin in theBoard.mCoins) {
            if (aCoin.mDead || aCoin.mIsBeingCollected) {
                continue
            }
            if (!aCoin.isSun() && !aCoin.isMoney()) {
                continue
            }

            // Read the values before the click: the game's handler may already know the coin changes
            // type (a usable seed packet becomes the cursor), these two never do.
            val aSunValue = aCoin.getSunValue()
            val aCoinValue = Coin.getCoinValue(aCoin.mType)

            aCoin.mouseDown(aCoin.mPosX.toInt(), aCoin.mPosY.toInt(), 1)
            if (!aCoin.mIsBeingCollected) {
                // The game's own handler refused the click (paused board, scene not playing): a player
                // clicking there would have got nothing either.
                continue
            }

            aCollected++
            if (aSunValue > 0) {
                sunsCollected++
                sunValue += aSunValue
            } else {
                coinsCollected++
                coinValue += aCoinValue
            }
        }

        return aCollected
    }
}
