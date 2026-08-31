package pro.sketchware.util.library;

import android.util.Log;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import pro.sketchware.core.project.SketchwarePaths;

/**
 * Installs a Jetpack dependency ZIP into the shared store at
 * {@code .sketchware/libs/JetpackLibs/<artifact>/} and derives everything a build needs from the
 * extracted structure alone: no manifest file to write and no cache directory to lose.
 *
 * <p>An artifact directory is an exploded AAR — {@code classes.jar}, {@code classes.dex},
 * {@code res/}, {@code AndroidManifest.xml}, {@code proguard.txt}, {@code assets/}, {@code libs/} —
 * which is exactly the layout the local library system already consumes. An installed artifact
 * therefore joins a project through the same code as any other local library: one copy on disk,
 * shared by every project that activates it, with its resources, DEX, manifest and ProGuard rules
 * already wired.</p>
 *
 * <p>The metadata that used to be maintained by hand is generated here:</p>
 * <ul>
 *   <li>{@code dependency-tree.json} — the transitive closure of what an artifact <em>references</em>,
 *       read from the constant pools of its own class files and matched against the packages the other
 *       directories define. A hand-written list can only contain what its author remembered (which is
 *       how a Compose bundle ended up with no {@code ui → ui-graphics} edge and 19 unreadable compile
 *       errors); this one contains what the bytecode needs.</li>
 *   <li>{@code config} — the resource package name {@code aapt2 --extra-packages} needs, taken from the
 *       artifact's own {@code AndroidManifest.xml}.</li>
 *   <li>{@code classes.dex} — produced with the embedded D8 when the ZIP ships only a
 *       {@code classes.jar}, because nothing else on the device can turn one into runnable code.</li>
 *   <li>{@code jetpack-info.json} — identity and what was detected, so the UI and a later build can
 *       explain the decisions instead of repeating them.</li>
 * </ul>
 *
 * <p>Work runs on one background thread that is shut down again as soon as its queue drains: an import
 * is a rare burst of I/O, and parking a worker plus its buffers for the whole life of the app would
 * cost a low-memory device for no benefit.</p>
 */
public final class JetpackLibsInstaller {
    private static final String TAG = "JetpackLibs";

    /** The id given to files that sit at the ZIP root, before the archive's name decides its real one. */
    private static final String ROOT_ID = "root";

    /** Wrappers some packing styles put around the real artifact directories. */
    private static final Set<String> WRAPPER_FOLDERS = Set.of(
            "librarys", "libraries", "lib", "libs", "aar", "artifact", "artifacts", "modules");

    /** Packages the app's own Kotlin runtime already provides to every Kotlin project. */
    private static final Set<String> RUNTIME_PACKAGE_PREFIXES = Set.of(
            "kotlin/", "kotlinx/", "org/jetbrains/", "androidx/annotation/");

    private static final Pattern TRAILING_VERSION = Pattern.compile("[-_]v?(\\d+(?:[._]\\d+){1,3})(?=$|[-_])");

    private static final Object LOCK = new Object();
    private static ThreadPoolExecutor executor;
    private static volatile boolean cancelled;

    private JetpackLibsInstaller() {}

    /** Progress and result callbacks; all are invoked off the main thread. */
    public interface Listener {
        void onStage(String message, int percent);

        void onFinished(Report report);

        void onFailed(String message);
    }

    /** What an import did, in the numbers a user can act on. */
    public static final class Report {
        /** True when the work was a re-scan of the store rather than an import. */
        public boolean rebuild;
        public int artifacts;
        public int dexed;
        public int edges;
        public File directory;
        public final List<String> names = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public String summary() {
            StringBuilder text = new StringBuilder();
            text.append(artifacts).append(rebuild
                            ? " artifacts re-read in " : " Jetpack artifacts installed into ")
                    .append(directory == null ? "the store" : directory.getName());
            if (edges > 0) text.append(", ").append(edges).append(" dependency edges detected");
            if (dexed > 0) text.append(", ").append(dexed).append(" DEX files generated");
            if (!warnings.isEmpty()) text.append(", ").append(warnings.size()).append(" note(s)");
            return text.toString();
        }
    }

    /**
     * Extracts {@code zip} into the shared store in the background.
     *
     * @param keepAppRuntimeDuplicates import {@code kotlin-stdlib} / {@code kotlinx-coroutines} copies
     *        from the ZIP instead of leaving the app's own ones (which every Kotlin project already
     *        receives) alone. Off by default: two Kotlin runtimes in one APK is how a Compose activity
     *        dies inside {@code CoroutineContext} plumbing, because the DEX merge keeps the first copy of
     *        every type and the two then disagree about default-method bridges.
     */
    public static void install(File zip, boolean keepAppRuntimeDuplicates, Listener listener) {
        cancelled = false;
        executor().execute(() -> {
            try {
                Report report = doInstall(zip, keepAppRuntimeDuplicates, listener);
                if (cancelled) {
                    listener.onFailed("Import cancelled");
                } else {
                    listener.onFinished(report);
                }
            } catch (Exception failure) {
                Log.e(TAG, "Jetpack dependency import failed", failure);
                listener.onFailed(describe(failure));
            } finally {
                shutdownWhenIdle();
            }
        });
    }

    /** Asks a running import to stop; artifacts already published stay usable. */
    public static void cancel() {
        cancelled = true;
        synchronized (LOCK) {
            if (executor != null) {
                executor.getQueue().clear();
            }
        }
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return executor != null && !executor.isShutdown() && !executor.getQueue().isEmpty();
        }
    }

    public static File getStoreDir() {
        return SketchwarePaths.getJetpackLibsDir();
    }

    /**
     * Re-derives the generated metadata for the artifacts the store already holds, with no ZIP involved.
     *
     * <p>A store installed before the dependency detection was able to read it keeps whatever
     * {@code dependency-tree.json} it was first given, and an empty list is invisible in the UI but
     * decisive in a build: activating a root then adds only the root, so the folders its classes
     * reference never reach the classpath. Folders are also edited by hand more often than any other
     * part of this feature, and a re-scan is the only way to notice. Anything the store cannot build for
     * itself (a missing {@code classes.dex}) is generated again here as well.</p>
     */
    public static void rescan(Listener listener) {
        cancelled = false;
        executor().execute(() -> {
            try {
                Report report = doRescan(listener);
                if (cancelled) {
                    listener.onFailed("Re-scan cancelled");
                } else {
                    listener.onFinished(report);
                }
            } catch (Exception failure) {
                Log.e(TAG, "Jetpack store re-scan failed", failure);
                listener.onFailed(describe(failure));
            } finally {
                shutdownWhenIdle();
            }
        });
    }

    /** Deletes artifacts from the store; the projects that activated them must re-check their list. */
    public static void uninstall(List<String> ids, Listener listener) {
        cancelled = false;
        executor().execute(() -> {
            try {
                listener.onFinished(doUninstall(ids));
            } catch (Exception failure) {
                Log.e(TAG, "Jetpack store delete failed", failure);
                listener.onFailed(describe(failure));
            } finally {
                shutdownWhenIdle();
            }
        });
    }

    private static Report doUninstall(List<String> ids) throws IOException {
        Report report = new Report();
        List<File> roots = new ArrayList<>();
        roots.add(SketchwarePaths.getJetpackLibsDir());
        roots.add(SketchwarePaths.getJetpackLibsFallbackDir());
        for (String id : ids) {
            // Only ever a single path element: a list row must not be able to delete elsewhere.
            String safe = id == null ? "" : sanitize(new File(id).getName());
            if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) continue;
            for (File root : roots) {
                File directory = new File(root, safe);
                if (directory.isDirectory()) {
                    deleteRecursively(directory);
                    if (!report.names.contains(safe)) report.names.add(safe);
                }
            }
        }
        report.artifacts = report.names.size();
        report.directory = getStoreDir();
        writeStoreInventory(getStoreDir());
        if (report.names.isEmpty()) {
            report.warnings.add("nothing was removed — the artifact folder is already gone");
        } else {
            report.warnings.add("open the Compose library list of every project that used these"
                    + " artifacts, so the removed names drop out of its library list");
        }
        return report;
    }

    private static Report doRescan(Listener listener) throws IOException {
        Report report = new Report();
        report.rebuild = true;
        File store = getStoreDir();
        File[] children = store.listFiles();
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        if (children == null) {
            throw new IOException("The Jetpack store at " + store.getAbsolutePath()
                    + " cannot be read — allow all-files access and try again");
        }
        List<File> directories = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory() && !child.getName().startsWith(".")) directories.add(child);
        }
        directories.sort(Comparator.comparing(File::getName));
        for (File directory : directories) {
            Artifact artifact = new Artifact(directory.getName(), directory);
            if (!artifact.hasClassesJar() && !artifact.hasDex()) {
                artifact.excluded = true;
                artifact.notes.add("has no classes.jar and no classes.dex — left untouched");
            }
            artifacts.put(artifact.id, artifact);
        }
        if (artifacts.isEmpty()) {
            report.directory = store;
            report.warnings.add("the store has no artifact folders to read");
            return report;
        }

        unwrapWrapper(store, artifacts, report);
        listener.onStage("Normalizing artifact payloads…", 10);
        normalizePayloads(artifacts, report);
        listener.onStage("Reading the packages each artifact defines…", 30);
        Map<String, Set<String>> owners = mapPackagesToArtifacts(artifacts);
        listener.onStage("Detecting dependency edges…", 50);
        linkDependencies(artifacts, owners, listener);

        int total = Math.max(1, artifacts.size());
        int done = 0;
        for (Artifact artifact : artifacts.values()) {
            if (cancelled) return report;
            if (artifact.excluded) continue;
            listener.onStage("Rebuilding " + artifact.id + "…", 50 + (++done) * 45 / total);
            readIdentity(artifact);
            for (String message : artifact.notes) report.warnings.add(artifact.id + ": " + message);
            writeConfig(artifact);
            writeDependencyTree(artifact, artifacts);
            writeInfo(artifact);
            LocalLibraryImportPackageIndex.rebuildPackages(artifact.directory);
            if (!artifact.hasDex() && artifact.hasClassesJar()) dex(artifact, report);
            report.artifacts++;
            report.edges += artifact.edgeCount;
            report.names.add(artifact.id);
        }
        for (Artifact artifact : artifacts.values()) {
            if (!artifact.excluded) continue;
            report.warnings.add(artifact.id + ": " + String.join("; ", artifact.notes));
        }
        report.directory = store;
        writeStoreInventory(store);
        return report;
    }

    private static ThreadPoolExecutor executor() {
        synchronized (LOCK) {
            if (executor == null || executor.isShutdown()) {
                ThreadPoolExecutor created = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "JetpackLibsImporter");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
                // Frees the worker thread between imports instead of parking it forever.
                created.allowCoreThreadTimeOut(true);
                executor = created;
            }
            return executor;
        }
    }

    private static void shutdownWhenIdle() {
        synchronized (LOCK) {
            if (executor != null && executor.getQueue().isEmpty()) {
                executor.shutdown();
                executor = null;
            }
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------ install

    private static Report doInstall(File zip, boolean keepRuntimeDuplicates, Listener listener)
            throws IOException {
        if (zip == null || !zip.isFile()) {
            throw new IOException("Selected Jetpack dependency ZIP is missing or unreadable");
        }
        Report report = new Report();
        File store = getStoreDir();
        File staging = new File(store, ".staging-" + System.currentTimeMillis());
        if (!staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Cannot create " + staging.getAbsolutePath()
                    + " — allow all-files access, or the store has to live in the app's own files");
        }

        try {
            listener.onStage("Extracting artifacts…", 5);
            Map<String, Artifact> artifacts = extract(zip, staging, listener, report);
            if (artifacts.isEmpty()) {
                throw new IOException("No artifact folders found. Expected <id>/classes.jar"
                        + " (and optionally classes.dex, res/, AndroidManifest.xml) inside the ZIP");
            }
            adoptRootArtifact(zip, artifacts, report);
            unwrapWrapper(staging, artifacts, report);
            report.artifacts = artifacts.size();

            listener.onStage("Normalizing artifact payloads…", 32);
            normalizePayloads(artifacts, report);

            listener.onStage("Reading the packages each artifact defines…", 35);
            Map<String, String> packageOwners = mapPackagesToArtifacts(artifacts);

            listener.onStage("Detecting dependency edges…", 55);
            linkDependencies(artifacts, packageOwners, listener);

            listener.onStage("Writing metadata and DEX…", 75);
            prepare(artifacts, keepRuntimeDuplicates, listener, report);

            publish(staging, artifacts, report);
            report.directory = store;
            listener.onStage("Done", 100);
            return report;
        } finally {
            deleteRecursively(staging);
        }
    }

    /** Extracts the ZIP, one directory per artifact id, tolerating the known wrapper layouts. */
    private static Map<String, Artifact> extract(File zip, File staging, Listener listener,
                                                 Report report) throws IOException {
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        try (ZipFile archive = new ZipFile(zip)) {
            List<? extends ZipEntry> entries = Collections.list(archive.entries());
            int total = Math.max(1, entries.size());
            int index = 0;
            for (ZipEntry entry : entries) {
                if (cancelled) return artifacts;
                index++;
                if (index % 128 == 0) {
                    listener.onStage("Extracting artifacts… " + index + "/" + total,
                            5 + index * 25 / total);
                }
                String path = entry.getName();
                if (entry.isDirectory() || isNoise(path)) continue;

                String id = artifactIdOf(path);
                Artifact artifact = artifacts.get(id);
                if (artifact == null) {
                    artifact = new Artifact(id, new File(staging, id));
                    artifacts.put(id, artifact);
                }
                File target = new File(artifact.directory, rolePathOf(path));
                if (!target.getCanonicalPath().startsWith(artifact.directory.getCanonicalPath() + File.separator)) {
                    report.warnings.add("ignored entry outside its artifact directory: " + path);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create " + parent.getAbsolutePath());
                }
                try (InputStream in = archive.getInputStream(entry);
                     OutputStream out = new FileOutputStream(target)) {
                    copy(in, out);
                }
            }
        }
        return artifacts;
    }

    private static boolean isNoise(String path) {
        String lower = path.toLowerCase();
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return name.startsWith(".") || name.equals("thumbs.db") || lower.startsWith("__macosx/")
                || lower.contains("meta-inf/com/google/android/");
    }

    /**
     * Finds the artifact that owns a ZIP entry: {@code <id>/…}, a wrapped {@code libraries/<id>/…} or
     * {@code librarys/<id>/…}, and a flat {@code dex/<id>.dex} that belongs to an artifact by name.
     */
    private static String artifactIdOf(String path) {
        String[] parts = path.split("/");
        // A single exploded AAR is often zipped without a folder around it: classes.jar, res/, … sit at
        // the archive root. They are one artifact, and its name is decided from the ZIP, not here.
        if (parts.length == 1) return ROOT_ID;
        if (parts.length >= 2 && WRAPPER_FOLDERS.contains(parts[0].toLowerCase())) {
            return sanitize(parts[1]);
        }
        if (parts.length == 2 && parts[1].toLowerCase().endsWith(".dex")
                && (parts[0].equalsIgnoreCase("dex") || parts[0].equalsIgnoreCase("jars"))) {
            return sanitize(parts[1].substring(0, parts[1].length() - 4));
        }
        return sanitize(parts[0]);
    }

    /** Maps a ZIP entry onto the role it plays inside its artifact directory. */
    private static String rolePathOf(String path) {
        String[] parts = path.split("/");
        if (parts.length == 1) return parts[0];
        int start;
        if (parts.length >= 2 && WRAPPER_FOLDERS.contains(parts[0].toLowerCase())) {
            start = 2;
        } else if (parts.length == 2 && parts[1].toLowerCase().endsWith(".dex")
                && parts[0].equalsIgnoreCase("dex")) {
            return "classes.dex";
        } else {
            start = 1;
        }
        StringBuilder relative = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (relative.length() > 0) relative.append('/');
            relative.append(parts[i]);
        }
        String lower = relative.toString().toLowerCase();
        if (lower.equals("proguard.txt") || lower.equals("proguard-rules.pro")
                || lower.equals("consumer-rules.pro") || lower.endsWith("proguard.pro")) {
            return "proguard.txt";
        }
        return relative.toString();
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        String result = out.toString();
        while (result.startsWith(".")) result = result.substring(1);
        return result.isEmpty() ? "artifact" : result;
    }

    // ------------------------------------------------------------------ index

    /** Records which packages each artifact defines, so a reference can be traced back to its owner. */
    /**
     * Which artifacts define which packages. A package may legitimately have more than one owner — Compose
     * splits classes across artifacts while keeping them in a shared package ({@code ui} itself defines
     * {@code androidx.compose.ui.graphics.vector}, and {@code androidx.compose.ui.text} is not owned by
     * {@code ui-text} alone) — so every owner is recorded and a reference becomes an edge to each of them.
     * Picking one winner would look tidy and lose the artifact whose classes the reference actually needs,
     * which is the same failure a missing hand-written dependency edge produces.
     */
    private static Map<String, Set<String>> mapPackagesToArtifacts(Map<String, Artifact> artifacts)
            throws IOException {
        Map<String, Set<String>> owners = new HashMap<>();
        for (Artifact artifact : artifacts.values()) {
            for (File jar : artifact.jars()) {
                artifact.definedPackages.addAll(packagesOf(jar));
            }
            for (String pack : artifact.definedPackages) {
                owners.computeIfAbsent(pack, key -> new LinkedHashSet<>()).add(artifact.id);
            }
        }
        /* A folder that ships only DEX would otherwise own nothing, and an artifact that owns nothing
           cannot be found by anything else — which is how an installed library ends up listed with zero
           dependencies. A DEX type list is read in a second pass on purpose: it names the classes the
           artifact references as well as the ones it defines, so a package is attributed to a DEX only
           when no JAR claims it. */
        for (Artifact artifact : artifacts.values()) {
            if (!artifact.definedPackages.isEmpty()) continue;
            Set<String> fromDex = new LinkedHashSet<>();
            if (readDexPackages(new File(artifact.directory, "classes.dex"), fromDex)) {
                artifact.definedPackages.addAll(fromDex);
                artifact.dexOnly = true;
            }
        }
        for (Artifact artifact : artifacts.values()) {
            if (!artifact.dexOnly) continue;
            for (String pack : artifact.definedPackages) {
                if (owners.containsKey(pack)) continue;
                owners.computeIfAbsent(pack, key -> new LinkedHashSet<>()).add(artifact.id);
            }
        }
        return owners;
    }

    private static Set<String> packagesOf(File jar) throws IOException {
        Set<String> packages = new LinkedHashSet<>();
        if (jar == null || !jar.isFile()) return packages;
        try (ZipFile archive = new ZipFile(jar)) {
            for (ZipEntry entry : Collections.list(archive.entries())) {
                String path = entry.getName();
                if (!path.endsWith(".class")) continue;
                int slash = path.lastIndexOf('/');
                if (slash > 0) packages.add(path.substring(0, slash));
            }
        }
        return packages;
    }

    /**
     * Fills each artifact's direct dependencies from the constant pools of its own classes: every
     * referenced package that another artifact defines is an edge, and nothing else can be.
     */
    private static void linkDependencies(Map<String, Artifact> artifacts,
                                         Map<String, Set<String>> packageOwners,
                                         Listener listener) throws IOException {
        int done = 0;
        int total = Math.max(1, artifacts.size());
        for (Artifact artifact : artifacts.values()) {
            if (cancelled) return;
            listener.onStage("Detecting dependencies of " + artifact.id + "…",
                    55 + (++done) * 18 / total);
            Set<String> references = new LinkedHashSet<>();
            for (File jar : artifact.jars()) {
                collectReferences(jar, references, artifact);
            }
            if (artifact.dexOnly) {
                readDexPackages(new File(artifact.directory, "classes.dex"), references);
                references.removeAll(artifact.definedPackages);
            }
            artifact.referencesFound = references.size();
            for (String reference : references) {
                if (artifact.definedPackages.contains(reference)) continue;
                Set<String> ownersOfReference = packageOwners.get(reference);
                if (ownersOfReference == null) continue;
                for (String owner : ownersOfReference) {
                    if (!owner.equals(artifact.id)) artifact.directDependencies.add(owner);
                }
            }
        }
    }

    private static void collectReferences(File jar, Set<String> packages, Artifact artifact)
            throws IOException {
        if (jar == null || !jar.isFile()) return;
        try (ZipFile archive = new ZipFile(jar)) {
            for (ZipEntry entry : Collections.list(archive.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                if (artifact != null) artifact.classesScanned++;
                try (InputStream in = archive.getInputStream(entry)) {
                    readReferencedPackages(readAll(in), packages);
                } catch (IOException | RuntimeException ignored) {
                    // One unreadable class must not cost the whole artifact: the remaining constant
                    // pools still describe most of what it references.
                }
            }
        }
    }

    /**
     * Reads the type list of a DEX file. Every entry is a field descriptor such as
     * {@code Landroidx/compose/ui/Foo;}, which is the same internal naming the constant pool uses, so a
     * folder that ships only DEX can still be wired to the artifacts it needs. Only the header and the
     * two index tables are touched — no encoded values are decoded, which is what keeps this cheap
     * enough to run over fifty artifacts on a low-end device.
     */
    private static boolean readDexPackages(File dex, Set<String> packages) {
        if (dex == null || !dex.isFile()) return false;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(dex, "r")) {
            byte[] magic = new byte[8];
            file.seek(0);
            file.readFully(magic);
            if (magic[0] != 'd' || magic[1] != 'e' || magic[2] != 'x' || magic[3] != '\n') return false;
            long length = file.length();
            if (u32(file, 40, length) != 0x12345678L) return false; // big-endian DEX: not ours to read
            long stringsSize = u32(file, 56, length);
            long stringsOff = u32(file, 60, length);
            long typesSize = u32(file, 64, length);
            long typesOff = u32(file, 68, length);
            if (stringsSize <= 0 || typesSize <= 0 || typesSize > 4_000_000) return false;
            if (stringsOff + stringsSize * 4 > length || typesOff + typesSize * 4 > length) return false;
            boolean any = false;
            for (long i = 0; i < typesSize; i++) {
                long index = u32(file, typesOff + i * 4, length);
                if (index < 0 || index >= stringsSize) continue;
                long dataOff = u32(file, stringsOff + index * 4, length);
                if (dataOff < 0 || dataOff >= length) continue;
                String descriptor = readCString(file, dataOff, length);
                if (descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                        || descriptor.charAt(descriptor.length() - 1) != ';') {
                    continue;
                }
                String internal = descriptor.substring(1, descriptor.length() - 1);
                int slash = internal.lastIndexOf('/');
                if (slash <= 0) continue;
                packages.add(internal.substring(0, slash));
                any = true;
            }
            return any;
        } catch (IOException | RuntimeException failure) {
            Log.d(TAG, "Cannot read DEX types of " + dex + ": " + describe(failure));
            return false;
        }
    }

    /** A DEX header field, or -1 when the file is shorter than the offset it asks for. */
    private static long u32(java.io.RandomAccessFile file, long offset, long length) throws IOException {
        if (offset < 0 || offset + 4 > length) return -1;
        file.seek(offset);
        return (file.readUnsignedByte()) | (file.readUnsignedByte() << 8)
                | (file.readUnsignedByte() << 16) | ((long) file.readUnsignedByte() << 24);
    }

    /** Reads a MUTF-8 string; its ulebe128 length is skipped because the terminator is authoritative. */
    private static String readCString(java.io.RandomAccessFile file, long offset, long length)
            throws IOException {
        int limit = (int) Math.min(length - offset, 4096);
        byte[] buffer = new byte[Math.max(0, limit)];
        file.seek(offset);
        file.readFully(buffer);
        int end = 0;
        while (end < buffer.length && buffer[end] != 0) end++;
        return new String(buffer, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Reads a class file's constant pool and records the package of each class its entries mention. Tags
     * 1 (Utf8) and 7 (Class) carry the names; every other entry still has to be stepped over, and Long and
     * Double occupy two pool slots each.
     *
     * <p>The entry is read whole and walked with an index instead of streamed. Two reasons, both learned
     * the hard way here: {@code InputStream.skip} may return fewer bytes than asked — which silently
     * changes the meaning of every byte after it — and a stream that misreads its own header has to be
     * caught, not trusted. Every read is bounds-checked for the same reason: this function's only output
     * is an <em>absence</em> when it fails, and an artifact reported as depending on nothing looks exactly
     * like a library that genuinely needs nothing. (The first version compared the magic number against
     * {@code 0xCAFEBABE} as an {@code int}; that literal is negative, so it never equalled the
     * {@code long} that was read, every class bailed out at line one, and a device showed 1 409 classes
     * read with zero references found.)</p>
     */
    private static void readReferencedPackages(byte[] data, Set<String> packages) {
        if (data == null || data.length < 10 || u32be(data, 0) != 0xCAFEBABEL) return;
        int count = u16be(data, 8);
        if (count <= 1) return;
        String[] utf8 = new String[count];
        int[] classes = new int[count];
        int classCount = 0;
        int pos = 10;
        pool:
        for (int i = 1; i < count; i++) {
            if (pos + 1 > data.length) break pool;
            int tag = data[pos++] & 0xFF;
            switch (tag) {
                case 1: {
                    if (pos + 2 > data.length) break pool;
                    int length = u16be(data, pos);
                    pos += 2;
                    if (length < 0 || pos + length > data.length) break pool;
                    utf8[i] = new String(data, pos, length, StandardCharsets.UTF_8);
                    pos += length;
                    break;
                }
                case 7:
                    if (pos + 2 > data.length) break pool;
                    if (classCount < classes.length) classes[classCount++] = u16be(data, pos);
                    pos += 2;
                    break;
                case 5: // Long
                case 6: // Double: eight bytes, and the following pool slot does not exist
                    if (pos + 8 > data.length) break pool;
                    pos += 8;
                    i++;
                    break;
                default: {
                    int size = constantPoolEntrySize(tag);
                    if (size < 0 || pos + size > data.length) break pool;
                    pos += size;
                    break;
                }
            }
        }
        describeClasses(utf8, classes, classCount, packages);
    }

    /**
     * The byte count that follows a constant-pool tag, or -1 for a tag this reader will not guess about.
     * An unknown tag ends the walk of that class rather than corrupting it: everything read before it is
     * still the truth, which is what keeps a class compiled by a newer javac from looking like a class
     * that uses nothing.
     */
    private static int constantPoolEntrySize(int tag) {
        switch (tag) {
            case 3:  // Integer
            case 4:  // Float
            case 9:  // Fieldref
            case 10: // Methodref
            case 11: // InterfaceMethodref
            case 12: // NameAndType
            case 17: // Dynamic
            case 18: // InvokeDynamic
            case 21: // ConstantDynamic
                return 4;
            case 7:  // Class
            case 8:  // String
            case 16: // MethodType
            case 19: // Module
            case 20: // Package
                return 2;
            case 15: // MethodHandle
                return 3;
            default:
                return -1;
        }
    }

    private static int u16be(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    }

    private static long u32be(byte[] data, int pos) {
        return ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
    }

    private static void describeClasses(String[] utf8, int[] classes, int classCount,
                                        Set<String> packages) {
        for (int i = 0; i < classCount; i++) {
            String name = classes[i] < utf8.length ? utf8[classes[i]] : null;
            if (name == null || name.isEmpty() || name.charAt(0) == '[') continue;
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash <= 0) continue;
            packages.add(name.substring(0, lastSlash));
        }
    }

    // ------------------------------------------------------------ payload normalisation

    /**
     * Gives the files of a root-packed AAR an artifact id, which the store needs because a folder name is
     * the only identity a store entry has. The ZIP's own name is preferred — it is what the user chose —
     * and the manifest's package is the fallback when the name says nothing.
     */
    private static void adoptRootArtifact(File zip, Map<String, Artifact> artifacts, Report report)
            throws IOException {
        Artifact root = artifacts.remove(ROOT_ID);
        if (root == null) return;
        String name = zip == null || zip.getName() == null ? "" : zip.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = sanitize(name);
        if (name.isEmpty() || ROOT_ID.equals(name)) {
            String pack = manifestPackage(new File(root.directory, "AndroidManifest.xml"));
            name = pack == null ? "library" : sanitize(pack.replace('.', '_'));
        }
        if (artifacts.containsKey(name)) name = name + "-root";

        File legacy = root.directory;
        File target = new File(legacy.getParentFile(), name);
        if (!target.exists()) {
            if (!legacy.renameTo(target)) {
                artifacts.put(ROOT_ID, root);
                report.warnings.add("the files at the ZIP root could not be renamed to " + name
                        + " — they stay under the name " + ROOT_ID + ", which still works as one library");
                return;
            }
        } else {
            // A folder of that name already came from the archive: keep what was packed deliberately and
            // add only what the root contributed, rather than replacing either side.
            mergeInto(legacy, target);
        }
        deleteRecursively(legacy);
        artifacts.put(name, new Artifact(name, target));
        report.warnings.add("the ZIP held its files at the root instead of one folder per artifact, so the"
                + " archive became a single artifact named " + name
                + " — pack a folder per library to get more than one out of it");
    }

    /** Moves files that have no counterpart yet, recursing through subfolders. */
    private static void mergeInto(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) return;
        for (File child : children) {
            File destination = new File(to, child.getName());
            if (child.isDirectory()) {
                if (!destination.isDirectory() && !destination.mkdirs()) {
                    throw new IOException("cannot create " + destination.getAbsolutePath());
                }
                mergeInto(child, destination);
            } else if (!destination.exists() && !child.renameTo(destination)) {
                throw new IOException("cannot move " + child.getName() + " into " + to.getAbsolutePath());
            }
        }
    }

    /**
     * Re-keys a ZIP that wrapped all of its artifacts in a single folder
     * ({@code bundle/androidx_compose_ui_ui_android/classes.jar}). The folder names are the manifest here,
     * so a wrapper directory must not become the only library — without this, everything would collapse
     * into one artifact whose classes.jar was renamed from inside it, which installs, lists no
     * dependencies and changes nothing about a build.
     */
    private static void unwrapWrapper(File base, Map<String, Artifact> artifacts, Report report) {
        if (artifacts.size() != 1) return;
        Map.Entry<String, Artifact> only = artifacts.entrySet().iterator().next();
        File wrapper = only.getValue().directory;
        File[] children = wrapper.listFiles();
        if (children == null) return;
        List<File> payloads = new ArrayList<>();
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")) continue;
            if (findDeep(child, ".jar", 1, true) != null || findDeep(child, ".aar", 1, true) != null
                    || new File(child, "classes.dex").isFile()) {
                payloads.add(child);
            }
        }
        if (payloads.size() < 2) return;
        int moved = 0;
        for (File payload : payloads) {
            String id = sanitize(payload.getName());
            File target = new File(base, id);
            int suffix = 1;
            while (target.exists()) target = new File(base, id + "-" + (++suffix));
            if (!payload.renameTo(target)) {
                report.warnings.add("cannot re-key " + payload.getName() + " out of " + only.getKey()
                        + " — the wrapper stays as one library, which will not compile against anything");
                break;
            }
            artifacts.put(target.getName(), new Artifact(target.getName(), target));
            moved++;
        }
        if (moved > 0) {
            artifacts.remove(only.getKey());
            deleteRecursively(wrapper);
            report.warnings.add("the ZIP wrapped " + moved + " artifacts inside " + only.getKey()
                    + "/ — each folder became its own library, named after the folder");
        }
    }

    /**
     * Puts every artifact folder into the shape the build reads, and records what had to be repaired.
     *
     * <p>A store directory is only useful if its classes sit at {@code <id>/classes.jar} and its DEX at
     * {@code <id>/classes.dex} — those exact paths are what the local library system hands to the
     * compiler, to AAPT2 and to the DEX merge. ZIPs arrive in other shapes all the time: an AAR that was
     * renamed instead of unpacked, a {@code jars/} or {@code classes/} subfolder, a jar still carrying
     * its artifact name. Each is one file move away from working, and each silently produces a library
     * that installs, lists zero dependencies and changes nothing when it is switched on — the worst
     * possible failure, because it looks like success.</p>
     */
    private static void normalizePayloads(Map<String, Artifact> artifacts, Report report) {
        for (Artifact artifact : artifacts.values()) {
            if (artifact.excluded) continue;
            try {
                normalize(artifact);
            } catch (IOException | RuntimeException failure) {
                String note = "could not inspect the folder (" + describe(failure) + ")";
                artifact.notes.add(note);
                report.warnings.add(artifact.id + ": " + note);
            }
        }
    }

    private static void normalize(Artifact artifact) throws IOException {
        File jar = artifact.classesJar();
        if (jar.isFile() && jar.length() < 22) {
            // A stub of a file is what an interrupted extraction leaves behind. It is not a library, and
            // leaving it would hide the real payload sitting next to it: classes.jar is the only name the
            // build looks at, so an empty one is worse than none.
            if (jar.delete()) artifact.notes.add("removed an empty classes.jar before looking for the"
                    + " real payload in this folder");
        }
        if (jar.isFile() && isAar(jar)) {
            // A renamed AAR: the real classes are one ZIP level deeper.
            File aar = new File(artifact.directory, "library.aar");
            if (!jar.renameTo(aar)) throw new IOException("cannot set aside " + jar.getName());
            try {
                unpackAar(artifact, aar, true);
                artifact.notes.add("classes.jar was an AAR — unpacked it in place");
            } catch (IOException | RuntimeException failure) {
                // Never leave the artifact worse than it was found: the renamed file is all it had.
                if (!new File(artifact.directory, "classes.jar").isFile()) aar.renameTo(jar);
                throw failure;
            }
        } else if (!jar.isFile()) {
            File aar = findDeep(artifact.directory, ".aar", 3, true);
            if (aar != null) {
                unpackAar(artifact, aar, false);
                artifact.notes.add("unpacked " + relativeTo(artifact, aar));
                if (artifact.hasClassesJar() && !deleteQuietly(aar)) {
                    note(artifact, "left the AAR at " + relativeTo(artifact, aar)
                            + " because it could not be deleted — its classes are installed once, from"
                            + " classes.jar, so the duplicate costs space only");
                }
            }
        }
        if (!artifact.hasClassesJar()) {
            // A jar under another name is still the artifact's API, and the compiler only ever looks at
            // classes.jar, so the name — not the content — is what is broken here.
            File stray = findDeep(artifact.directory, ".jar", 2, false);
            if (stray != null && stray.renameTo(jar)) {
                artifact.notes.add("renamed " + relativeTo(artifact, stray) + " to classes.jar");
            }
            if (!artifact.hasClassesJar()) {
                // Nothing but a secondary jar: moving it (not copying — a DEX generated from both copies
                // would merge the same types twice) is the only way its API reaches the compiler.
                File only = findDeep(artifact.directory, ".jar", 2, true);
                if (only != null && only.renameTo(jar)) {
                    artifact.notes.add("moved " + relativeTo(artifact, jar)
                            + " out of libs/ to classes.jar so the compiler can see it");
                }
            }
        }
        File dex = new File(artifact.directory, "classes.dex");
        if (!dex.isFile()) {
            File strayDex = findDeep(artifact.directory, ".dex", 3, false);
            if (strayDex != null && strayDex.renameTo(dex)) {
                artifact.notes.add("renamed " + relativeTo(artifact, strayDex) + " to classes.dex");
            }
        }
        if (!artifact.hasClassesJar() && artifact.hasDex()) {
            artifact.dexOnly = true;
            note(artifact, "ships classes.dex only: its code will run, but no compiler can read a DEX,"
                    + " so project code cannot compile against it — put the artifact's classes.jar in the"
                    + " folder and re-scan if you need its API");
        }
        if (!artifact.hasClassesJar() && !artifact.hasDex()) {
            note(artifact, "no classes found — an AAR must be unpacked, and a JAR must be named"
                    + " classes.jar (both checked recursively)");
        }
    }

    private static boolean deleteQuietly(File file) {
        return file == null || !file.isFile() || file.delete();
    }

    private static void note(Artifact artifact, String message) {
        artifact.notes.add(message);
    }

    /** Copies an AAR's parts into the artifact folder, keeping whatever the ZIP already provided. */
    private static void unpackAar(Artifact artifact, File aar, boolean replaceClasses) throws IOException {
        try (ZipFile archive = new ZipFile(aar)) {
            for (ZipEntry entry : Collections.list(archive.entries())) {
                String path = entry.getName();
                if (entry.isDirectory() || isNoise(path)) continue;
                String target = aarRoleOf(path);
                if (target == null) continue;
                File output = new File(artifact.directory, target);
                boolean overwrite = replaceClasses && target.equals("classes.jar");
                if (!overwrite && output.isFile()) continue;
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent.getAbsolutePath());
                }
                try (InputStream in = archive.getInputStream(entry);
                     OutputStream out = new FileOutputStream(output)) {
                    copy(in, out);
                }
            }
        }
    }

    /** Maps an AAR entry onto the local library layout; anything the build cannot use is dropped. */
    private static String aarRoleOf(String path) {
        String lower = path.toLowerCase();
        if (lower.equals("classes.jar")) return "classes.jar";
        if (lower.equals("androidmanifest.xml")) return "AndroidManifest.xml";
        if (lower.equals("proguard.txt") || lower.equals("proguard-rules.pro")
                || lower.equals("consumer-rules.pro") || lower.endsWith("proguard.pro")) {
            return "proguard.txt";
        }
        if (lower.startsWith("res/") || lower.startsWith("assets/") || lower.startsWith("libs/")) {
            return path;
        }
        return null;
    }

    /** True when the "jar" is really an AAR: only an AAR carries a nested classes.jar. */
    private static boolean isAar(File candidate) {
        if (candidate == null || !candidate.isFile()) return false;
        try (ZipFile archive = new ZipFile(candidate)) {
            return archive.getEntry("classes.jar") != null;
        } catch (IOException failure) {
            return false;
        }
    }

    /**
     * The first file ending in {@code suffix} below {@code directory}, up to {@code depth} levels deep.
     * Resource and metadata folders are skipped because they legitimately contain files of the same
     * suffix that must never be moved: {@code libs/} holds an AAR's secondary jars, and promoting one
     * would silently drop the rest of them from the build.
     */
    private static File findDeep(File directory, String suffix, int depth, boolean allowLibs) {
        if (directory == null || depth < 0) return null;
        File[] children = directory.listFiles();
        if (children == null) return null;
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, children);
        sorted.sort(Comparator.comparing(File::getName));
        for (File child : sorted) {
            if (!child.isFile() || !child.getName().toLowerCase().endsWith(suffix)) continue;
            File parent = child.getParentFile();
            if (!allowLibs && parent != null && "libs".equals(parent.getName().toLowerCase())) continue;
            return child;
        }
        for (File child : sorted) {
            if (!child.isDirectory()) continue;
            String name = child.getName().toLowerCase();
            if (name.equals("res") || name.equals("assets") || name.equals("jni")
                    || name.equals("meta-inf") || name.startsWith(".")
                    || (name.equals("libs") && !allowLibs)) {
                continue;
            }
            File found = findDeep(child, suffix, depth - 1, allowLibs);
            if (found != null) return found;
        }
        return null;
    }

    private static String relativeTo(Artifact artifact, File file) {
        try {
            String base = artifact.directory.getCanonicalPath() + File.separator;
            String full = file.getCanonicalPath();
            return full.startsWith(base) ? full.substring(base.length()) : file.getName();
        } catch (IOException failure) {
            return file.getName();
        }
    }

    /** Writes the generated files, and turns leftover JARs into DEX. */
    private static void prepare(Map<String, Artifact> artifacts, boolean keepRuntimeDuplicates,
                                Listener listener, Report report) throws IOException {
        // Keep and drop decisions are made for every artifact first: an edge may point at the artifact
        // whose fate is decided last, and writing trees while deciding would then require a folder that
        // is never installed (or, worse, silently drop one that is).
        for (Artifact artifact : artifacts.values()) {
            readIdentity(artifact);
            if (!keepRuntimeDuplicates && providesAppRuntime(artifact)) {
                artifact.excluded = true;
                report.warnings.add(artifact.id + " is Kotlin runtime the app already ships ("
                        + BuiltInLibraries.JETBRAINS_KOTLIN_STDLIB + ", "
                        + BuiltInLibraries.JETBRAINS_KOTLINX_COROUTINES_ANDROID
                        + ") — not installed, so it cannot shadow the copy every project uses. Enable"
                        + " the override switch if this ZIP deliberately carries a newer one");
            } else if (!artifact.hasClassesJar() && !artifact.hasDex()) {
                artifact.excluded = true;
                report.warnings.add(artifact.id + " has neither classes.jar nor classes.dex — skipped");
            }
        }

        int done = 0;
        int total = Math.max(1, artifacts.size());
        for (Artifact artifact : artifacts.values()) {
            if (cancelled) return;
            if (artifact.excluded) continue;
            listener.onStage("Preparing " + artifact.id + "…", 75 + (++done) * 20 / total);
            writeConfig(artifact);
            writeDependencyTree(artifact, artifacts);
            writeInfo(artifact);
            // The package index is what the library manager reads per artifact; generating it here keeps
            // the project's UI (which runs on the main thread) from scanning every JAR on click.
            LocalLibraryImportPackageIndex.rebuildPackages(artifact.directory);
            report.edges += artifact.edgeCount;
            if (!artifact.hasDex()) {
                dex(artifact, report);
            }
            report.names.add(artifact.id);
        }
    }

    /** True when the artifact's classes are the runtime every Kotlin project already receives. */
    private static boolean providesAppRuntime(Artifact artifact) {
        for (String pack : artifact.definedPackages) {
            String withSlash = pack + "/";
            for (String prefix : RUNTIME_PACKAGE_PREFIXES) {
                if (withSlash.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private static void readIdentity(Artifact artifact) throws IOException {
        File coordinateFile = new File(artifact.directory, "maven-coordinate");
        if (coordinateFile.isFile()) {
            String coordinate;
            try (InputStream in = new FileInputStream(coordinateFile)) {
                coordinate = new String(readAll(in), StandardCharsets.UTF_8).trim();
            }
            String[] parts = coordinate.split(":");
            if (parts.length == 3) {
                artifact.groupId = parts[0];
                artifact.artifactId = parts[1];
                artifact.version = parts[2];
                artifact.coordinate = coordinate;
            }
        }
        if (artifact.version == null) {
            Matcher matcher = TRAILING_VERSION.matcher(artifact.id);
            if (matcher.find()) artifact.version = matcher.group(1).replace('_', '.');
        }
        if (artifact.groupId == null) {
            artifact.groupId = guessGroup(artifact);
        }
        if (artifact.artifactId == null) {
            artifact.artifactId = artifact.id;
        }
        if (artifact.coordinate == null && artifact.version != null) {
            artifact.coordinate = artifact.groupId + ":" + artifact.artifactId + ":" + artifact.version;
        }
    }

    private static String guessGroup(Artifact artifact) {
        String pack = null;
        for (String candidate : artifact.definedPackages) {
            if (candidate.contains(".internal") || candidate.endsWith(".jni")) continue;
            if (pack == null || candidate.length() < pack.length()) pack = candidate;
        }
        if (pack == null) return artifact.id;
        String[] segments = pack.split("/");
        if (segments.length < 3) return pack.replace('/', '.');
        return segments[0] + "." + segments[1] + "." + segments[2];
    }

    /** The local library system reads the resource package name from this single-line file. */
    private static void writeConfig(Artifact artifact) throws IOException {
        String packageName = manifestPackage(new File(artifact.directory, "AndroidManifest.xml"));
        if (packageName == null) packageName = dominantPackage(artifact.definedPackages);
        if (packageName != null) writeText(new File(artifact.directory, "config"), packageName);
    }

    /** Reads {@code package="…"} from a plain-text manifest; a binary AAPT one has none. */
    private static String manifestPackage(File manifest) {
        if (manifest == null || !manifest.isFile()) return null;
        try {
            String xml = new String(readAll(new FileInputStream(manifest)), StandardCharsets.UTF_8);
            int start = xml.indexOf("package=\"");
            if (start < 0) return null;
            int end = xml.indexOf('"', start + 9);
            if (end < 0) return null;
            String value = xml.substring(start + 9, end);
            return value.contains(".") ? value : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String dominantPackage(Set<String> packages) {
        String best = null;
        for (String pack : packages) {
            if (pack.contains(".internal") || pack.endsWith(".jni")) continue;
            if (best == null || pack.length() < best.length()) best = pack;
        }
        return best == null ? null : best.replace('/', '.');
    }

    /**
     * Writes the closure, flattened. The local library machinery expands a root by one level, so the
     * file lists every transitive dependency with its depth and parent kept for display; edges that
     * point at an artifact which was not installed are recorded as built-in, so enabling a library can
     * never ask for a folder that does not exist.
     */
    private static void writeDependencyTree(Artifact artifact, Map<String, Artifact> artifacts)
            throws IOException {
        JsonArray entries = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>(artifact.directDependencies);
        int depth = 1;
        while (!frontier.isEmpty() && depth <= 16) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                if (!seen.add(id)) continue;
                Artifact dependency = artifacts.get(id);
                JsonObject entry = new JsonObject();
                entry.addProperty("folder", id);
                entry.addProperty("groupId", dependency != null ? dependency.guessGroup() : id);
                entry.addProperty("artifactId", id);
                entry.addProperty("version", dependency != null && dependency.version != null
                        ? dependency.version : "0");
                entry.addProperty("extension", "aar");
                entry.addProperty("builtIn", dependency == null || dependency.excluded);
                entry.addProperty("depth", depth);
                entry.addProperty("parent", artifact.id);
                entries.add(entry);
                if (dependency != null) next.addAll(dependency.directDependencies);
            }
            frontier = next;
            depth++;
        }
        artifact.edgeCount = entries.size();
        writeJson(new File(artifact.directory, "dependency-tree.json"), entries);
    }

    private static void writeInfo(Artifact artifact) {
        JsonObject info = new JsonObject();
        info.addProperty("id", artifact.id);
        info.addProperty("groupId", artifact.groupId);
        info.addProperty("artifactId", artifact.artifactId);
        info.addProperty("version", artifact.version == null ? "0" : artifact.version);
        if (artifact.coordinate != null) info.addProperty("coordinate", artifact.coordinate);
        info.addProperty("source", "jetpack-zip");
        info.addProperty("importedAt", System.currentTimeMillis());
        info.addProperty("edges", artifact.edgeCount);
        info.addProperty("classes", artifact.classesScanned);
        info.addProperty("references", artifact.referencesFound);
        info.addProperty("dexOnly", artifact.dexOnly);
        JsonArray notes = new JsonArray();
        for (String message : artifact.notes) notes.add(message);
        info.add("notes", notes);
        JsonArray packages = new JsonArray();
        for (String pack : artifact.definedPackages) packages.add(pack.replace('/', '.'));
        info.add("packages", packages);
        JsonArray direct = new JsonArray();
        for (String dependency : artifact.directDependencies) direct.add(dependency);
        info.add("directDependencies", direct);
        info.add("files", roleFiles(artifact.directory));
        try {
            writeJson(new File(artifact.directory, "jetpack-info.json"), info);
        } catch (IOException ignored) {
            // Identity is diagnostic only; losing it must not fail an otherwise good import.
        }
    }

    /** Turns {@code classes.jar} (plus any {@code libs/*.jar}) into {@code classes.dex}. */
    private static void dex(Artifact artifact, Report report) {
        File output = artifact.directory;
        try {
            List<java.nio.file.Path> programs = new ArrayList<>();
            for (File jar : artifact.jars()) {
                if (jar.isFile()) programs.add(jar.toPath());
            }
            if (programs.isEmpty()) return;
            // Intermediate DEX has no library resolution to do, which keeps memory flat: a low-end
            // device cannot run D8 over 50 artifacts with the full project classpath at once.
            D8.run(D8Command.builder()
                    .setIntermediate(true)
                    .setMode(CompilationMode.RELEASE)
                    .setMinApiLevel(21)
                    .addProgramFiles(programs)
                    .setOutput(output.toPath(), OutputMode.DexIndexed)
                    .build());
            report.dexed++;
        } catch (Throwable failure) {
            report.warnings.add(artifact.id + " could not be DEXed (" + describe(failure)
                    + ") — compilation still works, but its classes will be missing at runtime unless"
                    + " the ZIP ships classes.dex for it");
        }
    }

    /** Moves the finished artifacts into the store, replacing directories of the same name. */
    private static void publish(File staging, Map<String, Artifact> artifacts, Report report)
            throws IOException {
        File store = getStoreDir();
        if (!store.isDirectory() && !store.mkdirs()) {
            throw new IOException("Cannot create " + store.getAbsolutePath());
        }
        for (Artifact artifact : artifacts.values()) {
            if (cancelled) return;
            if (artifact.excluded) {
                deleteRecursively(artifact.directory);
                continue;
            }
            File target = new File(store, artifact.id);
            deleteRecursively(target);
            if (!artifact.directory.renameTo(target)) {
                // renameTo only works within one filesystem; shared storage and the app's own files can
                // be different mounts, so fall back to copying rather than lose the artifact.
                copyDirectory(artifact.directory, target);
                deleteRecursively(artifact.directory);
            }
        }
        if (!report.names.isEmpty()) {
            writeJson(new File(store, "jetpack-store.json"), buildInventory(artifacts));
        }
    }

    /**
     * Every file a store artifact contributes to a build, by absolute path. The store derives ids and
     * edges from folder contents, so this is the one place a person can read back exactly what was found
     * — the list a hand-written manifest would have carried, generated from the files instead of from a
     * description of them, and therefore unable to disagree with them.
     */
    private static JsonObject roleFiles(File directory) {
        JsonObject files = new JsonObject();
        for (String name : new String[]{"classes.jar", "classes.dex", "config", "proguard.txt",
                "AndroidManifest.xml", "packages.txt", "dependency-tree.json", "jetpack-info.json"}) {
            if (new File(directory, name).isFile()) files.addProperty(name,
                    new File(directory, name).getAbsolutePath());
        }
        for (String name : new String[]{"res", "assets", "libs", "jni"}) {
            if (new File(directory, name).isDirectory()) files.addProperty(name + "/",
                    new File(directory, name).getAbsolutePath());
        }
        return files;
    }

    /** Refreshes the store's own listing from what is on disk, after a delete or a re-scan. */
    private static void writeStoreInventory(File store) {
        JsonArray inventory = new JsonArray();
        for (JetpackLibs.Entry entry : JetpackLibs.installed()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.id);
            item.addProperty("version", entry.version);
            item.addProperty("coordinate", entry.coordinate);
            item.addProperty("edges", entry.edges);
            item.addProperty("dex", entry.hasDex ? "present" : "missing");
            File directory = new File(store, entry.id);
            if (directory.isDirectory()) item.add("files", roleFiles(directory));
            inventory.add(item);
        }
        try {
            writeJson(new File(store, "jetpack-store.json"), inventory);
        } catch (IOException ignored) {
            // The listing is a convenience; the store itself is the folders on disk.
        }
    }

    private static JsonArray buildInventory(Map<String, Artifact> artifacts) {
        JsonArray inventory = new JsonArray();
        List<String> names = new ArrayList<>();
        for (Artifact artifact : artifacts.values()) {
            if (!artifact.excluded) names.add(artifact.id);
        }
        Collections.sort(names);
        for (String name : names) {
            Artifact artifact = artifacts.get(name);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", artifact.id);
            entry.addProperty("version", artifact.version == null ? "0" : artifact.version);
            entry.addProperty("edges", artifact.edgeCount);
            entry.addProperty("dex", artifact.hasDex() ? "present" : "generated");
            JsonArray dependencies = new JsonArray();
            for (String dependency : artifact.directDependencies) dependencies.add(dependency);
            entry.add("directDependencies", dependencies);
            entry.add("files", roleFiles(artifact.directory));
            inventory.add(entry);
        }
        return inventory;
    }

    // ------------------------------------------------------------------ io

    private static void writeJson(File file, JsonElement json) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent.getAbsolutePath());
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file),
                StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent.getAbsolutePath());
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copyDirectory(File source, File target) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Cannot create " + target.getAbsolutePath());
        }
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File destination = new File(target, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, destination);
            } else {
                File parent = destination.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try (InputStream in = new FileInputStream(child);
                     OutputStream out = new FileOutputStream(destination)) {
                    copy(in, out);
                }
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }

    /** One artifact directory being assembled. */
    private static final class Artifact {
        final String id;
        final File directory;
        final Set<String> definedPackages = new LinkedHashSet<>();
        final Set<String> directDependencies = new LinkedHashSet<>();
        String groupId;
        String artifactId;
        String version;
        String coordinate;
        int edgeCount;
        int classesScanned;
        int referencesFound;
        boolean dexOnly;
        boolean excluded;
        /** What had to be repaired or could not be read, in the words a user can act on. */
        final List<String> notes = new ArrayList<>();

        Artifact(String id, File directory) {
            this.id = id;
            this.directory = directory;
        }

        File classesJar() {
            return new File(directory, "classes.jar");
        }

        /** {@code classes.jar} plus every JAR the AAR shipped under {@code libs/}. */
        List<File> jars() {
            List<File> jars = new ArrayList<>();
            jars.add(classesJar());
            File[] files = new File(directory, "libs").listFiles();
            if (files != null) {
                List<File> sorted = new ArrayList<>();
                Collections.addAll(sorted, files);
                Collections.sort(sorted, (left, right) -> left.getName().compareTo(right.getName()));
                for (File file : sorted) {
                    if (file.isFile() && file.getName().endsWith(".jar")) jars.add(file);
                }
            }
            return jars;
        }

        boolean hasDex() {
            return new File(directory, "classes.dex").isFile();
        }

        boolean hasClassesJar() {
            return classesJar().isFile();
        }

        String guessGroup() {
            return groupId == null ? id : groupId;
        }
    }
}
