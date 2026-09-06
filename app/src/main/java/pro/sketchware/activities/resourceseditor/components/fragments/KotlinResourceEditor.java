package pro.sketchware.activities.resourceseditor.components.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;

import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.databinding.FragmentKotlinResourceEditorBinding;
import pro.sketchware.util.EditorUtils;
import pro.sketchware.util.FileUtil;

/**
 * Edits a Compose Kotlin resource/configuration file (Color.kt, Theme.kt) in place of the
 * Java/XML values system. Java/XML projects keep the XML editors; this fragment is only ever
 * added for Jetpack Compose projects.
 */
public class KotlinResourceEditor extends Fragment {

    public static final String ARG_SC_ID = "sc_id";
    public static final String ARG_FILE_NAME = "file_name";

    private FragmentKotlinResourceEditorBinding binding;
    private String scId;
    private String fileName;
    private String filePath;
    private boolean loaded;
    public boolean hasUnsavedChanges;

    public static KotlinResourceEditor newInstance(String scId, String fileName) {
        KotlinResourceEditor fragment = new KotlinResourceEditor();
        Bundle args = new Bundle();
        args.putString(ARG_SC_ID, scId);
        args.putString(ARG_FILE_NAME, fileName);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            scId = getArguments().getString(ARG_SC_ID);
            fileName = getArguments().getString(ARG_FILE_NAME);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentKotlinResourceEditorBinding.inflate(inflater, container, false);
        binding.editor.setTypefaceText(EditorUtils.getTypeface(requireContext()));
        EditorUtils.loadKotlinConfig(binding.editor);
        binding.editor.setPinLineNumber(true);
        binding.editor.subscribeEvent(
                io.github.rosemoe.sora.event.ContentChangeEvent.class,
                (event, unsubscribe) -> {
                    if (loaded) {
                        hasUnsavedChanges = true;
                    }
                });
        load();
        return binding.getRoot();
    }

    private void load() {
        if (scId == null || fileName == null) {
            return;
        }
        filePath = SketchwarePaths.getProjectJavaPath(scId) + File.separator + fileName;
        String content;
        if (FileUtil.isExistFile(filePath)) {
            content = FileUtil.readFile(filePath);
        } else {
            content = new ProjectFilePaths(requireContext(), scId).getComposeResourceCode(fileName);
        }
        loaded = false;
        binding.editor.setText(content);
        loaded = true;
        hasUnsavedChanges = false;
    }

    /** Writes the edited Kotlin file back to the project source tree. */
    public void save() {
        if (filePath == null) {
            return;
        }
        FileUtil.writeFile(filePath, binding.editor.getText().toString());
        hasUnsavedChanges = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) {
            binding.editor.release();
            binding = null;
        }
    }
}
