package pro.sketchware.activities.design;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager2.widget.ViewPager2;

/**
 * ViewPager2 that cooperates with editors which have their own scrolling surface.
 *
 * <p>Horizontal gestures are given to the deepest scrollable child when it can still
 * scroll in that direction. Vertical gestures are never treated as page swipes. This
 * keeps code-editor scrolling responsive while preserving normal page swiping and the
 * natural hand-off at the editor's horizontal edges.</p>
 */
public class EditorPagerView extends ViewPager2 {
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
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureTarget = findDeepestViewUnder(this, downX, downY);
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;

                if (Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop) {
                    return super.onInterceptTouchEvent(event);
                }

                // A vertical gesture belongs to the editor/content, never to the pager.
                if (Math.abs(dy) > Math.abs(dx)) {
                    return false;
                }

                // Let a horizontally scrollable child consume the gesture. At its edge,
                // allow ViewPager2 to take over so page swiping still works naturally.
                if (Math.abs(dx) > touchSlop && gestureTarget != null) {
                    int direction = dx < 0 ? 1 : -1;
                    if (gestureTarget.canScrollHorizontally(direction)) {
                        return false;
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                gestureTarget = null;
                break;
        }

        return super.onInterceptTouchEvent(event);
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
