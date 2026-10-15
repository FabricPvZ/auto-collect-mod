package cn.enaium.fabricpvz.autocollect

import cn.enaium.fabricpvz.api.lawn.LawnEvents
import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

/**
 * Mod entry point (Fabric Loader's [ModInitializer]).
 *
 * The whole mod is three pieces: [AutoCollect] (which suns and coins to click), [AutoCollectToggle]
 * (the one button that switches it off and on again) and
 * [cn.enaium.fabricpvz.autocollect.mixin.BoardInputMixin] (the Java mixin that keeps a click on that
 * button away from the board). This class wires the first two to the game:
 *
 * - **collecting**: [LawnEvents.TICK], the game's own "one gameplay tick" call point
 *   (`Board.updateGame`, `Board.java:3571`). No mixin is needed: the board's coin pass has already
 *   run for this tick, so every coin the mod clicks is where the player would have seen it.
 * - **the button's picture**: [LawnEvents.POST_DRAW], the game's "the board finished drawing itself"
 *   call point (`BoardRender.java:1427-1429`, after the object passes that include the Menu button
 *   the control sits next to). No mixin needed for the picture either.
 * - **the button's input**: the mixin, because the modding API deliberately exposes no hook for
 *   "this click was consumed" - the same reason `speed-mod` has one.
 *
 * A Kotlin `class` with a no-argument constructor, not an `object`: Fabric Loader instantiates an
 * entry point reflectively through its no-argument constructor and a Kotlin `object` has only a
 * private one (it would have to be named through the `kotlin` language adapter of
 * fabric-language-kotlin, the language module this mod requires).
 *
 * Everything this class reports goes through the workspace's one logging stack (the SLF4J API, whose
 * Log4j backend the launcher publishes), so the mod's lines, Fabric Loader's own lines and the Mixin
 * subsystem's lines are one stream in one format.
 *
 * The companion is reached through its instance - Kotlin as `AutoCollectMod.controlsOf(board)`, the
 * Java side (the Mixin class and the end-to-end harness) as
 * `AutoCollectMod.Companion.controlsOf(board)` - so no member of this class is a Java static.
 */
class AutoCollectMod : ModInitializer {

    override fun onInitialize() {
        initialized = true
        val aStdlib = KotlinVersion::class.java.protectionDomain?.codeSource?.location?.toString()
            ?: "<bootstrap>"
        kotlinEntrypoint = "[$MOD_ID] Kotlin ${KotlinVersion.CURRENT} entry point class ran " +
            "(stdlib from $aStdlib, not from the mod jar)"

        LawnEvents.TICK.register(LawnEvents.Tick { theBoard, _ -> AutoCollect.tick(theBoard) })
        LawnEvents.POST_DRAW.register(LawnEvents.PostDraw { theBoard, theGraphics ->
            drawToggle(theBoard, theGraphics)
        })

        LOGGER.info("auto-collect registered (on by default, toggle next to the menu button)")
        LOGGER.info(kotlinEntrypoint)
    }

    companion object {

        /** Fabric mod id, as declared in `fabric.mod.json`. */
        val MOD_ID = "auto-collect-mod"

        /**
         * The mod's logger, named by the mod id: on the unified stack its name is the
         * `[auto-collect-mod]` every line of this mod carries (`log4j2.xml` in fabric-provider
         * formats it).
         */
        private val LOGGER = LoggerFactory.getLogger(MOD_ID)

        /** Set by [onInitialize]: proves to a test that Fabric Loader ran this entry point. */
        var initialized: Boolean = false

        /**
         * What the entry point logged when it ran: the toolchain's proof that the mod's entry point
         * itself is a Kotlin class of the mod jar, running under the real Fabric Loader with the Kotlin
         * standard library resolved from the launch class path.
         */
        var kotlinEntrypoint: String? = null

        /** The control built for the last drawn board, or null. */
        private var sToggle: AutoCollectToggle? = null

        /** The board's draw pass: builds the control on the board's first draw, then paints it. */
        private fun drawToggle(theBoard: Board, theGraphics: Graphics) {
            var aToggle = sToggle
            if (aToggle == null || aToggle.board() !== theBoard) {
                // A new board (the previous one is gone with its level): its control goes with it,
                // cancelling a press that was in flight. The control needs the board's app, so it is
                // created here rather than at mod init time (the game does not exist yet then).
                aToggle?.cancelPress()
                aToggle = AutoCollectToggle(theBoard)
                sToggle = aToggle
            }

            if (aToggle.isVisible()) {
                aToggle.draw(theGraphics)
            }
        }

        /**
         * The control of a board, or null when this board never drew itself (and therefore never
         * showed a control). Used by the input mixin, which must not create anything.
         */
        fun controlsOf(theBoard: Board): AutoCollectToggle? {
            val aToggle = sToggle
            return if (aToggle != null && aToggle.board() === theBoard) aToggle else null
        }

        /** The control built for the last drawn board, or null; used by tests. */
        fun controls(): AutoCollectToggle? = sToggle

        /** Drops the control of a board that is gone, cancelling a press that was in flight. */
        fun clearControls() {
            sToggle?.cancelPress()
            sToggle = null
        }
    }
}
