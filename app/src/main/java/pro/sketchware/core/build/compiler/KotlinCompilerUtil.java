package pro.sketchware.core.build.compiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import pro.sketchware.core.build.ProjectBuilder;
import pro.sketchware.core.build.ProjectFilePaths;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.LogUtil;

public class KotlinCompilerUtil {

    private static final String TAG = "KotlinCompilerUtil";

    /**
     * Service files whose presence marks a JAR as a Kotlin compiler plugin. Everything kotlinc is
     * given through {@code -Xplugin} is scanned for these descriptors, so a JAR without one of them
     * is simply not a plugin.
     */
    private static final String[] PLUGIN_SERVICE_FILES = {
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar",
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor",
    };

    public static boolean areAnyKtFilesPresent(ProjectBuilder bui) {
        return areAnyKtFilesPresent(bui.projectFilePaths);
    }

    public static boolean areAnyKtFilesPresent(ProjectFilePaths projectFilePaths) {
        return getFilesToCompile(projectFilePaths).stream()
                .anyMatch(it -> it.getName().endsWith(".kt"));
    }

    public static List<File> getFilesToCompile(ProjectFilePaths workspace) {
        String scId = workspace.sc_id;
        List<File> mFilesToCompile = new ArrayList<>();

        mFilesToCompile.addAll(getSourceFiles(new File(workspace.javaFilesPath)));
        mFilesToCompile.addAll(getSourceFiles(new File(workspace.rJavaDirectoryPath)));
        mFilesToCompile.addAll(getSourceFiles(new File(SketchwarePaths.getProjectJavaPath(scId))));

        return mFilesToCompile;
    }

    /**
     * Returns the directories that contain this project's Java sources.
     *
     * <p>The Kotlin compiler no longer accepts {@code .java} files in its source argument list since
     * Kotlin 2.4 removed {@code -Xcompile-java}. Java sources are read from {@code -Xjava-source-roots}
     * instead, so Kotlin can resolve mixed Java/Kotlin members while Sketchware's separate ECJ pass
     * compiles the actual Java bytecode. The root list mirrors the order used by
     * {@link #getFilesToCompile(ProjectFilePaths)} so generated sources (for example {@code R.java})
     * win over any user source with the same name.</p>
     */
    public static String[] getJavaSourceRoots(ProjectFilePaths workspace) {
        List<String> roots = new ArrayList<>();
        addExistingDirectory(roots, workspace.javaFilesPath);
        addExistingDirectory(roots, workspace.rJavaDirectoryPath);
        addExistingDirectory(roots, SketchwarePaths.getProjectJavaPath(workspace.sc_id));
        return roots.toArray(new String[0]);
    }

    private static void addExistingDirectory(List<String> roots, String path) {
        if (path != null && new File(path).isDirectory()) {
            roots.add(path);
        }
    }

    /**
     * Returns the JARs of the project's {@code kt_plugins} folder that really are Kotlin compiler
     * plugins, sorted by name so a build is reproducible.
     *
     * <p>The folder also holds the runtime JARs that were provisioned next to the Compose plugin
     * (kotlin-stdlib, annotations). Handing those to {@code -Xplugin} is pure noise: kotlinc builds
     * one isolated classloader over exactly these JARs and scans every entry for plugin services.
     * Worse, a plugin failure then lists plain libraries in its message and looks like a plugin that
     * is missing, when the actual complaint is about a single class.</p>
     */
    public static List<File> getCompilerPlugins(ProjectFilePaths workspace) {
        File pluginDir = new File(SketchwarePaths.getProjectKotlinCompilerPluginsPath(workspace.sc_id));
        if (!pluginDir.exists()) {
            return Collections.emptyList();
        }

        File[] children = pluginDir.listFiles(c -> c.isFile() && c.getName().endsWith(".jar"));
        if (children == null) {
            return Collections.emptyList();
        }

        List<File> plugins = new ArrayList<>();
        for (File candidate : children) {
            if (isCompilerPlugin(candidate)) {
                plugins.add(candidate);
            } else {
                LogUtil.d(TAG, "Ignoring " + candidate.getName()
                        + ": declares no Kotlin compiler plugin service");
            }
        }
        plugins.sort(Comparator.comparing(File::getName));
        return plugins;
    }

    /**
     * True unless the JAR was read successfully and declares no Kotlin compiler plugin service.
     *
     * <p>A JAR that cannot be opened is still passed on: silently dropping it here would turn a
     * read error into a confusing "plugin missing" report, while letting kotlinc open the file
     * itself names the real problem.</p>
     */
    public static boolean isCompilerPlugin(File jarFile) {
        if (jarFile == null || !jarFile.isFile()) return false;
        try (JarFile jar = new JarFile(jarFile)) {
            for (String serviceFile : PLUGIN_SERVICE_FILES) {
                if (jar.getEntry(serviceFile) != null) return true;
            }
            return false;
        } catch (IOException invalidJar) {
            LogUtil.w(TAG, "Cannot inspect " + jarFile + " for plugin services, passing it to kotlinc",
                    invalidJar);
            return true;
        }
    }

    /**
     * Lists the classes a JAR registers through the Kotlin compiler plugin service files. Used to
     * recognise a plugin by what it installs instead of by its file name, which carries the Kotlin
     * version and changes whenever the bundled compiler is upgraded.
     */
    public static List<String> getPluginServiceProviders(File jarFile) {
        List<String> providers = new ArrayList<>();
        for (String serviceFile : PLUGIN_SERVICE_FILES) {
            providers.addAll(readPluginServiceProviders(jarFile, serviceFile));
        }
        return providers;
    }

    /**
     * Verifies that every class the given plugin JARs register as a compiler plugin can be resolved
     * from the classloader that hosts kotlinc.
     *
     * <p>Kotlin's {@code ServiceLoaderLite} builds a classloader over the {@code -Xplugin} JARs whose
     * parent is the loader of the compiler itself. ART only ever loads classes out of DEX, so the
     * plain {@code .class} entries of a JAR copied into {@code kt_plugins} cannot provide the
     * registrar: the plugin classes have to be part of the Sketchware APK (see
     * {@code app/build.gradle}, {@code implementation libs.compose.compiler.plugin}) and are then
     * picked up through that parent delegation. Checking the same lookup here turns the resulting
     * compiler crash into an explanation of what is missing.</p>
     *
     * @throws IllegalStateException when a registered plugin class cannot be loaded
     */
    public static void ensurePluginRegistrarsAreLoadable(List<File> plugins) {
        ClassLoader hostLoader = KotlinCompilerUtil.class.getClassLoader();
        for (File plugin : plugins) {
            for (String serviceFile : PLUGIN_SERVICE_FILES) {
                for (String provider : readPluginServiceProviders(plugin, serviceFile)) {
                    try {
                        Class.forName(provider, false, hostLoader);
                    } catch (Throwable missing) {
                        throw new IllegalStateException(
                                "Kotlin compiler plugin " + plugin.getName() + " registers " + provider
                                        + ", which this Sketchware build cannot load. A compiler plugin's"
                                        + " classes must be packaged into the Sketchware APK (D8 compiles"
                                        + " the app's dependencies into DEX): Android cannot load classes"
                                        + " from the .class entries of a JAR taken from "
                                        + "files/kt_plugins. Rebuild/install a Sketchware APK that bundles"
                                        + " the plugin dependency, or remove the plugin JAR from the"
                                        + " project's kt_plugins folder.", missing);
                    }
                }
            }
        }
    }

    /**
     * Reads the implementation classes a JAR declares for one Kotlin plugin service file. Service
     * files hold one fully qualified class name per line, optionally with {@code #} comments.
     */
    private static List<String> readPluginServiceProviders(File jarFile, String serviceFile) {
        List<String> providers = new ArrayList<>();
        if (jarFile == null || !jarFile.isFile()) return providers;
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(serviceFile);
            if (entry == null) return providers;
            for (String line : readTextEntry(jar, entry).split("\n")) {
                String provider = line.trim();
                int comment = provider.indexOf('#');
                if (comment >= 0) provider = provider.substring(0, comment).trim();
                if (!provider.isEmpty()) providers.add(provider);
            }
        } catch (IOException invalidJar) {
            LogUtil.w(TAG, "Failed to read " + serviceFile + " from " + jarFile, invalidJar);
        }
        return providers;
    }

    private static String readTextEntry(JarFile jar, JarEntry entry) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) > 0) {
                content.write(chunk, 0, read);
            }
        }
        return new String(content.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<File> getSourceFiles(File dir) {
        List<File> files = new ArrayList<>();

        File[] children = dir.listFiles();
        if (children == null) return files;

        for (File child : children) {
            if (child.isDirectory()) {
                files.addAll(getSourceFiles(child));
            } else if (child.getName().endsWith(".kt") || child.getName().endsWith(".java")) {
                files.add(child);
            }
        }

        return files;
    }
}
