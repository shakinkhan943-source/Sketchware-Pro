package pro.sketchware.activities.preview;

import android.os.Bundle;

import pro.sketchware.beans.ViewBean;
import pro.sketchware.activities.editor.view.ItemView;
import pro.sketchware.activities.editor.view.ViewPane;
import pro.sketchware.activities.base.BaseAppCompatActivity;

import java.util.ArrayList;

import pro.sketchware.core.project.ProjectDataManager;
import pro.sketchware.util.UIHelper;
import pro.sketchware.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityLayoutPreviewBinding;
import pro.sketchware.tools.ViewBeanParser;
import pro.sketchware.tools.ViewBeanFactory;
import pro.sketchware.util.SketchwareUtil;
import pro.sketchware.util.UI;

public class LayoutPreviewActivity extends BaseAppCompatActivity {

    private ViewPane pane;

    private String content;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        ActivityLayoutPreviewBinding binding = ActivityLayoutPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        var toolbar = binding.toolbar;
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle(R.string.layout_preview_title);
        getSupportActionBar().setSubtitle(getIntent().getStringExtra("title"));
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(true);
        toolbar.setNavigationOnClickListener(v -> {
            if (!UIHelper.isClickThrottled()) {
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        content = getIntent().getStringExtra("xml");
        var sc_id = getIntent().getStringExtra("sc_id");
        pane = binding.pane;
        pane.initialize(sc_id, true);
        pane.updateRootLayout(sc_id, getIntent().getStringExtra("title"));
        pane.setVerticalScrollBarEnabled(true);
        pane.setResourceManager(ProjectDataManager.getResourceManager(sc_id));
        UI.addSystemWindowInsetToPadding(binding.pane, false, false, false, true);
    }

    @Override
    public void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (content != null) {
            try {
                var parser = new ViewBeanParser(content);
                var views = parser.parse();
                var rootAttributes = parser.getRootAttributes();
                if (rootAttributes != null) {
                    var rootBean = new ViewBean("root", ViewBeanParser.getViewTypeByClassName(rootAttributes.first));
                    rootBean.convert = rootAttributes.first;
                    new ViewBeanFactory(rootBean).applyAttributes(rootAttributes.second);
                    pane.updateRootLayout(rootBean);
                } else {
                    pane.updateRootLayout(getIntent().getStringExtra("sc_id"), getIntent().getStringExtra("title"));
                }
                loadViews(views);
            } catch (Exception e) {
                SketchwareUtil.toastError(e.toString());
            }
        } else {
            SketchwareUtil.toastError(Helper.getResString(R.string.error_content_null));
        }
    }

    private ItemView loadView(ViewBean view) {
        var itemView = pane.createItemView(view);
        pane.addViewAndUpdateIndex(itemView);
        if (itemView instanceof ItemView sy) {
            sy.setFixed(true);
            return sy;
        }
        return null;
    }

    private ItemView loadViews(ArrayList<ViewBean> views) {
        ItemView itemView = null;
        for (ViewBean view : views) {
            if (views.indexOf(view) == 0) {
                view.parent = "root";
                view.preParent = null;
                view.preParentType = -1;
                itemView = loadView(view);
            } else {
                loadView(view);
            }
        }
        return itemView;
    }
}
