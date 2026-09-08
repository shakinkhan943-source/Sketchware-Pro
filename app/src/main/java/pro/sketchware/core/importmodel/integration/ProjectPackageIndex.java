package pro.sketchware.core.importmodel.integration;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import pro.sketchware.core.project.SketchwarePaths;

/**
 * Simple names of project-local classes declared in a given package.
 *
 * <p>Types in the same package resolve without an import, so they must never be offered as
 * candidates. Only the project's own source roots are walked — never the whole filesystem.</p>
 */
final class ProjectPackageIndex {

    private ProjectPackageIndex() {
    }

    static Set<String> simpleNamesInPackage(String scId, String packageName) {
        if (packageName == null || packageName.isEmpty()) return Collections.emptySet();

        Set<String> names = new LinkedHashSet<>();
        String relative = packageName.replace('.', File.separatorChar);

        File userSources = new File(SketchwarePaths.getProjectJavaPath(scId));
        File generatedSources = new File(SketchwarePaths.getMyscPath(scId)
                + File.separator + "app" + File.separator + "src" + File.separator + "main"
                + File.separator + "java");

        for (File root : new File[]{userSources, generatedSources}) {
            collect(new File(root, relative), names);
            // Sketchware also stores user-created files flat under the java root.
            collect(root, names);
        }
        return names;
    }

    private static void collect(File directory, Set<String> names) {
        if (directory == null || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name.endsWith(".java") || name.endsWith(".kt")) {
                names.add(name.substring(0, name.lastIndexOf('.')));
            }
        }
    }
}
