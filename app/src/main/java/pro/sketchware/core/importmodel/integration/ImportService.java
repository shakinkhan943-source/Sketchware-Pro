package pro.sketchware.core.importmodel.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import pro.sketchware.core.build.CompileErrorSaver;
import pro.sketchware.core.importmodel.CompilerDiagnostic;
import pro.sketchware.core.importmodel.CompilerDiagnosticParser;
import pro.sketchware.core.importmodel.ImportLanguage;
import pro.sketchware.core.importmodel.ImportManager;
import pro.sketchware.core.importmodel.ImportModel;
import pro.sketchware.core.importmodel.SymbolIndex;

/**
 * Single entry point Sketchware uses for import intelligence.
 *
 * <p>It provides the environment (project package, project-local names, existing imports, symbol
 * index built from the real classpath) and delegates all decisions to {@link ImportManager}.
 * There is deliberately no second resolver: Java and Kotlin go through the same engine, the only
 * difference being the {@link ImportLanguage} carried by the request.</p>
 *
 * <p>All methods block while the index is (re)built and must be called off the main thread.</p>
 */
public final class ImportService {

    private ImportService() {
    }

    /** Resolve missing imports plus the cleanup Organize does. */
    public static ImportModel.Result resolveImports(String scId, String source, ImportLanguage language) {
        SymbolIndex index = ProjectSymbolIndexProvider.get(scId);
        ImportModel.Request request = new ImportModel.Request(source, language,
                buildContext(scId, source, language), ImportModel.Options.resolveDefaults());
        return new ImportManager(index).resolve(request);
    }

    /** Duplicate/unused/invalid import cleanup only — never guesses missing imports. */
    public static ImportModel.Result organizeImports(String scId, String source, ImportLanguage language) {
        SymbolIndex index = ProjectSymbolIndexProvider.get(scId);
        ImportModel.Request request = new ImportModel.Request(source, language,
                buildContext(scId, source, language), ImportModel.Options.organizeDefaults());
        return new ImportManager(index).organize(request);
    }

    /**
     * Unresolved symbols reported by the last Java/Kotlin compilation, used as an additional
     * signal for the caller's report. No compiler is invoked here: the log Sketchware already
     * saved after the last build is parsed.
     */
    public static List<String> lastCompilerUnresolvedSymbols(String scId, ImportLanguage language) {
        try {
            String log = new CompileErrorSaver(scId).getLogsFromFile();
            if (log == null || log.isEmpty()) return Collections.emptyList();
            List<String> symbols = new ArrayList<>();
            for (CompilerDiagnostic diagnostic : new CompilerDiagnosticParser().parse(log, language)) {
                if (!symbols.contains(diagnostic.symbol)) symbols.add(diagnostic.symbol);
            }
            return symbols;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /** Language of a file, derived from its name; defaults to Java. */
    public static ImportLanguage languageOf(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.US).endsWith(".kt")
                ? ImportLanguage.KOTLIN : ImportLanguage.JAVA;
    }

    private static ImportModel.Context buildContext(String scId, String source, ImportLanguage language) {
        String packageName = readPackage(source);

        // Symbols that never need an import: same-package project classes and the language's
        // implicit packages.
        Set<String> localNames = new HashSet<>(
                ProjectPackageIndex.simpleNamesInPackage(scId, packageName));
        Set<String> implicitPackages = new HashSet<>();
        implicitPackages.add("java.lang");
        if (!packageName.isEmpty()) implicitPackages.add(packageName);
        if (language == ImportLanguage.KOTLIN) {
            Collections.addAll(implicitPackages, "kotlin", "kotlin.collections", "kotlin.io",
                    "kotlin.ranges", "kotlin.sequences", "kotlin.text", "kotlin.annotation",
                    "kotlin.comparisons", "kotlin.jvm");
        }

        // ImportProcessor treats context.existingImports as "never remove". Only static imports go
        // in there: their members are often lowercase constants that the source analyzer cannot
        // reliably see as used, so dropping them would break compilation. Regular imports stay out
        // so that unused-import cleanup can actually do its job.
        Set<String> existingImports = new HashSet<>(readStaticImports(source));

        return new ImportModel.Context(packageName, localNames, Collections.emptySet(),
                implicitPackages, existingImports);
    }

    private static String readPackage(String source) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^[ \\t]*package\\s+([\\w.]+)").matcher(source == null ? "" : source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> readStaticImports(String source) {
        List<String> imports = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^[ \\t]*import\\s+static\\s+([\\w.*]+)").matcher(source == null ? "" : source);
        while (matcher.find()) imports.add(matcher.group(1));
        return imports;
    }
}
