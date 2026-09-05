package pro.sketchware.core.build;

import pro.sketchware.core.project.BuildConfig;
import pro.sketchware.core.project.BuiltInLibrary;
import pro.sketchware.util.io.EncryptedFileUtil;
import pro.sketchware.core.exception.SimpleException;
import pro.sketchware.core.exception.SketchwareException;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.io.ZipUtil;

import static android.system.OsConstants.S_IRUSR;
import static android.system.OsConstants.S_IWUSR;
import static android.system.OsConstants.S_IXUSR;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import android.os.StrictMode;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import com.android.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.android.tools.r8.CompilationFailedException;
import com.github.megatronking.stringfog.plugin.StringFogClassInjector;
import com.github.megatronking.stringfog.plugin.StringFogMappingPrinter;
import com.iyxan23.zipalignjava.InvalidZipException;
import com.iyxan23.zipalignjava.ZipAlign;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import mod.agus.jcoderz.dex.Dex;
import mod.agus.jcoderz.dex.TableOfContents;
import mod.agus.jcoderz.dx.command.dexer.DxContext;
import mod.agus.jcoderz.dx.command.dexer.Main;
import mod.agus.jcoderz.dx.merge.CollisionPolicy;
import mod.agus.jcoderz.dx.merge.DexMerger;
import pro.sketchware.util.library.ExtLibSelected;
import pro.sketchware.util.library.ManageLocalLibrary;
import pro.sketchware.core.build.BuildSettings;
import pro.sketchware.util.Helper;
import pro.sketchware.R;
import pro.sketchware.core.build.compiler.KotlinCompilerBridge;
import pro.sketchware.core.project.ProjectSettings;
import pro.sketchware.core.project.ProguardHandler;
import pro.sketchware.util.SystemLogPrinter;
import pro.sketchware.core.build.BuildProgressReceiver;
import pro.sketchware.util.library.BuiltInLibraries;
import pro.sketchware.util.library.ComposeBuiltInLibraries;
import pro.sketchware.util.library.JetpackLibs;
import pro.sketchware.core.build.compiler.DexCompiler;
import pro.sketchware.core.build.compiler.ResourceCompiler;
import pro.sketchware.core.exception.MissingFileException;
import pro.sketchware.util.LogUtil;
import pro.sketchware.util.TestkeySignBridge;
import pro.sketchware.util.apk.ApkSigner;
import pro.sketchware.core.build.compiler.JarBuilder;
import pro.sketchware.core.build.compiler.R8Compiler;
import pro.sketchware.core.build.ViewBindingBuilder;
import pro.sketchware.SketchApplication;
import pro.sketchware.util.library.BuiltInLibraryManager;
import pro.sketchware.util.FileUtil;
import pro.sketchware.util.SketchwareUtil;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ParseException;
import proguard.ProGuard;

/**
 * Orchestrates the entire build pipeline for a Sketchware user project:
 * resource compilation (AAPT2), Java compilation (ECJ), Kotlin compilation,
 * DEX generation (D8/Dx), DEX merging, ProGuard/R8 shrinking, StringFog
 * obfuscation, APK assembly, zipalign, and signing.
 * <p>
 * The typical build sequence called from the UI is:
 * <ol>
 *   <li>{@link #maybeExtractAapt2()} —verify the AAPT2 native library is available</li>
 *   <li>{@link #buildBuiltInLibraryInformation()} —resolve which built-in libraries are needed</li>
 *   <li>{@link #compileResources()} —AAPT2 compile + link</li>
 *   <li>{@link #generateViewBinding()} —generate ViewBinding Java sources (optional)</li>
 *   <li>{@link #compileJavaCode()} —ECJ incremental compilation</li>
 *   <li>{@link #createDexFilesFromClasses()} —D8/Dx conversion</li>
 *   <li>{@link #getDexFilesReady()} —merge library DEX files</li>
 *   <li>{@link #buildApk()} —assemble unsigned APK</li>
 *   <li>{@link #runZipalign(String, String)} —align the APK</li>
 *   <li>{@link #signDebugApk()} —sign with testkey</li>
 * </ol>
 *
 * @see BuildProgressReceiver
 * @see ProjectFilePaths
 */
public class ProjectBuilder {
    public static final String TAG = "AppBuilder";

    private final File aapt2Binary;
    private final Context context;
    public BuildSettings buildSettings;
    public ProjectFilePaths projectFilePaths;
    public ManageLocalLibrary localLibraryManager;
    public BuiltInLibraryManager builtInLibraryManager;
    public String androidJarPath;
    public ProguardHandler proguard;
    public ProjectSettings settings;
    private BuildProgressReceiver progressReceiver;
    private boolean buildAppBundle = false;
    private ArrayList<File> dexesToAddButNotMerge = new ArrayList<>();
    /** Pre-loaded cache set by the caller to avoid a redundant JSON read in {@link #compileJavaCode()}. */
    public IncrementalBuildCache preloadedBuildCache = null;
    /** Set to false by {@link #compileJavaCode()} when incremental build detects no changes. */
    private boolean classFilesChanged = true;

    /**
     * Timestamp keeping track of when compiling the project's resources started, needed for stats of how long compiling took.
     */
    private long timestampResourceCompilationStarted;

    /**
     * Creates a new project builder for the given project.
     * Initializes build settings, local library manager, ProGuard handler,
     * project settings, and AAPT2 binary path.
     *
     * @param context the Android context
     * @param projectFilePaths   the project file paths configuration
     */
    public ProjectBuilder(Context context, ProjectFilePaths projectFilePaths) {
        /* Detect some bad behaviour of the app */
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        );

        SystemLogPrinter.start();

        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);

            LogUtil.d(TAG, "Running Sketchware Pro " + info.versionName + " (" + info.versionCode + ")");

            ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);

            long fileSizeInBytes = new File(applicationInfo.sourceDir).length();
            LogUtil.d(TAG, "base.apk's size is " + Formatter.formatFileSize(context, fileSizeInBytes) + " (" + fileSizeInBytes + " B)");
        } catch (PackageManager.NameNotFoundException e) {
            LogUtil.e(TAG, "Somehow failed to get package info about us!", e);
        }

        aapt2Binary = new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        buildSettings = new BuildSettings(projectFilePaths.sc_id);
        this.context = context;
        this.projectFilePaths = projectFilePaths;
        localLibraryManager = new ManageLocalLibrary(projectFilePaths.sc_id);
        builtInLibraryManager = new BuiltInLibraryManager(projectFilePaths.sc_id);
        File defaultAndroidJar = new File(BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH, "android.jar");
        androidJarPath = buildSettings.getValue(BuildSettings.SETTING_ANDROID_JAR_PATH, defaultAndroidJar.getAbsolutePath());
        proguard = new ProguardHandler(projectFilePaths.sc_id);
        settings = new ProjectSettings(projectFilePaths.sc_id);
    }

    /**
     * Creates a new project builder with a progress receiver for UI updates.
     *
     * @param progressReceiver the progress receiver to report build steps to
     * @param context        the Android context
     * @param projectFilePaths          the project file paths configuration
     */
    public ProjectBuilder(BuildProgressReceiver progressReceiver, Context context, ProjectFilePaths projectFilePaths) {
        this(context, projectFilePaths);
        this.progressReceiver = progressReceiver;
    }

    /**
     * Checks if a file on local storage differs from a file in assets, and if so,
     * replaces the file on local storage with the one in assets.
     * <p/>
     * The file size is checked first as a fast path. If sizes match, SHA-256 is
     * compared to avoid keeping stale extracted build assets with identical size.
     *
     * @param fileInAssets The file in assets relative to assets/ in the APK
     * @param targetFile   The file on local storage
     * @return If the file in assets has been extracted
     */
    public static boolean hasFileChanged(String fileInAssets, String targetFile) {
        File compareToFile = new File(targetFile);
        EncryptedFileUtil fileUtil = new EncryptedFileUtil();
        long lengthOfFileInAssets = fileUtil.getAssetFileSize(SketchApplication.getAppContext(), fileInAssets);
        long length = compareToFile.exists() ? compareToFile.length() : 0;
        if (lengthOfFileInAssets == length && hasSameAssetContent(fileInAssets, compareToFile)) {
            return false;
        }

        /* Delete the file */
        fileUtil.deleteDirectory(compareToFile);
        /* Copy the file from assets to local storage */
        fileUtil.copyAssetFile(SketchApplication.getAppContext(), fileInAssets, targetFile);
        return true;
    }

    private static boolean hasSameAssetContent(String fileInAssets, File targetFile) {
        if (!targetFile.exists() || !targetFile.isFile()) {
            return false;
        }
        try (InputStream assetStream = SketchApplication.getAppContext().getAssets().open(fileInAssets);
             FileInputStream targetStream = new FileInputStream(targetFile)) {
            return Arrays.equals(computeSha256(assetStream), computeSha256(targetStream));
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.w(TAG, "Failed to compare extracted asset content: " + fileInAssets, e);
            return false;
        }
    }

    private static byte[] computeSha256(InputStream inputStream) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
        }
        return digest.digest();
    }

    /**
     * Compile resources and log time needed.
     *
     * @throws Exception Thrown when anything goes wrong while compiling resources
     */
    public void compileResources() throws IOException, SimpleException, MissingFileException {
        timestampResourceCompilationStarted = System.currentTimeMillis();
        ResourceCompiler compiler = new ResourceCompiler(
                this,
                aapt2Binary,
                buildAppBundle,
                progressReceiver);
        compiler.compile();
        LogUtil.d(TAG, "Compiling resources took " + (System.currentTimeMillis() - timestampResourceCompilationStarted) + " ms");
    }

    /**
     * Generates ViewBinding Java source files for all layout XML files.
     * Does nothing if ViewBinding is not enabled in project settings.
     *
     * @throws IOException  if reading layout files fails
     * @throws SAXException if parsing layout XML fails
     */
    public void generateViewBinding() throws IOException, SAXException {
        // Compose projects own their UI in Kotlin source and must not generate the XML ViewBinding
        // pipeline (or the AndroidX databinding dependencies it carries).
        if (projectFilePaths.buildConfig.isComposeProject()) {
            return;
        }
        if (settings.getValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING, ProjectSettings.SETTING_GENERIC_VALUE_FALSE)
                .equals(ProjectSettings.SETTING_GENERIC_VALUE_FALSE)) {
            return;
        }
        File outputDirectory = new File(projectFilePaths.javaFilesPath + File.separator + projectFilePaths.packageName.replace(".", File.separator) + File.separator + "databinding");
        outputDirectory.mkdirs();

        List<File> layouts = FileUtil.listFiles(projectFilePaths.layoutFilesPath, "xml").stream()
                .map(File::new)
                .collect(Collectors.toList());

        ViewBindingBuilder builder = new ViewBindingBuilder(layouts, outputDirectory, projectFilePaths.packageName);

        builder.generateBindings();
    }

    /**
     * Checks whether D8 is selected as the dexer (instead of Dx).
     *
     * @return {@code true} if D8 is enabled in build settings
     */
    public boolean isD8Enabled() {
        return buildSettings.getValue(
                BuildSettings.SETTING_DEXER,
                BuildSettings.SETTING_DEXER_DX
        ).equals(BuildSettings.SETTING_DEXER_D8);
    }

    /**
     * Returns a user-facing status message indicating which dexer is running.
     *
     * @return {@code "D8 is running..."} or {@code "Dx is running..."}
     */
    public String getDxRunningText() {
        return (isD8Enabled() ? "D8" : "Dx") + " is running...";
    }

    /**
     * Compile Java classes into DEX file(s)
     *
     * @throws Exception Thrown if the compiler had any problems compiling
     */
    public void createDexFilesFromClasses() throws CompilationFailedException, ReflectiveOperationException, IOException {
        FileUtil.makeDir(projectFilePaths.binDirectoryPath + File.separator + "dex");
        if (proguard.isShrinkingEnabled() && proguard.isR8Enabled()) return;
        File dexOutputDir = new File(projectFilePaths.binDirectoryPath, "dex");
        File[] existingDexFiles = dexOutputDir.exists() ? dexOutputDir.listFiles((dir, name) -> name.endsWith(".dex")) : null;

        if (isD8Enabled()) {
            long savedTimeMillis = System.currentTimeMillis();
            if (!classFilesChanged && existingDexFiles != null && existingDexFiles.length > 0) {
                Log.d(TAG, "Skipping D8: no .class files changed (incremental). Saved ~"
                        + (System.currentTimeMillis() - savedTimeMillis) + " ms");
                if (progressReceiver != null) {
                    progressReceiver.onProgress("DEX is up to date (no changes)", 17);
                }
                return;
            }
            try {
                DexCompiler.compileDexFiles(this);
                Log.d(TAG, "D8 took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
            } catch (CompilationFailedException | RuntimeException e) {
                LogUtil.e(TAG, "D8 failed to process .class files", e);
                throw e;
            }
        } else {
            long savedTimeMillis = System.currentTimeMillis();
            if (!classFilesChanged && existingDexFiles != null && existingDexFiles.length > 0) {
                Log.d(TAG, "Skipping Dx: no .class files changed (incremental). Saved ~"
                        + (System.currentTimeMillis() - savedTimeMillis) + " ms");
                if (progressReceiver != null) {
                    progressReceiver.onProgress("DEX is up to date (no changes)", 17);
                }
                return;
            }
            List<String> args = Arrays.asList(
                    "--debug",
                    "--verbose",
                    "--multi-dex",
                    "--output=" + projectFilePaths.binDirectoryPath + File.separator + "dex",
                    proguard.isShrinkingEnabled() ? projectFilePaths.proguardClassesPath : projectFilePaths.compiledClassesPath
            );

            try {
                Log.d(TAG, "Running Dx with these arguments: " + args);

                Main.clearInternTables();
                Main.Arguments arguments = new Main.Arguments();
                Method parseMethod = Main.Arguments.class.getDeclaredMethod("parse", String[].class);
                parseMethod.setAccessible(true);
                parseMethod.invoke(arguments, (Object) args.toArray(new String[0]));

                Main.run(arguments);
                Log.d(TAG, "Dx took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
            } catch (ReflectiveOperationException | IOException | RuntimeException e) {
                LogUtil.e(TAG, "Dx failed to process .class files", e);
                throw e;
            }
        }
    }

    /**
     * Builds the full classpath string for Java compilation, including:
     * android.jar, HTTP legacy (if enabled), MultiDex (if minSdk &lt; 21),
     * lambda stubs (if Java &gt; 1.7), built-in libraries, local libraries,
     * user-specified classpath, and project classpath JARs.
     *
     * @return colon-separated classpath string
     */
    public String getClasspath() {
        StringBuilder classpath = new StringBuilder();

        /*
         * Add ProjectFilePaths#compiledClassesPath (.sketchware/mysc/xxx/bin/classes) if it exists,
         * since it may already contain compiled Kotlin classes that ECJ should see on the classpath.
         */
        KotlinCompilerBridge.maybeAddKotlinFilesToClasspath(classpath, projectFilePaths);

        /* Add android.jar */
        classpath.append(androidJarPath);

        /* Add HTTP legacy files if wanted */
        if (!buildSettings.getValue(BuildSettings.SETTING_NO_HTTP_LEGACY, BuildSettings.SETTING_GENERIC_VALUE_FALSE)
                .equals(BuildSettings.SETTING_GENERIC_VALUE_TRUE)) {
            classpath.append(":").append(BuiltInLibraries.getLibraryClassesJarPathString(BuiltInLibraries.HTTP_LEGACY_ANDROID));
        }

        /* Include MultiDex library if needed */
        if (settings.getMinSdkVersion() < 21) {
            classpath.append(":").append(BuiltInLibraries.getLibraryClassesJarPathString(BuiltInLibraries.ANDROIDX_MULTIDEX));
        }

        /*
         * Add lambda helper classes
         * Since all versions above java 7 supports lambdas, this should work
         */
        if (!buildSettings.getValue(BuildSettings.SETTING_JAVA_VERSION,
                        BuildSettings.SETTING_JAVA_VERSION_1_7)
                .equals(BuildSettings.SETTING_JAVA_VERSION_1_7)) {
            classpath.append(":").append(new File(BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH, "core-lambda-stubs.jar").getAbsolutePath());
        }

        /* Add used built-in libraries to the classpath */
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            classpath.append(":").append(BuiltInLibraries.getLibraryClassesJarPathString(library.getName()));
        }

        /* Add the separate built-in Jetpack Compose bundle. */
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {
            classpath.append(":").append(ComposeBuiltInLibraries.getLibraryClassesJarPath(artifact.id).getAbsolutePath());
        }

        /* Add local libraries to the classpath */
        classpath.append(localLibraryManager.getJarLocalLibrary());

        /* Append user's custom classpath */
        if (!buildSettings.getValue(BuildSettings.SETTING_CLASSPATH, "").isEmpty()) {
            classpath.append(":").append(buildSettings.getValue(BuildSettings.SETTING_CLASSPATH, ""));
        }

        /* Add JARs from project's classpath */
        String classpathDirectoryPath = SketchwarePaths.getProjectClasspathPath(projectFilePaths.sc_id) + File.separator;
        ArrayList<String> classpathJarPaths = FileUtil.listFiles(classpathDirectoryPath, "jar");
        classpath.append(":").append(TextUtils.join(":", classpathJarPaths));

        return classpath.toString();
    }

    /**
     * @return Similar to {@link ProjectBuilder#getClasspath()}, but doesn't return some local libraries' JARs if ProGuard full mode is enabled
     */
    public String getProguardClasspath() {
        Set<String> localLibraryJarsWithFullModeOn = new HashSet<>();

        for (HashMap<String, Object> localLibrary : localLibraryManager.list) {
            Object nameObject = localLibrary.get("name");
            Object jarPathObject = localLibrary.get("jarPath");

            if (nameObject instanceof String name && jarPathObject instanceof String jarPath) {
                if (proguard.libIsProguardFMEnabled(name)) {
                    localLibraryJarsWithFullModeOn.add(jarPath);
                }
            }
        }

        String normalClasspath = getClasspath();
        StringBuilder classpath = new StringBuilder();
        for (String classpathPart : normalClasspath.split(":")) {
            if (localLibraryJarsWithFullModeOn.contains(classpathPart)) {
                continue;
            }

            if (!classpathPart.equals(projectFilePaths.compiledClassesPath)) {
                classpath.append(classpathPart).append(':');
            }
        }

        // remove trailing delimiter
        classpath.deleteCharAt(classpath.length() - 1);

        return classpath.toString();
    }

    /**
     * Dexes libraries.
     *
     * @return List of result DEX files which were merged or couldn't be merged with others.
     * @throws Exception Thrown if merging had problems
     */
    private Collection<File> dexLibraries(File outputDirectory, List<File> dexes) throws IOException {
        int lastDexNumber = 1;
        Collection<File> resultDexFiles = new LinkedList<>();
        LinkedList<Dex> dexObjects = new LinkedList<>();
        Iterator<File> toMergeIterator = dexes.iterator();

        // Use simple integer counters from DEX headers instead of the previous approach
        // which iterated all FieldId/MethodId/ProtoId objects and used LinkedList.contains().
        // That approach was O(n²) and never actually deduplicated because FieldId/MethodId/ProtoId
        // lack equals()/hashCode() (Object reference equality always returned false),
        // and their internal indices are DEX-specific (not comparable across files).
        int mergedFieldCount;
        int mergedMethodCount;
        int mergedProtoCount;
        int mergedTypeCount;

        {
            File firstFile = toMergeIterator.next();
            Dex firstDex;
            try (FileInputStream fis = new FileInputStream(firstFile)) {
                firstDex = new Dex(fis);
            }
            dexObjects.add(firstDex);
            TableOfContents toc = firstDex.getTableOfContents();
            mergedFieldCount = toc.fieldIds.size;
            mergedMethodCount = toc.methodIds.size;
            mergedProtoCount = toc.protoIds.size;
            mergedTypeCount = toc.typeIds.size;
        }

        while (toMergeIterator.hasNext()) {
            File dexFile = toMergeIterator.next();
            String nextMergedDexFilename = lastDexNumber == 1 ? "classes.dex" : "classes" + lastDexNumber + ".dex";

            Dex dex;
            try (FileInputStream fis = new FileInputStream(dexFile)) {
                dex = new Dex(fis);
            }
            TableOfContents toc = dex.getTableOfContents();

            boolean canMerge = mergedFieldCount + toc.fieldIds.size <= 0xffff
                    && mergedMethodCount + toc.methodIds.size <= 0xffff
                    && mergedProtoCount + toc.protoIds.size <= 0xffff
                    && mergedTypeCount + toc.typeIds.size <= 0xffff;

            if (!canMerge) {
                LogUtil.d(TAG, "Can't merge " + dexFile.getName() + " into " + nextMergedDexFilename
                        + " (fields=" + mergedFieldCount + "+" + toc.fieldIds.size
                        + ", methods=" + mergedMethodCount + "+" + toc.methodIds.size
                        + ", protos=" + mergedProtoCount + "+" + toc.protoIds.size
                        + ", types=" + mergedTypeCount + "+" + toc.typeIds.size + ")");
            }

            if (canMerge) {
                dexObjects.add(dex);
                mergedFieldCount += toc.fieldIds.size;
                mergedMethodCount += toc.methodIds.size;
                mergedProtoCount += toc.protoIds.size;
                mergedTypeCount += toc.typeIds.size;
            } else {
                File target = new File(outputDirectory, nextMergedDexFilename);
                mergeDexes(target, dexObjects);
                resultDexFiles.add(target);
                dexObjects.clear();
                dexObjects.add(dex);

                mergedFieldCount = toc.fieldIds.size;
                mergedMethodCount = toc.methodIds.size;
                mergedProtoCount = toc.protoIds.size;
                mergedTypeCount = toc.typeIds.size;
                lastDexNumber++;
            }
        }
        if (!dexObjects.isEmpty()) {
            File file = new File(outputDirectory, lastDexNumber == 1 ? "classes.dex" : "classes" + lastDexNumber + ".dex");
            mergeDexes(file, dexObjects);
            resultDexFiles.add(file);
        }

        return resultDexFiles;
    }

    /**
     * Get package names of in-use libraries which have resources, separated by <code>:</code>.
     */
    public String getLibraryPackageNames() {
        StringBuilder extraPackages = new StringBuilder();
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            if (library.hasResources()) {
                extraPackages.append(library.getPackageName()).append(":");
            }
        }
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {
            if (artifact.packageName != null && !artifact.packageName.isEmpty()) {
                extraPackages.append(artifact.packageName).append(":");
            }
        }
        return extraPackages + localLibraryManager.getPackageNameLocalLibrary();
    }

    /** Names of the local libraries this project activated, roots and expanded sub-dependencies. */
    private Set<String> activatedLocalLibraryNames() {
        Set<String> names = new HashSet<>();
        if (localLibraryManager == null || localLibraryManager.list == null) {
            return names;
        }
        for (HashMap<String, Object> library : localLibraryManager.list) {
            if (library == null) continue;
            Object name = library.get("name");
            if (name instanceof String) names.add((String) name);
        }
        return names;
    }

    /** Returns the selected artifact closure from the separate Compose bundle. */
    public List<ComposeBuiltInLibraries.ComposeArtifact> getSelectedComposeArtifacts() {
        // Every consumer of this list reads it to place the legacy bundle's jars, resources and DEX on
        // the build; with no bundle configured there is nothing to place, and asking for the manifest
        // would throw. An empty list is the honest answer, and the Jetpack store covers the project.
        if (!projectFilePaths.buildConfig.isComposeEnabled || !ComposeBuiltInLibraries.isBundleAvailable()) {
            return java.util.Collections.emptyList();
        }
        return ComposeBuiltInLibraries.getSelectedArtifacts(projectFilePaths.buildConfig.composeOptionalFeatures);
    }

    /**
     * Compiles the project's Java sources using Eclipse JDT (ECJ).
     * <p>
     * Uses incremental compilation when possible:
     * <ul>
     *   <li>Skips ECJ entirely if no Java files changed and R.java is unchanged.</li>
     *   <li>Compiles only the changed Activity files when the classpath and R.java are stable.</li>
     *   <li>Falls back to a full recompile when R.java changed, the classpath changed,
     *       user-written Java files changed, or no previous {@code .class} cache exists.</li>
     * </ul>
     */
    public void compileJavaCode() throws SimpleException, IOException {
        long savedTimeMillis = System.currentTimeMillis();

        // Feature-aware build: only the Java pipeline runs when the project actually contains Java
        // sources. Compose projects compile Kotlin + R.java, and Kotlin-only projects should not
        // pay for an ECJ pass just because they happen to share the generated source directory.
        if (!hasAnyJavaSources()) {
            File classesDir = new File(projectFilePaths.compiledClassesPath);
            boolean hasStaleClasses = classesDir.exists()
                    && !FileUtil.listFilesRecursively(classesDir, ".class").isEmpty();
            if (!hasStaleClasses) {
                classFilesChanged = false;
                Log.d(TAG, "No Java source files present, skipping ECJ compilation");
                if (progressReceiver != null) {
                    progressReceiver.onProgress("Java compilation skipped (no Java sources)", 13);
                }
                return;
            }
        }

        IncrementalBuildCache cache = preloadedBuildCache != null
                ? preloadedBuildCache
                : new IncrementalBuildCache(projectFilePaths.binDirectoryPath);
        if (preloadedBuildCache == null) cache.load();

        String currentClasspath = getClasspath();
        boolean classesExist = new File(projectFilePaths.compiledClassesPath).exists()
                && !FileUtil.listFilesRecursively(new File(projectFilePaths.compiledClassesPath), ".class").isEmpty();
        boolean cacheFileExists = cache.hasCacheFile();
        boolean proguardShrinkingEnabled = proguard.isShrinkingEnabled();
        boolean classpathChanged = cache.isClasspathChanged(currentClasspath);
        boolean cacheMigrationRequired = cache.requiresFullRebuildMigration();

        boolean canIncremental = classesExist
                && cacheFileExists
                && !proguardShrinkingEnabled
                && !classpathChanged
                && !cacheMigrationRequired;

        Log.d(TAG, "Incremental compile precheck: canIncremental=" + canIncremental
                + ", classesExist=" + classesExist
                + ", cacheFileExists=" + cacheFileExists
                + ", proguardShrinkingEnabled=" + proguardShrinkingEnabled
                + ", classpathChanged=" + classpathChanged
                + ", cacheMigrationRequired=" + cacheMigrationRequired
                + ", classpathHash=" + Integer.toHexString(currentClasspath.hashCode())
                + ", classpathLength=" + currentClasspath.length());

        if (!canIncremental) {
            Log.d(TAG, "Incremental build not possible, doing full ECJ recompile"
                    + " (classesExist=" + classesExist
                    + ", cacheFileExists=" + cacheFileExists
                    + ", proguardShrinkingEnabled=" + proguardShrinkingEnabled
                    + ", classpathChanged=" + classpathChanged
                    + ", cacheMigrationRequired=" + cacheMigrationRequired + ")");
            runEclipseCompiler(collectAllSourcePaths(), currentClasspath, savedTimeMillis);
            updateCacheAfterSuccessfulBuild(cache, currentClasspath);
            return;
        }

        List<File> allJavaFiles = FileUtil.listFilesRecursively(
                new File(projectFilePaths.javaFilesPath), ".java");
        List<File> customJavaFiles = new ArrayList<>();
        for (String customDir : getCustomJavaDirectories()) {
            if (FileUtil.isExistFile(customDir)) {
                customJavaFiles.addAll(FileUtil.listFilesRecursively(new File(customDir), ".java"));
            }
        }

        Set<String> currentJavaPaths = new HashSet<>();
        for (File f : allJavaFiles) currentJavaPaths.add(f.getAbsolutePath());
        for (File f : customJavaFiles) currentJavaPaths.add(f.getAbsolutePath());

        List<String> stalePaths = new ArrayList<>();
        for (String cachedPath : cache.getAllCachedFilePaths()) {
            boolean generatedSourceDeleted = isPathWithin(cachedPath, projectFilePaths.javaFilesPath)
                    && !currentJavaPaths.contains(cachedPath);
            boolean customSourceDeleted = isCustomJavaSourcePath(cachedPath)
                    && !currentJavaPaths.contains(cachedPath);
            if (generatedSourceDeleted || customSourceDeleted) {
                deleteOldClassFiles(cachedPath, cache);
                stalePaths.add(cachedPath);
                Log.d(TAG, (customSourceDeleted ? "Custom Java source deleted: " : "Generated Java source deleted: ")
                        + new File(cachedPath).getName()
                        + " —doing full ECJ recompile to validate remaining references safely");
            }
        }
        for (String p : stalePaths) cache.removeFromCache(p);

        List<File> dirtyCustomJavaFiles = new ArrayList<>();
        for (File customJavaFile : customJavaFiles) {
            if (cache.isDirtyFile(customJavaFile)) {
                dirtyCustomJavaFiles.add(customJavaFile);
            }
        }

        List<String> dirtyFilePaths = new ArrayList<>();
        for (File javaFile : allJavaFiles) {
            if (cache.isDirtyFile(javaFile)) {
                dirtyFilePaths.add(javaFile.getAbsolutePath());
            }
        }

        boolean rJavaChanged = cache.isRJavaChanged(projectFilePaths.rJavaDirectoryPath);
        Log.d(TAG, "Incremental compile checkpoint: rJavaChanged=" + rJavaChanged
                + ", rJavaDir=" + projectFilePaths.rJavaDirectoryPath);
        if (rJavaChanged || !dirtyCustomJavaFiles.isEmpty() || !stalePaths.isEmpty()) {
            String appRJavaRelativePath = projectFilePaths.packageNameAsFolders + File.separator + "R.java";
            boolean appRJavaChanged = cache.isRJavaFileChanged(projectFilePaths.rJavaDirectoryPath, appRJavaRelativePath);
            ArrayList<String> rJavaChanges = cache.describeRJavaChanges(projectFilePaths.rJavaDirectoryPath, 20);
            Log.d(TAG, "Incremental compile checkpoint: appRJavaChanged=" + appRJavaChanged
                    + ", appRJavaPath=" + appRJavaRelativePath);
            Log.d(TAG, "Incremental compile checkpoint: rJavaChanges=" + rJavaChanges);
            if (rJavaChanged) {
                Log.d(TAG, "R.java changed —resource IDs may have been reassigned, doing full ECJ recompile");
            }
            if (!dirtyCustomJavaFiles.isEmpty()) {
                Log.d(TAG, "User custom Java files changed: " + dirtyCustomJavaFiles.size()
                        + " —doing full ECJ recompile");
            }
            if (!stalePaths.isEmpty()) {
                Log.d(TAG, "Java source set changed: removed " + stalePaths.size()
                        + " source file(s) —doing full ECJ recompile");
            }
            for (String dirtyFilePath : dirtyFilePaths) {
                deleteOldClassFiles(dirtyFilePath, cache);
            }
            for (File dirtyCustomJavaFile : dirtyCustomJavaFiles) {
                deleteOldClassFiles(dirtyCustomJavaFile.getAbsolutePath(), cache);
            }
            runEclipseCompiler(collectAllSourcePaths(), currentClasspath, savedTimeMillis);
            updateCacheAfterSuccessfulBuild(cache, currentClasspath);
            return;
        }

        if (dirtyFilePaths.isEmpty()) {
            classFilesChanged = false;
            Log.d(TAG, "Incremental build: no Java files changed, skipping ECJ entirely. Saved ~"
                    + (System.currentTimeMillis() - savedTimeMillis) + " ms");
            if (progressReceiver != null) {
                progressReceiver.onProgress("Java is up to date (incremental build, no changes)", 13);
            }
            return;
        }

        for (String dirtyFilePath : dirtyFilePaths) {
            deleteOldClassFiles(dirtyFilePath, cache);
        }
        Log.d(TAG, "Incremental build: compiling " + dirtyFilePaths.size() + " changed file(s) out of " + allJavaFiles.size());
        if (progressReceiver != null) {
            progressReceiver.onProgress("Java is compiling... (incremental: " + dirtyFilePaths.size()
                    + " of " + allJavaFiles.size() + " file(s) changed)", 13);
        }
        dirtyFilePaths.add(projectFilePaths.rJavaDirectoryPath);
        String incrementalClasspath = projectFilePaths.compiledClassesPath + ":" + currentClasspath;
        runEclipseCompiler(dirtyFilePaths, incrementalClasspath, savedTimeMillis);
        updateCacheAfterSuccessfulBuild(cache, currentClasspath);
    }

    /**
     * Invokes the Eclipse JDT batch compiler with the given source paths and classpath.
     * Throws {@link SimpleException} on any compilation error.
     */
    private void runEclipseCompiler(List<String> sourcePaths, String classpath, long startTime)
            throws SimpleException, IOException {

        class EclipseOutOutputStream extends OutputStream {
            private final StringBuilder mBuffer = new StringBuilder();
            @Override public void write(int b) { mBuffer.append((char) b); }
            String getOut() { return mBuffer.toString(); }
        }
        class EclipseErrOutputStream extends OutputStream {
            private final StringBuilder mBuffer = new StringBuilder();
            @Override public void write(int b) { mBuffer.append((char) b); }
            String getOut() { return mBuffer.toString(); }
        }

        try (EclipseOutOutputStream outOutputStream = new EclipseOutOutputStream();
             PrintWriter outWriter = new PrintWriter(outOutputStream);
             EclipseErrOutputStream errOutputStream = new EclipseErrOutputStream();
             PrintWriter errWriter = new PrintWriter(errOutputStream)) {

            ArrayList<String> args = new ArrayList<>();
            args.add("-" + buildSettings.getValue(BuildSettings.SETTING_JAVA_VERSION,
                    BuildSettings.SETTING_JAVA_VERSION_1_7));
            args.add("-nowarn");
            if (!buildSettings.getValue(BuildSettings.SETTING_NO_WARNINGS,
                    BuildSettings.SETTING_GENERIC_VALUE_TRUE).equals(BuildSettings.SETTING_GENERIC_VALUE_TRUE)) {
                args.add("-deprecation");
            }
            args.add("-d");
            args.add(projectFilePaths.compiledClassesPath);
            args.add("-cp");
            args.add(classpath);
            args.add("-proc:none");
            args.addAll(sourcePaths);

            /* Avoid "package ;" line in that file causing issues while compiling */
            File rJavaFileWithoutPackage = new File(projectFilePaths.rJavaDirectoryPath, "R.java");
            if (rJavaFileWithoutPackage.exists() && !rJavaFileWithoutPackage.delete()) {
                LogUtil.w(TAG, "Failed to delete file " + rJavaFileWithoutPackage.getAbsolutePath());
            }

            org.eclipse.jdt.internal.compiler.batch.Main main =
                    new org.eclipse.jdt.internal.compiler.batch.Main(outWriter, errWriter, false, null, null);
            LogUtil.d(TAG, "Running Eclipse compiler with these arguments: " + args);
            main.compile(args.toArray(new String[0]));

            LogUtil.d(TAG, "System.out of Eclipse compiler: " + outOutputStream.getOut());
            if (main.globalErrorsCount <= 0) {
                LogUtil.d(TAG, "System.err of Eclipse compiler: " + errOutputStream.getOut());
                LogUtil.d(TAG, "Compiling Java files took " + (System.currentTimeMillis() - startTime) + " ms");
            } else {
                LogUtil.e(TAG, "Failed to compile Java files");
                throw new SimpleException(errOutputStream.getOut());
            }
        }
    }

    private List<String> collectAllSourcePaths() {
        List<String> paths = new ArrayList<>();
        paths.add(projectFilePaths.javaFilesPath);
        paths.add(projectFilePaths.rJavaDirectoryPath);
        String pathJava = SketchwarePaths.getProjectJavaPath(projectFilePaths.sc_id);
        if (FileUtil.isExistFile(pathJava)) paths.add(pathJava);
        String pathBroadcast = SketchwarePaths.getProjectBroadcastPath(projectFilePaths.sc_id);
        if (FileUtil.isExistFile(pathBroadcast)) paths.add(pathBroadcast);
        String pathService = SketchwarePaths.getProjectServicePath(projectFilePaths.sc_id);
        if (FileUtil.isExistFile(pathService)) paths.add(pathService);
        return paths;
    }

    private void updateCacheAfterSuccessfulBuild(IncrementalBuildCache cache, String classpath) {
        cache.clearTrackedJavaSources();
        for (File f : FileUtil.listFilesRecursively(new File(projectFilePaths.javaFilesPath), ".java")) {
            cache.markFileClean(f, getCurrentCompiledClassBasePath(f));
        }
        for (String customDir : getCustomJavaDirectories()) {
            if (FileUtil.isExistFile(customDir)) {
                for (File f : FileUtil.listFilesRecursively(new File(customDir), ".java")) {
                    cache.markFileClean(f, getCurrentCompiledClassBasePath(f));
                }
            }
        }
        cache.storeClasspath(classpath);
        cache.storeRJavaHash(projectFilePaths.rJavaDirectoryPath);
        cache.save();
    }

    private List<String> getCustomJavaDirectories() {
        List<String> dirs = new ArrayList<>();
        dirs.add(SketchwarePaths.getProjectJavaPath(projectFilePaths.sc_id));
        dirs.add(SketchwarePaths.getProjectBroadcastPath(projectFilePaths.sc_id));
        dirs.add(SketchwarePaths.getProjectServicePath(projectFilePaths.sc_id));
        return dirs;
    }

    /** @return true when the project contains at least one .java source in the build tree. */
    private boolean hasAnyJavaSources() {
        if (FileUtil.isExistFile(projectFilePaths.javaFilesPath)
                && !FileUtil.listFilesRecursively(new File(projectFilePaths.javaFilesPath), ".java").isEmpty()) {
            return true;
        }
        if (FileUtil.isExistFile(projectFilePaths.rJavaDirectoryPath)
                && !FileUtil.listFilesRecursively(new File(projectFilePaths.rJavaDirectoryPath), ".java").isEmpty()) {
            return true;
        }
        for (String customDir : getCustomJavaDirectories()) {
            if (FileUtil.isExistFile(customDir)
                    && !FileUtil.listFilesRecursively(new File(customDir), ".java").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isCustomJavaSourcePath(String absolutePath) {
        for (String customDir : getCustomJavaDirectories()) {
            if (isPathWithin(absolutePath, customDir)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPathWithin(String absolutePath, String rootPath) {
        return absolutePath.equals(rootPath) || absolutePath.startsWith(rootPath + File.separator);
    }

    /**
     * Deletes all {@code .class} files associated with the given {@code .java} source file
     * (including inner-class files like {@code Foo$1.class}) before recompiling that file,
     * so that stale inner-class files cannot accumulate in the output directory.
     */
    private void deleteOldClassFiles(String sourcePath, IncrementalBuildCache cache) {
        for (String classRel : getCompiledClassBasePathCandidates(sourcePath, cache)) {
            int lastSep = classRel.lastIndexOf(File.separator);
            File classDir = lastSep >= 0
                    ? new File(projectFilePaths.compiledClassesPath + File.separator + classRel.substring(0, lastSep))
                    : new File(projectFilePaths.compiledClassesPath);
            String baseName = lastSep >= 0 ? classRel.substring(lastSep + 1) : classRel;

            if (!classDir.exists()) continue;
            String topLevelClassName = baseName + ".class";
            String innerClassPrefix = baseName + "$";
            File[] toDelete = classDir.listFiles(
                    f -> {
                        String fileName = f.getName();
                        return fileName.equals(topLevelClassName)
                                || (fileName.startsWith(innerClassPrefix) && fileName.endsWith(".class"));
                    });
            if (toDelete != null) {
                for (File f : toDelete) {
                    if (!f.delete()) LogUtil.w(TAG, "Could not delete stale class file: " + f.getAbsolutePath());
                }
            }
        }
    }

    private List<String> getCompiledClassBasePathCandidates(String sourcePath, IncrementalBuildCache cache) {
        ArrayList<String> candidates = new ArrayList<>(3);
        addCompiledClassBasePathCandidate(candidates, cache.getStoredCompiledClassBasePath(sourcePath));
        addCompiledClassBasePathCandidate(candidates, getCurrentCompiledClassBasePath(new File(sourcePath)));
        addCompiledClassBasePathCandidate(candidates, getSourcePathDerivedClassBasePath(sourcePath));
        return candidates;
    }

    private void addCompiledClassBasePathCandidate(List<String> candidates, String candidate) {
        if (candidate != null && !candidate.isEmpty() && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private String getCurrentCompiledClassBasePath(File javaFile) {
        if (!javaFile.exists() || !javaFile.getName().endsWith(".java")) {
            return null;
        }

        String simpleName = javaFile.getName();
        simpleName = simpleName.substring(0, simpleName.length() - 5);
        String packageName = extractPackageName(FileUtil.readFile(javaFile.getAbsolutePath()));
        if (packageName.isEmpty()) {
            return simpleName;
        }
        return packageName.replace(".", File.separator) + File.separator + simpleName;
    }

    private String extractPackageName(String sourceCode) {
        boolean inBlockComment = false;
        for (String line : sourceCode.split("\\R")) {
            String trimmedLine = line.replace("\uFEFF", "").trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }

            if (inBlockComment) {
                int blockCommentEndIndex = trimmedLine.indexOf("*/");
                if (blockCommentEndIndex < 0) {
                    continue;
                }
                trimmedLine = trimmedLine.substring(blockCommentEndIndex + 2).trim();
                inBlockComment = false;
                if (trimmedLine.isEmpty()) {
                    continue;
                }
            }

            while (trimmedLine.startsWith("/*")) {
                int blockCommentEndIndex = trimmedLine.indexOf("*/", 2);
                if (blockCommentEndIndex < 0) {
                    inBlockComment = true;
                    trimmedLine = "";
                    break;
                }
                trimmedLine = trimmedLine.substring(blockCommentEndIndex + 2).trim();
            }

            if (trimmedLine.isEmpty() || trimmedLine.startsWith("//") || trimmedLine.startsWith("*")) {
                continue;
            }

            if (trimmedLine.startsWith("package ")) {
                int semicolonIndex = trimmedLine.indexOf(';');
                String packageName = semicolonIndex >= 0
                        ? trimmedLine.substring("package ".length(), semicolonIndex).trim()
                        : trimmedLine.substring("package ".length()).trim();
                return packageName;
            }
            break;
        }
        return "";
    }

    private String getSourcePathDerivedClassBasePath(String absolutePath) {
        String base = getManagedJavaSourceRoot(absolutePath);
        if (base == null) {
            return null;
        }

        String rel = absolutePath.substring(base.length());
        if (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.endsWith(".java") ? rel.substring(0, rel.length() - 5) : rel;
    }

    private String getManagedJavaSourceRoot(String absolutePath) {
        if (isPathWithin(absolutePath, projectFilePaths.javaFilesPath)) {
            return projectFilePaths.javaFilesPath;
        }
        for (String customDir : getCustomJavaDirectories()) {
            if (isPathWithin(absolutePath, customDir)) {
                return customDir;
            }
        }
        return null;
    }

    /**
     * Assembles the unsigned, unaligned APK from compiled resources, DEX files,
     * native libraries, and library JARs.
     *
     * @throws SketchwareException if APK assembly fails (e.g. duplicate files)
     */
    public void buildApk() throws SketchwareException {
        long savedTimeMillis = System.currentTimeMillis();
        String firstDexPath = dexesToAddButNotMerge.isEmpty() ? projectFilePaths.classesDexPath : dexesToAddButNotMerge.remove(0).getAbsolutePath();
        try {
            ApkBuilder apkBuilder = new ApkBuilder(new File(projectFilePaths.unsignedUnalignedApkPath), new File(projectFilePaths.resourcesApkPath), new File(firstDexPath), null, null, System.out);

            for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
                apkBuilder.addResourcesFromJar(BuiltInLibraries.getLibraryClassesJarPath(library.getName()));
            }

            for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {
                apkBuilder.addResourcesFromJar(ComposeBuiltInLibraries.getLibraryClassesJarPath(artifact.id));
            }

            for (String jarPath : localLibraryManager.getJarLocalLibrary().split(":")) {
                if (!jarPath.trim().isEmpty()) {
                    apkBuilder.addResourcesFromJar(new File(jarPath));
                }
            }

            /* Add project's native libraries */
            File nativeLibrariesDirectory = new File(SketchwarePaths.getProjectNativeLibsPath(projectFilePaths.sc_id));
            if (nativeLibrariesDirectory.exists()) {
                apkBuilder.addNativeLibraries(nativeLibrariesDirectory);
            }

            /* Add Local libraries' native libraries */
            for (String nativeLibraryDirectory : localLibraryManager.getNativeLibs()) {
                apkBuilder.addNativeLibraries(new File(nativeLibraryDirectory));
            }

            if (dexesToAddButNotMerge.isEmpty()) {
                List<String> dexFiles = FileUtil.listFiles(projectFilePaths.binDirectoryPath, "dex");
                for (String dexFile : dexFiles) {
                    String dexFileName = new File(dexFile).getName();
                    if (!dexFileName.equals("classes.dex")) {
                        apkBuilder.addFile(new File(dexFile), dexFileName);
                    }
                }
            } else {
                int dexNumber = 2;

                for (File dexFile : dexesToAddButNotMerge) {
                    apkBuilder.addFile(dexFile, "classes" + dexNumber + ".dex");
                    dexNumber++;
                }
            }

            apkBuilder.setDebugMode(false);
            apkBuilder.sealApk();
        } catch (ApkCreationException | SealedApkException e) {
            throw new SketchwareException(e.getMessage());
        } catch (DuplicateFileException e) {
            String message = "Duplicate files from two libraries detected \r\n";
            message += "File1: " + e.getFile1() + " \r\n";
            message += "File2: " + e.getFile2() + " \r\n";
            message += "Archive path: " + e.getArchivePath();
            throw new SketchwareException(message);
        }
        Log.d(TAG, "Building APK took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
        Log.d(TAG, "Time passed since starting to compile resources until building the unsigned APK: " +
                (System.currentTimeMillis() - timestampResourceCompilationStarted) + " ms");
    }

    /**
     * Either merges DEX files to as few as possible, or adds list of DEX files to add to the APK to
     * {@link #dexesToAddButNotMerge}.
     * <p>
     * Will merge DEX files if either the project's minSdkVersion is lower than 21, or if {@link BuildConfig#isDebugBuild}
     * of {@link ProjectFilePaths#N} in {@link #ProjectFilePaths} is false.
     *
     * @throws Exception Thrown if merging failed
     */
    public void getDexFilesReady() throws IOException {
        long savedTimeMillis = System.currentTimeMillis();
        ArrayList<File> dexes = new ArrayList<>();

        /* Add AndroidX MultiDex library if needed */
        if (settings.getMinSdkVersion() < 21) {
            dexes.add(BuiltInLibraries.getLibraryDexFile(BuiltInLibraries.ANDROIDX_MULTIDEX));
        }

        /* Add HTTP legacy files if wanted */
        if (!buildSettings.getValue(BuildSettings.SETTING_NO_HTTP_LEGACY, ProjectSettings.SETTING_GENERIC_VALUE_FALSE)
                .equals(ProjectSettings.SETTING_GENERIC_VALUE_TRUE)) {
            dexes.add(BuiltInLibraries.getLibraryDexFile(BuiltInLibraries.HTTP_LEGACY_ANDROID));
        }

        /* Add used built-in libraries' DEX files */
        for (BuiltInLibrary builtInLibrary : builtInLibraryManager.getLibraries()) {
            dexes.add(BuiltInLibraries.getLibraryDexFile(builtInLibrary.getName()));
        }

        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {
            dexes.add(ComposeBuiltInLibraries.getLibraryDexFile(artifact.id));
        }

        /* Add local libraries' main DEX files */
        ArrayList<HashMap<String, Object>> list = localLibraryManager.list;
        for (int localLibIdx = 0, listSize = list.size(); localLibIdx < listSize; localLibIdx++) {
            HashMap<String, Object> localLibrary = list.get(localLibIdx);
            Object localLibraryName = localLibrary.get("name");

            if (localLibraryName instanceof String) {
                Object localLibraryDexPath = localLibrary.get("dexPath");

                if (localLibraryDexPath instanceof String) {
                    if (!proguard.libIsProguardFMEnabled((String) localLibraryName)) {
                        dexes.add(new File((String) localLibraryDexPath));
                        /* Add library's extra DEX files */
                        File localLibraryDirectory = new File((String) localLibraryDexPath).getParentFile();

                        if (localLibraryDirectory != null) {
                            File[] localLibraryFiles = localLibraryDirectory.listFiles();

                            if (localLibraryFiles != null) {
                                for (File localLibraryFile : localLibraryFiles) {
                                    String filename = localLibraryFile.getName();

                                    if (!filename.equals("classes.dex")
                                            && filename.startsWith("classes") && filename.endsWith(".dex")) {
                                        dexes.add(localLibraryFile);
                                    }
                                }
                            }
                        }
                    }
                } else {
                    SketchwareUtil.toastError(String.format(Helper.getResString(R.string.error_invalid_dex_path), localLibIdx), Toast.LENGTH_LONG);
                }
            } else {
                SketchwareUtil.toastError(String.format(Helper.getResString(R.string.error_invalid_lib_name), localLibIdx), Toast.LENGTH_LONG);
            }
        }

        for (String dexFilePath : FileUtil.listFiles(projectFilePaths.binDirectoryPath + File.separator + "dex", "dex")) {
            dexes.add(new File(dexFilePath));
        }

        LogUtil.d(TAG, "Will merge these " + dexes.size() + " DEX files to classes.dex: " + dexes);

        if (settings.getMinSdkVersion() < 21 || !projectFilePaths.buildConfig.isDebugBuild) {
            // Cache: skip merge if all input DEX files are unchanged
            String dexFingerprint = computeDexMergeFingerprint(dexes);
            File fingerprintFile = new File(projectFilePaths.binDirectoryPath, "dex_merge_fingerprint");
            File mergedClassesDex = new File(projectFilePaths.binDirectoryPath, "classes.dex");
            if (mergedClassesDex.exists() && fingerprintFile.exists()) {
                try {
                    String cached = new String(java.nio.file.Files.readAllBytes(fingerprintFile.toPath()));
                    if (dexFingerprint.equals(cached)) {
                        Log.d(TAG, "Skipping DEX merge: all input DEX files unchanged (cached). Saved ~"
                                + (System.currentTimeMillis() - savedTimeMillis) + " ms");
                        if (progressReceiver != null) {
                            progressReceiver.onProgress("DEX merge is up to date (cached)", 18);
                        }
                        return;
                    }
                } catch (IOException e) {
                    Log.d(TAG, "Failed to read DEX merge fingerprint, will re-merge: " + e.getMessage());
                }
            }
            dexLibraries(new File(projectFilePaths.binDirectoryPath), dexes);
            // Save fingerprint after successful merge
            try {
                java.nio.file.Files.write(fingerprintFile.toPath(), dexFingerprint.getBytes());
            } catch (IOException e) {
                Log.d(TAG, "Failed to save DEX merge fingerprint: " + e.getMessage());
            }
            Log.d(TAG, "Merging DEX files took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
        } else {
            dexesToAddButNotMerge = dexes;
            Log.d(TAG, "Skipped merging DEX files due to debug build with minSdkVersion >= 21");
        }
    }

    /**
     * Verifies that the AAPT2 native library ({@code libaapt2.so}) is present and executable.
     * <p>
     * {@code libaapt2.so} is not a JNI shared library — it is the AAPT2 executable shipped in
     * {@code jniLibs/<abi>/}. It is run with {@link ProcessBuilder} from
     * {@link ApplicationInfo#nativeLibraryDir}, which the package installer only populates when
     * native libraries are extracted from the APK. If the app was packaged with compressed
     * native libraries (the AGP default for {@code minSdk >= 23}) the directory is empty and the
     * build cannot start.
     *
     * @throws SketchwareException If the AAPT2 binary is missing or not executable
     */
    public void maybeExtractAapt2() throws SketchwareException {
        if (!aapt2Binary.exists()) {
            throw new SketchwareException(describeMissingAapt2());
        }
        if (aapt2Binary.length() == 0) {
            throw new SketchwareException("AAPT2 binary at " + aapt2Binary.getAbsolutePath()
                    + " is empty (0 bytes). The installed APK is damaged - reinstall Sketchware Pro.");
        }
        aapt2Binary.setExecutable(true, false);
        aapt2Binary.setReadable(true, false);
        try {
            Os.chmod(aapt2Binary.getAbsolutePath(), S_IRUSR | S_IWUSR | S_IXUSR);
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to chmod AAPT2 binary with Os.chmod", e);
        }
        if (!aapt2Binary.canExecute()) {
            throw new SketchwareException("AAPT2 binary at " + aapt2Binary.getAbsolutePath()
                    + " could not be made executable.");
        }
    }

    /**
     * Builds a diagnostic message for a missing AAPT2 binary, listing the device ABIs and
     * whatever the installer actually placed in the native library directory. This turns an
     * opaque "file not found" into something that points at the real cause (native libraries
     * not extracted, or no {@code jniLibs} entry for this device's ABI).
     */
    private String describeMissingAapt2() {
        StringBuilder message = new StringBuilder("AAPT2 binary is missing at ")
                .append(aapt2Binary.getAbsolutePath());

        File nativeLibraryDirectory = aapt2Binary.getParentFile();
        String[] presentLibraries = nativeLibraryDirectory == null
                ? null
                : nativeLibraryDirectory.list();

        if (nativeLibraryDirectory != null && !nativeLibraryDirectory.exists()) {
            message.append(". The native library directory does not exist");
        } else if (presentLibraries == null || presentLibraries.length == 0) {
            message.append(". The native library directory is empty, which means the installer did not "
                    + "extract native libraries from the APK");
        } else {
            message.append(". The native library directory contains: ")
                    .append(TextUtils.join(", ", presentLibraries));
        }

        message.append(". Device ABIs: ")
                .append(TextUtils.join(", ", android.os.Build.SUPPORTED_ABIS))
                .append(". Reinstall Sketchware Pro using the APK built for this device's ABI, "
                        + "and make sure it was not installed from a stripped or re-signed (split) APK.");

        return message.toString();
    }

    /**
     * Populates the built-in library manager based on project configuration flags
     * (AppCompat, Firebase, Maps, AdMob, Gson, Glide, OkHttp, Kotlin, etc.).
     * This must be called before compilation so that the classpath includes
     * all required library JARs.
     */
    public void buildBuiltInLibraryInformation() {
        /* The legacy ZIP-and-JSON bundle is optional now that the shared Jetpack store exists: an
           artifact installed there reaches this build as a local library instead. Asking the bundle
           manager to extract a package nobody selected would abort the build with "no dependency package
           is configured" for a project that is wired correctly, so the whole legacy path is taken only
           when a package really is configured. */
        if (projectFilePaths.buildConfig.isComposeEnabled && ComposeBuiltInLibraries.isBundleAvailable()) {
            ComposeBuiltInLibraries.ensureExtracted();
        }

        // The AppCompat + Material component stack is the XML UI system. Compose projects own their
        // UI in the Compose runtime and must not pull in the unused XML UI libraries just because a
        // stray flag is set.
        if (projectFilePaths.buildConfig.isAppCompatEnabled
                && !projectFilePaths.buildConfig.isComposeProject()) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.ANDROIDX_APPCOMPAT);
            builtInLibraryManager.addLibrary(BuiltInLibraries.ANDROIDX_COORDINATORLAYOUT);
            builtInLibraryManager.addLibrary(BuiltInLibraries.MATERIAL);
        }
        if (projectFilePaths.buildConfig.isFirebaseEnabled) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_COMMON);
        }
        if (projectFilePaths.buildConfig.isFirebaseAuthUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_AUTH);
        }
        if (projectFilePaths.buildConfig.isFirebaseDatabaseUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_DATABASE);
        }
        if (projectFilePaths.buildConfig.isFirebaseStorageUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_STORAGE);
        }
        if (projectFilePaths.buildConfig.isMapUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.PLAY_SERVICES_MAPS);
        }
        if (projectFilePaths.buildConfig.isAdMobEnabled) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.PLAY_SERVICES_ADS);
        }
        if (projectFilePaths.buildConfig.isGsonUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.GSON);
        }
        if (projectFilePaths.buildConfig.isGlideUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.GLIDE);
        }
        if (projectFilePaths.buildConfig.isHttp3Used) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.OKHTTP_ANDROID);
        }

        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {
            // Validation is intentionally lightweight; the bundle contains all transitive artifacts.
            if (!ComposeBuiltInLibraries.getLibraryClassesJarPath(artifact.id).exists()) {
                throw new IllegalStateException("Missing built-in Compose artifact: " + artifact.id);
            }
        }

        KotlinCompilerBridge.maybeAddKotlinBuiltInLibraryDependenciesIfPossible(this, builtInLibraryManager);

        if (projectFilePaths.buildConfig.isComposeEnabled) {
            // Compose's AndroidUiDispatcher and window recomposer live in kotlinx-coroutines, and the
            // app ships that runtime for every Kotlin project — so a Compose project must get it even
            // when it did not install a runtime copy into the Jetpack store. A newer store copy replaces
            // this one below in applyRuntimeOverrides().
            builtInLibraryManager.addLibrary(BuiltInLibraries.JETBRAINS_KOTLINX_COROUTINES_ANDROID);
        }

        /* An artifact in the shared Jetpack store can deliberately carry a newer copy of a runtime the
           app also ships (kotlin-stdlib, kotlinx-coroutines). Only one version of each type may reach the
           DEX, and the merge keeps the first, so the copy this project activated wins and the built-in
           steps aside instead of shadowing it. When the built-in is the newer one the store copy is
           dropped from the project's library list instead — two copies of one runtime is how a Compose
           activity dies inside CoroutineContext. */
        Set<String> supersededStoreRuntimes = JetpackLibs.applyRuntimeOverrides(
                builtInLibraryManager, activatedLocalLibraryNames());
        if (!supersededStoreRuntimes.isEmpty()) {
            localLibraryManager.list.removeIf(library ->
                    supersededStoreRuntimes.contains(String.valueOf(library.get("name"))));
        }

        ExtLibSelected.addUsedDependencies(projectFilePaths.buildConfig.constVarComponent, builtInLibraryManager);
    }

    /**
     * Returns the built-in library manager used for this build.
     *
     * @return the built-in library manager instance
     */
    public BuiltInLibraryManager getBuiltInLibraryManager() {
        return builtInLibraryManager;
    }

    /**
     * Sign the debug APK file with testkey.
     * <p>
     * This method uses apksigner, but kellinwood's zipsigner as fallback.
     */
    public void signDebugApk() throws GeneralSecurityException, IOException, ClassNotFoundException, IllegalAccessException, InstantiationException {
        long savedTimeMillis = System.currentTimeMillis();
        TestkeySignBridge.signWithTestkey(projectFilePaths.unsignedUnalignedApkPath, projectFilePaths.finalToInstallApkPath);
        Log.d(TAG, "Signing debug APK took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    /**
     * Sign the release APK file with the given keystore credentials.
     * Zipaligns the unsigned APK first (if not already aligned), signs using {@link ApkSigner},
     * writes to {@link ProjectFilePaths#releaseApkPath}, and copies to {@link ProjectFilePaths#finalToInstallApkPath}.
     *
     * @param keystorePath Keystore file path
     * @param keystorePassword Keystore password
     * @param keyAlias Keystore key alias
     * @param keyPassword Key password (falls back to keystorePassword if null or empty)
     * @throws Exception if signing fails
     */
    public void signReleaseApk(String keystorePath, String keystorePassword, String keyAlias, String keyPassword) throws Exception {
        long savedTimeMillis = System.currentTimeMillis();

        File keystoreFile = new File(keystorePath);
        if (!keystoreFile.exists()) {
            throw new FileNotFoundException("Keystore file does not exist: " + keystorePath);
        }

        String inputForSigning = projectFilePaths.unsignedUnalignedApkPath;
        String alignedApk = projectFilePaths.unsignedAlignedApkPath;
        try {
            runZipalign(projectFilePaths.unsignedUnalignedApkPath, alignedApk);
            inputForSigning = alignedApk;
        } catch (Exception e) {
            LogUtil.w(TAG, "Zipalign before signing failed or skipped, signing unaligned directly: " + e.getMessage());
            inputForSigning = projectFilePaths.unsignedUnalignedApkPath;
        }

        File releaseApkDir = new File(projectFilePaths.releaseApkPath).getParentFile();
        if (releaseApkDir != null && !releaseApkDir.exists()) {
            releaseApkDir.mkdirs();
        }

        String effectiveKeyPassword = (keyPassword != null && !keyPassword.isEmpty()) ? keyPassword : keystorePassword;

        ApkSigner signer = new ApkSigner();
        signer.signWithKeyStore(inputForSigning, projectFilePaths.releaseApkPath,
                keystorePath, keystorePassword, keyAlias, effectiveKeyPassword, null);

        if (ApkSigner.LogCallback.errorCount.get() > 0) {
            throw new SketchwareException("ApkSigner failed to sign release APK with keystore");
        }

        FileUtil.copyFile(projectFilePaths.releaseApkPath, projectFilePaths.finalToInstallApkPath);
        Log.d(TAG, "Signing release APK took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    /**
     * Computes a fingerprint of all input DEX files for merge caching.
     * Uses path + size + lastModified to detect changes.
     */
    private String computeDexMergeFingerprint(List<File> dexFiles) {
        StringBuilder sb = new StringBuilder(dexFiles.size() * 64);
        for (File f : dexFiles) {
            sb.append(f.getAbsolutePath()).append('|')
              .append(f.length()).append('|')
              .append(f.lastModified()).append('\n');
        }
        return sb.toString();
    }

    private void mergeDexes(File target, List<Dex> dexes) throws IOException {
        DexMerger merger = new DexMerger(dexes.toArray(new Dex[0]), CollisionPolicy.KEEP_FIRST, new DxContext());
        merger.merge().writeTo(target);
    }

    /**
     * Adds all built-in libraries' ProGuard rules to {@code args}, if any.
     *
     * @param args List of arguments to add built-in libraries' ProGuard roles to.
     */
    private void proguardAddLibConfigs(List<String> args) {
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            File config = BuiltInLibraries.getLibraryProguardConfiguration(library.getName());
            if (config.exists()) {
                args.add("-include");
                args.add(config.getAbsolutePath());
            }
        }
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {
            File config = ComposeBuiltInLibraries.getLibraryProguardConfiguration(artifact.id);
            if (config.exists()) {
                args.add("-include");
                args.add(config.getAbsolutePath());
            }
        }
    }

    /**
     * Generates default ProGuard R.java rules and adds them to {@code args}.
     *
     * @param args List of arguments to add R.java rules to.
     */
    private void proguardAddRjavaRules(List<String> args) {
        FileUtil.writeFile(projectFilePaths.proguardAutoGeneratedExclusions, getRJavaRules());
        args.add("-include");
        args.add(projectFilePaths.proguardAutoGeneratedExclusions);
    }

    private String getRJavaRules() {
        StringBuilder sb = new StringBuilder("# R.java rules");
        for (BuiltInLibrary jp : builtInLibraryManager.getLibraries()) {
            if (jp.hasResources() && !jp.getPackageName().isEmpty()) {
                sb.append("\n");
                sb.append("-keep class ");
                sb.append(jp.getPackageName());
                sb.append(".** { *; }");
            }
        }
        for (HashMap<String, Object> libEntry : localLibraryManager.list) {
            String obj = String.valueOf(libEntry.get("name"));
            if (libEntry.containsKey("packageName") && !proguard.libIsProguardFMEnabled(obj)) {
                sb.append("\n");
                sb.append("-keep class ");
                sb.append(String.valueOf(libEntry.get("packageName")));
                sb.append(".** { *; }");
            }
        }
        sb.append("\n");
        sb.append("-keep class ").append(projectFilePaths.packageName).append(".R { *; }").append('\n');
        return sb.toString();
    }

    /**
     * Runs R8 compiler for code shrinking and optimization.
     * Packages compiled classes into a JAR, applies ProGuard rules from
     * built-in libraries, local libraries, and user configuration,
     * then invokes R8 with the project's min SDK version.
     *
     * @throws IOException if R8 compilation fails
     */
    public void runR8() throws IOException {
        long savedTimeMillis = System.currentTimeMillis();

        ArrayList<String> config = new ArrayList<>();
        config.add(ProguardHandler.ANDROID_PROGUARD_RULES_PATH);
        config.add(projectFilePaths.proguardAaptRules);
        config.add(proguard.getCustomProguardRules());
        var rules = new ArrayList<>(Arrays.asList(getRJavaRules().split("\n")));
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            File f = BuiltInLibraries.getLibraryProguardConfiguration(library.getName());
            if (f.exists()) {
                config.add(f.getAbsolutePath());
            }
        }
        config.addAll(localLibraryManager.getPgRules());
        ArrayList<String> jars = new ArrayList<>();
        jars.add(projectFilePaths.compiledClassesPath + ".jar");

        for (HashMap<String, Object> libEntry : localLibraryManager.list) {
            String obj = String.valueOf(libEntry.get("name"));
            if (libEntry.containsKey("jarPath") && proguard.libIsProguardFMEnabled(obj)) {
                jars.add(String.valueOf(libEntry.get("jarPath")));
            }
        }
        try {
            JarBuilder.INSTANCE.generateJar(new File(projectFilePaths.compiledClassesPath));
            new R8Compiler(rules, config.toArray(new String[0]), getProguardClasspath().split(":"), jars.toArray(new String[0]), settings.getMinSdkVersion(), projectFilePaths).compile();
        } catch (Exception e) {
            throw new IOException(e);
        }
        LogUtil.d(TAG, "R8 took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    /**
     * Runs ProGuard for code shrinking and obfuscation.
     * Applies global rules, AAPT2-generated rules, user custom rules,
     * built-in library rules, and local library rules.
     * Optionally generates seeds, usage, and mapping debug files.
     *
     * @throws IOException if ProGuard execution fails
     */
    public void runProguard() throws IOException {
        long savedTimeMillis = System.currentTimeMillis();

        ArrayList<String> args = new ArrayList<>();

        /* Include global ProGuard rules */
        args.add("-include");
        args.add(ProguardHandler.ANDROID_PROGUARD_RULES_PATH);

        /* Include ProGuard rules generated by AAPT2 */
        args.add("-include");
        args.add(projectFilePaths.proguardAaptRules);

        /* Include custom ProGuard rules */
        args.add("-include");
        args.add(proguard.getCustomProguardRules());

        proguardAddLibConfigs(args);
        proguardAddRjavaRules(args);

        /* Include local libraries' ProGuard rules */
        for (String rule : localLibraryManager.getPgRules()) {
            args.add("-include");
            args.add(rule);
        }

        /* ProGuard -injars accepts both JAR files and directories of .class files */
        args.add("-injars");
        args.add(projectFilePaths.compiledClassesPath);

        for (HashMap<String, Object> libEntry : localLibraryManager.list) {
            String obj = String.valueOf(libEntry.get("name"));
            if (libEntry.containsKey("jarPath") && proguard.libIsProguardFMEnabled(obj)) {
                args.add("-injars");
                args.add(String.valueOf(libEntry.get("jarPath")));
            }
        }
        args.add("-libraryjars");
        args.add(getProguardClasspath());
        args.add("-outjars");
        args.add(projectFilePaths.proguardClassesPath);
        if (proguard.isDebugFilesEnabled()) {
            args.add("-printseeds");
            args.add(projectFilePaths.proguardSeedsPath);
            args.add("-printusage");
            args.add(projectFilePaths.proguardUsagePath);
            args.add("-printmapping");
            args.add(projectFilePaths.proguardMappingPath);
        }
        LogUtil.d(TAG, "About to run ProGuard with these arguments: " + args);

        Configuration configuration = new Configuration();

        try {
            ConfigurationParser parser = new ConfigurationParser(args.toArray(new String[0]), System.getProperties());
            try {
                parser.parse(configuration);
            } finally {
                parser.close();
            }
        } catch (ParseException e) {
            throw new IOException(e);
        }

        try {
            new ProGuard(configuration).execute();
        } catch (Exception e) {
            throw new IOException(e);
        }

        LogUtil.d(TAG, "ProGuard took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    /**
     * Runs StringFog string encryption on compiled class files.
     * Injects the StringFog runtime classes and encrypts string constants
     * using XOR-based obfuscation. Generates a mapping file for debugging.
     */
    public void runStringfog() {
        try {
            StringFogMappingPrinter stringFogMappingPrinter = new StringFogMappingPrinter(new File(projectFilePaths.binDirectoryPath,
                    "stringFogMapping.txt"));
            StringFogClassInjector stringFogClassInjector = new StringFogClassInjector(new String[0],
                    "UTF-8",
                    "com.github.megatronking.stringfog.xor.StringFogImpl",
                    "com.github.megatronking.stringfog.xor.StringFogImpl",
                    stringFogMappingPrinter);
            stringFogMappingPrinter.startMappingOutput();
            stringFogMappingPrinter.ouputInfo("UTF-8", "com.github.megatronking.stringfog.xor.StringFogImpl");
            stringFogClassInjector.doFog2ClassInDir(new File(projectFilePaths.compiledClassesPath));
            ZipUtil.extractAssetZip(context, "stringfog/stringfog.zip", projectFilePaths.compiledClassesPath);
        } catch (Exception e) {
            LogUtil.e("StringFog", "Failed to run StringFog", e);
        }
    }

    /**
     * Runs zipalign on an APK to ensure 4-byte alignment of uncompressed data.
     *
     * @param inPath  path to the input (unaligned) APK
     * @param outPath path to write the aligned APK
     * @throws SketchwareException if zipalign fails
     */
    public void runZipalign(String inPath, String outPath) throws SketchwareException {
        LogUtil.d(TAG, "About to zipalign " + inPath + " to " + outPath);
        long savedTimeMillis = System.currentTimeMillis();

        try (RandomAccessFile in = new RandomAccessFile(inPath, "r");
             FileOutputStream out = new FileOutputStream(outPath)) {
            ZipAlign.alignZip(in, out);
        } catch (IOException e) {
            throw new SketchwareException("Couldn't run zipalign on " + inPath + " with output path " + outPath + ": " + Log.getStackTraceString(e));
        } catch (InvalidZipException e) {
            throw new SketchwareException("Failed to zipalign due to the given zip being invalid: " + Log.getStackTraceString(e));
        }

        LogUtil.d(TAG, "zipalign took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    /**
     * Sets whether to build an Android App Bundle (AAB) instead of an APK.
     *
     * @param buildAppBundle {@code true} to produce AAB output
     */
    public void setBuildAppBundle(boolean buildAppBundle) {
        this.buildAppBundle = buildAppBundle;
    }
}
