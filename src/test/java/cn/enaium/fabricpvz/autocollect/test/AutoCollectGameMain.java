package cn.enaium.fabricpvz.autocollect.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import cn.enaium.fabricpvz.api.PvzFabricApi;
import cn.enaium.fabricpvz.autocollect.AutoCollect;
import cn.enaium.fabricpvz.autocollect.AutoCollectMod;
import cn.enaium.fabricpvz.autocollect.AutoCollectToggle;
import cn.enaium.fabricpvz.portable.lawn.Board;
import cn.enaium.fabricpvz.portable.lawn.Coin;
import cn.enaium.fabricpvz.portable.lawn.CoinMotion;
import cn.enaium.fabricpvz.portable.lawn.CoinType;
import cn.enaium.fabricpvz.portable.lawn.CursorType;
import cn.enaium.fabricpvz.portable.lawn.GameConstants;
import cn.enaium.fabricpvz.portable.lawn.GameMode;
import cn.enaium.fabricpvz.portable.lawn.GameObjectType;
import cn.enaium.fabricpvz.portable.lawn.GameScenes;
import cn.enaium.fabricpvz.portable.lawn.ImageID;
import cn.enaium.fabricpvz.portable.lawn.LawnApp;
import cn.enaium.fabricpvz.portable.lawn.Plant;
import cn.enaium.fabricpvz.portable.lawn.Resources;
import cn.enaium.fabricpvz.portable.lawn.SeedType;
import cn.enaium.fabricpvz.portable.lawn.Zombie;
import cn.enaium.fabricpvz.portable.lawn.ZombieStatus;
import cn.enaium.fabricpvz.portable.lawn.ZombieType;
import cn.enaium.fabricpvz.portable.sexy.Rect;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The game entry point of the end-to-end run: Fabric Loader starts this class instead of the real
 * {@code Main} ({@code -Dpvz.game.mainClass}), so everything below runs inside the loader's class
 * loader with the mods of a real {@code mods/} directory already resolved, their entry points run and
 * their Mixin configurations applied.
 *
 * <p>Because the loader defines its own copies of the game, the API and the mod classes, this class
 * cannot report to the test through memory: every observation is appended to the file named by
 * {@code -Dpvz.test.report} and the JUnit side asserts on it.
 *
 * <p>Phases:
 * <ol>
 *   <li>what the loader resolved, which mods are installed next to this one, and that this mod's
 *       Kotlin entry point ran;</li>
 *   <li>a real headless board ({@code preNewGame} + {@code startPlaying}) driven through the game's
 *       own frame entry point {@code LawnApp.updateFrames()};</li>
 *   <li>collecting with no control drawn yet: real suns and coins are put on the board, the board
 *       runs, and the game's own money counters must move by exactly their value, with nothing
 *       left over;</li>
 *   <li>the toggle: where it is, that a click flips it, that a switched-off mod really collects
 *       nothing, and that a click on it never reaches the board (the mod's Java mixin);</li>
 *   <li>coins dropped by kills ({@code ZombieStatus.dieWithLoot} of a Yeti drops four diamonds,
 *       {@code Board.dropLootPiece} is the game's own loot roll), all collected the same way.</li>
 * </ol>
 */
public final class AutoCollectGameMain {

    /** Ticks driven per measurement: enough for a clicked coin to fly to its counter and score. */
    private static final int TICKS = 300;

    /** Ticks driven while the mod is switched off; nothing may be collected in them. */
    private static final int OFF_TICKS = 60;

    /** The game's own loot rolls used for the "coins dropped by kills" case. */
    private static final int LOOT_ROLLS = 300;

    /** A sun-countdown far beyond this run: no sun may fall from the sky by the game's own timer. */
    private static final int NO_SKY_SUNS = 1_000_000;

    private static final StringBuilder REPORT = new StringBuilder();

    private static String sError = "";

    private AutoCollectGameMain() {
    }

    public static void main(String[] args) {
        Path aReportPath = Path.of(requiredProperty("pvz.test.report"));
        LawnApp aApp = null;
        try {
            loaderPhase();
            aApp = boardPhase();
            collectionPhase(aApp);
            controlsPhase(aApp);
            togglePhase(aApp);
            dropPhase(aApp);
        } catch (Throwable aThrowable) {
            sError = aThrowable.toString();
            aThrowable.printStackTrace();
        } finally {
            report("harnessError", sError.isEmpty() ? "none" : sError);
            try {
                flush(aReportPath);
            } catch (Exception anException) {
                anException.printStackTrace();
            }

            if (aApp != null) {
                LawnApp.gLawnApp = null;
            }
        }

        System.out.println("[auto-collect] report written to " + aReportPath);
        System.out.println(REPORT);

        if (!sError.isEmpty()) {
            throw new IllegalStateException("the auto-collect harness failed: " + sError);
        }
    }

    // ---------------------------------------------------------------- phase 1

    /** What the loader resolved, which mods sit next to this one, and that the entry point ran. */
    private static void loaderPhase() {
        FabricLoader aLoader = FabricLoader.getInstance();
        report("loaderVersion", aLoader.getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("<missing>"));
        report("gameVersion", aLoader.getRawGameVersion());
        report("environment", aLoader.getEnvironmentType().name().toLowerCase(Locale.ROOT));

        List<String> aModIds = new ArrayList<>();
        for (var aContainer : aLoader.getAllMods()) {
            aModIds.add(aContainer.getMetadata().getId());
        }
        report("modsLoaded", String.join(",", new TreeSet<>(aModIds)));
        report("modCount", aModIds.size());
        report("autoCollectModLoaded", aLoader.isModLoaded(AutoCollectMod.Companion.getMOD_ID()));
        report("speedModLoaded", aLoader.isModLoaded("speed-mod"));

        report("apiInitialized", PvzFabricApi.isInitialized());
        // Set by this mod's own entry point, which only Fabric Loader runs.
        report("autoCollectEntrypointRan", AutoCollectMod.Companion.getInitialized());
        // The mod's entry point is a Kotlin class of the mod jar: what it logged shows the Kotlin
        // standard library resolved at runtime (from the launch class path, not from the mod jar).
        report("kotlinEntrypoint", AutoCollectMod.Companion.getKotlinEntrypoint() == null
                ? "<missing>" : AutoCollectMod.Companion.getKotlinEntrypoint());
    }

    // ---------------------------------------------------------------- phase 2

    /** A real level: the game's own {@code preNewGame} + {@code startPlaying}, headlessly. */
    private static LawnApp boardPhase() {
        LawnApp aApp = new HeadlessLawn.LoadedReanimApp();
        aApp.mGameMode = GameMode.GAMEMODE_ADVENTURE;
        HeadlessLawn.initialize(aApp);
        aApp.mPlayerInfo.mLevel = 1;
        // Not a first-time player: no tutorial popups, and the game's own loot drops are enabled
        // (Board.canDropLoot, Board.java:2823-2825).
        aApp.mPlayerInfo.mFinishedAdventure = 1;

        aApp.preNewGame(GameMode.GAMEMODE_ADVENTURE, false);
        Board aBoard = aApp.mBoard;
        report("boardCreated", aBoard != null);
        aApp.startPlaying();
        // As in the speed mod's harness: the level-intro cutscene's last two lines, so the board is
        // in its playing scene with its menu button back on screen.
        aBoard.mMenuButton.mBtnNoDraw = false;
        // An adventure level picks its seeds in the seed chooser, which is what fills the bank;
        // Board.initLevel leaves the packets at SEED_NONE until then (Board.java:1074-1109). A board
        // that is playing always has real packets - and collecting a sun reads their costs
        // (Coin.java:1015-1027 calls Board.getCurrentPlantCost for every packet), so the harness fills
        // the bank the way the chooser would before anything is collected.
        int[] aSeeds = {
            SeedType.SEED_PEASHOOTER, SeedType.SEED_SUNFLOWER, SeedType.SEED_CHERRYBOMB,
            SeedType.SEED_WALLNUT, SeedType.SEED_REPEATER, SeedType.SEED_SNOWPEA,
            SeedType.SEED_CHOMPER, SeedType.SEED_POTATOMINE, SeedType.SEED_TANGLEKELP,
            SeedType.SEED_TORCHWOOD
        };
        int aPackets = Math.min(aBoard.mSeedBank.mNumPackets, aSeeds.length);
        for (int i = 0; i < aPackets; i++) {
            aBoard.mSeedBank.mSeedPackets[i].setPacketType(aSeeds[i], SeedType.SEED_NONE);
        }
        report("seedBankPackets", aPackets);
        // The game's own sky-sun timer (BoardWaves.java:961-971): pushed out of the way so that every
        // sun and coin on the board in the phases below was put there by this harness.
        aBoard.mSunCountDown = NO_SKY_SUNS;

        report("gameScene", aApp.mGameScene == GameScenes.SCENE_PLAYING ? "PLAYING" : String.valueOf(aApp.mGameScene));
        report("boardLevel", aBoard.mLevel);
        report("boardInWidgetTree", aApp.mWidgetManager.mWidgets.contains(aBoard));
        report("skySunCountdown", aBoard.mSunCountDown);
        report("startingSunMoney", aBoard.mSunMoney);
        report("startingPlayerCoins", aApp.mPlayerInfo.mCoins);
        report("startingCoinsCollected", aBoard.mCoinsCollected);
        // The mod collects from its own state, before any control exists - the state is on by default.
        report("modEnabledAtStart", AutoCollect.INSTANCE.isEnabled());
        report("controlsBeforeFirstDraw", AutoCollectMod.Companion.controls() != null);
        return aApp;
    }

    // ---------------------------------------------------------------- phase 3

    /**
     * Suns and coins on a real board, collected with no control drawn yet: the game's own counters
     * must move by exactly the sum of what was put there, and nothing may be left uncollected.
     */
    private static void collectionPhase(LawnApp theApp) {
        Board aBoard = theApp.mBoard;

        // Three sun types (25 / 15 / 50, Coin.java:1044-1049) and the three money types
        // (1 / 5 / 100, Coin.java:1051-1055): distinct values, so the sums below are real sums.
        List<Coin> aSuns = List.of(
                aBoard.addCoin(400, 60, CoinType.COIN_SUN, CoinMotion.COIN_MOTION_FROM_SKY),
                aBoard.addCoin(200, 60, CoinType.COIN_SMALLSUN, CoinMotion.COIN_MOTION_FROM_PLANT),
                aBoard.addCoin(600, 60, CoinType.COIN_LARGESUN, CoinMotion.COIN_MOTION_FROM_SKY));
        List<Coin> aCoins = List.of(
                aBoard.addCoin(300, 200, CoinType.COIN_SILVER, CoinMotion.COIN_MOTION_COIN),
                aBoard.addCoin(350, 220, CoinType.COIN_GOLD, CoinMotion.COIN_MOTION_COIN),
                aBoard.addCoin(400, 240, CoinType.COIN_DIAMOND, CoinMotion.COIN_MOTION_COIN));
        // Neither a sun nor money: collecting it would tick the chocolate counter and start its fade
        // (Coin.java:478-500), which "collect the sun and the coins" must not do.
        Coin aChocolate = aBoard.addCoin(500, 300, CoinType.COIN_CHOCOLATE, CoinMotion.COIN_MOTION_COIN);

        report("sunsPlaced", aSuns.size());
        report("coinsPlaced", aCoins.size());
        report("expectedSunValue", aSuns.stream().mapToInt(Coin::getSunValue).sum());
        report("expectedCoinValue", aCoins.stream().mapToInt(coin -> Coin.getCoinValue(coin.mType)).sum());
        report("sunTypesPlaced", types(aSuns));
        report("coinTypesPlaced", types(aCoins));
        // A falling sun is collectible from the tick it exists (Coin.mouseHitTest has no landing or
        // age condition, Coin.java:1165-1168): every sun above is still in the air here.
        report("sunsStillFalling", aSuns.stream().filter(coin -> !coin.mHitGround).count());
        report("sunCollectedBeforeAnyTick", aSuns.get(0).mIsBeingCollected);

        int aSunBefore = aBoard.mSunMoney;
        int aCoinsBefore = theApp.mPlayerInfo.mCoins;
        int aLevelCoinsBefore = aBoard.mCoinsCollected;
        int aModTicksBefore = AutoCollect.INSTANCE.getTicks();

        ticks(theApp, TICKS);

        report("ticksDriven", TICKS);
        report("modTicksInPhase", AutoCollect.INSTANCE.getTicks() - aModTicksBefore);
        report("sunMoneyBefore", aSunBefore);
        report("sunMoneyAfter", aBoard.mSunMoney);
        report("sunDelta", aBoard.mSunMoney - aSunBefore);
        report("playerCoinsBefore", aCoinsBefore);
        report("playerCoinsAfter", theApp.mPlayerInfo.mCoins);
        report("coinsDelta", theApp.mPlayerInfo.mCoins - aCoinsBefore);
        report("levelCoinsDelta", aBoard.mCoinsCollected - aLevelCoinsBefore);
        report("levelCoinsCollectedDigits", aBoard.mLevelCoinsCollected);
        report("sunsLeftOnBoard", liveSuns(aBoard));
        report("coinsLeftOnBoard", liveMoney(aBoard));
        report("chocolateCollected", aBoard.mChocolateCollected);
        report("chocolateBeingCollected", aChocolate.mIsBeingCollected);
        report("modStatsAfterCollection", AutoCollect.INSTANCE.stats());
        report("modSunsCollected", AutoCollect.INSTANCE.getSunsCollected());
        report("modCoinsCollected", AutoCollect.INSTANCE.getCoinsCollected());
        report("modSunValue", AutoCollect.INSTANCE.getSunValue());
        report("modCoinValue", AutoCollect.INSTANCE.getCoinValue());
        report("waveAfterCollection", aBoard.mCurrentWave);
        report("zombiesAfterCollection", aBoard.mZombies.mSize);
    }

    // ---------------------------------------------------------------- phase 4

    /** The toggle: where it is, what a click does, and that the board never sees that click. */
    private static void controlsPhase(LawnApp theApp) {
        Board aBoard = theApp.mBoard;

        // The control is created by the mod's post-draw hook; one real draw pass creates it.
        theApp.mWidgetManager.drawScreen();
        AutoCollectToggle aToggle = AutoCollectMod.Companion.controls();
        report("controlsCreated", aToggle != null && aToggle.board() == aBoard);
        report("controlsVisible", aToggle != null && aToggle.isVisible());
        report("controlsOfBoardAfterDraw", AutoCollectMod.Companion.controlsOf(aBoard) == aToggle);

        Rect aRect = aToggle.rect();
        Rect aMenu = new Rect(aBoard.mMenuButton.mX, aBoard.mMenuButton.mY,
                aBoard.mMenuButton.mWidth, aBoard.mMenuButton.mHeight);
        report("menuButtonRect", rect(aMenu));
        report("toggleRect", rect(aRect));
        report("stoneArt", sliceSize(ImageID.IMAGE_BUTTON_LEFT) + " " + sliceSize(ImageID.IMAGE_BUTTON_MIDDLE)
                + " " + sliceSize(ImageID.IMAGE_BUTTON_RIGHT));
        report("stoneArtHeight", artHeight());
        report("panelHeightIsStoneArt", aRect.mHeight == artHeight());
        report("drawnPanelIsTheHitRect",
                AutoCollectToggle.Companion.drawnWidth(aRect.mWidth) == aRect.mWidth);
        report("toggleIsLeftOfMenuInItsBand",
                aRect.mY == aMenu.mY
                        && aRect.mX + aRect.mWidth + AutoCollectToggle.Companion.getGAP() <= aMenu.mX);
        report("gapToMenuButton", aMenu.mX - (aRect.mX + aRect.mWidth));
        report("toggleDoesNotOverlapMenuArt", !aRect.intersects(aMenu));
        report("insideBoard",
                aRect.mX >= 0 && aRect.mX + aRect.mWidth <= GameConstants.BOARD_WIDTH
                        && aRect.mY + aRect.mHeight <= GameConstants.BOARD_HEIGHT);
        report("labelWhileOn", aToggle.label());

        // The board's own input still works with the mod's mixin installed: a shovel click on a plant
        // digs it up...
        Plant aPlant = aBoard.newPlant(0, 0, SeedType.SEED_PEASHOOTER, SeedType.SEED_NONE);
        aBoard.pickUpTool(GameObjectType.OBJECT_TYPE_SHOVEL);
        report("shovelCursor", aBoard.mCursorObject.mCursorType == CursorType.CURSOR_TYPE_SHOVEL);
        int aShovelled = aBoard.mPlantsShoveled;
        click(aBoard, aPlant.mX + 40, aPlant.mY + 40);
        report("plantDugUpByShovelClick", aBoard.mPlantsShoveled - aShovelled);

        // ...and a shovel click on the toggle digs nothing up, because the mixin took the click.
        Plant aSecondPlant = aBoard.newPlant(0, 0, SeedType.SEED_PEASHOOTER, SeedType.SEED_NONE);
        report("secondPlantPlaced", aSecondPlant != null);
        aBoard.pickUpTool(GameObjectType.OBJECT_TYPE_SHOVEL);
        int aShovelledBefore = aBoard.mPlantsShoveled;
        boolean aEnabledBefore = AutoCollect.INSTANCE.isEnabled();
        int[] aCentre = centre(aRect);
        aBoard.mouseDown(aCentre[0], aCentre[1], 1);
        report("clickOnToggleConsumed", aBoard.mIgnoreMouseUp);
        aBoard.mouseUp(aCentre[0], aCentre[1], 1);
        report("plantDugUpByToggleClick", aBoard.mPlantsShoveled - aShovelledBefore);
        report("stateFlippedByThatClick", AutoCollect.INSTANCE.isEnabled() != aEnabledBefore);
        // Back to the state the phase started in, and the board out of shovel mode.
        AutoCollect.INSTANCE.setEnabled(true);
        aBoard.clearCursor();
        report("secondPlantStillThere", !aSecondPlant.mDead);
        report("cursorAfterControls", aBoard.mCursorObject.mCursorType);
    }

    // ---------------------------------------------------------------- phase 5

    /** The toggle switches the mod off, and off means nothing at all is collected. */
    private static void togglePhase(LawnApp theApp) {
        Board aBoard = theApp.mBoard;
        AutoCollectToggle aToggle = AutoCollectMod.Companion.controls();
        int[] aCentre = centre(aToggle.rect());

        // Off: a sun and a coin are put on the board and the game runs on; both must still be there
        // afterwards, uncollected, and no counter may have moved.
        click(aBoard, aCentre[0], aCentre[1]);
        report("enabledAfterToggleClick", AutoCollect.INSTANCE.isEnabled());
        report("labelAfterToggleClick", aToggle.label());

        Coin aSun = aBoard.addCoin(400, 60, CoinType.COIN_SUN, CoinMotion.COIN_MOTION_FROM_SKY);
        Coin aCoin = aBoard.addCoin(300, 200, CoinType.COIN_GOLD, CoinMotion.COIN_MOTION_COIN);
        int aSunBeforeOff = aBoard.mSunMoney;
        int aCoinsBeforeOff = theApp.mPlayerInfo.mCoins;

        ticks(theApp, OFF_TICKS);

        report("ticksDrivenWhileOff", OFF_TICKS);
        report("sunDeltaWhileOff", aBoard.mSunMoney - aSunBeforeOff);
        report("coinsDeltaWhileOff", theApp.mPlayerInfo.mCoins - aCoinsBeforeOff);
        report("sunsLeftWhileOff", liveSuns(aBoard));
        report("coinsLeftWhileOff", liveMoney(aBoard));
        report("sunBeingCollectedWhileOff", aSun.mIsBeingCollected);
        report("coinBeingCollectedWhileOff", aCoin.mIsBeingCollected);

        // On again: the same two coins are collected, exactly as they would have been at once.
        click(aBoard, aCentre[0], aCentre[1]);
        report("enabledAfterSecondToggleClick", AutoCollect.INSTANCE.isEnabled());
        report("labelAfterSecondToggleClick", aToggle.label());

        int aSunBeforeOn = aBoard.mSunMoney;
        int aCoinsBeforeOn = theApp.mPlayerInfo.mCoins;

        ticks(theApp, TICKS);

        report("sunDeltaAfterSecondClick", aBoard.mSunMoney - aSunBeforeOn);
        report("coinsDeltaAfterSecondClick", theApp.mPlayerInfo.mCoins - aCoinsBeforeOn);
        report("sunsLeftAfterSecondClick", liveSuns(aBoard));
        report("coinsLeftAfterSecondClick", liveMoney(aBoard));
        report("modStatsAfterToggle", AutoCollect.INSTANCE.stats());
    }

    // ---------------------------------------------------------------- phase 6

    /** Coins dropped by kills: the game's own loot path, and every coin of it must be collected. */
    private static void dropPhase(LawnApp theApp) {
        Board aBoard = theApp.mBoard;

        // A real kill: a Yeti zombie's death drops four diamonds, deterministically
        // (ZombieStatus.java:548-553), through the same ZombieStatus.dieWithLoot every zombie's death
        // goes through. Nothing may be left of them afterwards.
        Zombie aYeti = aBoard.addZombieInRow(ZombieType.ZOMBIE_YETI, 2, -1);
        report("yetiSpawned", aYeti != null);
        int aCoinsBeforeYeti = theApp.mPlayerInfo.mCoins;
        int aLevelCoinsBeforeYeti = aBoard.mCoinsCollected;
        ZombieStatus.dieWithLoot(aYeti);
        report("yetiDiamondsOnBoard", countMoneyOfType(aBoard, CoinType.COIN_DIAMOND));

        ticks(theApp, TICKS);

        report("coinsDeltaAfterYeti", theApp.mPlayerInfo.mCoins - aCoinsBeforeYeti);
        report("levelCoinsDeltaAfterYeti", aBoard.mCoinsCollected - aLevelCoinsBeforeYeti);
        report("liveMoneyCoinsAfterYeti", liveMoney(aBoard));

        // The random loot roll of every normal kill: Board.dropLootPiece (Board.java:2731-2821),
        // called by ZombieStatus.dropLoot (ZombieStatus.java:555) with the zombie's own value.
        int aLootValue = 0;
        for (int aRoll = 0; aRoll < LOOT_ROLLS; aRoll++) {
            int aBefore = moneyValueOnBoard(aBoard);
            aBoard.dropLootPiece(400, 300, 1);
            aLootValue += moneyValueOnBoard(aBoard) - aBefore;
        }
        report("lootRolls", LOOT_ROLLS);
        report("lootMoneyCoins", liveMoney(aBoard));
        report("lootOtherCoins", liveOtherCoins(aBoard));
        report("lootMoneyValue", aLootValue);

        int aCoinsBeforeLoot = theApp.mPlayerInfo.mCoins;
        int aLevelCoinsBeforeLoot = aBoard.mCoinsCollected;

        ticks(theApp, TICKS);

        report("coinsDeltaAfterLoot", theApp.mPlayerInfo.mCoins - aCoinsBeforeLoot);
        report("levelCoinsDeltaAfterLoot", aBoard.mCoinsCollected - aLevelCoinsBeforeLoot);
        report("liveMoneyCoinsAfterLoot", liveMoney(aBoard));
        report("liveSunCoinsAfterLoot", liveSuns(aBoard));
        report("modStatsAtEnd", AutoCollect.INSTANCE.stats());
        report("modSunsCollectedAtEnd", AutoCollect.INSTANCE.getSunsCollected());
        report("modCoinsCollectedAtEnd", AutoCollect.INSTANCE.getCoinsCollected());
        report("modSunValueAtEnd", AutoCollect.INSTANCE.getSunValue());
        report("modCoinValueAtEnd", AutoCollect.INSTANCE.getCoinValue());
        report("sunMoneyAtEnd", aBoard.mSunMoney);
        report("playerCoinsAtEnd", theApp.mPlayerInfo.mCoins);
        report("levelCoinsCollectedAtEnd", aBoard.mCoinsCollected);
        report("zombiesAtEnd", aBoard.mZombies.mSize);
    }

    // ---------------------------------------------------------------- counting helpers

    /** The suns a player could click right now. */
    private static long liveSuns(Board theBoard) {
        long aCount = 0;
        for (Coin aCoin : theBoard.mCoins) {
            if (!aCoin.mDead && aCoin.isSun()) {
                aCount++;
            }
        }
        return aCount;
    }

    /** The money a player could click right now. */
    private static long liveMoney(Board theBoard) {
        long aCount = 0;
        for (Coin aCoin : theBoard.mCoins) {
            if (!aCoin.mDead && aCoin.isMoney()) {
                aCount++;
            }
        }
        return aCount;
    }

    /** The total value of the money on the board right now. */
    private static int moneyValueOnBoard(Board theBoard) {
        int aValue = 0;
        for (Coin aCoin : theBoard.mCoins) {
            if (!aCoin.mDead && aCoin.isMoney()) {
                aValue += Coin.getCoinValue(aCoin.mType);
            }
        }
        return aValue;
    }

    /** How many non-money coins are on the board right now (presents, chocolate, awards, ...). */
    private static long liveOtherCoins(Board theBoard) {
        long aCount = 0;
        for (Coin aCoin : theBoard.mCoins) {
            if (!aCoin.mDead && !aCoin.isSun() && !aCoin.isMoney()) {
                aCount++;
            }
        }
        return aCount;
    }

    private static int countMoneyOfType(Board theBoard, int theType) {
        int aCount = 0;
        for (Coin aCoin : theBoard.mCoins) {
            if (!aCoin.mDead && aCoin.mType == theType) {
                aCount++;
            }
        }
        return aCount;
    }

    private static String types(List<Coin> theCoins) {
        List<String> aNames = new ArrayList<>();
        for (Coin aCoin : theCoins) {
            aNames.add(String.valueOf(aCoin.mType));
        }
        return String.join(",", aNames);
    }

    // ---------------------------------------------------------------- driving helpers

    /** Runs the game's own frame entry point {@code theTicks} times. */
    private static void ticks(LawnApp theApp, int theTicks) {
        for (int aTick = 0; aTick < theTicks; aTick++) {
            theApp.updateFrames();
        }
    }

    /** A press and release at a point, the way the framework's widget manager routes a click. */
    private static void click(Board theBoard, int theX, int theY) {
        theBoard.mouseDown(theX, theY, 1);
        theBoard.mouseUp(theX, theY, 1);
    }

    private static int[] centre(Rect theRect) {
        return new int[] {theRect.mX + theRect.mWidth / 2, theRect.mY + theRect.mHeight / 2};
    }

    /** Height of the stone art the button is drawn at (the 9-slice ignores the requested height). */
    private static int artHeight() {
        return Resources.getImageById(ImageID.IMAGE_BUTTON_MIDDLE).getHeight();
    }

    private static String sliceSize(int theImageId) {
        return Resources.getImageById(theImageId).getWidth() + "x"
                + Resources.getImageById(theImageId).getHeight();
    }

    private static String rect(Rect theRect) {
        return theRect.mX + "," + theRect.mY + "," + theRect.mWidth + "x" + theRect.mHeight;
    }

    // ---------------------------------------------------------------- reporting

    private static String requiredProperty(String theName) {
        String aValue = System.getProperty(theName, "");
        if (aValue.isBlank()) {
            throw new IllegalStateException("-D" + theName + " is required");
        }
        return aValue;
    }

    private static void report(String theKey, Object theValue) {
        REPORT.append(theKey).append('=').append(theValue).append('\n');
    }

    private static void flush(Path theReportPath) throws Exception {
        Files.writeString(theReportPath, REPORT.toString());
    }
}
