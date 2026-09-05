package pro.sketchware.activities.design;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

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
        refreshState();
    }

    @Override
    public boolean performClick() {
        ViewEditorFragment editor = findEditorFragment();
        if (editor == null) {
            refreshState();
            return false;
        }

        boolean redo = "redo".equals(getTag());
        boolean available = redo ? editor.canRedo() : editor.canUndo();
        if (!available) {
            refreshState();
            return false;
        }

        if (redo) {
            editor.performRedo();
        } else {
            editor.performUndo();
        }
        refreshState();
        return super.performClick();
    }

    private void refreshState() {
        ViewEditorFragment editor = findEditorFragment();
        if (editor == null) {
            setEnabled(false);
            setAlpha(0.45f);
            return;
        }

        boolean redo = "redo".equals(getTag());
        setEnabled(redo ? editor.canRedo() : editor.canUndo());
        setAlpha(isEnabled() ? 1f : 0.45f);
    }

    @Nullable
    private ViewEditorFragment findEditorFragment() {
        Context context = getContext();
        if (!(context instanceof FragmentActivity)) {
            return null;
        }
        return findIn((FragmentActivity) context, ((FragmentActivity) context)
                .getSupportFragmentManager().getFragments());
    }

    @Nullable
    private static ViewEditorFragment findIn(FragmentActivity activity, List<Fragment> fragments) {
        for (Fragment fragment : fragments) {
            if (fragment instanceof ViewEditorFragment) {
                return (ViewEditorFragment) fragment;
            }
            ViewEditorFragment nested = findIn(activity, fragment.getChildFragmentManager().getFragments());
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
