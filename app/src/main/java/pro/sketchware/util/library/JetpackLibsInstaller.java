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
        public int artifacts;
        public int dexed;
        public int edges;
        public File directory;
        public final List<String> names = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public String summary() {
            StringBuilder text = new StringBuilder();
            text.append(artifacts).append(" Jetpack artifacts installed into ")
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
            report.artifacts = artifacts.size();

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
    private static Map<String, String> mapPackagesToArtifacts(Map<String, Artifact> artifacts)
            throws IOException {
        Map<String, String> owners = new HashMap<>();
        for (Artifact artifact : artifacts.values()) {
            List<File> jars = artifact.jars();
            for (File jar : jars) {
                artifact.definedPackages.addAll(packagesOf(jar));
            }
            for (String pack : artifact.definedPackages) {
                owners.putIfAbsent(pack, artifact.id);
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
                                         Map<String, String> packageOwners,
                                         Listener listener) throws IOException {
        int done = 0;
        int total = Math.max(1, artifacts.size());
        for (Artifact artifact : artifacts.values()) {
            if (cancelled) return;
            listener.onStage("Detecting dependencies of " + artifact.id + "…",
                    55 + (++done) * 18 / total);
            Set<String> references = new LinkedHashSet<>();
            for (File jar : artifact.jars()) {
                collectReferences(jar, references);
            }
            for (String reference : references) {
                if (artifact.definedPackages.contains(reference)) continue;
                String owner = packageOwners.get(reference);
                if (owner != null && !owner.equals(artifact.id)) {
                    artifact.directDependencies.add(owner);
                }
            }
        }
    }

    private static void collectReferences(File jar, Set<String> packages) throws IOException {
        if (jar == null || !jar.isFile()) return;
        try (ZipFile archive = new ZipFile(jar)) {
            for (ZipEntry entry : Collections.list(archive.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream in = archive.getInputStream(entry)) {
                    readReferencedPackages(in, packages);
                } catch (IOException | RuntimeException ignored) {
                    // One unreadable class must not cost the whole artifact: the remaining constant
                    // pools still describe most of what it references.
                }
            }
        }
    }

    /**
     * Reads a class file's constant pool and records the package of each class it mentions. Only tags 1
     * (Utf8) and 7 (Class) carry the names, but the walk has to stay aligned with the pool, and Long and
     * Double occupy two slots each.
     */
    private static void readReferencedPackages(InputStream input, Set<String> packages)
            throws IOException {
        BufferedInputStream in = new BufferedInputStream(input);
        if (readU4(in) != 0xCAFEBABE) return;
        readU2(in); // minor version
        readU2(in); // major version
        int count = readU2(in);
        String[] utf8 = new String[count];
        int[] classes = new int[count];
        int classCount = 0;
        for (int i = 1; i < count; i++) {
            int tag = in.read();
            if (tag < 0) throw new IOException("truncated constant pool");
            switch (tag) {
                case 1: {
                    int length = readU2(in);
                    utf8[i] = new String(readFully(in, length), StandardCharsets.UTF_8);
                    break;
                }
                case 7:
                    if (classCount < classes.length) classes[classCount++] = readU2(in);
                    break;
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    skip(in, 4);
                    break;
                case 5: // Long
                case 6: // Double
                    skip(in, 8);
                    i++; // occupies two entries of the pool
                    break;
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    skip(in, 2);
                    break;
                case 15: // MethodHandle
                    skip(in, 3);
                    break;
                default:
                    throw new IOException("unsupported constant pool tag " + tag);
            }
        }
        for (int i = 0; i < classCount; i++) {
            String name = classes[i] < utf8.length ? utf8[classes[i]] : null;
            if (name == null || name.isEmpty() || name.charAt(0) == '[') continue;
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash <= 0) continue;
            packages.add(name.substring(0, lastSlash));
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
        JsonArray packages = new JsonArray();
        for (String pack : artifact.definedPackages) packages.add(pack.replace('/', '.'));
        info.add("packages", packages);
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

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[Math.max(0, length)];
        int offset = 0;
        while (offset < bytes.length) {
            int read = in.read(bytes, offset, bytes.length - offset);
            if (read < 0) throw new IOException("truncated class file");
            offset += read;
        }
        return bytes;
    }

    private static int readU2(InputStream in) throws IOException {
        int high = in.read();
        int low = in.read();
        if (high < 0 || low < 0) throw new IOException("truncated class file");
        return (high << 8) | low;
    }

    private static long readU4(InputStream in) throws IOException {
        return ((long) readU2(in) << 16) | readU2(in);
    }

    private static void skip(InputStream in, int bytes) throws IOException {
        int left = bytes;
        while (left > 0) {
            int read = in.read();
            if (read < 0) throw new IOException("truncated class file");
            left--;
        }
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
        boolean excluded;

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
