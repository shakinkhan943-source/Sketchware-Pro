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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import pro.sketchware.activities.editor.view.ItemView;
import pro.sketchware.activities.editor.view.ScrollContainer;
import pro.sketchware.beans.LayoutBean;
import pro.sketchware.beans.ViewBean;
import pro.sketchware.util.InjectAttributeHandler;
import pro.sketchware.util.PropertiesUtil;
import pro.sketchware.util.ViewUtil;

/**
 * ConstraintLayout used by the XML previewer.
 *
 * ConstraintLayout has rules which cannot be represented by Sketchware's
 * legacy LayoutBean. Preview therefore reads the injected XML attributes and
 * delegates the actual measurement/positioning to AndroidX ConstraintLayout.
 */
public class ItemConstraintLayout extends ConstraintLayout implements ItemView, ScrollContainer {

    private ViewBean viewBean;
    private boolean isSelected;
    private boolean isFixed;
    private Paint paint;
    private Rect rect;
    private boolean applyingPreviewConstraints;
    private boolean previewConstraintsDirty = true;
    private final Map<String, Integer> previewIds = new HashMap<>();

    public ItemConstraintLayout(Context context) {
        super(context);
        initialize(context);
    }

    private void initialize(Context context) {
        setDrawingCacheEnabled(true);
        setWillNotDraw(false);
        setMinimumWidth((int) ViewUtil.dpToPx(context, 32.0F));
        setMinimumHeight((int) ViewUtil.dpToPx(context, 32.0F));
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(ViewUtil.dpToPx(context, 2.0F));
        rect = new Rect();
    }

    @Override
    public void reindexChildren() {
        int childIdx = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ItemView itemView) {
                ViewBean bean = itemView.getBean();
                if (bean != null) {
                    bean.index = childIdx++;
                }
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (previewConstraintsDirty && !applyingPreviewConstraints) {
            applyPreviewConstraints();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Applies the complete set of ConstraintLayout attributes understood by
     * the preview. The first step is deliberately ID-safe: ConstraintSet.clone
     * requires every child in the container to have a valid unique ID. The
     * editor temporarily inserts a highlight TextView during drag-and-drop;
     * that view is not an ItemView and historically had no ID, causing the
     * crash reported by ConstraintSet.clone().
     */
    public void applyPreviewConstraints() {
        if (applyingPreviewConstraints || getChildCount() == 0) {
            return;
        }

        applyingPreviewConstraints = true;
        try {
            ensureChildIds();

            ConstraintSet set = new ConstraintSet();
            set.clone(this);

            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof ItemView itemView)) {
                    continue;
                }
                ViewBean bean = itemView.getBean();
                if (bean == null || child.getId() == View.NO_ID) {
                    continue;
                }

                InjectAttributeHandler attrs = new InjectAttributeHandler(bean);
                applyDimensions(set, child, attrs, bean);
                applyMargins(set, child.getId(), attrs, bean);
                applyConnections(set, child, attrs);
                applyAdvanced(set, child, attrs);
            }

            // A widget newly dropped into a ConstraintLayout may not have any
            // positional constraint yet. Keep it visible and deterministic
            // without affecting widgets which already have explicit constraints.
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof ItemView itemView) || child.getId() == View.NO_ID) {
                    continue;
                }
                ViewBean bean = itemView.getBean();
                if (bean == null) {
                    continue;
                }
                InjectAttributeHandler attrs = new InjectAttributeHandler(bean);
                if (!hasPositionConstraint(attrs)) {
                    applyAutomaticPlacement(set, child, i);
                }
            }

            previewConstraintsDirty = false;
            set.applyTo(this);
        } finally {
            applyingPreviewConstraints = false;
        }
    }

    /**
     * Guarantees that ConstraintSet.clone() can safely inspect every child.
     * Existing IDs are preserved; only missing/duplicate IDs are replaced.
     * The XML/editor id remains in the view tag and is never modified.
     */
    private void ensureChildIds() {
        previewIds.clear();
        Set<Integer> usedIds = new HashSet<>();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int id = child.getId();
            if (id == View.NO_ID || usedIds.contains(id)) {
                id = View.generateViewId();
                while (usedIds.contains(id)) {
                    id = View.generateViewId();
                }
                child.setId(id);
            }
            usedIds.add(id);

            Object tag = child.getTag();
            if (tag != null) {
                previewIds.put(normalizeReference(tag.toString()), id);
            }

            if (child instanceof ItemView itemView && itemView.getBean() != null) {
                String beanId = itemView.getBean().id;
                if (!TextUtils.isEmpty(beanId)) {
                    previewIds.put(normalizeReference(beanId), id);
                }
            }
        }
    }

    private void applyDimensions(ConstraintSet set, View child, InjectAttributeHandler attrs, ViewBean bean) {
        int width = resolveDimension(attrs.getAttributeValueOf("layout_width"), bean.layout.width);
        int height = resolveDimension(attrs.getAttributeValueOf("layout_height"), bean.layout.height);
        set.constrainWidth(child.getId(), width);
        set.constrainHeight(child.getId(), height);

        String widthPercent = attrs.getAttributeValueOf("layout_constraintWidth_percent");
        Float widthRatio = parsePercent(widthPercent);
        if (widthRatio != null) {
            set.constrainWidth(child.getId(), ConstraintSet.MATCH_CONSTRAINT);
            // Explicitly select the percent match-constraint mode rather than
            // relying on constrainPercentWidth() alone to imply it - some
            // ConstraintLayout versions only switch to percent sizing when
            // the default mode is set explicitly.
            set.constrainDefaultWidth(child.getId(), ConstraintSet.MATCH_CONSTRAINT_PERCENT);
            set.constrainPercentWidth(child.getId(), widthRatio);
        }

        String heightPercent = attrs.getAttributeValueOf("layout_constraintHeight_percent");
        Float heightRatio = parsePercent(heightPercent);
        if (heightRatio != null) {
            set.constrainHeight(child.getId(), ConstraintSet.MATCH_CONSTRAINT);
            set.constrainDefaultHeight(child.getId(), ConstraintSet.MATCH_CONSTRAINT_PERCENT);
            set.constrainPercentHeight(child.getId(), heightRatio);
        }

        String widthDefault = attrs.getAttributeValueOf("layout_constraintWidth_default");
        if (!TextUtils.isEmpty(widthDefault)) {
            applyMatchConstraintDefault(set, child.getId(), widthDefault, true);
        }

        String heightDefault = attrs.getAttributeValueOf("layout_constraintHeight_default");
        if (!TextUtils.isEmpty(heightDefault)) {
            applyMatchConstraintDefault(set, child.getId(), heightDefault, false);
        }

        int minWidth = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintWidth_min"));
        int maxWidth = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintWidth_max"));
        int minHeight = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintHeight_min"));
        int maxHeight = resolveDimensionOptional(attrs.getAttributeValueOf("layout_constraintHeight_max"));
        if (minWidth >= 0) set.constrainMinWidth(child.getId(), minWidth);
        if (maxWidth >= 0) set.constrainMaxWidth(child.getId(), maxWidth);
        if (minHeight >= 0) set.constrainMinHeight(child.getId(), minHeight);
        if (maxHeight >= 0) set.constrainMaxHeight(child.getId(), maxHeight);

        String constrainedWidth = attrs.getAttributeValueOf("layout_constrainedWidth");
        if (!TextUtils.isEmpty(constrainedWidth)) {
            set.constrainedWidth(child.getId(), Boolean.parseBoolean(constrainedWidth));
        }
        String constrainedHeight = attrs.getAttributeValueOf("layout_constrainedHeight");
        if (!TextUtils.isEmpty(constrainedHeight)) {
            set.constrainedHeight(child.getId(), Boolean.parseBoolean(constrainedHeight));
        }
    }

    private void applyMatchConstraintDefault(ConstraintSet set, int childId, String value, boolean horizontal) {
        String normalized = value.trim().toLowerCase();
        int mode;
        if ("wrap".equals(normalized)) {
            mode = ConstraintSet.MATCH_CONSTRAINT_WRAP;
        } else if ("percent".equals(normalized)) {
            mode = ConstraintSet.MATCH_CONSTRAINT_PERCENT;
        } else {
            mode = ConstraintSet.MATCH_CONSTRAINT_SPREAD;
        }
        if (horizontal) {
            set.constrainDefaultWidth(childId, mode);
        } else {
            set.constrainDefaultHeight(childId, mode);
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
                return ConstraintSet.MATCH_CONSTRAINT;
            }
            return beanValue > 0
                    ? (int) ViewUtil.dpToPx(getContext(), beanValue)
                    : ConstraintSet.WRAP_CONTENT;
        }

        String value = xmlValue.trim().toLowerCase();
        if ("match_parent".equals(value) || "fill_parent".equals(value)) {
            return ConstraintSet.MATCH_CONSTRAINT;
        }
        if ("wrap_content".equals(value)) {
            return ConstraintSet.WRAP_CONTENT;
        }
        if (value.endsWith("dp")) {
            try {
                float number = Float.parseFloat(value.substring(0, value.length() - 2).trim());
                return number == 0f
                        ? ConstraintSet.MATCH_CONSTRAINT
                        : Math.round(ViewUtil.dpToPx(getContext(), number));
            } catch (NumberFormatException ignored) {
                return ConstraintSet.WRAP_CONTENT;
            }
        }
        if (value.endsWith("px")) {
            try {
                float number = Float.parseFloat(value.substring(0, value.length() - 2).trim());
                return number == 0f ? ConstraintSet.MATCH_CONSTRAINT : Math.round(number);
            } catch (NumberFormatException ignored) {
                return ConstraintSet.WRAP_CONTENT;
            }
        }
        try {
            int number = Integer.parseInt(value);
            return number == 0 ? ConstraintSet.MATCH_CONSTRAINT : number;
        } catch (NumberFormatException ignored) {
            return ConstraintSet.WRAP_CONTENT;
        }
    }

    private int resolveDimensionOptional(String value) {
        if (TextUtils.isEmpty(value) || "wrap".equalsIgnoreCase(value) || "spread".equalsIgnoreCase(value)) {
            return -1;
        }
        return resolveDimension(value, -999);
    }

    private void applyMargins(ConstraintSet set, int childId, InjectAttributeHandler attrs, ViewBean bean) {
        int all = resolveMargin(attrs.getAttributeValueOf("layout_margin"), -1);
        int left = resolveMargin(attrs.getAttributeValueOf("layout_marginLeft"), all >= 0 ? all : bean.layout.marginLeft);
        int right = resolveMargin(attrs.getAttributeValueOf("layout_marginRight"), all >= 0 ? all : bean.layout.marginRight);
        int top = resolveMargin(attrs.getAttributeValueOf("layout_marginTop"), all >= 0 ? all : bean.layout.marginTop);
        int bottom = resolveMargin(attrs.getAttributeValueOf("layout_marginBottom"), all >= 0 ? all : bean.layout.marginBottom);
        int start = resolveMargin(attrs.getAttributeValueOf("layout_marginStart"), left);
        int end = resolveMargin(attrs.getAttributeValueOf("layout_marginEnd"), right);

        setSafeMargin(set, childId, ConstraintSet.LEFT, start);
        setSafeMargin(set, childId, ConstraintSet.RIGHT, right);
        setSafeMargin(set, childId, ConstraintSet.TOP, top);
        setSafeMargin(set, childId, ConstraintSet.BOTTOM, bottom);
        setSafeMargin(set, childId, ConstraintSet.START, start);
        setSafeMargin(set, childId, ConstraintSet.END, end);

        setGoneMargin(set, childId, ConstraintSet.LEFT, attrs.getAttributeValueOf("layout_goneMarginLeft"));
        setGoneMargin(set, childId, ConstraintSet.RIGHT, attrs.getAttributeValueOf("layout_goneMarginRight"));
        setGoneMargin(set, childId, ConstraintSet.TOP, attrs.getAttributeValueOf("layout_goneMarginTop"));
        setGoneMargin(set, childId, ConstraintSet.BOTTOM, attrs.getAttributeValueOf("layout_goneMarginBottom"));
        setGoneMargin(set, childId, ConstraintSet.START, attrs.getAttributeValueOf("layout_goneMarginStart"));
        setGoneMargin(set, childId, ConstraintSet.END, attrs.getAttributeValueOf("layout_goneMarginEnd"));
    }

    private void setSafeMargin(ConstraintSet set, int childId, int side, int valueDp) {
        if (valueDp >= 0) {
            set.setMargin(childId, side, dp(valueDp));
        }
    }

    private void setGoneMargin(ConstraintSet set, int childId, int side, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        int margin = resolveMargin(value, -1);
        if (margin >= 0) {
            set.setGoneMargin(childId, side, dp(margin));
        }
    }

    private int resolveMargin(String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        return PropertiesUtil.resolveSize(value, fallback);
    }

    private int dp(int value) {
        return Math.max(0, (int) ViewUtil.dpToPx(getContext(), value));
    }

    /**
     * Sentinel meaning "no such target". This cannot be 0, because 0 is the
     * real value of {@link ConstraintSet#PARENT_ID}. Using 0 as an
     * "unresolved" marker (as an earlier version of this code did) silently
     * dropped every constraint pointing at "parent", since {@code connect()}
     * treated the valid parent id the same as "not found" and skipped the
     * call to {@code set.connect(...)} entirely. That bug is the reason only
     * coincidental top/start-to-parent cases looked correct while bias,
     * percent sizing, MATCH_CONSTRAINT and bottom/end-to-parent constraints
     * silently did nothing.
     */
    private static final int TARGET_UNRESOLVED = Integer.MIN_VALUE;

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

        String circleTarget = attrs.getAttributeValueOf("layout_constraintCircle");
        if (!TextUtils.isEmpty(circleTarget)) {
            int targetId = resolveTarget(circleTarget);
            if (targetId != TARGET_UNRESOLVED) {
                int radius = resolveMargin(attrs.getAttributeValueOf("layout_constraintCircleRadius"), 0);
                Float angle = parseFloat(attrs.getAttributeValueOf("layout_constraintCircleAngle"));
                set.constrainCircle(child.getId(), targetId, dp(Math.max(0, radius)), angle != null ? angle : 0f);
            }
        }
    }

    private void connect(ConstraintSet set, int childId, int sourceSide, int targetSide, String target) {
        int targetId = resolveTarget(target);
        if (targetId != TARGET_UNRESOLVED) {
            set.connect(childId, sourceSide, targetId, targetSide);
        }
    }

    /**
     * Resolves an XML constraint target ("parent", "@id/foo", "@+id/foo", or
     * a bare Sketchware component id) to the Android View id ConstraintSet
     * needs. "parent" always resolves to {@link ConstraintSet#PARENT_ID}
     * (which is 0) and must be special-cased rather than looked up in
     * {@link #previewIds}, since 0 is also used elsewhere as an
     * "unresolved" sentinel.
     */
    private int resolveTarget(String value) {
        if (TextUtils.isEmpty(value)) {
            return TARGET_UNRESOLVED;
        }
        String reference = normalizeReference(value);
        if ("parent".equals(reference)) {
            return ConstraintSet.PARENT_ID;
        }
        Integer id = previewIds.get(reference);
        return id != null ? id : TARGET_UNRESOLVED;
    }

    private String normalizeReference(String value) {
        if (value == null) {
            return "";
        }
        String reference = value.trim();
        if ("parent".equals(reference)) {
            return "parent";
        }
        int slash = reference.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < reference.length()) {
            reference = reference.substring(slash + 1);
        }
        if (reference.startsWith("@+id/")) {
            reference = reference.substring(5);
        } else if (reference.startsWith("@id/")) {
            reference = reference.substring(4);
        } else if (reference.startsWith("@+")) {
            int separator = reference.indexOf('/');
            if (separator >= 0) reference = reference.substring(separator + 1);
        }
        return reference;
    }

    private void applyAdvanced(ConstraintSet set, View child, InjectAttributeHandler attrs) {
        int id = child.getId();

        Float horizontalBias = parseFloat(attrs.getAttributeValueOf("layout_constraintHorizontal_bias"));
        if (horizontalBias != null) set.setHorizontalBias(id, clamp01(horizontalBias));
        Float verticalBias = parseFloat(attrs.getAttributeValueOf("layout_constraintVertical_bias"));
        if (verticalBias != null) set.setVerticalBias(id, clamp01(verticalBias));

        String ratio = attrs.getAttributeValueOf("layout_constraintDimensionRatio");
        if (!TextUtils.isEmpty(ratio)) set.setDimensionRatio(id, ratio.trim());

        Float horizontalWeight = parseFloat(attrs.getAttributeValueOf("layout_constraintHorizontal_weight"));
        if (horizontalWeight != null) set.setHorizontalWeight(id, Math.max(0f, horizontalWeight));
        Float verticalWeight = parseFloat(attrs.getAttributeValueOf("layout_constraintVertical_weight"));
        if (verticalWeight != null) set.setVerticalWeight(id, Math.max(0f, verticalWeight));

        applyChainStyle(set, id, attrs.getAttributeValueOf("layout_constraintHorizontal_chainStyle"), true);
        applyChainStyle(set, id, attrs.getAttributeValueOf("layout_constraintVertical_chainStyle"), false);
    }

    private void applyChainStyle(ConstraintSet set, int id, String value, boolean horizontal) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        String normalized = value.trim().toLowerCase();
        int style;
        if ("packed".equals(normalized)) {
            style = ConstraintSet.CHAIN_PACKED;
        } else if ("spread_inside".equals(normalized) || "spreadinside".equals(normalized)) {
            style = ConstraintSet.CHAIN_SPREAD_INSIDE;
        } else {
            style = ConstraintSet.CHAIN_SPREAD;
        }
        if (horizontal) {
            set.setHorizontalChainStyle(id, style);
        } else {
            set.setVerticalChainStyle(id, style);
        }
    }

    private Float parseFloat(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Float parsePercent(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String normalized = value.trim();
        try {
            if (normalized.endsWith("%")) {
                return clamp01(Float.parseFloat(normalized.substring(0, normalized.length() - 1).trim()) / 100f);
            }
            return clamp01(Float.parseFloat(normalized));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
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
            if (!TextUtils.isEmpty(attrs.getAttributeValueOf(name))) {
                return true;
            }
        }
        return false;
    }

    private void applyAutomaticPlacement(ConstraintSet set, View child, int index) {
        int id = child.getId();
        int previousId = 0;
        ViewGroup.LayoutParams childParams = child.getLayoutParams();
        boolean matchConstraintWidth = childParams != null && childParams.width == 0;
        boolean matchConstraintHeight = childParams != null && childParams.height == 0;
        for (int i = index - 1; i >= 0; i--) {
            View previous = getChildAt(i);
            if (previous instanceof ItemView && previous.getId() != View.NO_ID) {
                previousId = previous.getId();
                break;
            }
        }
        set.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
        if (matchConstraintWidth) {
            set.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        }
        if (previousId != 0) {
            set.connect(id, ConstraintSet.TOP, previousId, ConstraintSet.BOTTOM);
        } else {
            set.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
        }
        if (matchConstraintHeight) {
            set.connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
        }
    }

    public void markPreviewConstraintsDirty() {
        previewConstraintsDirty = true;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child instanceof ItemView) {
            previewConstraintsDirty = true;
        }
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if (child instanceof ItemView) {
            previewConstraintsDirty = true;
        }
    }

    @Override
    public void addView(View child, int index) {
        int childCount = getChildCount();
        if (index > childCount) {
            super.addView(child);
        } else {
            super.addView(child, index);
        }
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
    public void setFixed(boolean fixed) {
        isFixed = fixed;
    }

    public boolean getSelection() {
        return isSelected;
    }

    @Override
    public void setSelection(boolean selection) {
        isSelected = selection;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!isFixed) {
            if (isSelected) {
                paint.setColor(0x9599d5d0);
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                canvas.drawRect(rect, paint);
            }
            paint.setColor(0x60000000);
            int width = getMeasuredWidth();
            int height = getMeasuredHeight();
            canvas.drawLine(0, 0, width, 0, paint);
            canvas.drawLine(0, 0, 0, height, paint);
            canvas.drawLine(width, 0, width, height, paint);
            canvas.drawLine(0, height, width, height, paint);
        }
        super.onDraw(canvas);
    }

    @Override
    public void setChildScrollEnabled(boolean scrollEnabled) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ScrollContainer scrollContainer) {
                scrollContainer.setChildScrollEnabled(scrollEnabled);
            }
            if (child instanceof ItemHorizontalScrollView horizontalScrollView) {
                horizontalScrollView.setScrollEnabled(scrollEnabled);
            }
            if (child instanceof ItemVerticalScrollView verticalScrollView) {
                verticalScrollView.setScrollEnabled(scrollEnabled);
            }
        }
    }
}
