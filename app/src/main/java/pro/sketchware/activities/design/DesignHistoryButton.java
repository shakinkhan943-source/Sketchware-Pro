package pro.sketchware.activities.design;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

/**
 * Compact toolbar history control used beside the file selector. It performs undo/redo on whichever
 * editor is currently active (the drag-and-drop view editor or the Java/Kotlin source code editor).
 *
 * <p>The active editor is resolved through an {@link UndoRedoHost} supplied by the hosting
 * {@link DesignActivity}, so the button no longer has to hunt fragments itself. Activities call
 * {@link #refreshVisualState()} whenever the undo/redo availability may have changed.</p>
 */
public class DesignHistoryButton extends AppCompatImageButton {

    /** Abstraction over the editor whose history a history button should act on. */
    public interface UndoRedoHost {
        boolean canUndo();

        boolean canRedo();

        void performUndo();

        void performRedo();
    }

    public DesignHistoryButton(Context context) {
        super(context);
    }

    public DesignHistoryButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DesignHistoryButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshVisualState();
    }

    /**
     * Resolves the active undo/redo host from the hosting activity. Returns {@code null} when the
     * context is not a {@link DesignActivity} (e.g. during layout preview).
     */
    @Nullable
    private UndoRedoHost resolveHost() {
        Context context = getContext();
        if (context instanceof DesignActivity designActivity) {
            return designActivity.getActiveUndoRedoHost();
        }
        return null;
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        UndoRedoHost host = resolveHost();
        if (host != null) {
            boolean redo = "redo".equals(getTag());
            boolean available = redo ? host.canRedo() : host.canUndo();
            if (available) {
                if (redo) {
                    host.performRedo();
                } else {
                    host.performUndo();
                }
                handled = true;
            }
        }
        refreshVisualState();
        return handled;
    }

    /**
     * Updates the button's dimmed/enabled look to reflect the active editor's history state.
     * Safe to call from any state-change callback.
     */
    public void refreshVisualState() {
        UndoRedoHost host = resolveHost();
        boolean available;
        if (host == null) {
            available = false;
        } else {
            boolean redo = "redo".equals(getTag());
            available = redo ? host.canRedo() : host.canUndo();
        }
        setEnabled(available);
        setAlpha(available ? 1f : 0.45f);
    }
}
