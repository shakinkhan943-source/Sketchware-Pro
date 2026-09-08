package pro.sketchware.activities.editor.manage.library.material3;

import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Objects;

import pro.sketchware.R;
import pro.sketchware.activities.base.BaseAppCompatActivity;
import pro.sketchware.beans.ProjectLibraryBean;
import pro.sketchware.core.project.ProjectType;
import pro.sketchware.databinding.ManageLibraryMaterial3Binding;
import pro.sketchware.util.Helper;
import pro.sketchware.util.library.ComposeMaterial3LibraryManager;

/**
 * Material 3 management for <b>Jetpack Compose</b> projects.
 *
 * <p>This is the Compose half of the split Material 3 management: Java/XML projects keep
 * {@link Material3LibraryActivity} (backed by the AppCompat bean), while a Compose project opens
 * this screen, which edits the Compose project's own Material 3 configuration. The generated
 * {@code Theme.kt}/{@code Color.kt} are built from that configuration.</p>
 */
public class ComposeMaterial3LibraryActivity extends BaseAppCompatActivity {

    private ManageLibraryMaterial3Binding binding;
    private ComposeMaterial3LibraryManager composeMaterial3Manager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ManageLibraryMaterial3Binding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        enableEdgeToEdgeNoContrast();

        String projectType = getIntent().getStringExtra("project_type");
        if (!ProjectType.COMPOSE.equals(ProjectType.normalize(projectType))) {
            // Compose Material 3 belongs to Compose projects; the Java/XML system has its own
            // management screen (Material3LibraryActivity).
            Toast.makeText(this, R.string.design_library_compose_not_available_java_xml, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        initialize();
    }

    private void initialize() {
        binding.toolbar.setNavigationOnClickListener(Helper.getBackPressedClickListener(this));

        composeMaterial3Manager = new ComposeMaterial3LibraryManager(
                (ProjectLibraryBean) Objects.requireNonNull(getIntent().getParcelableExtra("compose")));

        binding.libSwitch.setChecked(composeMaterial3Manager.isMaterial3Enabled());
        binding.dynamicColorsSwitch.setChecked(composeMaterial3Manager.isDynamicColorsEnabled());

        binding.toggleGroup.setEnabled(composeMaterial3Manager.isMaterial3Enabled());
        binding.dynamicColorsSwitch.setEnabled(composeMaterial3Manager.isMaterial3Enabled());

        binding.libSwitch.setOnCheckedChangeListener(getOnCheckedChangeListener());

        binding.layoutSwitchLib.setOnClickListener(view ->
                binding.libSwitch.setChecked(!binding.libSwitch.isChecked()));
        binding.layoutSwitchDynamicColors.setOnClickListener(view -> {
            if (binding.libSwitch.isChecked()) {
                binding.dynamicColorsSwitch.setChecked(!binding.dynamicColorsSwitch.isChecked());
            }
        });

        switch (composeMaterial3Manager.getTheme()) {
            case ComposeMaterial3LibraryManager.THEME_LIGHT -> binding.selectLight.setChecked(true);
            case ComposeMaterial3LibraryManager.THEME_DARK -> binding.selectDark.setChecked(true);
            default -> binding.selectDayNight.setChecked(true);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                composeMaterial3Manager.setConfiguration(
                        ComposeMaterial3LibraryManager.CONFIG_MATERIAL3, binding.libSwitch.isChecked());
                composeMaterial3Manager.setConfiguration(
                        ComposeMaterial3LibraryManager.CONFIG_DYNAMIC_COLORS, binding.dynamicColorsSwitch.isChecked());

                String theme = ComposeMaterial3LibraryManager.THEME_DAY_NIGHT;
                if (binding.selectLight.isChecked()) {
                    theme = ComposeMaterial3LibraryManager.THEME_LIGHT;
                } else if (binding.selectDark.isChecked()) {
                    theme = ComposeMaterial3LibraryManager.THEME_DARK;
                }
                composeMaterial3Manager.setConfiguration(ComposeMaterial3LibraryManager.CONFIG_THEME, theme);

                Intent resultIntent = new Intent();
                resultIntent.putExtra("compose", composeMaterial3Manager.getComposeLibraryBean());
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private CompoundButton.OnCheckedChangeListener getOnCheckedChangeListener() {
        return (buttonView, isChecked) -> {
            binding.toggleGroup.setEnabled(isChecked);
            binding.dynamicColorsSwitch.setEnabled(isChecked);
            if (!isChecked) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.library_appcompat_disabled_title)
                        .setMessage(R.string.compose_material3_disabled_message)
                        .setPositiveButton(R.string.common_word_ok, null)
                        .show();
            }
        };
    }
}
