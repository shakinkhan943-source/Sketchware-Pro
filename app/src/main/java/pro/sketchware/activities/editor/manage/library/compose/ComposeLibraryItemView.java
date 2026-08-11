package pro.sketchware.activities.editor.manage.library.compose;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;

import pro.sketchware.activities.editor.manage.library.LibraryItemView;
import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.util.Helper;
import pro.sketchware.util.library.ComposeLibraryManager;
import pro.sketchware.R;

@SuppressLint("ViewConstructor")
public class ComposeLibraryItemView extends LibraryItemView {

    public ComposeLibraryItemView(Context context) {
        super(context);
    }

    @Override
    public void setData(@Nullable ProjectLibraryBean projectLibraryBean) {
        icon.setImageResource(R.drawable.ic_mtrl_compose);
        title.setText(Helper.getResString(R.string.design_library_title_compose));
        description.setText(Helper.getResString(R.string.design_library_description_compose));
        assert projectLibraryBean != null;
        boolean isEnabled = new ComposeLibraryManager(projectLibraryBean).isComposeEnabled();
        enabled.setText(isEnabled ? "ON" : "OFF");
        enabled.setSelected(isEnabled);
    }
}
