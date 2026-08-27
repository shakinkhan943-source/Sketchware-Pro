package pro.sketchware.activities.settings;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.util.List;

import dev.pranav.filepicker.FilePickerCallback;
import dev.pranav.filepicker.FilePickerDialogFragment;
import dev.pranav.filepicker.FilePickerOptions;

import pro.sketchware.R;
import pro.sketchware.util.library.ComposeBuiltInLibraries;
import pro.sketchware.util.library.ComposeDependencyManager;

/** User-facing configuration screen for the external Compose dependency package. */
public class ComposeLibraryActivity extends AppCompatActivity {
    private File selectedZip;
    private File selectedJson;
    private TextView status;
    private MaterialButton apply;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Jetpack Compose Dependencies");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Jetpack Compose Dependencies");
        title.setTextSize(22);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(this);
        description.setText("Select the Compose dependency ZIP and its accompanying JSON manifest. The manifest is parsed automatically and file paths are detected from the selected package.");
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = padding / 2;
        root.addView(description, descriptionParams);

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);

        status = new TextView(this);
        status.setTextSize(14);
        cardContent.addView(status, new LinearLayout.LayoutParams(-1, -2));
        card.setContentView(cardContent);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = padding;
        root.addView(card, cardParams);

        MaterialButton zipButton = new MaterialButton(this);
        zipButton.setText("Select Compose dependency ZIP");
        zipButton.setOnClickListener(v -> pickZip());
        root.addView(zipButton, new LinearLayout.LayoutParams(-1, -2));

        MaterialButton jsonButton = new MaterialButton(this);
        jsonButton.setText("Select accompanying JSON");
        jsonButton.setOnClickListener(v -> pickJson());
        root.addView(jsonButton, new LinearLayout.LayoutParams(-1, -2));

        apply = new MaterialButton(this);
        apply.setText("Use selected package");
        apply.setEnabled(false);
        apply.setOnClickListener(v -> applyPackage());
        root.addView(apply, new LinearLayout.LayoutParams(-1, -2));

        MaterialButton clear = new MaterialButton(this);
        clear.setText("Remove configured package");
        clear.setOnClickListener(v -> {
            ComposeDependencyManager.clear();
            selectedZip = null;
            selectedJson = null;
            refreshStatus();
            Toast.makeText(this, "Compose dependency package removed", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        refreshStatus();
    }

    private void pickZip() {
        FilePickerOptions options = new FilePickerOptions();
        options.setExtensions(new String[]{"zip"});
        options.setTitle("Select Compose dependency ZIP");
        new FilePickerDialogFragment(options, new FilePickerCallback() {
            @Override
            public void onFileSelected(File file) {
                selectedZip = file;
                refreshStatus();
            }
        }).show(getSupportFragmentManager(), "compose_zip_picker");
    }

    private void pickJson() {
        FilePickerOptions options = new FilePickerOptions();
        options.setExtensions(new String[]{"json"});
        options.setTitle("Select Compose dependency JSON");
        new FilePickerDialogFragment(options, new FilePickerCallback() {
            @Override
            public void onFileSelected(File file) {
                selectedJson = file;
                refreshStatus();
            }
        }).show(getSupportFragmentManager(), "compose_json_picker");
    }

    private void applyPackage() {
        if (selectedZip == null || selectedJson == null) return;
        apply.setEnabled(false);
        status.setText("Validating and preparing Compose dependencies…");
        new Thread(() -> {
            try {
                ComposeDependencyManager.configure(selectedZip, selectedJson);
                ComposeDependencyManager.ensureReady();
                runOnUiThread(() -> {
                    refreshStatus();
                    Toast.makeText(this, "Compose dependency package is ready", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Package error: " + e.getMessage());
                    apply.setEnabled(true);
                });
            }
        }, "compose-package-prepare").start();
    }

    private void refreshStatus() {
        StringBuilder value = new StringBuilder();
        if (ComposeDependencyManager.isConfigured()) {
            value.append("Configured package: ").append(ComposeDependencyManager.getConfiguredPackageName(), 0, Math.min(12, ComposeDependencyManager.getConfiguredPackageName().length())).append("…");
        } else {
            value.append("No Compose dependency package configured.");
        }
        if (selectedZip != null) value.append("\nZIP: ").append(selectedZip.getName());
        if (selectedJson != null) value.append("\nJSON: ").append(selectedJson.getName());
        status.setText(value.toString());
        apply.setEnabled(selectedZip != null && selectedJson != null);
    }
}
