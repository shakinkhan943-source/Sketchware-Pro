package pro.sketchware.core.build.compiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import pro.sketchware.core.build.ProjectBuilder;
import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.build.BuildProgressReceiver;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.library.BuiltInLibraries;
import pro.sketchware.util.library.BuiltInLibraryManager;
import pro.sketchware.util.FileUtil;
import pro.sketchware.util.LogUtil;
import pro.sketchware.SketchApplication;

/**
 * Bridge class for Kotlin compilation in the build pipeline.
 *
 * Uses KotlinCompilerEnhanced for faster incremental compilation with:
 * - Automatic file change detection via CRC32 hashing
 * - Compiled class file caching
 * - Optional parallel compilation
 *
 * This maintains the same public interface as before, ensuring
 * compatibility with the rest of the build system.
 */
public class KotlinCompilerBridge {
    private static final String TAG = "KotlinCompilerBridge";

    /** Filename of the bundled Kotlin Compose compiler plugin. */
    private static final String COMPOSE_COMPILER_PLUGIN_JAR = "kotlin-compose-compiler-plugin-2.4.10.jar";

    /** Directory containing the Compose compiler plugin and its runtime dependencies. */
    private static final String COMPOSE_COMPILER_PLUGIN_ASSET_DIR = "libs/kt_plugins";

    /**
     * Ensures the Jetpack Compose compiler plugin and its runtime dependencies are
     * available in the project's kt_plugins folder.
     *
     * kotlinc only accepts a plugin as a JAR path ({@code -Xplugin=<jar>}) because it reads the
     * plugin's {@code META-INF/services} descriptors and its {@code -P} options from that file.
     * Copying the JAR is therefore necessary but not sufficient: ART defines classes from DEX only,
     * so the registrar class cannot come out of the copied JAR's {@code .class} entries and
     * ServiceLoaderLite reports the registrar as missing even though it is present. The plugin is
     * packaged with the app as well ({@code implementation libs.compose.compiler.plugin}) so that
     * the isolated plugin classloader inherits the class from its parent.
     *
     * <p>Every JAR of the APK's kt_plugins asset directory is mirrored, so a plugin can never be
     * shipped beside a stale version of the Kotlin stdlib it was built against; KotlinCompilerUtil
     * then selects the actual compiler plugins when it builds pluginClasspaths.</p>
     *
     * @return true when any Compose plugin asset was installed/upgraded.
     */
    private static boolean maybeProvisionComposeCompilerPlugin(
            ProjectBuilder builder, boolean projectUsesCompose) {
        if (!projectUsesCompose) {
            return false;
        }
        try {
            String pluginDirPath = SketchwarePaths.getProjectKotlinCompilerPluginsPath(builder.projectFilePaths.sc_id);
            File pluginDir = new File(pluginDirPath);
            if (!pluginDir.exists() && !pluginDir.mkdirs()) {
                LogUtil.w(TAG, "Failed to create kt_plugins directory: " + pluginDirPath);
                return false;
            }

            boolean changed = false;
            String[] assets = SketchApplication.getAppContext().getAssets().list(COMPOSE_COMPILER_PLUGIN_ASSET_DIR);
            if (assets != null) {
                for (String assetName : assets) {
                    if (!assetName.endsWith(".jar")) {
                        continue;
                    }
                    String assetPath = COMPOSE_COMPILER_PLUGIN_ASSET_DIR + "/" + assetName;
                    File target = new File(pluginDir, assetName);
                    if (ProjectBuilder.hasFileChanged(assetPath, target.getAbsolutePath())) {
                        changed = true;
                    }
                }
            }

            File plugin = new File(pluginDir, COMPOSE_COMPILER_PLUGIN_JAR);
            if (plugin.exists()) {
                LogUtil.d(TAG, "Kotlin Compose compiler plugin is ready: " + plugin.getAbsolutePath());
            } else {
                LogUtil.w(TAG, "Kotlin Compose compiler plugin could not be provisioned from assets");
            }
            return changed;
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to provision Kotlin Compose compiler plugin", e);
            return false;
        }
    }

    /**
     * Whether this project must be compiled with the Compose compiler plugin.
     *
     * <p>{@link pro.sketchware.core.project.BuildConfig#isComposeEnabled} is the authoritative
     * answer during a build: it is derived from the Compose library flag and from the presence of a
     * Kotlin Activity, which in this fork is always a Compose Activity. The source scan remains as a
     * fallback for callers that compile before {@code ProjectFilePaths#initializeMetadata} ran, so a
     * project can never lose the plugin because a caller did not populate the metadata.</p>
     */
    private static boolean projectUsesCompose(ProjectBuilder builder) {
        if (builder.projectFilePaths.buildConfig != null
                && builder.projectFilePaths.buildConfig.isComposeEnabled) {
            return true;
        }
        try {
            for (File sourceFile : KotlinCompilerUtil.getFilesToCompile(builder.projectFilePaths)) {
                if (sourceFile.isFile() && sourceFileReferencesCompose(sourceFile)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to scan sources for Compose usage", e);
        }
        return false;
    }

    /**
     * Fails fast when a Compose project has no Compose compiler plugin in its plugin folder.
     *
     * <p>Without the plugin the generated Kotlin still looks plausible, and the failure only shows
     * up later as a Compose runtime error on the device or as an obscure "unresolved @Composable"
     * message. Naming the missing file before kotlinc starts is the only point where this build
     * mistake can be explained: the plugin is not a library a project depends on, it is a build-time
     * component of the app that is provisioned from the APK's assets.</p>
     */
    private static void ensureComposeCompilerPluginAvailable(ProjectBuilder builder, boolean projectUsesCompose) {
        if (!projectUsesCompose) {
            return;
        }
        for (File plugin : KotlinCompilerUtil.getCompilerPlugins(builder.projectFilePaths)) {
            for (String provider : KotlinCompilerUtil.getPluginServiceProviders(plugin)) {
                if (provider.contains("androidx.compose.compiler.plugins.")) {
                    LogUtil.d(TAG, "Using Compose compiler plugin: " + plugin.getName());
                    return;
                }
            }
        }
        throw new IllegalStateException("This project uses Jetpack Compose, but the Kotlin Compose"
                + " compiler plugin (" + COMPOSE_COMPILER_PLUGIN_JAR + ") is not available in "
                + SketchwarePaths.getProjectKotlinCompilerPluginsPath(builder.projectFilePaths.sc_id)
                + ". The plugin is copied from the app's assets during the build; install the"
                + " Sketchware APK built from this source tree and try again.");
    }

    private static boolean sourceFileReferencesCompose(File sourceFile) {
        if (!sourceFile.canRead() || sourceFile.length() > 2 * 1024 * 1024) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(sourceFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("composable") || lower.contains("androidx.compose")
                        || lower.contains("@stable") || lower.contains("@immutable")) {
                    return true;
                }
            }
        } catch (IOException e) {
            LogUtil.w(TAG, "Failed to read source file " + sourceFile.getAbsolutePath(), e);
        }
        return false;
    }

    public static void compileKotlinCodeIfPossible(BuildProgressReceiver receiver,
                                                     ProjectBuilder builder) throws Throwable {
        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {
            boolean usesCompose = projectUsesCompose(builder);
            boolean pluginChanged = maybeProvisionComposeCompilerPlugin(builder, usesCompose);
            ensureComposeCompilerPluginAvailable(builder, usesCompose);
            receiver.onProgress("Kotlin is compiling...", 12);
            try {
                KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);
                if (pluginChanged) {
                    compiler.clearCache();
                }
                compiler.compile();
                LogUtil.d(TAG, compiler.getCacheStats());
            } catch (Exception e) {
                LogUtil.e(TAG, "Kotlin compilation failed", e);
                throw e;
            }
        }
    }

    public static void maybeAddKotlinBuiltInLibraryDependenciesIfPossible(
            ProjectBuilder builder,
            BuiltInLibraryManager builtInLibraryManager) {
        if (KotlinCompilerUtil.areAnyKtFilesPresent(builder)) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.JETBRAINS_KOTLIN_STDLIB);
        }
    }

    public static void maybeAddKotlinFilesToClasspath(StringBuilder classpath,
                                                        ProjectFilePaths workspace) {
        if (FileUtil.isExistFile(workspace.compiledClassesPath)) {
            classpath.append(workspace.compiledClassesPath);
            classpath.append(":");
        }
    }

    public static String getKotlinHome(ProjectFilePaths workspace) {
        return workspace.binDirectoryPath + File.separator + "kotlin_home";
    }

    public static void clearKotlinCompilationCache(ProjectBuilder builder) {
        try {
            KotlinCompilerEnhanced compiler = new KotlinCompilerEnhanced(builder);
            compiler.clearCache();
            LogUtil.d(TAG, "Kotlin compilation cache cleared");
        } catch (Exception e) {
            LogUtil.w(TAG, "Error clearing Kotlin incremental cache", e);
        }
    }
}
