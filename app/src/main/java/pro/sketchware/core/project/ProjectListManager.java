package pro.sketchware.core.project;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

import pro.sketchware.util.LogUtil;
import pro.sketchware.util.io.EncryptedFileUtil;
import pro.sketchware.util.GsonMapHelper;
import pro.sketchware.util.MapValueHelper;
import pro.sketchware.util.io.SharedPrefsHelper;

public class ProjectListManager {
    public static SharedPrefsHelper sharedPrefsHelper;

    /** Re-entrancy guard: project data managers read this class while the migration runs. */
    private static final ThreadLocal<Boolean> ensuringProjectType = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static String decryptProjectJson(EncryptedFileUtil fileUtil, String projectMetadataPath) throws IOException {
        byte[] fileBytes = fileUtil.readFileBytes(projectMetadataPath);
        if (fileBytes == null && new File(projectMetadataPath).length() > 0L) {
            throw new IOException("Failed to read project metadata file: " + projectMetadataPath);
        }
        return fileUtil.decryptToString(fileBytes);
    }

    private static byte[] encryptProjectJson(EncryptedFileUtil fileUtil, String jsonData, String projectMetadataPath) throws IOException {
        try {
            return fileUtil.encryptString(jsonData);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to encrypt project metadata file: " + projectMetadataPath, e);
        }
    }

    public static ArrayList<HashMap<String, Object>> listProjects() {
        String projectFileName = "project";
        ArrayList<HashMap<String, Object>> projects = new ArrayList<>();
        EncryptedFileUtil fileUtil = new EncryptedFileUtil();
        File[] listFiles = new File(SketchwarePaths.getProjectListBasePath()).listFiles();
        if (listFiles == null) {
            return projects;
        }
        for (File projectDirectory : listFiles) {
            try {
                if (new File(projectDirectory, projectFileName).exists()) {
                    String projectMetadataPath = projectDirectory.getAbsolutePath() + File.separator + projectFileName;
                    HashMap<String, Object> parsedProject = GsonMapHelper.fromJson(decryptProjectJson(fileUtil, projectMetadataPath));
                    if (MapValueHelper.getString(parsedProject, "sc_id").equals(projectDirectory.getName())) {
                        ensureProjectType(parsedProject, projectDirectory.getName(), projectMetadataPath, fileUtil);
                        projects.add(parsedProject);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LogUtil.e("ProjectListManager", "Failed to load project metadata from " + projectDirectory.getAbsolutePath(), e);
            }
        }
        return projects;
    }

    public static HashMap<String, Object> getProjectByPackageName(String packageName) {
        for (HashMap<String, Object> project : listProjects()) {
            if (MapValueHelper.getString(project, "my_sc_pkg_name").equals(packageName) && MapValueHelper.getInt(project, "proj_type") == 1) {
                return project;
            }
        }
        return null;
    }

    public static void deleteProject(Context context, String projectId) {
        File projectDirectory = new File(SketchwarePaths.getProjectListPath(projectId));
        if (projectDirectory.exists()) {
            EncryptedFileUtil fileUtil = new EncryptedFileUtil();
            fileUtil.deleteDirectory(projectDirectory);
            fileUtil.deleteDirectoryByPath(SketchwarePaths.getMyscPath(projectId));
            fileUtil.deleteDirectoryByPath(SketchwarePaths.getImagesPath() + File.separator + projectId);
            fileUtil.deleteDirectoryByPath(SketchwarePaths.getSoundsPath() + File.separator + projectId);
            fileUtil.deleteDirectoryByPath(SketchwarePaths.getFontsResourcePath() + File.separator + projectId);
            fileUtil.deleteDirectoryByPath(SketchwarePaths.getIconsPath() + File.separator + projectId);
            fileUtil.deleteDirectoryByPath(SketchwarePaths.getDataPath(projectId));
            fileUtil.deleteDirectoryByPath(SketchwarePaths.getBackupPath(projectId));
            new SharedPrefsHelper(context, "D01_" + projectId).clearAll();
            new SharedPrefsHelper(context, "D02_" + projectId).clearAll();
            new SharedPrefsHelper(context, "D03_" + projectId).clearAll();
            new SharedPrefsHelper(context, "D04_" + projectId).clearAll();
        }
    }

    public static void initializeDb(Context context, boolean unused) {
        if (sharedPrefsHelper == null) {
            sharedPrefsHelper = new SharedPrefsHelper(context, "P15");
        }
    }

    public static void saveProject(String projectId, HashMap<String, Object> projectData) {
        File projectListBaseDir = new File(SketchwarePaths.getProjectListBasePath());
        if (!projectListBaseDir.exists()) {
            projectListBaseDir.mkdirs();
        }
        String projectMetadataPath = SketchwarePaths.getProjectListPath(projectId) + File.separator + "project";
        String jsonData = GsonMapHelper.toJson(projectData);
        EncryptedFileUtil fileUtil = new EncryptedFileUtil();
        try {
            boolean saved = fileUtil.writeBytes(projectMetadataPath, encryptProjectJson(fileUtil, jsonData, projectMetadataPath));
            if (!saved) {
                LogUtil.e("ProjectListManager", "Failed to write project metadata for " + projectId + " to " + projectMetadataPath);
            }
        } catch (IOException | RuntimeException e) {
            LogUtil.e("ProjectListManager", "Failed to save project metadata for " + projectId + " to " + projectMetadataPath, e);
        }
    }

    /**
     * Ensures the project list metadata carries the {@link ProjectType#METADATA_KEY} tag.
     *
     * <p>Old projects were created before the Java/XML vs Compose split. They default to
     * {@link ProjectType#JAVA_XML} and receive an explicit tag so the rest of the app can rely on a
     * constant metadata shape. A legacy project that already enabled the Compose library is kept as
     * a Compose project instead of being silently downgraded.</p>
     */
    private static void ensureProjectType(HashMap<String, Object> project, String projectId,
                                          String projectMetadataPath, EncryptedFileUtil fileUtil) {
        if (Boolean.TRUE.equals(ensuringProjectType.get())) {
            return;
        }
        ensuringProjectType.set(Boolean.TRUE);
        try {
            Object stored = project.get(ProjectType.METADATA_KEY);
            if (stored instanceof String) {
                String normalized = ProjectType.normalize((String) stored);
                if (!normalized.equals(stored)) {
                    project.put(ProjectType.METADATA_KEY, normalized);
                    persistProjectType(project, projectMetadataPath, fileUtil);
                }
                return;
            }

            String resolved = ProjectType.JAVA_XML;
            try {
                if (pro.sketchware.beans.ProjectLibraryBean.LIB_USE_Y.equals(
                        ProjectDataManager.getLibraryManager(projectId).getCompose().useYn)) {
                    resolved = ProjectType.COMPOSE;
                }
            } catch (RuntimeException e) {
                LogUtil.w("ProjectListManager", "Failed to read legacy Compose flag for project " + projectId, e);
            }
            if (!ProjectType.COMPOSE.equals(resolved)) {
                try {
                    for (var activity : ProjectDataManager.getFileManager(projectId).getActivities()) {
                        if (activity != null && activity.isComposeActivity()) {
                            resolved = ProjectType.COMPOSE;
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    LogUtil.w("ProjectListManager", "Failed to read legacy project files for " + projectId, e);
                }
            }
            project.put(ProjectType.METADATA_KEY, resolved);
            persistProjectType(project, projectMetadataPath, fileUtil);
        } finally {
            ensuringProjectType.set(Boolean.FALSE);
        }
    }

    private static void persistProjectType(HashMap<String, Object> project, String projectMetadataPath,
                                           EncryptedFileUtil fileUtil) {
        try {
            fileUtil.writeBytes(projectMetadataPath, encryptProjectJson(fileUtil, GsonMapHelper.toJson(project), projectMetadataPath));
        } catch (IOException | RuntimeException e) {
            LogUtil.e("ProjectListManager", "Failed to persist project type migration for " + projectMetadataPath, e);
        }
    }

    public static String getNextProjectId() {
        int nextId = 601;
        for (HashMap<String, Object> project : listProjects()) {
            try {
                nextId = Math.max(nextId, Integer.parseInt(MapValueHelper.getString(project, "sc_id")) + 1);
            } catch (NumberFormatException e) {
                LogUtil.w("ProjectListManager", "Caught NumberFormatException", e);
            }
        }
        return String.valueOf(nextId);
    }

    public static HashMap<String, Object> getProjectById(String projectId) {
        EncryptedFileUtil fileUtil = new EncryptedFileUtil();
        String projectDir = SketchwarePaths.getProjectListPath(projectId);
        if (!new File(projectDir).exists()) {
            return null;
        }
        String projectMetadataPath = projectDir + File.separator + "project";
        try {
            HashMap<String, Object> parsedProject = GsonMapHelper.fromJson(decryptProjectJson(fileUtil, projectMetadataPath));
            try {
                if (!MapValueHelper.getString(parsedProject, "sc_id").equals(projectId)) {
                    return null;
                }
                ensureProjectType(parsedProject, projectId, projectMetadataPath, fileUtil);
                return parsedProject;
            } catch (RuntimeException e) {
                LogUtil.e("ProjectListManager", "Failed to validate project metadata for " + projectId + " from " + projectMetadataPath, e);
                return parsedProject;
            }
        } catch (IOException | RuntimeException e) {
            LogUtil.e("ProjectListManager", "Failed to load project metadata for " + projectId + " from " + projectMetadataPath, e);
            return null;
        }
    }

    public static void updateProject(String projectId, HashMap<String, Object> projectData) {
        File projectDirectory = new File(SketchwarePaths.getProjectListPath(projectId));
        if (projectDirectory.exists()) {
            String projectMetadataPath = projectDirectory + File.separator + "project";
            EncryptedFileUtil fileUtil = new EncryptedFileUtil();
            try {
                HashMap<String, Object> existingProject = GsonMapHelper.fromJson(decryptProjectJson(fileUtil, projectMetadataPath));
                if (MapValueHelper.getString(existingProject, "sc_id").equals(projectId)) {
                    if (projectData.containsKey("isIconAdaptive")) {
                        existingProject.put("isIconAdaptive", projectData.get("isIconAdaptive"));
                    }
                    if (projectData.containsKey("custom_icon")) {
                        existingProject.put("custom_icon", projectData.get("custom_icon"));
                    }
                    if (projectData.containsKey(ProjectType.METADATA_KEY)) {
                        existingProject.put(ProjectType.METADATA_KEY,
                                ProjectType.normalize(String.valueOf(projectData.get(ProjectType.METADATA_KEY))));
                    }
                    existingProject.put("my_sc_pkg_name", projectData.get("my_sc_pkg_name"));
                    existingProject.put("my_ws_name", projectData.get("my_ws_name"));
                    existingProject.put("my_app_name", projectData.get("my_app_name"));
                    existingProject.put("sc_ver_code", projectData.get("sc_ver_code"));
                    existingProject.put("sc_ver_name", projectData.get("sc_ver_name"));
                    existingProject.put("sketchware_ver", projectData.get("sketchware_ver"));
                    existingProject.put("color_accent", projectData.get("color_accent"));
                    existingProject.put("color_primary", projectData.get("color_primary"));
                    existingProject.put("color_primary_dark", projectData.get("color_primary_dark"));
                    existingProject.put("color_control_highlight", projectData.get("color_control_highlight"));
                    existingProject.put("color_control_normal", projectData.get("color_control_normal"));
                    boolean saved = fileUtil.writeBytes(projectMetadataPath, encryptProjectJson(fileUtil, GsonMapHelper.toJson(existingProject), projectMetadataPath));
                    if (!saved) {
                        LogUtil.e("ProjectListManager", "Failed to write updated project metadata for " + projectId + " to " + projectMetadataPath);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LogUtil.e("ProjectListManager", "Failed to update project metadata for " + projectId + " at " + projectMetadataPath, e);
            }
        }
    }

    public static String getNextWorkspaceName() {
        ArrayList<HashMap<String, Object>> projects = listProjects();
        ArrayList<Integer> projectIndices = new ArrayList<>();

        for (HashMap<String, Object> project : projects) {
            String workspaceName = MapValueHelper.getString(project, "my_ws_name");
            if (workspaceName.equals("NewProject")) {
                projectIndices.add(1);
            } else if (workspaceName.indexOf("NewProject") == 0) {
                try {
                    projectIndices.add(Integer.parseInt(workspaceName.substring(10)));
                } catch (NumberFormatException e) {
                    LogUtil.w("ProjectListManager", "Failed to parse project index from: " + workspaceName, e);
                }
            }
        }

        projectIndices.sort(new IntegerComparator());
        int lastConsecutiveIndex = 0;

        for (int nextProjectIndex : projectIndices) {
            int expectedNext = lastConsecutiveIndex + 1;
            if (nextProjectIndex == expectedNext) {
                lastConsecutiveIndex = expectedNext;
            } else {
                if (nextProjectIndex == lastConsecutiveIndex) {
                    continue;
                }
                break;
            }
        }

        if (lastConsecutiveIndex == 0) {
            return "NewProject";
        } else {
            return "NewProject" + (lastConsecutiveIndex + 1);
        }
    }

    public static void syncAllProjects() {
        for (String projectKey : sharedPrefsHelper.getAll().keySet()) {
            saveProject(projectKey, sharedPrefsHelper.getMap(projectKey));
        }
        sharedPrefsHelper.clearAll();
    }

    private static class IntegerComparator implements Comparator<Integer> {

        @Override
        public int compare(Integer first, Integer second) {
            return first.compareTo(second);
        }
    }
}
