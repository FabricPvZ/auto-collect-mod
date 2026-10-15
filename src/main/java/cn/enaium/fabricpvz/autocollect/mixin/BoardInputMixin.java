package cn.enaium.fabricpvz.autocollect.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.enaium.fabricpvz.autocollect.AutoCollectMod;
import cn.enaium.fabricpvz.autocollect.AutoCollectToggle;
import cn.enaium.fabricpvz.portable.lawn.Board;

/**
 * Routes presses and releases over the auto-collect toggle away from the board.
 *
 * <p>The board is a widget that covers the screen and routes its own hand-drawn buttons
 * ({@code mMenuButton}, {@code mStoreButton}) itself, inside {@code BoardInput.mouseDown} /
 * {@code BoardInput.mouseUp}. The mod's toggle is hand-drawn the same way, so it is serviced the same
 * way: this mixin asks the toggle first and cancels the board's handler when the click landed on it -
 * the only thing standing between "the player switched the mod off" and "the player shovelled a plant
 * in the corner of the lawn".
 *
 * <p>The cancellation is paired with the board's own {@code mIgnoreMouseUp} convention (the flag
 * {@code BoardInput} uses for "discard the release of a press I swallowed"), so a press that starts on
 * the toggle and ends on the lawn cannot half-reach the board either. The framework's widget manager
 * is not touched: clicks outside the toggle keep flowing through the board exactly as they always
 * did, and a click over a dialog never arrives here in the first place (the dialog widget is above
 * the board).
 *
 * <p>This is the mod's only mixin, and it is Java: a Mixin configuration is a Java class annotated
 * with {@code @Mixin}, which is what Mixin's own processor reads. The mod's gameplay code, its state
 * and its control are Kotlin ({@code src/main/kotlin}). Both callbacks are injector-required (the
 * mixin config keeps the default {@code require}), so a game change that removes these two methods
 * fails loudly at load instead of silently dropping input handling.
 */
@Mixin(Board.class)
public abstract class BoardInputMixin {

    /** {@code Board::MouseDown} (Board.java:4092) - the click's first stop. */
    @Inject(method = "mouseDown(III)V", at = @At("HEAD"), cancellable = true)
    private void autocollect$mouseDown(int theX, int theY, int theClickCount, CallbackInfo ci) {
        Board self = (Board) (Object) this;
        AutoCollectToggle aToggle = AutoCollectMod.Companion.controlsOf(self);
        if (aToggle == null || !aToggle.mouseDown(theX, theY, theClickCount)) {
            return;
        }

        self.mIgnoreMouseUp = true;
        ci.cancel();
    }

    /** {@code Board::MouseUp} (Board.java:4108) - the release of a click the toggle accepted. */
    @Inject(method = "mouseUp(III)V", at = @At("HEAD"), cancellable = true)
    private void autocollect$mouseUp(int theX, int theY, int theClickCount, CallbackInfo ci) {
        Board self = (Board) (Object) this;
        AutoCollectToggle aToggle = AutoCollectMod.Companion.controlsOf(self);
        if (aToggle == null || !aToggle.mouseUp(theX, theY, theClickCount)) {
            return;
        }

        self.mIgnoreMouseUp = false;
        ci.cancel();
    }
}
