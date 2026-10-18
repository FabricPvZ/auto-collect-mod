package cn.enaium.fabricpvz.autocollect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import kotlin.KotlinVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cn.enaium.fabricpvz.provider.PvzGameProvider;

/**
 * End-to-end verification of the auto-collect mod on the real Fabric Loader.
 *
 * <p>The test does exactly what a launcher does: it copies the <em>built mod jar</em> into a real
 * {@code mods/} directory - together with the sibling speed mod's jar, which is installed in this
 * workspace - and starts {@code net.fabricmc.loader.impl.launch.knot.KnotClient} in a second JVM with
 * the game, the loader (plus Mixin and ASM, the game's launch libraries)
 * and the API modules on the class path. The loader resolves both mods, runs their
 * {@code ModInitializer}s (this mod's is a Kotlin class), registers their Mixin configurations and
 * only then calls the game entry point, which this test redirects to
 * {@link cn.enaium.fabricpvz.autocollect.test.AutoCollectGameMain} with {@code -Dpvz.game.mainClass}.
 *
 * <p>Running inside the loader is the whole point: without it nothing would collect anything, so the
 * numbers below are the only way to show the mod works as a mod and not as a library.
 *
 * <p>The harness reports through a file because the loader defines its own copies of the game, the
 * API and the mod classes; the assertions below are all made on that report.
 */
class AutoCollectEndToEndTest {

    /** Launcher main class of the real Fabric Loader (client environment), from its own manifest. */
    private static final String LAUNCHER_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";

    /** System property carrying the class path the launcher has to run with. */
    private static final String FORK_CLASSPATH_PROPERTY = "pvz.test.forkClasspath";

    /** System property naming the built mod jar. */
    private static final String MOD_JAR_PROPERTY = "pvz.test.modJar";

    /** The sibling mod this one is loaded next to, if its jar is where this workspace installs it. */
    private static final List<String> COMPANION_JARS = List.of(
            "../PvZ-Portable/run/mods/speed-mod-1.0.0.jar",
            "../speed-mod/build/libs/speed-mod-1.0.0.jar");

    /**
     * Mod ids that must be resolved: the loader, the game, the aggregate API mod and the API modules,
     * plus this mod and the speed mod it is installed next to.
     */
    private static final List<String> DEPENDENCY_MODS = List.of(
            "fabricloader", "pvz-portable", "fabric-api",
            "api-base", "api-events", "api-plant", "api-zombie");

    /** Ticks the harness drives per measurement; the assertions below use it for their arithmetic. */
    private static final int TICKS = 300;

    /** The sun the harness puts on the board in its collecting phase: 25 + 15 + 50. */
    private static final int PLACED_SUN = 25 + 15 + 50;

    /** The money the harness puts on the board in its collecting phase: 1 + 5 + 100. */
    private static final int PLACED_MONEY = 1 + 5 + 100;

    /** Sun of the one sun created while the mod is switched off and collected after switching it on. */
    private static final int TOGGLE_SUN = 25;

    /** Money of the one coin created while the mod is switched off and collected after switching it on. */
    private static final int TOGGLE_MONEY = 5;

    /** A Yeti zombie's death drops four diamonds (100 each), ZombieStatus.java:548-552. */
    private static final int YETI_MONEY = 4 * 100;

    @TempDir
    Path tmp;

    @Test
    void collectsSunAndCoinsNextToTheSpeedModFromTheInstalledModJar() throws Exception {
        Path modJar = Path.of(requiredProperty(MOD_JAR_PROPERTY));
        assertTrue(Files.isRegularFile(modJar), "the mod jar must be built first: " + modJar);
        assertModJarContents(modJar);

        Path mods = Files.createDirectories(tmp.resolve("mods"));
        Path installed = Files.copy(modJar, mods.resolve(modJar.getFileName()));
        assertTrue(Files.size(installed) > 0, "the installed mod jar must not be empty");

        // The mod is installed next to the speed mod, exactly like in the game directory.
        Path companion = installCompanionJar(mods);

        Path runDir = Files.createDirectories(tmp.resolve("run"));
        Path report = tmp.resolve("auto-collect-report.txt");
        Path output = tmp.resolve("loader-output.txt");

        int exitCode = launch(mods, runDir, report, output);
        assertEquals(0, exitCode, "Fabric Loader must boot the game with the mod: " + failureMessage(output, report));

        Map<String, String> values = readReport(report);
        assertEquals("none", values.get("harnessError"), "the harness must run to the end");

        // 1) The loader resolved the game, the API modules, this mod and the speed mod it sits next
        //    to, and ran the entry points.
        // Against the provider's own statement, not a literal: what this asserts is that the loader is
        // handed the version the game provider publishes, whatever the game version is.
        assertEquals(PvzGameProvider.DEFAULT_GAME_VERSION, values.get("gameVersion"),
            "the provider must report the game's version");
        assertEquals("client", values.get("environment"));
        assertEquals("true", values.get("autoCollectModLoaded"));
        assertEquals("true", values.get("speedModLoaded"),
                "the speed mod's jar must be resolved next to this one");
        for (String id : DEPENDENCY_MODS) {
            assertTrue(List.of(values.get("modsLoaded").split(",")).contains(id),
                    "mod " + id + " must be loaded, got " + values.get("modsLoaded"));
        }
        assertTrue(List.of(values.get("modsLoaded").split(",")).contains("speed-mod"),
                "the loader's resolved mod list must name the speed mod, got " + values.get("modsLoaded"));
        // The mod is Kotlin, so the real language module has to be resolved next to it: this mod
        // declares the dependency in its fabric.mod.json and the toolchain puts the artifact on the
        // launcher class path (a development run discovers the mods that are on the class path).
        assertTrue(List.of(values.get("modsLoaded").split(",")).contains("fabric-language-kotlin"),
                "fabric-language-kotlin must be resolved next to this mod, got " + values.get("modsLoaded"));
        assertEquals("true", values.get("apiInitialized"));
        assertEquals("true", values.get("autoCollectEntrypointRan"));

        // 1b) The entry point is a Kotlin class of this mod's jar, and it ran inside the loader with the
        //     Kotlin standard library the language module carries (its nested jar, which the loader puts
        //     on the class path - never a stdlib in a mod jar).
        String kotlinLine = values.get("kotlinEntrypoint");
        assertNotNull(kotlinLine, "the mod's Kotlin entry point must have reported from inside the loader");
        assertTrue(kotlinLine.startsWith("[auto-collect-mod] Kotlin " + KotlinVersion.CURRENT
                        + " entry point class ran"),
                "the entry point must be a Kotlin class run against the toolchain's stdlib, got: " + kotlinLine);
        assertFalse(kotlinLine.contains("org.jetbrains.kotlin/kotlin-stdlib"),
                "the mod's runtime stdlib must be the one fabric-language-kotlin carries, not a plain "
                        + "library that happens to be on this test's class path, got: " + kotlinLine);

        // 2) A real level is running: the board is in the widget tree and the scene is playing.
        assertEquals("true", values.get("boardCreated"));
        assertEquals("PLAYING", values.get("gameScene"));
        assertEquals("true", values.get("boardInWidgetTree"));

        // 3) Collecting, with no control drawn yet: three suns (25/15/50) and three coins (1/5/100) are
        //    put on a real board; the board's own counters must move by exactly their value, the mod's
        //    own tally must agree with them, and nothing may be left on the board.
        assertEquals("true", values.get("modEnabledAtStart"), "the mod collects without a click: on by default");
        assertEquals("false", values.get("controlsBeforeFirstDraw"),
                "the control only exists after the board drew itself");
        assertEquals("1000000", values.get("skySunCountdown"),
                "the harness must have pushed the game's own sky-sun timer out of the way");
        assertEquals("3", values.get("sunsPlaced"));
        assertEquals("3", values.get("coinsPlaced"));
        assertEquals(String.valueOf(PLACED_SUN), values.get("expectedSunValue"));
        assertEquals(String.valueOf(PLACED_MONEY), values.get("expectedCoinValue"));
        assertEquals("4,5,6", values.get("sunTypesPlaced"), "one sun, one small sun, one large sun");
        assertEquals("1,2,3", values.get("coinTypesPlaced"), "silver, gold, diamond");
        assertEquals("3", values.get("sunsStillFalling"),
                "a sun is clickable while it falls (Coin.java:1165-1168), so all three are in the air");
        assertEquals("false", values.get("sunCollectedBeforeAnyTick"));
        assertEquals(String.valueOf(TICKS), values.get("modTicksInPhase"),
                "the mod must see one tick per game tick (Board.java:3571)");
        assertEquals(String.valueOf(PLACED_SUN), values.get("sunDelta"), "the sun counter must move by 90");
        assertEquals(String.valueOf(PLACED_MONEY), values.get("coinsDelta"), "the coin bank must move by 106");
        assertEquals(String.valueOf(PLACED_MONEY), values.get("levelCoinsDelta"));
        assertEquals("0", values.get("sunsLeftOnBoard"), "no sun may stay uncollected");
        assertEquals("0", values.get("coinsLeftOnBoard"), "no coin may stay uncollected");
        assertEquals("0", values.get("chocolateCollected"),
                "a chocolate is neither sun nor money and must be left where it is");
        assertEquals("false", values.get("chocolateBeingCollected"));
        assertEquals("3", values.get("modSunsCollected"));
        assertEquals("3", values.get("modCoinsCollected"));
        assertEquals(String.valueOf(PLACED_SUN), values.get("modSunValue"));
        assertEquals(String.valueOf(PLACED_MONEY), values.get("modCoinValue"));

        // 4) The toggle: a stone panel in the menu button's band, left of it (where the speed mod's
        //    rows below the menu button cannot reach), and a click on it is consumed by the mod's
        //    Java mixin instead of reaching the board.
        assertEquals("true", values.get("controlsCreated"));
        assertEquals("true", values.get("controlsVisible"));
        assertEquals("true", values.get("controlsOfBoardAfterDraw"));
        assertEquals("true", values.get("insideBoard"));
        assertEquals("true", values.get("toggleIsLeftOfMenuInItsBand"));
        assertEquals("4", values.get("gapToMenuButton"));
        assertEquals("true", values.get("toggleDoesNotOverlapMenuArt"));
        assertEquals("true", values.get("panelHeightIsStoneArt"));
        assertEquals("true", values.get("drawnPanelIsTheHitRect"),
                "the drawn panel must be the rectangle the control hit-tests with");
        assertEquals("ON", values.get("labelWhileOn"), "the mod is on by default");
        assertEquals("true", values.get("shovelCursor"));
        assertEquals("1", values.get("plantDugUpByShovelClick"), "the board's own input must still work");
        assertEquals("true", values.get("clickOnToggleConsumed"), "the mixin must take the click");
        assertEquals("0", values.get("plantDugUpByToggleClick"), "a click on the toggle must not reach the board");
        assertEquals("true", values.get("stateFlippedByThatClick"));
        assertEquals("true", values.get("secondPlantStillThere"));

        // 5) Switched off, the mod collects nothing at all; switched on again it collects what it
        //    skipped - a sun 25 and a coin 5, exactly.
        assertEquals("false", values.get("enabledAfterToggleClick"));
        assertEquals("OFF", values.get("labelAfterToggleClick"));
        assertEquals("0", values.get("sunDeltaWhileOff"));
        assertEquals("0", values.get("coinsDeltaWhileOff"));
        assertEquals("1", values.get("sunsLeftWhileOff"));
        assertEquals("1", values.get("coinsLeftWhileOff"));
        assertEquals("false", values.get("sunBeingCollectedWhileOff"));
        assertEquals("false", values.get("coinBeingCollectedWhileOff"));
        assertEquals("true", values.get("enabledAfterSecondToggleClick"));
        assertEquals("ON", values.get("labelAfterSecondToggleClick"));
        assertEquals(String.valueOf(TOGGLE_SUN), values.get("sunDeltaAfterSecondClick"));
        assertEquals(String.valueOf(TOGGLE_MONEY), values.get("coinsDeltaAfterSecondClick"));
        assertEquals("0", values.get("sunsLeftAfterSecondClick"));
        assertEquals("0", values.get("coinsLeftAfterSecondClick"));

        // 6) Coins dropped by kills. A Yeti's death drops exactly four diamonds through
        //    ZombieStatus.dieWithLoot; the game's own loot roll (Board.dropLootPiece) drops a random
        //    coin for a normal kill. Every coin of both must be collected, and the coin bank must move
        //    by exactly their value.
        assertEquals("true", values.get("yetiSpawned"));
        assertEquals("4", values.get("yetiDiamondsOnBoard"), "a Yeti drops four diamonds");
        assertEquals(String.valueOf(YETI_MONEY), values.get("coinsDeltaAfterYeti"));
        assertEquals(String.valueOf(YETI_MONEY), values.get("levelCoinsDeltaAfterYeti"));
        assertEquals("0", values.get("liveMoneyCoinsAfterYeti"));

        int lootCoins = intValue(values, "lootMoneyCoins");
        int lootValue = intValue(values, "lootMoneyValue");
        assertTrue(lootCoins > 0, "300 loot rolls of the game's own drop path must drop something");
        assertTrue(lootValue >= lootCoins, "a dropped coin is worth at least 1, got " + lootValue);
        assertEquals(String.valueOf(lootValue), values.get("coinsDeltaAfterLoot"),
                "dropped coins must be credited exactly once each");
        assertEquals(String.valueOf(lootValue), values.get("levelCoinsDeltaAfterLoot"));
        assertEquals("0", values.get("liveMoneyCoinsAfterLoot"), "no dropped coin may stay uncollected");

        // 7) The whole run, in the game's own counters: the sun counter and the coin bank moved by
        //    exactly what was put on the board, and the mod's own tally agrees with them.
        int startSun = intValue(values, "startingSunMoney");
        int startCoins = intValue(values, "startingPlayerCoins");
        int startLevelCoins = intValue(values, "startingCoinsCollected");
        assertEquals(String.valueOf(startSun + PLACED_SUN + TOGGLE_SUN), values.get("sunMoneyAtEnd"),
                "the sun counter must have moved by exactly the suns collected");
        assertEquals(String.valueOf(startCoins + PLACED_MONEY + TOGGLE_MONEY + YETI_MONEY + lootValue),
                values.get("playerCoinsAtEnd"), "the coin bank must have moved by exactly the coins collected");
        assertEquals(String.valueOf(startLevelCoins + PLACED_MONEY + TOGGLE_MONEY + YETI_MONEY + lootValue),
                values.get("levelCoinsCollectedAtEnd"));
        assertEquals("4", values.get("modSunsCollectedAtEnd"), "3 suns + 1 sun after the toggle");
        assertEquals(String.valueOf(3 + 1 + 4 + lootCoins), values.get("modCoinsCollectedAtEnd"));
        assertEquals(String.valueOf(PLACED_SUN + TOGGLE_SUN), values.get("modSunValueAtEnd"));
        assertEquals(String.valueOf(PLACED_MONEY + TOGGLE_MONEY + YETI_MONEY + lootValue),
                values.get("modCoinValueAtEnd"));
    }

    /** Asserts a reported integer is exactly the expected value. */
    private static int intValue(Map<String, String> theValues, String theKey) {
        String aValue = theValues.get(theKey);
        assertNotNull(aValue, "the report must carry " + theKey);
        return Integer.parseInt(aValue.trim());
    }

    /** The jar the loader will read: the mod's own classes and metadata, no bundled dependencies. */
    private static void assertModJarContents(Path modJar) throws IOException {
        List<String> entries;
        try (JarFile jar = new JarFile(modJar.toFile())) {
            entries = jar.stream().map(JarEntry::getName).collect(Collectors.toList());
        }

        assertTrue(entries.contains("fabric.mod.json"), "the jar must carry fabric.mod.json, got " + entries);
        assertTrue(entries.contains("auto-collect-mod.mixins.json"), "the jar must carry the mixin config");
        assertTrue(entries.contains("cn/enaium/fabricpvz/autocollect/AutoCollectMod.class"),
                "the Kotlin entry point class");
        assertTrue(entries.contains("cn/enaium/fabricpvz/autocollect/AutoCollect.class"));
        assertTrue(entries.contains("cn/enaium/fabricpvz/autocollect/AutoCollectToggle.class"));
        assertTrue(entries.contains("cn/enaium/fabricpvz/autocollect/mixin/BoardInputMixin.class"),
                "the Java mixin");
        // The mod's Kotlin classes are in the jar, the Kotlin standard library is not: it is a launcher
        // library here (the game's launch libraries publish it), shared by every Kotlin mod of an installation.
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("kotlin/")),
                "a mod jar must bundle no stdlib classes, got " + entries);
    }

    /**
     * Copies the sibling speed mod's jar into the mods directory when this workspace has one, so the
     * run is the real "both mods installed" case. The copy is verified (a build in flight could be
     * caught mid-write) and retried once.
     */
    private static Path installCompanionJar(Path theMods) throws Exception {
        Path aSource = null;
        for (String aCandidate : COMPANION_JARS) {
            Path aPath = Path.of(aCandidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(aPath)) {
                aSource = aPath;
                break;
            }
        }

        if (aSource == null) {
            fail("the speed mod's jar is not installed; looked for " + COMPANION_JARS
                    + " - this mod is meant to be installed next to it");
        }

        Path aTarget = theMods.resolve(aSource.getFileName());
        for (int anAttempt = 0; anAttempt < 2; anAttempt++) {
            Files.copy(aSource, aTarget, StandardCopyOption.REPLACE_EXISTING);
            if (isReadableModJar(aTarget)) {
                System.out.println("[auto-collect-e2e] companion mod installed: " + aSource + " ("
                        + Files.size(aTarget) + " bytes)");
                return aTarget;
            }

            Thread.sleep(1000);
        }

        fail("the speed mod's jar could not be copied readably (a build of that project may be running): "
                + aSource);
        return aTarget;
    }

    /** Whether a file really is a mod jar: a readable zip carrying {@code fabric.mod.json}. */
    private static boolean isReadableModJar(Path theJar) {
        try (JarFile jar = new JarFile(theJar.toFile())) {
            jar.getEntry("fabric.mod.json");
            return jar.getEntry("fabric.mod.json") != null;
        } catch (IOException anException) {
            return false;
        }
    }

    /**
     * Starts the real Fabric Loader in a second JVM, exactly like the launcher would.
     *
     * @return the launcher's exit code
     */
    private int launch(Path mods, Path runDir, Path report, Path output) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("--enable-native-access=ALL-UNNAMED");

        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            // SDL (LWJGL's bindings) needs the first thread.
            command.add("-XstartOnFirstThread");
        }

        command.add("-cp");
        command.add(forkClasspath());
        // The harness entry point lives in this project's test classes, which are not part of the
        // launcher's class path proper: the provider adds these entries to the loader's own class
        // loader, so the harness and the mod/game classes it inspects are all defined by the same
        // class loader (otherwise it would read a second copy of every static).
        command.add("-Dpvz.extraClasspath=" + harnessDirectory());
        // Development mode: the loader also discovers the mods that are on the class path (the API
        // modules resolved from mavenLocal). The mods under test are deliberately NOT there: they are
        // only in the mods directory, exactly like jars a player copied into the installation.
        command.add("-Dfabric.development=true");
        // This port is the only provider on the class path.
        command.add("-Dfabric.skipMcProvider=true");
        // A crash must exit instead of opening the loader's Swing error window.
        command.add("-Dfabric.noGui=true");
        command.add("-Dpvz.gameDir=" + runDir);
        command.add("-Dfabric.modsFolder=" + mods);
        command.add("-Dpvz.game.mainClass=cn.enaium.fabricpvz.autocollect.test.AutoCollectGameMain");
        command.add("-Dpvz.test.report=" + report);
        command.add(LAUNCHER_CLASS);
        command.add("-savedir");
        command.add(runDir.resolve("userdata").toString());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(tmp.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true);
        System.out.println("[auto-collect-e2e] boot command:\n  " + String.join(" \\\n  ", command));
        Process process = builder.start();

        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Fabric Loader did not finish; launcher output:\n" + read(output));
        }

        System.out.println("[auto-collect-e2e] " + LAUNCHER_CLASS + " exited with " + process.exitValue());
        System.out.println("[auto-collect-e2e] launcher output:\n" + read(output));
        return process.exitValue();
    }

    /**
     * The class path the launcher runs with: the test classes, the loader, the API modules, the game
     * and the game provider - but not this project's own main output, so the mods under test can only
     * come from the mods directory.
     */
    private static String forkClasspath() {
        String classpath = System.getProperty(FORK_CLASSPATH_PROPERTY, "");
        if (classpath.isBlank()) {
            fail("-D" + FORK_CLASSPATH_PROPERTY + " is not set (run through Gradle)");
        }
        return classpath;
    }

    /** The directory holding the harness classes (this test's own output directory). */
    private static String harnessDirectory() {
        try {
            return Path.of(AutoCollectEndToEndTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot locate the harness classes", e);
        }
    }

    private static Map<String, String> readReport(Path report) throws IOException {
        assertTrue(Files.isRegularFile(report), "the harness must have written its report");
        return parse(Files.readString(report));
    }

    private static Map<String, String> parse(String content) {
        Map<String, String> values = new HashMap<>();
        for (String line : content.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals), line.substring(equals + 1).trim());
            }
        }
        return values;
    }

    private static String read(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path) : "<no output>";
    }

    private static String failureMessage(Path output, Path report) throws IOException {
        String tail = read(output);
        int keep = 4000;
        if (tail.length() > keep) {
            tail = "..." + tail.substring(tail.length() - keep);
        }
        return "\n--- launcher output (tail) ---\n" + tail
                + "\n--- report ---\n" + (Files.exists(report) ? Files.readString(report) : "<none>");
    }

    private static String requiredProperty(String theName) {
        String value = System.getProperty(theName, "");
        assertNotNull(value, "-D" + theName + " is required");
        assertTrue(!value.isBlank(), "-D" + theName + " is required");
        return value;
    }
}
