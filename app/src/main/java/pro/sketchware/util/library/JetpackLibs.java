package pro.sketchware.util.library;

import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import pro.sketchware.core.project.SketchwarePaths;

/**
 * Read-only view of the shared Jetpack dependency store
 * ({@code .sketchware/libs/JetpackLibs/<artifact>/}).
 *
 * <p>The store exists once for the whole device and every project that activates an artifact reads the
 * same files, so this class only answers two questions the build cannot guess for itself: what is
 * installed, and whether an installed artifact carries a <em>newer</em> copy of a library the app also
 * ships. The second one matters because the DEX merge keeps the first definition of every type: a
 * project that ends up with two Kotlin runtimes gets whichever came first, and the Compose artifacts
 * were compiled against the other one — which surfaces far from its cause, as an
 * {@code AbstractMethodError} inside {@code kotlin.coroutines.CoroutineContext} the moment a
 * {@code ComposeView} attaches to a window.</p>
 */
public final class JetpackLibs {
    private static final String TAG = "JetpackLibs";
    private static final String INFO_FILE = "jetpack-info.json";

    private JetpackLibs() {}

    /** One installed artifact. */
    public static final class Entry {
        public final String id;
        public final String artifactId;
        public final String version;
        public final String coordinate;
        public final int edges;
        public final boolean hasDex;
        public final boolean hasResources;
        public final boolean hasProguard;
        public final long sizeBytes;
        /** Why this artifact could not be read fully, or {@code null} when nothing needed explaining. */
        public final String note;

        Entry(String id, String artifactId, String version, String coordinate, int edges,
              boolean hasDex, boolean hasResources, boolean hasProguard, long sizeBytes, String note) {
            this.id = id;
            this.artifactId = artifactId;
            this.version = version;
            this.coordinate = coordinate;
            this.edges = edges;
            this.hasDex = hasDex;
            this.hasResources = hasResources;
            this.hasProguard = hasProguard;
            this.sizeBytes = sizeBytes;
            this.note = note;
        }

        /** A one-line description for the library manager and build log. */
        public String describe() {
            StringBuilder text = new StringBuilder(id);
            if (version != null && !version.isEmpty() && !"0".equals(version)) {
                text.append("  ").append(version);
            }
            text.append("  ·  ").append(edges).append(" deps");
            if (!hasDex) text.append("  ·  no DEX!");
            if (hasResources) text.append("  ·  res");
            if (note != null) text.append("\n").append(note);
            return text.toString();
        }
    }

    public static boolean isInstalled() {
        File store = SketchwarePaths.getJetpackLibsDir();
        File[] children = store.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")
                        && new File(child, "classes.jar").isFile()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every installed artifact, sorted by id, so a list and a build agree. */
    public static List<Entry> installed() {
        List<Entry> entries = new ArrayList<>();
        for (File directory : storeDirectories()) {
            entries.add(read(directory));
        }
        Collections.sort(entries, (left, right) -> left.id.compareTo(right.id));
        return entries;
    }

    /** The directory a store artifact lives in, whichever root holds it; {@code null} when absent. */
    public static File directoryOf(String id) {
        if (id == null) return null;
        for (File root : new File[]{SketchwarePaths.getJetpackLibsDir(),
                SketchwarePaths.getJetpackLibsFallbackDir()}) {
            File candidate = new File(root, id);
            if (candidate.isDirectory()) return candidate;
        }
        return null;
    }

    public static Set<String> installedIds() {
        Set<String> ids = new HashSet<>();
        for (Entry entry : installed()) {
            ids.add(entry.id);
        }
        return ids;
    }

    /**
     * Removes built-in libraries that an activated store artifact supersedes with a newer copy of the
     * same library, so a project carries exactly one version of each runtime piece.
     *
     * @param activatedLocalLibraries library names the project selected (roots and expanded
     *        sub-dependencies alike), because an installed-but-unused artifact must not replace anything
     */
    public static void applyRuntimeOverrides(BuiltInLibraryManager manager,
                                             Collection<String> activatedLocalLibraries) {
        if (manager == null || activatedLocalLibraries == null || activatedLocalLibraries.isEmpty()) {
            return;
        }
        List<Entry> installed = installed();
        if (installed.isEmpty()) return;

        List<String> names = new ArrayList<>();
        for (pro.sketchware.core.project.BuiltInLibrary library : manager.getLibraries()) {
            if (library != null && library.getName() != null) names.add(library.getName());
        }
        for (String builtInName : names) {
            String artifact = artifactOfBuiltInName(builtInName);
            String builtInVersion = versionOfBuiltInName(builtInName);
            if (artifact == null) continue;
            for (Entry entry : installed) {
                if (!activatedLocalLibraries.contains(entry.id)) continue;
                if (!artifact.equals(entry.artifactId)) continue;
                if (compareVersions(entry.version, builtInVersion) <= 0) continue;
                manager.removeLibrary(builtInName);
                Log.d(TAG, "Using " + entry.id + " (" + entry.version + ") from the Jetpack store"
                        + " instead of the built-in " + builtInName);
                break;
            }
        }
    }

    /** {@code kotlinx-coroutines-android-1.8.1} → {@code kotlinx-coroutines-android}. */
    private static String artifactOfBuiltInName(String name) {
        String version = versionOfBuiltInName(name);
        if (version == null) return null;
        return name.substring(0, name.length() - version.length() - 1);
    }

    /** The trailing {@code -<digits…>} of a built-in library name, or {@code null}. */
    private static String versionOfBuiltInName(String name) {
        int start = -1;
        for (int i = name.length() - 1; i >= 0; i--) {
            char c = name.charAt(i);
            if (c == '-') {
                return start > i + 1 ? name.substring(i + 1) : null;
            }
            if (!Character.isDigit(c) && c != '.') return null;
            if (start < 0) start = i;
        }
        return null;
    }

    /** Compares dotted numeric versions; a missing or unparsable part sorts as 0. */
    static int compareVersions(String left, String right) {
        String[] lefts = split(left);
        String[] rights = split(right);
        for (int i = 0; i < Math.max(lefts.length, rights.length); i++) {
            int a = i < lefts.length ? toInt(lefts[i]) : 0;
            int b = i < rights.length ? toInt(rights[i]) : 0;
            if (a != b) return a < b ? -1 : 1;
        }
        return 0;
    }

    private static String[] split(String version) {
        if (version == null || version.isEmpty() || "0".equals(version)) return new String[0];
        return version.replace('_', '.').replace('-', '.').split("\\.");
    }

    private static int toInt(String value) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isDigit(c)) break;
            result = result * 10 + (c - '0');
        }
        return result;
    }

    private static List<File> storeDirectories() {
        List<File> directories = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File root : new File[]{SketchwarePaths.getJetpackLibsDir(),
                SketchwarePaths.getJetpackLibsFallbackDir()}) {
            File[] children = root.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!child.isDirectory() || child.getName().startsWith(".")) continue;
                if (!new File(child, "classes.jar").isFile() && !new File(child, "classes.dex").isFile()) {
                    continue;
                }
                if (seen.add(child.getName())) directories.add(child);
            }
        }
        return directories;
    }

    private static Entry read(File directory) {
        String artifactId = null;
        String version = null;
        String coordinate = null;
        int edges = 0;
        File info = new File(directory, INFO_FILE);
        JsonObject infoObject = null;
        if (info.isFile()) {
            try {
                JsonElement parsed = JsonParser.parseString(
                        pro.sketchware.util.FileUtil.readFile(info.getAbsolutePath()));
                if (parsed.isJsonObject()) {
                    JsonObject object = parsed.getAsJsonObject();
                    infoObject = object;
                    artifactId = string(object, "artifactId");
                    version = string(object, "version");
                    coordinate = string(object, "coordinate");
                    JsonElement edgeCount = object.get("edges");
                    if (edgeCount != null && edgeCount.isJsonPrimitive()) {
                        try {
                            edges = edgeCount.getAsInt();
                        } catch (NumberFormatException ignored) {
                            // a hand-edited file must not hide an installed artifact
                        }
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read " + info + ": " + e.getMessage());
            }
        }
        File tree = new File(directory, "dependency-tree.json");
        if (edges == 0 && tree.isFile()) {
            try {
                JsonElement parsed = JsonParser.parseString(
                        pro.sketchware.util.FileUtil.readFile(tree.getAbsolutePath()));
                if (parsed.isJsonArray()) edges = parsed.getAsJsonArray().size();
            } catch (RuntimeException ignored) {
                // fall through: the count is cosmetic
            }
        }
        if (artifactId == null) artifactId = directory.getName();
        long size = 0L;
        for (String name : new String[]{"classes.jar", "classes.dex", "proguard.txt", "config"}) {
            size += new File(directory, name).length();
        }
        String note = null;
        if (infoObject != null) {
            // Nothing in this method may hide an installed artifact: a broken metadata file is worth
            // reporting, but a library that still compiles must stay listed and switchable.
            try {
                JsonElement notes = infoObject.get("notes");
                if (notes != null && notes.isJsonArray() && notes.getAsJsonArray().size() > 0) {
                    StringBuilder text = new StringBuilder();
                    for (JsonElement element : notes.getAsJsonArray()) {
                        if (text.length() > 0) text.append(" ");
                        text.append(element.getAsString());
                    }
                    note = text.toString();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return new Entry(directory.getName(), artifactId, version == null ? "0" : version, coordinate,
                edges, new File(directory, "classes.dex").isFile(),
                new File(directory, "res").isDirectory(),
                new File(directory, "proguard.txt").isFile(), size, note);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return null;
        String text = value.getAsString();
        return text.isEmpty() ? null : text;
    }
}
