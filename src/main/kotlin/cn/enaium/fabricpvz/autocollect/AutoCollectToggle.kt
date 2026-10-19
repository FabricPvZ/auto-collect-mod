package cn.enaium.fabricpvz.autocollect

import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.ImageID
import cn.enaium.fabricpvz.portable.lawn.Resources
import cn.enaium.fabricpvz.portable.lawn.widget.GameButton
import cn.enaium.fabricpvz.portable.sexy.Rect
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import cn.enaium.fabricpvz.portable.sexy.graphics.Image

/**
 * The mod's one control: a stone button in the top-right corner that shows whether the mod is
 * collecting (`ON`) and flips it when pressed (`OFF`).
 *
 * ## Where it sits, and why there
 *
 * The vanilla board's top-right corner is the game's own Menu button (`Board.mMenuButton`,
 * `Board.java:352-386`: `681,-10,117x46` in adventure mode, `628,-10,163x46` where a store button is
 * used). The band to its left is the one part of the top strip the UI leaves free - the seed bank is
 * on the left (`SeedBank.draw` puts it at `0,0` and grows it rightwards with the packet count,
 * `Board.java:2477-2486`; the retail `images/SeedBank.png` is 446x87, so the bank is 446 wide at six
 * packets and 599 at ten, and 71 pixels at `606..677` have room), and everything *below* the Menu
 * button is where a speed-setting mod puts its row (see `speed-mod`), so a second mod's control must
 * not go there. This button therefore sits in the Menu button's own band, 4 pixels to its left, and
 * can never overlap either.
 *
 * The button is drawn and routed by hand, exactly like the game's own `mMenuButton`: geometry from
 * the Menu button every time it is drawn, the game's own stone 9-slice rendering (so it looks like
 * the button it sits next to), and a click this mod takes away from the board - see
 * [cn.enaium.fabricpvz.autocollect.mixin.BoardInputMixin].
 *
 * ## Why the panels are 71 pixels wide and 46 tall
 *
 * `GameButton.drawStoneButton` (`GameButton.java:87-125`) draws the retail 9-slice at the slices' own
 * size: left `36x46`, middle `46x46`, right `35x46`, repeating the middle slice
 * `(w - 36 - 35) / 46` times and using the requested height only to place the label. A panel is
 * therefore 71, 117, 163 ... wide and always 46 tall, and any other request draws art that is wider
 * and taller than the rectangle the mod hit-tests with. [panelWidth] and [drawnWidth] are that
 * arithmetic; the button uses the smallest panel the art allows (71x46), which is also the only one
 * that fits between the seed bank and the Menu button on a ten-packet bank.
 */
class AutoCollectToggle(private val mBoard: Board) {

    /** The button, drawn with the game's own stone rendering. */
    private val mButton = GameButton(BUTTON_ID)

    /** The press that is in flight, or null. */
    private var mPressed: GameButton? = null

    init {
        mButton.mApp = mBoard.mApp
        mButton.mParentWidget = mBoard
        mButton.mDrawStoneButton = true
        mButton.mLabel = label()
        layout()
    }

    /** The board this control belongs to. */
    fun board(): Board = mBoard

    /** True while the game's own Menu button is on screen. */
    fun isVisible(): Boolean = !mBoard.mMenuButton.mBtnNoDraw

    /** The label the button shows: the state of [AutoCollect]. */
    /**
     * The button's label, through the API's translation facility: a translation table (the i18n mod's,
     * or one the player drops next to the game) may carry `[AUTO_COLLECT_ON]` / `[AUTO_COLLECT_OFF]`,
     * and plain "ON" / "OFF" is what a language without those entries shows. Looked up per draw, so a
     * language switch takes effect on the next frame.
     */
    fun label(): String = if (AutoCollect.isEnabled())
        cn.enaium.fabricpvz.api.text.Translations.translate("[AUTO_COLLECT_ON]", ON_LABEL)
    else
        cn.enaium.fabricpvz.api.text.Translations.translate("[AUTO_COLLECT_OFF]", OFF_LABEL)

    /** The button's rectangle in board coordinates. */
    fun rect(): Rect = Rect(mButton.mX, mButton.mY, mButton.mWidth, mButton.mHeight)

    /** Places the button in the Menu button's band, 4 pixels to its left. */
    private fun layout() {
        val aMenu = mBoard.mMenuButton
        mButton.resize(aMenu.mX - GAP - panelWidth(0), aMenu.mY, panelWidth(0), panelHeight())
    }

    /** Draws the button; called from the board's draw pass (`LawnEvents.POST_DRAW`). */
    fun draw(theGraphics: Graphics) {
        layout()
        mButton.mLabel = label()
        mButton.draw(theGraphics)
    }

    /** Whether a point in board coordinates is inside the button. */
    fun contains(theX: Int, theY: Int): Boolean {
        layout()
        return Rect(mButton.mX, mButton.mY, mButton.mWidth, mButton.mHeight).contains(theX, theY)
    }

    /**
     * Handles a press in board coordinates.
     *
     * @return whether the click belongs to the button and the board must not see it
     */
    fun mouseDown(theX: Int, theY: Int, theClickCount: Int): Boolean {
        if (!isVisible() || mBoard.mIgnoreMouseUp) {
            // mIgnoreMouseUp means the board is discarding the click that is already in flight.
            return false
        }

        if (!contains(theX, theY)) {
            return false
        }

        mPressed = mButton
        mButton.mIsDown = true
        mButton.mIsOver = true
        return true
    }

    /**
     * Handles the release of a press this control accepted; the toggle fires when the release lands
     * on the button, like the game's own buttons. A release anywhere else cancels the press.
     *
     * @return whether the release belongs to the button
     */
    fun mouseUp(theX: Int, theY: Int, theClickCount: Int): Boolean {
        val aButton = mPressed ?: return false
        mPressed = null
        aButton.mIsDown = false
        aButton.mIsOver = false

        if (contains(theX, theY) && theClickCount > 0) {
            AutoCollect.toggle()
            mButton.mLabel = label()
        }

        return true
    }

    /** Drops an in-flight press (the board was replaced while the mouse was down). */
    fun cancelPress() {
        val aButton = mPressed ?: return
        aButton.mIsDown = false
        aButton.mIsOver = false
        mPressed = null
    }

    companion object {

        /** The label the button shows while the mod collects. */
        val ON_LABEL = "ON"

        /** The label the button shows while the mod is switched off. */
        val OFF_LABEL = "OFF"

        /** Space between the button and the game's Menu button. */
        val GAP = 4

        private const val BUTTON_ID = 0

        /** The stone art every panel is drawn with; all three slices are 46 pixels tall. */
        private fun slice(theImageId: Int): Image = Resources.getImageById(theImageId)

        /**
         * The height every stone panel is drawn at (the 9-slice ignores the requested height).
         *
         * <p>Java reaches the members of this companion through the companion instance
         * ({@code AutoCollectToggle.Companion.drawnWidth(width)}): nothing here is a Java static.
         */
        fun panelHeight(): Int = slice(ImageID.IMAGE_BUTTON_MIDDLE).getHeight()

        /**
         * The width the game's 9-slice really draws for a panel requested to be [theWidth] wide:
         * `left + ((theWidth - left - right) / middle) * middle + right`. The requested width is
         * rounded down to whole middle slices, so a request is only a drawn width when it is exactly
         * `left + n*middle + right` - [panelWidth] is.
         */
        fun drawnWidth(theWidth: Int): Int {
            val aLeft = slice(ImageID.IMAGE_BUTTON_LEFT).getWidth()
            val aMiddle = slice(ImageID.IMAGE_BUTTON_MIDDLE).getWidth()
            val aRight = slice(ImageID.IMAGE_BUTTON_RIGHT).getWidth()
            val aRepeat = (theWidth - aLeft - aRight) / aMiddle
            return aLeft + maxOf(0, aRepeat) * aMiddle + aRight
        }

        /** A width the 9-slice draws in full: `left + middleSlices*middle + right`. */
        fun panelWidth(theMiddleSlices: Int): Int {
            val aLeft = slice(ImageID.IMAGE_BUTTON_LEFT).getWidth()
            val aMiddle = slice(ImageID.IMAGE_BUTTON_MIDDLE).getWidth()
            val aRight = slice(ImageID.IMAGE_BUTTON_RIGHT).getWidth()
            return aLeft + maxOf(0, theMiddleSlices) * aMiddle + aRight
        }
    }
}
