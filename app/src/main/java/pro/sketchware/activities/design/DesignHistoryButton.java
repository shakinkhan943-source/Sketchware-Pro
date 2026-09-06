package pro.sketchware.activities.design;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.util.List;

import pro.sketchware.activities.design.fragments.ViewEditorFragment;

/** Compact toolbar history control used beside the file selector. */
public class DesignHistoryButton extends AppCompatImageButton {

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

    @Override
    public boolean performClick() {
        ViewEditorFragment editor = findEditorFragment();
        if (editor == null) {
            refreshVisualState();
            return false;
        }

        boolean redo = "redo".equals(getTag());
        boolean available = redo ? editor.canRedo() : editor.canUndo();
        if (!available) {
            refreshVisualState();
            return false;
        }

        if (redo) {
            editor.performRedo();
        } else {
            editor.performUndo();
        }
        refreshVisualState();
        return true;
    }

    private void refreshVisualState() {
        ViewEditorFragment editor = findEditorFragment();
        if (editor == null) {
            setAlpha(0.55f);
            return;
        }

        boolean redo = "redo".equals(getTag());
        setAlpha((redo ? editor.canRedo() : editor.canUndo()) ? 1f : 0.45f);
    }

    @Nullable
    private ViewEditorFragment findEditorFragment() {
        Context context = getContext();
        if (!(context instanceof FragmentActivity)) {
            return null;
        }
        return findIn(((FragmentActivity) context).getSupportFragmentManager().getFragments());
    }

    @Nullable
    private static ViewEditorFragment findIn(List<Fragment> fragments) {
        for (Fragment fragment : fragments) {
            if (fragment instanceof ViewEditorFragment) {
                return (ViewEditorFragment) fragment;
            }
            ViewEditorFragment nested = findIn(fragment.getChildFragmentManager().getFragments());
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
