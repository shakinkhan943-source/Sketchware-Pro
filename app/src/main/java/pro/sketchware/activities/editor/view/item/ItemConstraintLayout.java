package pro.sketchware.activities.editor.view.item;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import pro.sketchware.beans.LayoutBean;
import pro.sketchware.beans.ViewBean;
import pro.sketchware.activities.editor.view.ItemView;
import pro.sketchware.activities.editor.view.ScrollContainer;
import pro.sketchware.util.InjectAttributeHandler;
import pro.sketchware.util.PropertiesUtil;
import pro.sketchware.util.ViewUtil;

/**
 * ConstraintLayout used by the XML previewer.
 *
 * Sketchware's ViewBean/LayoutBean predates ConstraintLayout and therefore cannot
 * represent all ConstraintLayout dimensions/constraints.  The preview must read
 * the original injected XML attributes and translate them to the real
 * ConstraintLayout engine instead of trying to emulate its measurement rules.
 */
public class ItemConstraintLayout extends ConstraintLayout implements ItemView, ScrollContainer {

    private ViewBean viewBean = null;
    private boolean isSelected = false;
    private boolean isFixed = false;
    private Paint paint;
    private Rect rect;
    private boolean applyingPreviewConstraints;

    public ItemConstraintLayout(Context context) {
        super(context);
        initialize(context);
    }

    @Override
    public void reindexChildren() {
        int childIdx = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ItemView editorItem) {
                editorItem.getBean().index = childIdx++;
            }
        }
    }

    private void initialize(Context context) {
        setDrawingCacheEnabled(true);
        setWillNotDraw(false);
        setMinimumWidth((int) ViewUtil.dpToPx(context, 32.0F));
        setMinimumHeight((int) ViewUtil.dpToPx(context, 32.0F));
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(ViewUtil.dpToPx(getContext(), 2.0F));
        rect = new Rect();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!applyingPreviewConstraints) {
            applyPreviewConstraints();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Translate every ConstraintLayout attribute that matters to preview into
     * real LayoutParams/ConstraintSet state. This is intentionally independent
     * from Sketchware's old LinearLayout-oriented LayoutBean representation.
     */
    public void applyPreviewConstraints() {
        if (applyingPreviewConstraints || getChildCount() == 0) {
            return;
        }
        applyingPreviewConstraints = true;
        try {
            ConstraintSet set = new ConstraintSet();
            set.clone(this);

            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof ItemView itemView) || child.getId() == View.NO_ID) {
                    continue;
                }

                ViewBean bean = itemView.getBean();
                InjectAttributeHandler attrs = new InjectAttributeHandler(bean);
                applyDimensions(set, child, attrs, bean);
                applyMargins(set, child.getId(), attrs, bean);
                applyConnections(set, child, attrs);
                applyAdvanced(set, child.getId(), attrs);
            }

            // Legacy Sketchware widgets have no ConstraintLayout editor state.
            // Give unconstrained newly-added widgets a deterministic position,
            // while never overriding an explicit XML constraint.
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof ItemView itemView) || child.getId() == View.NO_ID) {
                    continue;
                }
                InjectAttributeHandler attrs = new InjectAttributeHandler(itemView.getBean());
                if (!hasPositionConstraint(attrs)) {
                    applyAutomaticPlacement(set, child, i);
                }
            }

            set.applyTo(this);
        } finally {
            applyingPreviewConstraints = false;
        }
    }

    private void applyDimensions(ConstraintSet set, View child, InjectAttributeHandler attrs, ViewBean bean) {
        int width = resolveDimension(attrs.getAttributeValueOf("layout_width"), bean.layout.width);
        int height = resolveDimension(attrs.getAttributeValueOf("layout_height"), bean.layout.height);
        set.constrainWidth(child.getId(), width);
        set.constrainHeight(child.getId(), height);

        String widthPercent = attrs.getAttributeValueOf("layout_constraintWidth_percent");
        if (!TextUtils.isEmpty(widthPercent)) {
            Float percent = parseFloat(widthPercent);
            if (percent != null) {
                set.constrainWidth(child.getId(), ConstraintSet.MATCH_CONSTRAINT);
                set.constrainPercentWidth(child.getId(), percent);
            }
        }

        String heightPercent = attrs.getAttributeValueOf("layout_constraintHeight_percent");
        if (!TextUtils.isEmpty(heightPercent)) {
            Float percent = parseFloat(heightPercent);
            if (percent != null) {
                set.constrainHeight(child.getId(), ConstraintSet.MATCH_CONSTRAINT);
                set.constrainPercentHeight(child.getId(), percent);
            }
        }
    }

    private int resolveDimension(String xmlValue, int beanValue) {
        if (TextUtils.isEmpty(xmlValue)) {
            if (beanValue == LayoutBean.LAYOUT_MATCH_PARENT) {
                return ConstraintSet.MATCH_CONSTRAINT;
            }
            if (beanValue == LayoutBean.LAYOUT_WRAP_CONTENT) {
                return ConstraintSet.WRAP_CONTENT;
            }
            if (beanValue == LayoutBean.LAYOUT_NOTUSED) {
                return ConstraintSet.WRAP_CONTENT;
            }
            return beanValue > 0
                    ? (int) ViewUtil.dpToPx(getContext(), beanValue)
                    : ConstraintSet.WRAP_CONTENT;
        }

        String value = xmlValue.trim();
        if ("match_parent".equals(value) || "fill_parent".equals(value)) {
            return ConstraintSet.MATCH_CONSTRAINT;
        }
        if ("wrap_content".equals(value)) {
            return ConstraintSet.WRAP_CONTENT;
        }
        if (value.endsWith("dp") || value.endsWith("px")) {
            try {
                float number = Float.parseFloat(value.substring(0, value.length() - 2).trim());
                if (number == 0f) {
                    return ConstraintSet.MATCH_CONSTRAINT;
                }
                if (value.endsWith("px")) {
                    return Math.round(number);
                }
                return Math.round(ViewUtil.dpToPx(getContext(), number));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            int number = Integer.parseInt(value);
            return number == 0 ? ConstraintSet.MATCH_CONSTRAINT : number;
        } catch (NumberFormatException ignored) {
            return ConstraintSet.WRAP_CONTENT;
        }
    }

    private void applyMargins(ConstraintSet set, int childId, InjectAttributeHandler attrs, ViewBean bean) {
        int all = resolveMargin(attrs.getAttributeValueOf("layout_margin"), -1);
        int left = resolveMargin(attrs.getAttributeValueOf("layout_marginLeft"), all >= 0 ? all : bean.layout.marginLeft);
        int right = resolveMargin(attrs.getAttributeValueOf("layout_marginRight"), all >= 0 ? all : bean.layout.marginRight);
        int top = resolveMargin(attrs.getAttributeValueOf("layout_marginTop"), all >= 0 ? all : bean.layout.marginTop);
        int bottom = resolveMargin(attrs.getAttributeValueOf("layout_marginBottom"), all >= 0 ? all : bean.layout.marginBottom);
        int start = resolveMargin(attrs.getAttributeValueOf("layout_marginStart"), left);
        int end = resolveMargin(attrs.getAttributeValueOf("layout_marginEnd"), right);

        set.setMargin(childId, ConstraintSet.LEFT, dp(start));
        set.setMargin(childId, ConstraintSet.RIGHT, dp(right));
        set.setMargin(childId, ConstraintSet.TOP, dp(top));
        set.setMargin(childId, ConstraintSet.BOTTOM, dp(bottom));
        set.setMargin(childId, ConstraintSet.START, dp(start));
        set.setMargin(childId, ConstraintSet.END, dp(end));
    }

    private int resolveMargin(String value, int fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        return PropertiesUtil.resolveSize(value, fallback);
    }

    private int dp(int value) {
        return (int) ViewUtil.dpToPx(getContext(), value);
    }

    private void applyConnections(ConstraintSet set, View child, InjectAttributeHandler attrs) {
        int id = child.getId();
        connect(set, id, ConstraintSet.LEFT, ConstraintSet.LEFT, attrs.getAttributeValueOf("layout_constraintLeft_toLeftOf"));
        connect(set, id, ConstraintSet.LEFT, ConstraintSet.RIGHT, attrs.getAttributeValueOf("layout_constraintLeft_toRightOf"));
        connect(set, id, ConstraintSet.RIGHT, ConstraintSet.LEFT, attrs.getAttributeValueOf("layout_constraintRight_toLeftOf"));
        connect(set, id, ConstraintSet.RIGHT, ConstraintSet.RIGHT, attrs.getAttributeValueOf("layout_constraintRight_toRightOf"));
        connect(set, id, ConstraintSet.START, ConstraintSet.START, attrs.getAttributeValueOf("layout_constraintStart_toStartOf"));
        connect(set, id, ConstraintSet.START, ConstraintSet.END, attrs.getAttributeValueOf("layout_constraintStart_toEndOf"));
        connect(set, id, ConstraintSet.END, ConstraintSet.START, attrs.getAttributeValueOf("layout_constraintEnd_toStartOf"));
        connect(set, id, ConstraintSet.END, ConstraintSet.END, attrs.getAttributeValueOf("layout_constraintEnd_toEndOf"));
        connect(set, id, ConstraintSet.TOP, ConstraintSet.TOP, attrs.getAttributeValueOf("layout_constraintTop_toTopOf"));
        connect(set, id, ConstraintSet.TOP, ConstraintSet.BOTTOM, attrs.getAttributeValueOf("layout_constraintTop_toBottomOf"));
        connect(set, id, ConstraintSet.BOTTOM, ConstraintSet.TOP, attrs.getAttributeValueOf("layout_constraintBottom_toTopOf"));
        connect(set, id, ConstraintSet.BOTTOM, ConstraintSet.BOTTOM, attrs.getAttributeValueOf("layout_constraintBottom_toBottomOf"));
        connect(set, id, ConstraintSet.BASELINE, ConstraintSet.BASELINE, attrs.getAttributeValueOf("layout_constraintBaseline_toBaselineOf"));
        connect(set, id, ConstraintSet.BASELINE, ConstraintSet.TOP, attrs.getAttributeValueOf("layout_constraintBaseline_toTopOf"));
        connect(set, id, ConstraintSet.BASELINE, ConstraintSet.BOTTOM, attrs.getAttributeValueOf("layout_constraintBaseline_toBottomOf"));
    }

    private void connect(ConstraintSet set, int childId, int source, int targetSide, String target) {
        int targetId = resolveTarget(target);
        if (targetId != 0) {
            set.connect(childId, source, targetId, targetSide);
        }
    }

    private int resolveTarget(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        String reference = value.trim();
        if ("parent".equals(reference)) return ConstraintSet.PARENT_ID;
        int slash = reference.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < reference.length()) {
            reference = reference.substring(slash + 1);
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (tag != null && reference.equals(tag.toString())) {
                return child.getId();
            }
        }
        return 0;
    }

    private void applyAdvanced(ConstraintSet set, int childId, InjectAttributeHandler attrs) {
        Float horizontalBias = parseFloat(attrs.getAttributeValueOf("layout_constraintHorizontal_bias"));
        if (horizontalBias != null) set.setHorizontalBias(childId, horizontalBias);
        Float verticalBias = parseFloat(attrs.getAttributeValueOf("layout_constraintVertical_bias"));
        if (verticalBias != null) set.setVerticalBias(childId, verticalBias);

        String ratio = attrs.getAttributeValueOf("layout_constraintDimensionRatio");
        if (!TextUtils.isEmpty(ratio)) set.setDimensionRatio(childId, ratio);

        Float horizontalWeight = parseFloat(attrs.getAttributeValueOf("layout_constraintHorizontal_weight"));
        if (horizontalWeight != null) set.setHorizontalWeight(childId, horizontalWeight);
        Float verticalWeight = parseFloat(attrs.getAttributeValueOf("layout_constraintVertical_weight"));
        if (verticalWeight != null) set.setVerticalWeight(childId, verticalWeight);

        int minWidth = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintWidth_min"));
        if (minWidth >= 0) set.constrainMinWidth(childId, minWidth);
        int maxWidth = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintWidth_max"));
        if (maxWidth >= 0) set.constrainMaxWidth(childId, maxWidth);
        int minHeight = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintHeight_min"));
        if (minHeight >= 0) set.constrainMinHeight(childId, minHeight);
        int maxHeight = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintHeight_max"));
        if (maxHeight >= 0) set.constrainMaxHeight(childId, maxHeight);
    }

    private int resolveDimensionOptional(String value) {
        if (TextUtils.isEmpty(value) || "wrap".equals(value) || "spread".equals(value)) return -1;
        return resolveDimension(value, -999);
    }

    private Float parseFloat(String value) {
        if (TextUtils.isEmpty(value)) return null;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean hasPositionConstraint(InjectAttributeHandler attrs) {
        String[] names = {
                "layout_constraintLeft_toLeftOf", "layout_constraintLeft_toRightOf",
                "layout_constraintRight_toLeftOf", "layout_constraintRight_toRightOf",
                "layout_constraintStart_toStartOf", "layout_constraintStart_toEndOf",
                "layout_constraintEnd_toStartOf", "layout_constraintEnd_toEndOf",
                "layout_constraintTop_toTopOf", "layout_constraintTop_toBottomOf",
                "layout_constraintBottom_toTopOf", "layout_constraintBottom_toBottomOf",
                "layout_constraintBaseline_toBaselineOf", "layout_constraintBaseline_toTopOf",
                "layout_constraintBaseline_toBottomOf", "layout_constraintCircle"
        };
        for (String name : names) {
            if (!TextUtils.isEmpty(attrs.getAttributeValueOf(name))) return true;
        }
        return false;
    }

    private void applyAutomaticPlacement(ConstraintSet set, View child, int index) {
        int id = child.getId();
        int previousId = 0;
        for (int i = index - 1; i >= 0; i--) {
            View previous = getChildAt(i);
            if (previous instanceof ItemView && previous.getId() != View.NO_ID) {
                previousId = previous.getId();
                break;
            }
        }
        set.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
        if (previousId != 0) {
            set.connect(id, ConstraintSet.TOP, previousId, ConstraintSet.BOTTOM);
        } else {
            set.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
        }
    }

    @Override
    public void addView(View child, int index) {
        int childCount = getChildCount();
        if (index > childCount) {
            super.addView(child);
            return;
        }
        super.addView(child, index);
        post(this::applyPreviewConstraints);
    }

    @Override
    public ViewBean getBean() {
        return viewBean;
    }

    @Override
    public void setBean(ViewBean viewBean) {
        this.viewBean = viewBean;
    }

    @Override
    public boolean getFixed() {
        return isFixed;
    }

    @Override
    public void setFixed(boolean isFixed) {
        this.isFixed = isFixed;
    }

    public boolean getSelection() {
        return isSelected;
    }

    @Override
    public void setSelection(boolean selected) {
        isSelected = selected;
        invalidate();
    }

    @Override
    public void onDraw(@NonNull Canvas canvas) {
        if (!isFixed) {
            if (isSelected) {
                paint.setColor(0x9599d5d0);
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                canvas.drawRect(rect, paint);
            }
            paint.setColor(0x60000000);
            int measuredWidth = getMeasuredWidth();
            int measuredHeight = getMeasuredHeight();
            canvas.drawLine(0.0F, 0.0F, measuredWidth, 0.0F, paint);
            canvas.drawLine(0.0F, 0.0F, 0.0F, measuredHeight, paint);
            canvas.drawLine(measuredWidth, 0.0F, measuredWidth, measuredHeight, paint);
            canvas.drawLine(0.0F, measuredHeight, measuredWidth, measuredHeight, paint);
        }
        super.onDraw(canvas);
    }

    @Override
    public void setChildScrollEnabled(boolean scrollEnabled) {
        for (int i = 0; i < getChildCount(); ++i) {
            View child = getChildAt(i);
            if (child instanceof ScrollContainer) {
                ((ScrollContainer) child).setChildScrollEnabled(scrollEnabled);
            }
            if (child instanceof ItemHorizontalScrollView) {
                ((ItemHorizontalScrollView) child).setScrollEnabled(scrollEnabled);
            }
            if (child instanceof ItemVerticalScrollView) {
                ((ItemVerticalScrollView) child).setScrollEnabled(scrollEnabled);
            }
        }
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(
                (int) ViewUtil.dpToPx(getContext(), left),
                (int) ViewUtil.dpToPx(getContext(), top),
                (int) ViewUtil.dpToPx(getContext(), right),
                (int) ViewUtil.dpToPx(getContext(), bottom));
    }
}