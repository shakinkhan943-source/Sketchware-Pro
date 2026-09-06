package pro.sketchware.activities.design;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Hosts ViewPager2 while allowing nested horizontal editors to consume their own swipes.
 * ViewPager2 itself is final, so gesture arbitration must live in a parent container.
 */
public class EditorPagerView extends FrameLayout {
    private final int touchSlop;
    private float downX;
    private float downY;
    private View gestureTarget;

    public EditorPagerView(@NonNull Context context) {
        this(context, null);
    }

    public EditorPagerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipToPadding(false);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureTarget = findDeepestViewUnder(this, downX, downY);
                return false;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;

                if (Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop) {
                    return false;
                }

                if (Math.abs(dy) > Math.abs(dx)) {
                    requestDisallowInterceptTouchEvent(true);
                    return false;
                }

                if (Math.abs(dx) > touchSlop && canTargetScrollHorizontally(dx)) {
                    requestDisallowInterceptTouchEvent(true);
                }
                return false;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                gestureTarget = null;
                requestDisallowInterceptTouchEvent(false);
                return false;

            default:
                return false;
        }
    }

    private boolean canTargetScrollHorizontally(float dx) {
        int direction = dx < 0 ? 1 : -1;
        View view = gestureTarget;
        while (view != null && view != this) {
            if (view.canScrollHorizontally(direction)) {
                return true;
            }
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    @Nullable
    private static View findDeepestViewUnder(@NonNull View root, float x, float y) {
        if (!(root instanceof ViewGroup)) {
            return root;
        }

        ViewGroup group = (ViewGroup) root;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != VISIBLE || child.getAlpha() <= 0f) {
                continue;
            }

            Rect bounds = new Rect();
            child.getHitRect(bounds);
            if (bounds.contains((int) x, (int) y)) {
                float childX = x - child.getLeft();
                float childY = y - child.getTop();
                View deepest = findDeepestViewUnder(child, childX, childY);
                return deepest != null ? deepest : child;
            }
        }
        return root;
    }
}
