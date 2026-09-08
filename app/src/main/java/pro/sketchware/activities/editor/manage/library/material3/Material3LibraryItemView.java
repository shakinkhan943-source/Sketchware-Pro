package pro.sketchware.activities.editor.manage.library.material3;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;

import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.activities.editor.manage.library.LibraryItemView;

import pro.sketchware.util.Helper;
import pro.sketchware.R;
import pro.sketchware.util.library.ComposeMaterial3LibraryManager;
import pro.sketchware.util.library.Material3LibraryManager;

@SuppressLint("ViewConstructor")
public class Material3LibraryItemView extends LibraryItemView {

    /**
     * Whether this item reflects the Compose Material 3 system. Java/XML projects show the state
     * of the XML Material 3 configuration (AppCompat bean); Compose projects show the state of
     * their own Compose Material 3 configuration (Compose bean).
     */
    private boolean isCompose;

    public Material3LibraryItemView(Context context) {
        super(context);
    }

    public Material3LibraryItemView setCompose(boolean compose) {
        isCompose = compose;
        return this;
    }

    @Override
    public void setData(@Nullable ProjectLibraryBean projectLibraryBean) {
        icon.setImageResource(R.drawable.ic_mtrl_material3);
        title.setText(Helper.getResString(isCompose
                ? R.string.design_library_title_compose_material3
                : R.string.design_library_title_material3));
        description.setText(Helper.getResString(isCompose
                ? R.string.compose_material3_description
                : R.string.material3_description));
        boolean isEnabled = isCompose
                ? new ComposeMaterial3LibraryManager(projectLibraryBean).isMaterial3Enabled()
                : new Material3LibraryManager(projectLibraryBean).isMaterial3Enabled();
        enabled.setText(isEnabled ? "ON" : "OFF");
        enabled.setSelected(isEnabled);
    }
}
