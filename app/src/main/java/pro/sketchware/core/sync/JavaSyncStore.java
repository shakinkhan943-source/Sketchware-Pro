package pro.sketchware.core.sync;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.FileUtil;
import pro.sketchware.util.GsonUtils;
import pro.sketchware.util.LogUtil;

/**
 * Reads and writes {@link JavaSyncMetadata} files.
 * <p>
 * Location: {@code .sketchware/data/<sc_id>/java_sync/<JavaName>.json} — inside the regular project
 * data directory, so the metadata is part of every project backup/export/import.
 * <p>
 * All methods are safe to call for projects that never used the Java editor: they then simply
 * return empty metadata and never create files.
 */
public final class JavaSyncStore {

    private static final String DIR_NAME = "java_sync";

    private JavaSyncStore() {
    }

    public static String getDirectory(String scId) {
        return SketchwarePaths.getDataPath(scId) + File.separator + DIR_NAME;
    }

    public static String getFilePath(String scId, String javaName) {
        return getDirectory(scId) + File.separator + javaName + ".json";
    }

    public static boolean hasMetadata(String scId, String javaName) {
        return FileUtil.isExistFile(getFilePath(scId, javaName));
    }

    /**
     * @return the stored metadata, or fresh empty metadata when nothing was stored yet.
     */
    public static JavaSyncMetadata load(String scId, String javaName) {
        String path = getFilePath(scId, javaName);
        if (!FileUtil.isExistFile(path)) {
            return new JavaSyncMetadata(javaName);
        }
        try {
            String content = FileUtil.readFile(path);
            if (content == null || content.trim().isEmpty()) {
                return new JavaSyncMetadata(javaName);
            }
            JavaSyncMetadata metadata = GsonUtils.getGson().fromJson(content, JavaSyncMetadata.class);
            if (metadata == null) {
                return new JavaSyncMetadata(javaName);
            }
            metadata.javaName = javaName;
            metadata.getUserCode();
            metadata.getRegionSnapshots();
            return metadata;
        } catch (RuntimeException e) {
            LogUtil.e("JavaSyncStore", "Failed to read Java sync metadata of " + javaName, e);
            return new JavaSyncMetadata(javaName);
        }
    }

    public static void save(String scId, JavaSyncMetadata metadata) {
        if (metadata == null) {
            return;
        }
        String path = getFilePath(scId, metadata.javaName);
        try {
            if (metadata.isEmpty()) {
                // Nothing worth persisting: keep the project as clean as it was before.
                if (FileUtil.isExistFile(path)) {
                    FileUtil.deleteFile(path);
                }
                return;
            }
            metadata.lastSyncedAt = System.currentTimeMillis();
            FileUtil.makeDir(getDirectory(scId));
            FileUtil.writeFile(path, GsonUtils.getGson().toJson(metadata));
        } catch (RuntimeException e) {
            LogUtil.e("JavaSyncStore", "Failed to write Java sync metadata of " + metadata.javaName, e);
        }
    }

    public static void delete(String scId, String javaName) {
        String path = getFilePath(scId, javaName);
        if (FileUtil.isExistFile(path)) {
            FileUtil.deleteFile(path);
        }
    }

    /**
     * @return a short fingerprint of all synchronization metadata of a project. It is mixed into the
     * code generation cache key so a build picks up manually written Java code.
     */
    public static String getFingerprint(String scId) {
        File dir = new File(getDirectory(scId));
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (File file : files) {
            parts.add(file.getName() + ":" + file.length() + ":" + file.lastModified());
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }
}
