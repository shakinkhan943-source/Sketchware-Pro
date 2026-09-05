package pro.sketchware.activities.design;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import pro.sketchware.beans.ProjectFileBean;
import pro.sketchware.activities.tools.SrcViewerActivity;
import pro.sketchware.activities.editor.manage.ManageCollectionActivity;
import pro.sketchware.activities.editor.manage.ViewSelectorActivity;
import pro.sketchware.activities.editor.manage.font.ManageFontActivity;
import pro.sketchware.activities.editor.manage.image.ManageImageActivity;
import pro.sketchware.activities.editor.manage.library.ManageLibraryActivity;
import pro.sketchware.activities.editor.manage.sound.ManageSoundActivity;
import pro.sketchware.activities.editor.manage.view.ManageViewActivity;
import pro.sketchware.activities.base.BaseAppCompatActivity;
import pro.sketchware.activities.tools.CompileLogActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import pro.sketchware.core.async.BackgroundTasks;
import pro.sketchware.util.io.SharedPrefsHelper;
import pro.sketchware.util.DeviceUtil;
import pro.sketchware.core.codegen.LayoutGenerator;
import pro.sketchware.core.build.ProjectBuilder;
import pro.sketchware.activities.design.fragments.ViewEditorFragment;
import pro.sketchware.util.SketchToast;
import pro.sketchware.core.project.BlockHistoryManager;
import pro.sketchware.activities.design.fragments.ComponentListFragment;
import pro.sketchware.core.project.ViewHistoryManager;
import pro.sketchware.core.project.ProjectDataStore;
import pro.sketchware.core.project.ProjectDataManager;
import pro.sketchware.core.project.ResourceManager;
import pro.sketchware.core.project.ProjectListManager;
import pro.sketchware.core.async.TaskHost;
import pro.sketchware.util.UIHelper;
import pro.sketchware.activities.design.fragments.EventListFragment;
import pro.sketchware.activities.design.fragments.JavaEditorFragment;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.MapValueHelper;
import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.exception.SimpleException;
import pro.sketchware.util.UI;
import pro.sketchware.activities.editor.ManagePermissionActivity;
import pro.sketchware.activities.editor.ManageResourceActivity;
import pro.sketchware.activities.editor.ManageAssetsActivity;
import pro.sketchware.activities.editor.ManageJavaActivity;
import pro.sketchware.core.build.compiler.KotlinCompilerBridge;
import pro.sketchware.core.project.ProguardHandler;
import pro.sketchware.core.project.StringfogHandler;
import pro.sketchware.util.Helper;
import pro.sketchware.util.SystemLogPrinter;
import pro.sketchware.activities.editor.manifest.AndroidManifestInjection;
import pro.sketchware.activities.settings.ConfigActivity;
import pro.sketchware.core.build.BuildProgressReceiver;
import pro.sketchware.util.library.BuiltInLibraries;
import pro.sketchware.core.build.CompileErrorSaver;
import pro.sketchware.core.exception.MissingFileException;
import pro.sketchware.util.LogUtil;
import pro.sketchware.activities.editor.LogReaderActivity;
import pro.sketchware.R;
import pro.sketchware.activities.appcompat.ManageAppCompatActivity;
import pro.sketchware.activities.editor.command.ManageXMLCommandActivity;
import pro.sketchware.activities.editor.view.CodeViewerActivity;
import pro.sketchware.activities.editor.view.ViewCodeEditorActivity;
import pro.sketchware.activities.resourceseditor.ResourcesEditorActivity;
import pro.sketchware.dialogs.BuildSettingsBottomSheet;
import pro.sketchware.util.FileUtil;
import pro.sketchware.util.SketchwareUtil;
import pro.sketchware.util.ThemeUtils;
import pro.sketchware.util.apk.ApkSignatures;

public class DesignActivity extends BaseAppCompatActivity implements View.OnClickListener {
    private static final int DESTINATION_SOURCE = 0;
    private static final int DESTINATION_UI = 1;
    private static final int DESTINATION_EVENTS = 2;
    private static final int DESTINATION_COMPONENTS = 3;

    private static final String STATE_CURRENT_DESTINATION = "current_editor_destination";
    private static final String STATE_PREVIOUS_PRIMARY_DESTINATION = "previous_primary_destination";
    private static final String FRAGMENT_TAG_EVENTS = "design_editor_events";
    private static final String FRAGMENT_TAG_COMPONENTS = "design_editor_components";

    public static String sc_id;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FirebaseCrashlytics crashlytics = getFirebaseCrashlytics();
    private ImageView xmlLayoutOrientation;
    private boolean isRestoringData;
    private int currentDestination = DESTINATION_SOURCE;
    private int previousPrimaryDestination = DESTINATION_SOURCE;
    /** Guards programmatic page changes so the pager callback doesn't re-enter. */
    private boolean isSynchronizingPager;
    /**
     * Number of pages the editor pager currently exposes. Java + XML files show two pages
     * (source & UI); Kotlin/Compose files are source-only and show a single page.
     */
    private int currentPagerPageCount = 2;
    private ProjectFileBean lastViewTabProjectFile;
    private View coordinatorLayout;
    private DrawerLayout drawer;
    private ProjectFilePaths projectFilePaths;
    private SharedPrefsHelper prefP1;
    private SharedPrefsHelper prefP12;
    private Menu toolbarMenu;
    private ProjectFileBean projectFile;
    private TextView fileName;
    private String currentJavaFileName;
    private ViewPager2 editorPager;
    private EditorPagerAdapter pagerAdapter;
    private ViewEditorFragment viewTabAdapter;
    private final ActivityResultLauncher<Intent> openCollectionManager = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            if (viewTabAdapter != null) {
                viewTabAdapter.refreshFavorites();
            }
        }
    });
    private final ActivityResultLauncher<Intent> openResourcesManager = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            if (viewTabAdapter != null && currentDestination == DESTINATION_UI) {
                viewTabAdapter.refreshAllViews();
                refreshViewTabAdapter();
            }
        }
    });
    private final ActivityResultLauncher<Intent> openViewCodeEditor = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            if (viewTabAdapter != null) {
                viewTabAdapter.refreshAllViews();
            }
        }
    });
    private EventListFragment eventTabAdapter;
    private ComponentListFragment componentTabAdapter;
    private JavaEditorFragment javaTabAdapter;
    private final ActivityResultLauncher<Intent> openImageManager = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            refresh();
        }
    });
    public final ActivityResultLauncher<Intent> changeOpenFile = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK) {
            var data = result.getData();
            if (data == null) return;
            projectFile = data.getParcelableExtra("project_file");
            refresh();
        }
    });
    private final ActivityResultLauncher<Intent> openLibraryManager = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            refresh();
            if (viewTabAdapter != null && currentDestination == DESTINATION_UI && viewTabAdapter.getProjectFileBean() != null) {
                viewTabAdapter.updatePropertyViews();
            }
        }
    });
    private final ActivityResultLauncher<Intent> openViewManager = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            refresh();
        }
    });
    private BuildTask currentBuildTask;
    private final BroadcastReceiver buildCancelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BuildTask.ACTION_CANCEL_BUILD.equals(intent.getAction())) {
                if (currentBuildTask != null) {
                    currentBuildTask.cancelBuild();
                }
            }
        }
    };

    /**
     * Saves the app's version information to the currently opened Sketchware project file.
     */
    private void saveVersionCodeInformationToProject() {
        HashMap<String, Object> projectMetadata = ProjectListManager.getProjectById(sc_id);
        if (projectMetadata != null) {
            projectMetadata.put("sketchware_ver", DeviceUtil.getVersionCode(getApplicationContext()));
            ProjectListManager.updateProject(sc_id, projectMetadata);
        }
    }

    private void loadProject(boolean haveSavedState) {
        ProjectDataManager.getProjectDataManager(sc_id, haveSavedState);
        ProjectDataManager.getFileManager(sc_id, haveSavedState);
        ResourceManager resourceManager = ProjectDataManager.getResourceManager(sc_id, haveSavedState);
        ProjectDataManager.getLibraryManager(sc_id, haveSavedState);
        projectFile = getDefaultProjectFile();
        ViewHistoryManager.getInstance(sc_id);
        BlockHistoryManager.getInstance(sc_id);
        // Resource backup is now lazy — ensureBackedUp() is called
        // before any resource modification, not eagerly on project open.
    }

    private ProjectFileBean getDefaultProjectFile() {
        return ProjectDataManager.getFileManager(sc_id).getFileByXmlName(ProjectFileBean.DEFAULT_XML_NAME);
    }

    private void refreshFileSelector() {
        if (projectFile == null) {
            projectFile = getDefaultProjectFile();
        }
        if (projectFile == null) {
            return;
        }

        if (projectFile.isKotlin()) {
            previousPrimaryDestination = DESTINATION_SOURCE;
            if (currentDestination == DESTINATION_UI) {
                showEditorDestination(DESTINATION_SOURCE, false);
            }
        }

        String javaFileName = projectFile.getJavaName();
        String xmlFileName = projectFile.getXmlName();
        String sourceFileName = projectFile.isKotlin()
                ? projectFile.getSourceFileName()
                : javaFileName;

        if (!javaFileName.isEmpty()) {
            currentJavaFileName = javaFileName;
        }

        if (currentDestination == DESTINATION_UI) {
            if (!ProjectFileBean.DEFAULT_XML_NAME.equals(xmlFileName)
                    && ProjectDataManager.getFileManager(sc_id).getFileByXmlName(xmlFileName) == null) {
                projectFile = getDefaultProjectFile();
                xmlFileName = ProjectFileBean.DEFAULT_XML_NAME;
            }
            fileName.setText(xmlFileName);
        } else {
            if (!ProjectFileBean.DEFAULT_JAVA_NAME.equals(currentJavaFileName)
                    && ProjectDataManager.getFileManager(sc_id)
                    .getActivityByJavaName(currentJavaFileName) == null) {
                projectFile = getDefaultProjectFile();
                currentJavaFileName = ProjectFileBean.DEFAULT_JAVA_NAME;
                sourceFileName = projectFile.isKotlin()
                        ? projectFile.getSourceFileName()
                        : currentJavaFileName;
            }
            fileName.setText(sourceFileName);
        }
        updatePagerPages();
        updateFileSelectorIcon();
    }

    /**
     * Keeps the pager in sync with the kind of file that is open: Kotlin (Compose) files
     * have no XML layout, so the UI page is removed; Java files expose both pages.
     */
    private void updatePagerPages() {
        int newCount = pageCountForCurrentFile();
        FragmentStateAdapter adapter = editorPager != null ? pagerAdapter : null;
        int oldCount = currentPagerPageCount;
        currentPagerPageCount = newCount;
        if (adapter == null || oldCount == newCount) {
            return;
        }
        if (newCount > oldCount) {
            adapter.notifyItemRangeInserted(oldCount, newCount - oldCount);
        } else {
            adapter.notifyItemRangeRemoved(newCount, oldCount - newCount);
        }
    }

    private int pageCountForCurrentFile() {
        return projectFile != null && projectFile.isKotlin() ? 1 : 2;
    }

    private void updateFileSelectorIcon() {
        if (xmlLayoutOrientation == null) {
            return;
        }
        xmlLayoutOrientation.setImageResource(
                currentDestination == DESTINATION_UI && projectFile != null
                        ? R.drawable.ic_mtrl_devices
                        : R.drawable.ic_mtrl_code);
    }

    /**
     * Creates the undo/redo hook on a (possibly restored) UI editor fragment.
     */
    private void hookViewEditor(ViewEditorFragment fragment) {
        fragment.setOnUndoRedoStateChanged(this::updateUndoRedoState);
    }

    /**
     * Re-links the activity-level fragment references after the pager has (re)created or
     * restored its page fragments, and restores the visible workspace. Runs after the
     * first layout pass.
     */
    private void syncEditorFragmentReferences() {
        if (editorPager == null || pagerAdapter == null) {
            return;
        }
        Fragment source = pagerAdapter.getFragment(DESTINATION_SOURCE);
        if (source instanceof JavaEditorFragment) {
            javaTabAdapter = (JavaEditorFragment) source;
        }
        Fragment ui = pagerAdapter.getFragment(DESTINATION_UI);
        if (ui instanceof ViewEditorFragment) {
            viewTabAdapter = (ViewEditorFragment) ui;
            hookViewEditor(viewTabAdapter);
        }
        // Restore the visible workspace: an overlay (Events/Components) if that was the
        // active destination, otherwise the matching pager page.
        if (isOverlayDestination(currentDestination)) {
            displayOverlay(currentDestination);
        } else {
            synchronizePagerWithDestination();
        }
        // Covers the case where the project finished loading before the restored page
        // fragments were re-linked: make sure the visible page reflects the project.
        if (projectFile != null && !isRestoringData) {
            refreshCurrentDestination();
            updateDestinationChrome();
        }
    }

    /**
     * Re-links the Events/Components overlay fragments restored by the FragmentManager
     * (they keep their tags across activity recreation).
     */
    private void restoreOverlayFragments() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment restoredEvents = fragmentManager.findFragmentByTag(FRAGMENT_TAG_EVENTS);
        eventTabAdapter = restoredEvents instanceof EventListFragment
                ? (EventListFragment) restoredEvents
                : null;
        Fragment restoredComponents = fragmentManager.findFragmentByTag(FRAGMENT_TAG_COMPONENTS);
        componentTabAdapter = restoredComponents instanceof ComponentListFragment
                ? (ComponentListFragment) restoredComponents
                : null;
    }

    private Fragment getOrCreateFragmentForDestination(int destination) {
        return switch (destination) {
            case DESTINATION_EVENTS -> {
                if (eventTabAdapter == null) {
                    eventTabAdapter = new EventListFragment();
                }
                yield eventTabAdapter;
            }
            case DESTINATION_COMPONENTS -> {
                if (componentTabAdapter == null) {
                    componentTabAdapter = new ComponentListFragment();
                }
                yield componentTabAdapter;
            }
            default -> null;
        };
    }

    private String getFragmentTag(int destination) {
        return switch (destination) {
            case DESTINATION_COMPONENTS -> FRAGMENT_TAG_COMPONENTS;
            default -> FRAGMENT_TAG_EVENTS;
        };
    }

    private boolean isOverlayDestination(int destination) {
        return destination == DESTINATION_EVENTS || destination == DESTINATION_COMPONENTS;
    }

    /**
     * Shows an overlay workspace (Events/Components) on top of the pager.
     */
    private void displayOverlay(int destination) {
        Fragment target = getOrCreateFragmentForDestination(destination);
        if (target == null) {
            return;
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true);
        hideOverlayFragment(transaction, eventTabAdapter, target);
        hideOverlayFragment(transaction, componentTabAdapter, target);
        if (target.isAdded()) {
            transaction.show(target);
        } else {
            transaction.add(
                    R.id.editor_fragment_container, target, getFragmentTag(destination));
        }
        transaction.setMaxLifecycle(target, Lifecycle.State.RESUMED);
        transaction.setPrimaryNavigationFragment(target);
        transaction.commitNow();
        if (editorPager != null) {
            editorPager.setVisibility(View.GONE);
        }
    }

    private void hideOverlay() {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        hideOverlayFragment(transaction, eventTabAdapter, null);
        hideOverlayFragment(transaction, componentTabAdapter, null);
        transaction.commitNow();
        if (editorPager != null) {
            editorPager.setVisibility(View.VISIBLE);
        }
    }

    private void hideOverlayFragment(
            FragmentTransaction transaction, Fragment fragment, Fragment target) {
        if (fragment != null && fragment != target && fragment.isAdded()) {
            transaction.hide(fragment);
            transaction.setMaxLifecycle(fragment, Lifecycle.State.STARTED);
        }
    }

    private void showEditorDestination(int destination) {
        showEditorDestination(destination, true);
    }

    private void showEditorDestination(int destination, boolean refreshContent) {
        if (destination < DESTINATION_SOURCE || destination > DESTINATION_COMPONENTS) {
            return;
        }
        if (destination == DESTINATION_UI && projectFile != null && projectFile.isKotlin()) {
            SketchwareUtil.toast(Helper.getResString(R.string.design_tab_ui_disabled));
            synchronizePagerWithDestination();
            return;
        }
        if (destination == currentDestination) {
            synchronizePagerWithDestination();
            return;
        }

        if (currentDestination == DESTINATION_EVENTS && eventTabAdapter != null) {
            eventTabAdapter.resetEventValues();
        } else if (currentDestination == DESTINATION_COMPONENTS
                && componentTabAdapter != null) {
            componentTabAdapter.unselectAll();
        }

        boolean leavingOverlay = isOverlayDestination(currentDestination);
        currentDestination = destination;
        if (isOverlayDestination(destination)) {
            displayOverlay(destination);
        } else {
            previousPrimaryDestination = destination;
            if (leavingOverlay) {
                hideOverlay();
            }
            synchronizePagerWithDestination();
        }

        updateDestinationChrome();
        if (refreshContent && projectFile != null) {
            refreshFileSelector();
            refreshCurrentDestination();
        }
        invalidateOptionsMenu();
    }

    /**
     * Moves the pager (smoothly) to the page that matches the current destination,
     * without re-triggering the page-change callback.
     */
    private void synchronizePagerWithDestination() {
        if (editorPager == null || isOverlayDestination(currentDestination)) {
            return;
        }
        int target = currentDestination == DESTINATION_UI ? DESTINATION_UI : DESTINATION_SOURCE;
        if (editorPager.getCurrentItem() == target) {
            return;
        }
        isSynchronizingPager = true;
        editorPager.setCurrentItem(target, true);
        isSynchronizingPager = false;
    }

    private void updateDestinationChrome() {
        boolean isUiDestination = currentDestination == DESTINATION_UI;
        if (toolbarMenu != null) {
            MenuItem xmlEditor = toolbarMenu.findItem(R.id.design_menu_xml_editor);
            if (xmlEditor != null) {
                xmlEditor.setVisible(isUiDestination);
            }
            MenuItem importXml = toolbarMenu.findItem(R.id.design_menu_import_xml);
            if (importXml != null) {
                importXml.setVisible(isUiDestination);
            }
            MenuItem search = toolbarMenu.findItem(R.id.design_option_menu_search);
            if (search != null) {
                search.setVisible(currentDestination == DESTINATION_EVENTS);
            }
        }
        if (viewTabAdapter != null && viewTabAdapter.getView() != null) {
            if (!isUiDestination && viewTabAdapter.isPropertyViewVisible()) {
                viewTabAdapter.togglePropertyView(false);
            }
            viewTabAdapter.showHidePropertyView(isUiDestination);
        } else {
            View propertyView = findViewById(R.id.view_property);
            if (propertyView != null) {
                propertyView.setVisibility(isUiDestination ? View.VISIBLE : View.GONE);
            }
        }
        updateFileSelectorIcon();
        updateUndoRedoState();
        synchronizePagerWithDestination();
    }

    /**
     * Keeps the toolbar Undo/Redo actions in sync with the UI editor's history.
     */
    private void updateUndoRedoState() {
        if (toolbarMenu == null) {
            return;
        }
        boolean uiAvailable = currentDestination == DESTINATION_UI && viewTabAdapter != null;
        MenuItem undo = toolbarMenu.findItem(R.id.design_option_menu_undo);
        if (undo != null) {
            undo.setEnabled(uiAvailable && viewTabAdapter.canUndo());
        }
        MenuItem redo = toolbarMenu.findItem(R.id.design_option_menu_redo);
        if (redo != null) {
            redo.setEnabled(uiAvailable && viewTabAdapter.canRedo());
        }
    }

    /**
     * Mirrors the old Run/Stop button state onto the toolbar: the run icon toggles to a
     * stop icon while a build is running, and the build-only overflow items are disabled.
     */
    public void updateRunToolbarState(boolean isRunning) {
        if (toolbarMenu != null) {
            MenuItem runItem = toolbarMenu.findItem(R.id.design_option_menu_run);
            if (runItem != null) {
                runItem.setIcon(ContextCompat.getDrawable(
                        this, isRunning ? R.drawable.ic_mtrl_stop : R.drawable.ic_mtrl_run));
                runItem.setTitle(Helper.getResString(
                        isRunning ? R.string.design_run_stop : R.string.design_run));
                runItem.setIconTintList(ColorStateList.valueOf(ThemeUtils.getColor(
                        this, isRunning ? R.attr.colorError : R.attr.colorControlNormal)));
            }
            MenuItem buildSettings = toolbarMenu.findItem(R.id.design_menu_build_settings);
            if (buildSettings != null) {
                buildSettings.setEnabled(!isRunning);
            }
            MenuItem cleanTemp = toolbarMenu.findItem(R.id.design_menu_clean_temp);
            if (cleanTemp != null) {
                cleanTemp.setEnabled(!isRunning);
            }
        }
        View progressContainer = findViewById(R.id.progress_container);
        if (progressContainer != null) {
            progressContainer.setVisibility(isRunning ? View.VISIBLE : View.GONE);
        }
    }

    private void onRunClicked() {
        if (currentBuildTask != null && !currentBuildTask.isBuildFinished) {
            if (!currentBuildTask.canceled) {
                currentBuildTask.cancelBuild();
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        BuildTask buildTask = new BuildTask(this);
        currentBuildTask = buildTask;
        buildTask.execute();
    }

    private void refreshViewTabAdapter() {
        lastViewTabProjectFile = projectFile;
        if (viewTabAdapter != null && projectFile != null) {
            updateFileSelectorIcon();
            viewTabAdapter.initialize(projectFile);
        }
    }

    private void refreshEventTabAdapter() {
        if (eventTabAdapter != null && projectFile != null) {
            eventTabAdapter.setCurrentActivity(projectFile);
            eventTabAdapter.refreshEvents();
        }
    }

    private void refreshComponentTabAdapter() {
        if (componentTabAdapter != null && projectFile != null) {
            componentTabAdapter.setProjectFile(projectFile);
            componentTabAdapter.refreshData();
        }
    }

    private void refreshJavaTabAdapter() {
        if (javaTabAdapter != null && projectFile != null) {
            javaTabAdapter.setProjectFile(projectFile);
            // Blocks -> source: always show what the blocks currently generate.
            javaTabAdapter.refresh();
        }
    }

    private void refreshCurrentDestination() {
        switch (currentDestination) {
            case DESTINATION_UI -> {
                if (projectFile != lastViewTabProjectFile) {
                    refreshViewTabAdapter();
                }
            }
            case DESTINATION_EVENTS -> refreshEventTabAdapter();
            case DESTINATION_COMPONENTS -> refreshComponentTabAdapter();
            default -> refreshJavaTabAdapter();
        }
    }

    private void refresh() {
        refreshFileSelector();
        refreshCurrentDestination();
        updateDestinationChrome();
    }

    /**
     * Shows a Snackbar indicating that a problem occurred while compiling. The user can click on "SHOW" to get to {@link CompileLogActivity}.
     *
     * @param error The error, to be later displayed as text in {@link CompileLogActivity}
     */
    private void indicateCompileErrorOccurred(String error) {
        new CompileErrorSaver(sc_id).writeLogsToFile(error);
        Snackbar snackbar = Snackbar.make(coordinatorLayout, Helper.getResString(R.string.snackbar_show_compile_log), Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction(Helper.getResString(R.string.common_word_show), v -> {
            if (!UIHelper.isClickThrottled()) {
                snackbar.dismiss();
                Intent intent = new Intent(getApplicationContext(), CompileLogActivity.class);
                intent.putExtra("error", error);
                intent.putExtra("sc_id", sc_id);
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        });
        snackbar.show();
    }

    @Override
    public void finish() {
        ProjectDataManager.clearAll();
        ViewHistoryManager.clearInstance();
        BlockHistoryManager.clearInstance();
        setResult(RESULT_CANCELED, getIntent());
        super.finish();
    }

    private void checkForUnsavedProjectData() {
        if (ProjectDataManager.getLibraryManager(sc_id).hasBackup() || ProjectDataManager.getFileManager(sc_id).hasBackup() || ProjectDataManager.getResourceManager(sc_id).hasBackup() || ProjectDataManager.getProjectDataManager(sc_id).hasViewBackup() || ProjectDataManager.getProjectDataManager(sc_id).hasLogicBackup()) {
            askIfToRestoreOldUnsavedProjectData();
        }
    }

    /**
     * Opens the debug APK to install.
     */
    private void installBuiltApk() {
        TaskHost taskHost = TaskHost.of(this);
        taskHost.postToUi(() -> {
            if (!ConfigActivity.isSettingEnabled(ConfigActivity.SETTING_ROOT_AUTO_INSTALL_PROJECTS)) {
                requestPackageInstallerInstall();
            } else {
                File apkUri = new File(projectFilePaths.finalToInstallApkPath);
                long length = apkUri.length();
                Shell.getShell(shell -> {
                    if (shell.isRoot()) {
                        List<String> stdout = new LinkedList<>();
                        List<String> stderr = new LinkedList<>();

                        Shell.cmd("cat " + apkUri + " | pm install -S " + length).to(stdout, stderr).submit(result ->
                                taskHost.postToUi(() -> {
                                    if (result.isSuccess()) {
                                        SketchwareUtil.toast(Helper.getResString(R.string.design_toast_package_installed));
                                        if (ConfigActivity.isSettingEnabled(ConfigActivity.SETTING_ROOT_AUTO_OPEN_AFTER_INSTALLING)) {
                                            Intent launcher = getPackageManager().getLaunchIntentForPackage(projectFilePaths.packageName);
                                            if (launcher != null) {
                                                startActivity(launcher);
                                            } else {
                                                SketchwareUtil.toastError(Helper.getResString(R.string.design_error_cannot_launch));
                                            }
                                        }
                                    } else {
                                        SketchwareUtil.toastError(String.format(Helper.getResString(R.string.design_error_install_failed), result.getCode()), Toast.LENGTH_LONG);
                                        LogUtil.e("DesignActivity", "Failed to install package, result code: " + result.getCode() + ". stdout: " + stdout + ", stderr: " + stderr);
                                    }
                                }));
                    } else {
                        taskHost.postToUi(() -> {
                            SketchwareUtil.toastError(Helper.getResString(R.string.design_error_no_root_access));
                            requestPackageInstallerInstall();
                        });
                    }
                });
            }
        });
    }

    private void requestPackageInstallerInstall() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri = FileProvider.getUriForFile(getApplicationContext(), getApplicationContext().getPackageName() + ".provider", new File(projectFilePaths.finalToInstallApkPath));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            SketchwareUtil.toastError(Helper.getResString(R.string.error_no_package_installer));
        }
    }

    public void hideViewPropertyView() {
        if (viewTabAdapter != null && viewTabAdapter.getView() != null) {
            viewTabAdapter.togglePropertyView(false);
        }
    }

    private void saveChangesAndCloseProject() {
        showLoadingDialog();
        SaveChangesProjectCloser saveChangesProjectCloser = new SaveChangesProjectCloser(this);
        saveChangesProjectCloser.execute();
    }

    private void saveProject() {
        showLoadingDialog();
        ProjectSaver projectSaver = new ProjectSaver(this);
        projectSaver.execute();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.END)) {
                    drawer.closeDrawer(GravityCompat.END);
                } else if (viewTabAdapter != null && viewTabAdapter.isPropertyViewVisible()) {
                    hideViewPropertyView();
                } else {
                    if (currentDestination == DESTINATION_EVENTS
                            || currentDestination == DESTINATION_COMPONENTS) {
                        showEditorDestination(previousPrimaryDestination);
                    } else if (currentDestination == DESTINATION_UI) {
                        showEditorDestination(DESTINATION_SOURCE);
                    } else if (prefP12.getBooleanDefault("P12I2")) {
                        showLoadingDialog();
                        saveChangesAndCloseProject();
                    } else {
                        showSaveBeforeQuittingDialog();
                    }
                }
            }
        });
        setContentView(R.layout.design);
        if (!isStoragePermissionGranted()) {
            finish();
        }

        if (savedInstanceState == null) {
            sc_id = getIntent().getStringExtra("sc_id");
        } else {
            sc_id = savedInstanceState.getString("sc_id");
            currentDestination = savedInstanceState.getInt(
                    STATE_CURRENT_DESTINATION, DESTINATION_SOURCE);
            previousPrimaryDestination = savedInstanceState.getInt(
                    STATE_PREVIOUS_PRIMARY_DESTINATION, DESTINATION_SOURCE);
            if (currentDestination < DESTINATION_SOURCE
                    || currentDestination > DESTINATION_COMPONENTS) {
                currentDestination = DESTINATION_SOURCE;
            }
            if (previousPrimaryDestination != DESTINATION_SOURCE
                    && previousPrimaryDestination != DESTINATION_UI) {
                previousPrimaryDestination = DESTINATION_SOURCE;
            }
        }

        if (sc_id == null || sc_id.isEmpty()) {
            finish();
            return;
        }

        prefP1 = new SharedPrefsHelper(getApplicationContext(), "P1");
        prefP12 = new SharedPrefsHelper(getApplicationContext(), "P12");

        Toolbar toolbar = findViewById(R.id.toolbar);
        // Compact toolbar: no project name/ID title, just actions.
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        drawer = findViewById(R.id.drawer_layout);
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        UI.addWindowInsetToMargin(findViewById(R.id.container), WindowInsetsCompat.Type.navigationBars(), false, false, false, true);

        coordinatorLayout = findViewById(R.id.layout_coordinator);
        fileName = findViewById(R.id.file_name);

        findViewById(R.id.file_name_container).setOnClickListener(this);

        xmlLayoutOrientation = findViewById(R.id.img_orientation);

        restoreOverlayFragments();

        editorPager = findViewById(R.id.editor_pager);
        pagerAdapter = new EditorPagerAdapter(this);
        editorPager.setAdapter(pagerAdapter);
        // Keep both pages (Java + UI) alive so swiping preserves their editor state.
        editorPager.setOffscreenPageLimit(1);
        editorPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (isSynchronizingPager) {
                    return;
                }
                showEditorDestination(position, true);
            }
        });

        IntentFilter filter = new IntentFilter(BuildTask.ACTION_CANCEL_BUILD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(buildCancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(buildCancelReceiver, filter);
        }
    }

    private boolean isDebugApkExists() {
        if (projectFilePaths != null) {
            return FileUtil.isExistFile(projectFilePaths.finalToInstallApkPath);
        }
        return false;
    }

    private void updateBottomMenu() {
        if (toolbarMenu != null) {
            handler.post(() -> {
                if (toolbarMenu == null) {
                    return;
                }
                MenuItem cleanTemp = toolbarMenu.findItem(R.id.design_menu_clean_temp);
                if (cleanTemp != null) {
                    cleanTemp.setVisible(projectFilePaths != null
                            && FileUtil.isExistFile(projectFilePaths.projectMyscPath));
                }
                boolean apkExists = isDebugApkExists();
                MenuItem install = toolbarMenu.findItem(R.id.design_menu_install_apk);
                if (install != null) {
                    install.setVisible(apkExists);
                }
                MenuItem signatures = toolbarMenu.findItem(R.id.design_menu_show_signatures);
                if (signatures != null) {
                    signatures.setVisible(apkExists);
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(buildCancelReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was never registered (e.g. onCreate returned early when sc_id was missing)
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.design_menu, menu);
        toolbarMenu = menu;
        updateUndoRedoState();
        updateRunToolbarState(currentBuildTask != null && !currentBuildTask.isBuildFinished);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.design_option_menu_undo) {
            if (viewTabAdapter != null) {
                viewTabAdapter.performUndo();
            }
        } else if (itemId == R.id.design_option_menu_redo) {
            if (viewTabAdapter != null) {
                viewTabAdapter.performRedo();
            }
        } else if (itemId == R.id.design_option_menu_events) {
            showEditorDestination(DESTINATION_EVENTS);
        } else if (itemId == R.id.design_option_menu_components) {
            showEditorDestination(DESTINATION_COMPONENTS);
        } else if (itemId == R.id.design_option_menu_run) {
            onRunClicked();
        } else if (itemId == R.id.design_option_menu_title_save_project) {
            saveProject();
        } else if (itemId == R.id.design_option_menu_search) {
            if (eventTabAdapter != null) {
                eventTabAdapter.toggleSearchBar();
            }
        } else if (itemId == R.id.design_option_menu_open_tools) {
            if (!drawer.isDrawerOpen(GravityCompat.END)) {
                drawer.openDrawer(GravityCompat.END);
            }
        } else if (itemId == R.id.design_menu_build_settings) {
            BuildSettingsBottomSheet sheet = BuildSettingsBottomSheet.newInstance(sc_id);
            sheet.show(getSupportFragmentManager(), BuildSettingsBottomSheet.TAG);
        } else if (itemId == R.id.design_menu_clean_temp) {
            if (projectFilePaths != null) {
                BackgroundTasks.runIo(TaskHost.of(this), "DesignActivity",
                        () -> FileUtil.deleteFile(projectFilePaths.projectMyscPath), () -> {
                            updateBottomMenu();
                            SketchwareUtil.toast(Helper.getResString(R.string.design_toast_clean_temp_done));
                        }, error -> Log.e("DesignActivity", "Failed to clean temporary files", error));
            }
        } else if (itemId == R.id.design_menu_show_last_error) {
            new CompileErrorSaver(sc_id).showLastErrors(this);
        } else if (itemId == R.id.design_menu_show_source) {
            showCurrentActivitySrcCode();
        } else if (itemId == R.id.design_menu_install_apk) {
            if (projectFilePaths != null && FileUtil.isExistFile(projectFilePaths.finalToInstallApkPath)) {
                installBuiltApk();
            } else {
                SketchwareUtil.toast(Helper.getResString(R.string.design_error_apk_not_exist));
            }
        } else if (itemId == R.id.design_menu_show_signatures) {
            if (projectFilePaths != null) {
                ApkSignatures apkSignatures = new ApkSignatures(this, projectFilePaths.finalToInstallApkPath);
                apkSignatures.showSignaturesDialog();
            }
        } else if (itemId == R.id.design_menu_xml_editor) {
            toViewCodeEditor();
        } else if (itemId == R.id.design_menu_import_xml) {
            if (viewTabAdapter != null) {
                viewTabAdapter.showImportXmlDialog();
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        showLoadingDialog();

        HashMap<String, Object> projectInfo = ProjectListManager.getProjectById(sc_id);
        projectFilePaths = new ProjectFilePaths(getApplicationContext(), SketchwarePaths.getMyscPath(sc_id), projectInfo);

        // Re-link the pager's page fragments after the first layout pass (this is when
        // ViewPager2 restores its saved state and (re)creates the page fragments).
        if (editorPager != null) {
            editorPager.post(this::syncEditorFragmentReferences);
        }

        try {
            ProjectLoader projectLoader = new ProjectLoader(this, savedInstanceState);
            projectLoader.execute();
        } catch (RuntimeException e) {
            if (crashlytics != null) {
                crashlytics.log("ProjectLoader failed");
                crashlytics.recordException(e);
            }
        } finally {
            SystemLogPrinter.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isStoragePermissionGranted()) {
            finish();
        }

        long freeMegabytes = DeviceUtil.getFreeStorageMB();
        if (freeMegabytes < 100L && freeMegabytes > 0L) {
            warnAboutInsufficientStorageSpace();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putString("sc_id", sc_id);
        outState.putInt(STATE_CURRENT_DESTINATION, currentDestination);
        outState.putInt(STATE_PREVIOUS_PRIMARY_DESTINATION, previousPrimaryDestination);
        super.onSaveInstanceState(outState);
        if (!isStoragePermissionGranted()) {
            finish();
        }

        if (!isRestoringData) {
            UnsavedChangesSaver unsavedChangesSaver = new UnsavedChangesSaver(this);
            unsavedChangesSaver.execute();
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.file_name_container) {
            if (currentDestination == DESTINATION_UI) {
                showAvailableViews();
            } else {
                showAvailableSourceFiles();
            }
        }
    }

    /**
     * Show a dialog asking about saving the project before quitting.
     */
    private void showSaveBeforeQuittingDialog() {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this);
        dialog.setTitle(Helper.getResString(R.string.design_quit_title_exit_projet));
        dialog.setIcon(R.drawable.ic_mtrl_exit);
        dialog.setMessage(Helper.getResString(R.string.design_quit_message_confirm_save));
        dialog.setPositiveButton(Helper.getResString(R.string.design_quit_button_save_and_exit), (v, which) -> {
            if (!UIHelper.isClickThrottled()) {
                v.dismiss();
                try {
                    saveChangesAndCloseProject();
                } catch (RuntimeException e) {
                    if (crashlytics != null) crashlytics.recordException(e);
                    dismissLoadingDialog();
                }
            }
        });
        dialog.setNegativeButton(Helper.getResString(R.string.common_word_exit), (v, which) -> {
            if (!UIHelper.isClickThrottled()) {
                v.dismiss();
                try {
                    showLoadingDialog();
                    DiscardChangesProjectCloser discardChangesProjectCloser = new DiscardChangesProjectCloser(this);
                    discardChangesProjectCloser.execute();
                } catch (RuntimeException e) {
                    if (crashlytics != null) crashlytics.recordException(e);
                    dismissLoadingDialog();
                }
            }
        });
        dialog.setNeutralButton(Helper.getResString(R.string.common_word_cancel), null);
        dialog.show();
    }

    /**
     * Show a dialog warning the user about low free space.
     */
    private void warnAboutInsufficientStorageSpace() {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this);
        dialog.setTitle(Helper.getResString(R.string.common_word_warning));
        dialog.setIcon(R.drawable.break_warning_96_red);
        dialog.setMessage(Helper.getResString(R.string.common_message_insufficient_storage_space));
        dialog.setPositiveButton(Helper.getResString(R.string.common_word_ok), null);
        dialog.show();
    }

    private void askIfToRestoreOldUnsavedProjectData() {
        isRestoringData = true;
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this);
        dialog.setIcon(R.drawable.ic_mtrl_history);
        dialog.setTitle(Helper.getResString(R.string.design_restore_data_title));
        dialog.setMessage(Helper.getResString(R.string.design_restore_data_message_confirm));
        dialog.setPositiveButton(Helper.getResString(R.string.common_word_restore), (v, which) -> {
            if (!UIHelper.isClickThrottled()) {
                boolean hasLibraryBackup = ProjectDataManager.getLibraryManager(sc_id).hasBackup();
                boolean hasFileBackup = ProjectDataManager.getFileManager(sc_id).hasBackup();
                boolean hasResourceBackup = ProjectDataManager.getResourceManager(sc_id).hasBackup();
                boolean hasViewBackup = ProjectDataManager.getProjectDataManager(sc_id).hasViewBackup();
                boolean hasLogicBackup = ProjectDataManager.getProjectDataManager(sc_id).hasLogicBackup();
                if (hasLibraryBackup) {
                    ProjectDataManager.getLibraryManager(sc_id).loadFromBackup();
                }
                if (hasFileBackup) {
                    ProjectDataManager.getFileManager(sc_id).loadFromBackup();
                }
                if (hasResourceBackup) {
                    ProjectDataManager.getResourceManager(sc_id).loadFromBackup();
                }
                if (hasViewBackup) {
                    ProjectDataManager.getProjectDataManager(sc_id).loadViewFromBackup();
                }
                if (hasLogicBackup) {
                    ProjectDataManager.getProjectDataManager(sc_id).loadLogicFromBackup();
                }
                if (hasLibraryBackup) {
                    ProjectDataManager.getFileManager(sc_id).syncWithLibrary(ProjectDataManager.getLibraryManager(sc_id));
                    ProjectDataManager.getProjectDataManager(sc_id).removeAdmobComponents(ProjectDataManager.getLibraryManager(sc_id).getFirebaseDB());
                    ProjectDataManager.getProjectDataManager(sc_id).removeFirebaseViews(ProjectDataManager.getLibraryManager(sc_id).getAdmob(), ProjectDataManager.getFileManager(sc_id));
                    ProjectDataManager.getProjectDataManager(sc_id).removeMapViews(ProjectDataManager.getLibraryManager(sc_id).getGoogleMap(), ProjectDataManager.getFileManager(sc_id));
                }
                if (hasFileBackup || hasLibraryBackup) {
                    ProjectDataManager.getProjectDataManager(sc_id).syncWithFileManager(ProjectDataManager.getFileManager(sc_id));
                }
                if (hasResourceBackup) {
                    ProjectDataManager.getProjectDataManager(sc_id).syncSounds(ProjectDataManager.getResourceManager(sc_id));
                    ProjectDataManager.getProjectDataManager(sc_id).syncFonts(ProjectDataManager.getResourceManager(sc_id));
                }
                refresh();
                isRestoringData = false;
                v.dismiss();
            }
        });
        dialog.setNegativeButton(Helper.getResString(R.string.common_word_no), (v, which) -> {
            isRestoringData = false;
            v.dismiss();
        });
        dialog.setCancelable(false);
        dialog.show();
    }

    private void showCurrentActivitySrcCode() {
        if (projectFile == null) return;
        showLoadingDialog();
        String filename = Helper.getText(fileName);
        BackgroundTasks.callIoIfAlive(TaskHost.of(this), "DesignActivity", () ->
                new ProjectFilePaths(getApplicationContext(), sc_id).getFileSrc(
                        filename,
                        ProjectDataManager.getFileManager(sc_id),
                        ProjectDataManager.getProjectDataManager(sc_id),
                        ProjectDataManager.getLibraryManager(sc_id)), code -> {
            dismissLoadingDialog();
            if (code.isEmpty()) {
                SketchwareUtil.toast(Helper.getResString(R.string.design_error_generate_source));
                return;
            }
            var scheme = filename.endsWith(".xml") ? CodeViewerActivity.SCHEME_XML : CodeViewerActivity.SCHEME_JAVA;
            launchActivity(CodeViewerActivity.class, null, new Pair<>("code", code), new Pair<>("sc_id", sc_id), new Pair<>("scheme", scheme));
        }, error -> {
            Log.e("DesignActivity", "Failed to generate source code", error);
            dismissLoadingDialog();
            SketchwareUtil.toast(Helper.getResString(R.string.design_error_generate_source));
        });
    }

    /**
     * Source selection uses the same activity manager as the visual editor, including its create
     * and edit flows. Source mode intentionally hides custom XML views but still returns a newly
     * created Activity immediately so its generated source opens in the editor.
     */
    private void showAvailableSourceFiles() {
        Intent intent = new Intent(getApplicationContext(), ViewSelectorActivity.class);
        intent.putExtra("sc_id", sc_id);
        intent.putExtra(ViewSelectorActivity.EXTRA_SOURCE_MODE, true);
        intent.putExtra(ViewSelectorActivity.EXTRA_CURRENT_SOURCE,
                projectFile == null ? "" : projectFile.getSourceFileName());
        changeOpenFile.launch(intent);
    }

    private void showAvailableViews() {
        Intent intent = new Intent(getApplicationContext(), ViewSelectorActivity.class);
        intent.putExtra("sc_id", sc_id);
        intent.putExtra("current_xml", projectFile.getXmlName());
        intent.putExtra("is_custom_view", projectFile.fileType == 1 || projectFile.fileType == 2);
        changeOpenFile.launch(intent);
    }

    /**
     * Opens {@link ViewCodeEditorActivity}.
     */
    void toViewCodeEditor() {
        if (projectFile == null) return;
        showLoadingDialog();
        String filename = Helper.getText(fileName);
        ProjectFileBean currentProjectFile = projectFile;
        BackgroundTasks.callIoIfAlive(TaskHost.of(this), "DesignActivity", () -> {
            var xmlGenerator = new LayoutGenerator(projectFilePaths.buildConfig, currentProjectFile);
            var projectDataManager = ProjectDataManager.getProjectDataManager(sc_id);
            var viewBeans = projectDataManager.getViews(filename);
            var viewFab = projectDataManager.getFabView(filename);
            xmlGenerator.setExcludeAppcompat(true);
            xmlGenerator.setViews(ProjectDataStore.getSortedRootViews(viewBeans), viewFab);
            return xmlGenerator.toXmlString();
        }, content -> {
            dismissLoadingDialog();
            launchActivity(ViewCodeEditorActivity.class, openViewCodeEditor, new Pair<>("title", filename), new Pair<>("content", content));
        }, error -> {
            Log.e("DesignActivity", "Failed to generate view code", error);
            dismissLoadingDialog();
            SketchwareUtil.toast(Helper.getResString(R.string.design_error_generate_code));
        });
    }

    /**
     * Opens {@link LogReaderActivity}.
     */
    void toLogReader() {
        Intent intent = new Intent(getApplicationContext(), LogReaderActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("sc_id", sc_id);
        startActivity(intent);
    }

    /**
     * Opens {@link ManageCollectionActivity}.
     */
    void toCollectionManager() {
        launchActivity(ManageCollectionActivity.class, openCollectionManager);
    }

    /**
     * Opens {@link AndroidManifestInjection}.
     */
    void toAndroidManifestManager() {
        if (projectFile == null) return;
        launchActivity(AndroidManifestInjection.class, null, new Pair<>("file_name", currentJavaFileName));
    }

    /**
     * Opens {@link ManageAppCompatActivity}.
     */
    void toAppCompatInjectionManager() {
        if (projectFile == null) return;
        launchActivity(ManageAppCompatActivity.class, null, new Pair<>("file_name", projectFile.getXmlName()));
    }

    /**
     * Opens {@link ManageAssetsActivity}.
     */
    void toAssetManager() {
        launchActivity(ManageAssetsActivity.class, null);
    }

    /**
     * Shows a {@link CustomBlocksDialog}.
     */
    void toCustomBlocksViewer() {
        new CustomBlocksDialog().show(this, sc_id);
    }

    /**
     * Opens {@link ManageJavaActivity}.
     */
    void toJavaManager() {
        launchActivity(ManageJavaActivity.class, null, new Pair<>("pkgName", projectFilePaths.packageName));
    }

    /**
     * Opens {@link ManagePermissionActivity}.
     */
    void toPermissionManager() {
        launchActivity(ManagePermissionActivity.class, null);
    }

    /**
     * Opens {@link ManageProguardActivity}.
     */
    void toProguardManager() {
        launchActivity(ManageProguardActivity.class, null);
    }

    /**
     * Opens {@link ManageResourceActivity}.
     */
    void toResourceManager() {
        launchActivity(ManageResourceActivity.class, openResourcesManager);
    }

    /**
     * Opens {@link ResourcesEditorActivity}.
     */
    void toResourceEditor() {
        launchActivity(ResourcesEditorActivity.class, openResourcesManager);
    }

    /**
     * Opens {@link ManageStringFogFragment}.
     */
    void toStringFogManager() {
        var fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag("stringFogFragment") == null) {
            var bottomSheet = new ManageStringFogFragment();
            bottomSheet.show(fragmentManager, "stringFogFragment");
        }
    }

    /**
     * Opens {@link ManageFontActivity}.
     */
    void toFontManager() {
        launchActivity(ManageFontActivity.class, null);
    }

    /**
     * Opens {@link ManageImageActivity}.
     */
    void toImageManager() {
        launchActivity(ManageImageActivity.class, openImageManager);
    }

    /**
     * Opens {@link ManageLibraryActivity}.
     */
    void toLibraryManager() {
        launchActivity(ManageLibraryActivity.class, openLibraryManager);
    }

    /**
     * Opens {@link ManageViewActivity}.
     */
    void toViewManager() {
        launchActivity(ManageViewActivity.class, openViewManager);
    }

    /**
     * Opens {@link ManageSoundActivity}.
     */
    void toSoundManager() {
        launchActivity(ManageSoundActivity.class, null);
    }

    /**
     * Opens {@link SrcViewerActivity}.
     */
    void toSourceCodeViewer() {
        launchActivity(SrcViewerActivity.class, null, new Pair<>("current", Helper.getText(fileName)));
    }

    /**
     * Opens {@link ManageXMLCommandActivity}.
     */
    void toXMLCommandManager() {
        launchActivity(ManageXMLCommandActivity.class, null);
    }

    @SafeVarargs
    private void launchActivity(Class<? extends Activity> toLaunch, ActivityResultLauncher<Intent> optionalLauncher, Pair<String, String>... extras) {
        Intent intent = new Intent(getApplicationContext(), toLaunch);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("sc_id", sc_id);
        for (Pair<String, String> extra : extras) {
            intent.putExtra(extra.first, extra.second);
        }

        if (optionalLauncher == null) {
            startActivity(intent);
        } else {
            optionalLauncher.launch(intent);
        }
    }

    /**
     * The Java/Compose + UI workspace pages. Both pages are kept alive (offscreen page
     * limit 1) so switching between them preserves their editor state; the pager enforces
     * RESUMED for the visible page and STARTED for the other.
     */
    private class EditorPagerAdapter extends FragmentStateAdapter {
        EditorPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == DESTINATION_UI) {
                viewTabAdapter = new ViewEditorFragment();
                hookViewEditor(viewTabAdapter);
                return viewTabAdapter;
            }
            javaTabAdapter = new JavaEditorFragment();
            return javaTabAdapter;
        }

        @Override
        public int getItemCount() {
            return currentPagerPageCount;
        }
    }

    private abstract static class BaseTask {
        protected final WeakReference<DesignActivity> activityRef;

        protected BaseTask(DesignActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        protected DesignActivity getActivity() {
            return activityRef.get();
        }

        /**
         * Persists all project data (views, logic, files, resources, libraries) in parallel.
         *
         * @return {@code true} if data was saved successfully
         */
        protected static boolean saveProjectDataToFiles(String sc_id) {
            ProjectDataManager.getResourceManager(sc_id).cleanupAllResources();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CompletableFuture<Boolean> fileFuture = CompletableFuture.supplyAsync(
                () -> ProjectDataManager.getFileManager(sc_id).saveToData(), pool);
            CompletableFuture<Boolean> dataFuture = CompletableFuture.supplyAsync(
                () -> ProjectDataManager.getProjectDataManager(sc_id).saveAllData(), pool);
            CompletableFuture<Boolean> resourceFuture = CompletableFuture.supplyAsync(
                () -> ProjectDataManager.getResourceManager(sc_id).saveToData(), pool);
            CompletableFuture<Boolean> libraryFuture = CompletableFuture.supplyAsync(
                () -> ProjectDataManager.getLibraryManager(sc_id).saveToData(), pool);
            CompletableFuture.allOf(fileFuture, dataFuture, resourceFuture, libraryFuture).join();
            pool.shutdown();
            return fileFuture.join() && dataFuture.join() && resourceFuture.join() && libraryFuture.join();
        }
    }

    private static class BuildTask extends BaseTask implements BuildProgressReceiver {
        public static final String ACTION_CANCEL_BUILD = "pro.sketchware.activities.design.ACTION_CANCEL_BUILD";
        private static final String CHANNEL_ID = "build_notification_channel";
        private final ExecutorService executorService = BackgroundTasks.createSingleThreadExecutor("DesignBuild");
        private final NotificationManager notificationManager;
        private final int notificationId = 1;
        private final LinearLayout progressContainer;
        private final TextView progressText;
        private final TextView stepInfoText;
        private final LinearProgressIndicator progressBar;
        public volatile boolean canceled;
        private volatile boolean isBuildFinished;
        private boolean isShowingNotification = false;
        private long buildStartTime;

        public BuildTask(DesignActivity activity) {
            super(activity);
            notificationManager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            progressContainer = activity.findViewById(R.id.progress_container);
            progressText = activity.findViewById(R.id.progress_text);
            stepInfoText = activity.findViewById(R.id.progress_step_info);
            progressBar = activity.findViewById(R.id.progress);
        }

        public void execute() {
            onPreExecute();
            executorService.execute(this::doInBackground);
        }

        private void onPreExecute() {
            DesignActivity activity = getActivity();
            if (activity == null) return;

            postToUi(activity, () -> {
                buildStartTime = System.currentTimeMillis();
                activity.updateRunToolbarState(true);
                activity.prefP1.put("P1I10", true);
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                maybeShowNotification();
            });
        }

        private void doInBackground() {
            DesignActivity activity = getActivity();

            try {
                if (activity == null) return;
                var q = activity.projectFilePaths;
                var sc_id = DesignActivity.sc_id;
                onProgress("Deleting temporary files...", 1);
                FileUtil.deleteFile(q.generatedFilesPath);

                q.createBuildDirectories(activity.getApplicationContext());
                q.deleteValuesV21Directory();
                q.extractAssetsToRes(activity.getApplicationContext(), SketchwarePaths.getResourceZipPath("600"));
                if (MapValueHelper.get(ProjectListManager.getProjectById(sc_id), "custom_icon")) {
                    q.copyMipmapFolder(SketchwarePaths.getIconsPath() + File.separator + sc_id + File.separator + "mipmaps");
                    if (MapValueHelper.get(ProjectListManager.getProjectById(sc_id), "isIconAdaptive", false)) {
                        q.createLauncherIconXml("""
                                <?xml version="1.0" encoding="utf-8"?>
                                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android" >
                                <background android:drawable="@mipmap/ic_launcher_background"/>
                                <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
                                <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
                                </adaptive-icon>""");
                    } else {
                        q.copyAppIcon(SketchwarePaths.getIconsPath() + File.separator + sc_id + File.separator + "icon.png");
                    }
                }

                onProgress("Generating source code...", 2);
                long generateSourceStepStarted = System.currentTimeMillis();
                long copyImagesStarted = System.currentTimeMillis();
                ResourceManager resourceManager = ProjectDataManager.getResourceManager(sc_id);
                resourceManager.copyImagesToDir(q.resDirectoryPath + File.separator + "drawable-xhdpi");
                long copyImagesDuration = System.currentTimeMillis() - copyImagesStarted;
                long copySoundsStarted = System.currentTimeMillis();
                resourceManager = ProjectDataManager.getResourceManager(sc_id);
                resourceManager.copySoundsToDir(q.resDirectoryPath + File.separator + "raw");
                long copySoundsDuration = System.currentTimeMillis() - copySoundsStarted;
                long copyFontsStarted = System.currentTimeMillis();
                resourceManager = ProjectDataManager.getResourceManager(sc_id);
                resourceManager.copyFontsToDir(q.assetsPath + File.separator + "fonts");
                long copyFontsDuration = System.currentTimeMillis() - copyFontsStarted;
                Log.d("DesignActivity$BuildTask", "Step 2 timing: copied resources (images=" + copyImagesDuration
                        + " ms, sounds=" + copySoundsDuration
                        + " ms, fonts=" + copyFontsDuration + " ms)");
                long builderInitializationStarted = System.currentTimeMillis();
                ProjectBuilder builder = new ProjectBuilder(this, activity.getApplicationContext(), q);
                long builderInitializationDuration = System.currentTimeMillis() - builderInitializationStarted;
                Log.d("DesignActivity$BuildTask", "Step 2 timing: ProjectBuilder initialization took "
                        + builderInitializationDuration + " ms");

                var fileManager = ProjectDataManager.getFileManager(sc_id);
                var dataManager = ProjectDataManager.getProjectDataManager(sc_id);
                var libraryManager = ProjectDataManager.getLibraryManager(sc_id);
                long metadataInitializationStarted = System.currentTimeMillis();
                q.initializeMetadata(libraryManager, fileManager, dataManager);
                long metadataInitializationDuration = System.currentTimeMillis() - metadataInitializationStarted;
                Log.d("DesignActivity$BuildTask", "Step 2 timing: initializeMetadata took "
                        + metadataInitializationDuration + " ms");
                long builtInLibraryInformationStarted = System.currentTimeMillis();
                builder.buildBuiltInLibraryInformation();
                long builtInLibraryInformationDuration = System.currentTimeMillis() - builtInLibraryInformationStarted;
                Log.d("DesignActivity$BuildTask", "Step 2 timing: buildBuiltInLibraryInformation took "
                        + builtInLibraryInformationDuration + " ms, builtInLibraryCount="
                        + builder.getBuiltInLibraryManager().getLibraries().size());
                long generateProjectFilesStarted = System.currentTimeMillis();
                q.generateProjectFiles(fileManager, dataManager, libraryManager, builder.getBuiltInLibraryManager());
                long generateProjectFilesDuration = System.currentTimeMillis() - generateProjectFilesStarted;
                Log.d("DesignActivity$BuildTask", "Step 2 timing: generateProjectFiles took "
                        + generateProjectFilesDuration + " ms");
                long incrementalPrecheckStarted = System.currentTimeMillis();
                pro.sketchware.core.build.IncrementalBuildCache buildCache =
                        new pro.sketchware.core.build.IncrementalBuildCache(q.binDirectoryPath);
                buildCache.load();
                String buildClasspath = builder.getClasspath();
                boolean compiledClassesAvailable = new File(q.compiledClassesPath).exists()
                        && !FileUtil.listFilesRecursively(new File(q.compiledClassesPath), ".class").isEmpty();
                boolean cacheFileExists = buildCache.hasCacheFile();
                boolean proguardShrinkingEnabled = builder.proguard.isShrinkingEnabled();
                boolean classpathChanged = buildCache.isClasspathChanged(buildClasspath);
                boolean cacheMigrationRequired = buildCache.requiresFullRebuildMigration();
                boolean incrementalMode = compiledClassesAvailable
                        && cacheFileExists
                        && !proguardShrinkingEnabled
                        && !classpathChanged
                        && !cacheMigrationRequired;
                Log.d("DesignActivity$BuildTask", "Incremental build precheck: mode=" + incrementalMode
                        + ", compiledClassesAvailable=" + compiledClassesAvailable
                        + ", cacheFileExists=" + cacheFileExists
                        + ", proguardShrinkingEnabled=" + proguardShrinkingEnabled
                        + ", classpathChanged=" + classpathChanged
                        + ", cacheMigrationRequired=" + cacheMigrationRequired
                        + ", classpathHash=" + Integer.toHexString(buildClasspath.hashCode())
                        + ", classpathLength=" + buildClasspath.length());
                Log.d("DesignActivity$BuildTask", "Step 2 timing: build cache load + classpath + incremental precheck took "
                        + (System.currentTimeMillis() - incrementalPrecheckStarted) + " ms");
                builder.preloadedBuildCache = buildCache;
                long prepareBuildDirectoriesStarted = System.currentTimeMillis();
                if (incrementalMode) {
                    Log.d("DesignActivity$BuildTask", "Build cache strategy: incremental mode, cleaning only R.java directory");
                    q.cleanRJavaOnly();
                } else {
                    Log.d("DesignActivity$BuildTask", "Build cache strategy: full rebuild, cleaning bin and R.java directories");
                    q.cleanBuildCache();
                }
                q.prepareBuildDirectories();
                Log.d("DesignActivity$BuildTask", "Step 2 timing: cache cleanup + prepareBuildDirectories took "
                        + (System.currentTimeMillis() - prepareBuildDirectoriesStarted) + " ms");
                Log.d("DesignActivity$BuildTask", "Step 2 total timing: "
                        + (System.currentTimeMillis() - generateSourceStepStarted) + " ms");
                builder.maybeExtractAapt2();
                if (canceled) {
                    return;
                }

                BuiltInLibraries.extractCompileAssets(this);
                if (canceled) {
                    return;
                }

                onProgress("AAPT2 is running...", 8);
                builder.compileResources();
                if (canceled) {
                    return;
                }

                onProgress("Generating view binding...", 11);
                builder.generateViewBinding();
                if (canceled) {
                    return;
                }

                KotlinCompilerBridge.compileKotlinCodeIfPossible(this, builder);
                if (canceled) {
                    return;
                }

                onProgress("Java is compiling...", 13);
                builder.compileJavaCode();
                if (canceled) {
                    return;
                }

                StringfogHandler stringfogHandler = new StringfogHandler(sc_id);
                stringfogHandler.start(this, builder);
                if (canceled) {
                    return;
                }

                ProguardHandler proguardHandler = new ProguardHandler(sc_id);
                proguardHandler.start(this, builder);
                if (canceled) {
                    return;
                }

                onProgress(builder.getDxRunningText(), 17);
                builder.createDexFilesFromClasses();
                if (canceled) {
                    return;
                }

                onProgress("Merging DEX files...", 18);
                builder.getDexFilesReady();
                if (canceled) {
                    return;
                }

                onProgress("Building APK...", 19);
                builder.buildApk();
                if (canceled) {
                    return;
                }

                onProgress("Signing APK...", 20);
                builder.signDebugApk();
                if (canceled) {
                    return;
                }

                postToUi(activity, activity::installBuiltApk);
            } catch (MissingFileException e) {
                postToUi(activity, () -> {
                    boolean isMissingDirectory = e.isMissingDirectory();

                    MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity);
                    if (isMissingDirectory) {
                        dialog.setTitle(R.string.build_error_missing_directory_title);
                        dialog.setMessage(String.format(Helper.getResString(R.string.build_error_missing_directory_msg), e.getMissingFile().getAbsolutePath()));
                        dialog.setNeutralButton(R.string.common_word_create, (v, which) -> {
                            v.dismiss();
                            if (!e.getMissingFile().mkdirs()) {
                                SketchwareUtil.toastError(Helper.getResString(R.string.build_error_failed_create_directory));
                            }
                        });
                    } else {
                        dialog.setTitle(R.string.build_error_missing_file_title);
                        dialog.setMessage(String.format(Helper.getResString(R.string.build_error_missing_file_msg), e.getMissingFile().getAbsolutePath()));
                    }
                    dialog.setPositiveButton(R.string.common_word_dismiss, null);
                    dialog.show();
                });
            } catch (SimpleException simpleException) {
                postToUi(activity, () -> activity.indicateCompileErrorOccurred(simpleException.getMessage()));
            } catch (Throwable tr) {
                LogUtil.e("DesignActivity$BuildTask", "Failed to build project", tr);
                postToUi(activity, () -> activity.indicateCompileErrorOccurred(Log.getStackTraceString(tr)));
            } finally {
                onPostExecute(activity);
            }
        }

        @Override
        public void onProgress(String progress, int step) {
            int totalSteps = 20;

            DesignActivity activity = getActivity();
            if (activity == null) return;

            postToUi(activity, () -> {
                progressBar.setIndeterminate(step == -1);
                if (!canceled) {
                    updateNotification(progress + " (" + step + " / " + totalSteps + ")");
                }
                progressText.setText(progress);
                var progressInt = (step * 100) / totalSteps;
                progressBar.setProgress(progressInt, true);

                long elapsed = (System.currentTimeMillis() - buildStartTime) / 1000;
                String elapsedStr = String.format("%d:%02d", elapsed / 60, elapsed % 60);
                if (step >= 1) {
                    stepInfoText.setText(step + "/" + totalSteps + " · " + elapsedStr);
                } else {
                    stepInfoText.setText(elapsedStr);
                }

                Log.d("DesignActivity$BuildTask", step + " / " + totalSteps);
            });
        }

        private void onPostExecute(DesignActivity activity) {
            isBuildFinished = true;
            executorService.shutdown();
            if (isShowingNotification) {
                notificationManager.cancel(notificationId);
                isShowingNotification = false;
            }
            if (activity == null) return;

            postToUi(activity, () -> {
                if (activity.currentBuildTask == this) {
                    activity.currentBuildTask = null;
                }
                activity.updateRunToolbarState(false);
                activity.updateBottomMenu();
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        public void cancelBuild() {
            canceled = true;
            onProgress("Canceling build...", -1);
            if (isShowingNotification) {
                notificationManager.cancel(notificationId);
                isShowingNotification = false;
            }
            DesignActivity activity = getActivity();
            if (activity != null) {
                postToUi(activity, () -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
        }

        private boolean hasNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                DesignActivity activity = getActivity();
                return activity != null
                        && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        private void maybeShowNotification() {
            DesignActivity activity = getActivity();
            if (activity == null) return;
            if (!hasNotificationPermission()) return;

            if (!isShowingNotification) {
                createNotificationChannelIfNeeded();

                NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_mtrl_code)
                        .setContentTitle(Helper.getResString(R.string.notification_building_project))
                        .setContentText(Helper.getResString(R.string.notification_starting_build))
                        .setOngoing(true)
                        .setProgress(0, 0, true)
                        .addAction(R.drawable.ic_cancel_white_96dp, Helper.getResString(R.string.notification_cancel_build), getCancelPendingIntent());

                notificationManager.notify(notificationId, builder.build());
                isShowingNotification = true;
            }
        }

        private void updateNotification(String progress) {
            DesignActivity activity = getActivity();
            if (activity == null) return;
            if (!hasNotificationPermission()) return;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_mtrl_code)
                    .setContentTitle(Helper.getResString(R.string.notification_building_project))
                    .setContentText(progress)
                    .setOngoing(true)
                    .setProgress(0, 0, true)
                    .addAction(R.drawable.ic_cancel_white_96dp, Helper.getResString(R.string.notification_cancel_build), getCancelPendingIntent());

            notificationManager.notify(notificationId, builder.build());
        }

        private PendingIntent getCancelPendingIntent() {
            DesignActivity activity = getActivity();
            if (activity == null) return null;

            Intent cancelIntent = new Intent(BuildTask.ACTION_CANCEL_BUILD);
            return PendingIntent.getBroadcast(activity, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        private void createNotificationChannelIfNeeded() {
            DesignActivity activity = getActivity();
            if (activity == null) return;

            CharSequence name = Helper.getResString(R.string.notification_channel_build);
            String description = Helper.getResString(R.string.notification_channel_build_description);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            notificationManager.createNotificationChannel(channel);
        }

        private void postToUi(DesignActivity activity, Runnable action) {
            if (activity == null || action == null) {
                return;
            }
            TaskHost.of(activity).postToUi(action);
        }
    }

    private static class ProjectLoader extends BaseTask {
        private final Bundle savedInstanceState;

        public ProjectLoader(DesignActivity activity, Bundle savedInstanceState) {
            super(activity);
            this.savedInstanceState = savedInstanceState;
        }

        public void execute() {
            DesignActivity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.showLoadingDialog();
            BackgroundTasks.runIoIfAlive(TaskHost.of(activity), "DesignActivity$ProjectLoader", this::doInBackground, () -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity == null) {
                    return;
                }
                currentActivity.updateBottomMenu();
                currentActivity.refresh();
                currentActivity.dismissLoadingDialog();
                if (savedInstanceState == null) {
                    currentActivity.checkForUnsavedProjectData();
                }
            }, error -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity != null) {
                    currentActivity.dismissLoadingDialog();
                }
            });
        }

        private void doInBackground() {
            DesignActivity activity = getActivity();
            if (activity != null) {
                activity.loadProject(savedInstanceState != null);
            }
        }
    }

    private static class DiscardChangesProjectCloser extends BaseTask {

        public DiscardChangesProjectCloser(DesignActivity activity) {
            super(activity);
        }

        public void execute() {
            DesignActivity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.showLoadingDialog();
            BackgroundTasks.runIo(TaskHost.of(activity), "DesignActivity$DiscardChangesProjectCloser", this::doInBackground, () -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity != null) {
                    currentActivity.dismissLoadingDialog();
                    currentActivity.finish();
                }
            }, error -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity != null) {
                    currentActivity.dismissLoadingDialog();
                    currentActivity.finish();
                }
            });
        }

        private void doInBackground() {
            DesignActivity activity = getActivity();
            if (activity != null) {
                try {
                    var sc_id = DesignActivity.sc_id;
                    ResourceManager rm = ProjectDataManager.getResourceManager(sc_id);
                    if (rm.hasLazyBackup()) {
                        rm.restoreImagesFromTemp();
                        rm.restoreSoundsFromTemp();
                        rm.restoreFontsFromTemp();
                    }
                    ProjectDataManager.discardAll();
                } catch (RuntimeException e) {
                    if (activity.crashlytics != null) {
                        activity.crashlytics.log("DiscardChangesProjectCloser cleanup failed");
                        activity.crashlytics.recordException(e);
                    }
                }
            }
        }
    }

    private static class ProjectSaver extends BaseTask {

        public ProjectSaver(DesignActivity activity) {
            super(activity);
        }

        public void execute() {
            DesignActivity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.showLoadingDialog();
            BackgroundTasks.callIo(TaskHost.of(activity), "DesignActivity$ProjectSaver", this::doInBackground, dataSaved -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity == null) {
                    return;
                }
                if (dataSaved) {
                    SketchToast.toast(currentActivity.getApplicationContext(), Helper.getResString(R.string.common_message_complete_save), SketchToast.TOAST_NORMAL).show();
                    currentActivity.saveVersionCodeInformationToProject();
                } else {
                    SketchToast.toast(currentActivity.getApplicationContext(), Helper.getResString(R.string.common_message_save_failed), SketchToast.TOAST_WARNING).show();
                }
                currentActivity.dismissLoadingDialog();
            }, error -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity != null) {
                    SketchToast.toast(currentActivity.getApplicationContext(), Helper.getResString(R.string.common_message_save_failed), SketchToast.TOAST_WARNING).show();
                    currentActivity.dismissLoadingDialog();
                }
            });
        }

        private boolean doInBackground() {
            var currentScId = DesignActivity.sc_id;
            boolean dataSaved = saveProjectDataToFiles(currentScId);
            if (dataSaved) {
                ProjectDataManager.getResourceManager(currentScId).deleteTempDirs();
            }
            return dataSaved;
        }
    }

    private static class SaveChangesProjectCloser extends BaseTask {

        public SaveChangesProjectCloser(DesignActivity activity) {
            super(activity);
        }

        public void execute() {
            DesignActivity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.showLoadingDialog();
            BackgroundTasks.callIo(TaskHost.of(activity), "DesignActivity$SaveChangesProjectCloser", this::doInBackground, dataSaved -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity == null) {
                    return;
                }
                if (dataSaved) {
                    SketchToast.toast(currentActivity.getApplicationContext(), Helper.getResString(R.string.common_message_complete_save), SketchToast.TOAST_NORMAL).show();
                    currentActivity.saveVersionCodeInformationToProject();
                    currentActivity.dismissLoadingDialog();
                    currentActivity.finish();
                } else {
                    SketchToast.toast(currentActivity.getApplicationContext(), Helper.getResString(R.string.common_message_save_failed), SketchToast.TOAST_WARNING).show();
                    currentActivity.dismissLoadingDialog();
                }
            }, error -> {
                DesignActivity currentActivity = getActivity();
                if (currentActivity != null) {
                    SketchToast.toast(currentActivity.getApplicationContext(), Helper.getResString(R.string.common_message_save_failed), SketchToast.TOAST_WARNING).show();
                    currentActivity.dismissLoadingDialog();
                }
            });
        }

        private boolean doInBackground() {
            var currentScId = DesignActivity.sc_id;
            boolean dataSaved = saveProjectDataToFiles(currentScId);
            if (dataSaved) {
                ProjectDataManager.getResourceManager(currentScId).deleteTempDirs();
            }
            return dataSaved;
        }
    }

    private static class UnsavedChangesSaver extends BaseTask {

        public UnsavedChangesSaver(DesignActivity activity) {
            super(activity);
        }

        public void execute() {
            DesignActivity activity = getActivity();
            if (activity == null) {
                return;
            }
            BackgroundTasks.runIo(TaskHost.of(activity), "DesignActivity$UnsavedChangesSaver", this::doInBackground, null, null);
        }

        private void doInBackground() {
            DesignActivity activity = getActivity();
            if (activity != null) {
                var currentScId = DesignActivity.sc_id;
                ProjectDataStore ecInstance = ProjectDataManager.getProjectDataManager(currentScId);
                synchronized (ecInstance) {
                    ecInstance.saveAllBackup();
                }
            }
        }
    }

    private static FirebaseCrashlytics getFirebaseCrashlytics() {
        try {
            return FirebaseCrashlytics.getInstance();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
