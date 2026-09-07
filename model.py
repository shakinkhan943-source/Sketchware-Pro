import os
import zipfile

# Define the base directory structure matching your Sketchware Pro repo
base_package_dir = os.path.join("pro", "sketchware", "core", "importmodel")
os.makedirs(base_package_dir, exist_ok=True)

files = {}

files["ImportLanguage.java"] = """package pro.sketchware.core.importmodel;

public enum ImportLanguage {
    JAVA,
    KOTLIN,
    UNKNOWN
}
"""

files["SymbolKind.java"] = """package pro.sketchware.core.importmodel;

public enum SymbolKind {
    CLASS, INTERFACE, ENUM, ANNOTATION, OBJECT, 
    FUNCTION, PROPERTY, EXTENSION_FUNCTION, 
    STATIC_MEMBER, NESTED_TYPE, ALIAS, UNKNOWN
}
"""

files["SymbolSourceKind.java"] = """package pro.sketchware.core.importmodel;

public enum SymbolSourceKind {
    PROJECT_SOURCE, PROJECT_DEPENDENCY, ANDROID_SDK, 
    ANDROIDX, KOTLIN_STDLIB, COMPOSE, EXTERNAL_JAR, 
    EXTERNAL_AAR, UNKNOWN
}
"""

files["ImportDeclaration.java"] = """package pro.sketchware.core.importmodel;

public class ImportDeclaration {
    public ImportLanguage language;
    public String rawLine;
    public boolean isStatic;
    public boolean isWildcard;
    public String qualifiedName; // e.g., java.util.List
    public String packageName;   // e.g., java.util
    public String simpleName;    // e.g., List or *
    public String memberName;    // for static imports
    public String alias;         // Kotlin only
    public int startOffset;
    public int endOffset;
    public int lineNumber;
    public boolean valid;
    public String parseError;

    public ImportDeclaration() { this.valid = true; }
    
    public String getImportedName() {
        if (alias != null && !alias.isEmpty()) return alias;
        return simpleName;
    }
}
"""

files["SourceAnalysis.java"] = """package pro.sketchware.core.importmodel;

import java.util.*;

public class SourceAnalysis {
    public ImportLanguage language;
    public String packageName;
    public int packageStartOffset = -1;
    public int packageEndOffset = -1;
    
    public List<ImportDeclaration> imports = new ArrayList<>();
    
    public Set<String> usedTypeNames = new HashSet<>();
    public Set<String> usedAnnotationNames = new HashSet<>();
    public Set<String> usedFunctionNames = new HashSet<>();
    public Set<String> usedStaticMemberNames = new HashSet<>();
    
    public int importRegionStartOffset = -1;
    public int importRegionEndOffset = -1;
    
    public String lineSeparator = "\\n";
    public boolean possiblyBrokenSyntax;
}
"""

files["SymbolCandidate.java"] = """package pro.sketchware.core.importmodel;

public class SymbolCandidate {
    public String simpleName;
    public String qualifiedName;
    public String packageName;
    public SymbolKind kind;
    public ImportLanguage language;
    public SymbolSourceKind sourceKind;
    public int priority; // Higher is better
    public boolean requiresStaticImport;
}
"""

files["ImportEdit.java"] = """package pro.sketchware.core.importmodel;

public class ImportEdit {
    public int startOffset;
    public int endOffset;
    public String replacement;

    public ImportEdit(int startOffset, int endOffset, String replacement) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.replacement = replacement;
    }
}
"""

files["ImportResolutionResult.java"] = """package pro.sketchware.core.importmodel;

import java.util.ArrayList;
import java.util.List;

public class ImportResolutionResult {
    public boolean success;
    public boolean changed;
    public String originalSource;
    public String updatedSource;
    public List<ImportEdit> edits = new ArrayList<>();
    
    public List<String> addedImports = new ArrayList<>();
    public List<String> removedImports = new ArrayList<>();
    public List<String> unresolvedSymbols = new ArrayList<>();
    public List<String> ambiguousSymbols = new ArrayList<>();
    
    public String userSummary = "";
}
"""

files["ImportRequest.java"] = """package pro.sketchware.core.importmodel;

public class ImportRequest {
    public String source;
    public ImportLanguage language;
    public ProjectImportContext context;
    public ImportOptions options;
    public String documentToken; // To detect concurrent edits

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final ImportRequest req = new ImportRequest();
        public Builder source(String s) { req.source = s; return this; }
        public Builder language(ImportLanguage l) { req.language = l; return this; }
        public Builder context(ProjectImportContext c) { req.context = c; return this; }
        public Builder options(ImportOptions o) { req.options = o; return this; }
        public Builder documentToken(String t) { req.documentToken = t; return this; }
        public ImportRequest build() { 
            if (req.options == null) req.options = ImportOptions.resolveDefaults();
            return req; 
        }
    }
}
"""

files["ImportOptions.java"] = """package pro.sketchware.core.importmodel;

public class ImportOptions {
    public boolean resolveMissing = true;
    public boolean removeDuplicates = true;
    public boolean removeUnused = true;
    public boolean organizeAfterResolve = true;
    public boolean preserveWildcards = true;
    public boolean allowKotlinAliases = true;

    public static ImportOptions resolveDefaults() {
        return new ImportOptions();
    }

    public static ImportOptions organizeDefaults() {
        ImportOptions opts = new ImportOptions();
        opts.resolveMissing = false;
        return opts;
    }
}
"""

files["ProjectImportContext.java"] = """package pro.sketchware.core.importmodel;

import java.io.File;
import java.util.List;

public class ProjectImportContext {
    public String projectId;
    public File cacheDir;
    public List<File> sourceRoots;
    public List<ClasspathEntry> classpathEntries;
    public File androidSdkJar;
}
"""

files["ClasspathEntry.java"] = """package pro.sketchware.core.importmodel;

import java.io.File;

public class ClasspathEntry {
    public enum Kind { JAR, AAR, DIRECTORY, SDK_JAR, KOTLIN_LIB }
    
    public String id;
    public File file;
    public Kind kind;
    public SymbolSourceKind sourceKind;
}
"""

files["EditorImportGateway.java"] = """package pro.sketchware.core.importmodel;

import java.util.List;

public interface EditorImportGateway {
    String getSourceText();
    ImportLanguage detectLanguage();
    void applyEdits(List<ImportEdit> edits);
    void replaceWholeTextSafely(String newText);
    String getDocumentVersionToken();
}
"""

files["SymbolIndex.java"] = """package pro.sketchware.core.importmodel;

import java.util.List;

public interface SymbolIndex {
    List<SymbolCandidate> querySimpleName(String simpleName);
    boolean containsPackageAndSimpleName(String packageName, String simpleName);
    boolean containsQualifiedName(String qualifiedName);
}
"""

files["ImportParser.java"] = """package pro.sketchware.core.importmodel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImportParser {
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
        "^\\\\s*import\\\\s+(static\\\\s+)?([\\\\w.]+(?:\\\\.\\\\*)?)(?:\\\\s+as\\\\s+(\\\\w+))?\\\\s*;?\\\\s*$"
    );

    public List<ImportDeclaration> parse(String source, ImportLanguage language) {
        List<ImportDeclaration> imports = new ArrayList<>();
        String[] lines = source.split("\\\\r?\\\\n", -1);
        int offset = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = IMPORT_PATTERN.matcher(line);
            if (m.matches()) {
                ImportDeclaration decl = new ImportDeclaration();
                decl.language = language;
                decl.rawLine = line;
                decl.isStatic = m.group(1) != null;
                decl.alias = m.group(3);
                
                String fullPath = m.group(2);
                decl.isWildcard = fullPath.endsWith(".*");
                
                if (decl.isWildcard) {
                    decl.packageName = fullPath.substring(0, fullPath.length() - 2);
                    decl.simpleName = "*";
                    decl.qualifiedName = fullPath;
                } else {
                    int lastDot = fullPath.lastIndexOf('.');
                    if (lastDot > 0) {
                        decl.packageName = fullPath.substring(0, lastDot);
                        decl.simpleName = fullPath.substring(lastDot + 1);
                    } else {
                        decl.packageName = "";
                        decl.simpleName = fullPath;
                    }
                    decl.qualifiedName = fullPath;
                }
                
                decl.startOffset = offset;
                decl.endOffset = offset + line.length();
                decl.lineNumber = i + 1;
                decl.valid = true;
                imports.add(decl);
            }
            offset += line.length() + 1;
        }
        return imports;
    }
}
"""

files["SourceAnalyzer.java"] = """package pro.sketchware.core.importmodel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SourceAnalyzer {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\\\s*package\\\\s+([\\\\w.]+)\\\\s*;?", Pattern.MULTILINE);
    private static final Pattern TYPE_USAGE_PATTERN = Pattern.compile("\\\\b([A-Z]\\\\w*)\\\\b");
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@([A-Z]\\\\w*)");

    public SourceAnalysis analyze(String source, ImportLanguage language) {
        SourceAnalysis analysis = new SourceAnalysis();
        analysis.language = language;
        analysis.lineSeparator = source.contains("\\r\\n") ? "\\r\\n" : "\\n";

        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(source);
        if (pkgMatcher.find()) {
            analysis.packageName = pkgMatcher.group(1);
            analysis.packageStartOffset = pkgMatcher.start();
            analysis.packageEndOffset = pkgMatcher.end();
        }

        ImportParser parser = new ImportParser();
        analysis.imports = parser.parse(source, language);
        
        if (!analysis.imports.isEmpty()) {
            analysis.importRegionStartOffset = analysis.imports.get(0).startOffset;
            analysis.importRegionEndOffset = analysis.imports.get(analysis.imports.size() - 1).endOffset;
        }

        String cleanSource = stripCommentsAndStrings(source);
        
        Matcher typeMatcher = TYPE_USAGE_PATTERN.matcher(cleanSource);
        while (typeMatcher.find()) {
            analysis.usedTypeNames.add(typeMatcher.group(1));
        }

        Matcher annoMatcher = ANNOTATION_PATTERN.matcher(cleanSource);
        while (annoMatcher.find()) {
            analysis.usedAnnotationNames.add(annoMatcher.group(1));
        }

        if (language == ImportLanguage.KOTLIN) {
            Pattern funcPattern = Pattern.compile("\\\\b([a-z]\\\\w*)\\\\s*[(\\\\{]");
            Matcher funcMatcher = funcPattern.matcher(cleanSource);
            while (funcMatcher.find()) {
                analysis.usedFunctionNames.add(funcMatcher.group(1));
            }
        }

        return analysis;
    }

    private String stripCommentsAndStrings(String source) {
        return source.replaceAll("//.*", "")
                     .replaceAll("/\\\\*.*?\\\\*/", "")
                     .replaceAll("\\".*?\\"", "\\"\\"")
                     .replaceAll("'.*?'", "''");
    }
}
"""

files["ImportFormatter.java"] = """package pro.sketchware.core.importmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ImportFormatter {
    public List<ImportDeclaration> sortImports(List<ImportDeclaration> imports, ImportLanguage language) {
        List<ImportDeclaration> sorted = new ArrayList<>(imports);
        Collections.sort(sorted, Comparator.comparing((ImportDeclaration i) -> {
            if (i.isStatic) return "0_" + i.qualifiedName;
            if (i.qualifiedName.startsWith("android.")) return "1_" + i.qualifiedName;
            if (i.qualifiedName.startsWith("androidx.")) return "2_" + i.qualifiedName;
            if (i.qualifiedName.startsWith("java.")) return "8_" + i.qualifiedName;
            if (i.qualifiedName.startsWith("javax.")) return "9_" + i.qualifiedName;
            return "5_" + i.qualifiedName;
        }));
        return sorted;
    }

    public String renderImportBlock(List<ImportDeclaration> imports, ImportLanguage language, String lineSeparator) {
        StringBuilder sb = new StringBuilder();
        for (ImportDeclaration imp : imports) {
            if (language == ImportLanguage.JAVA) {
                sb.append("import ");
                if (imp.isStatic) sb.append("static ");
                sb.append(imp.qualifiedName).append(";");
            } else if (language == ImportLanguage.KOTLIN) {
                sb.append("import ").append(imp.qualifiedName);
                if (imp.alias != null && !imp.alias.isEmpty()) {
                    sb.append(" as ").append(imp.alias);
                }
            }
            sb.append(lineSeparator);
        }
        return sb.toString();
    }
}
"""

files["ImportInjector.java"] = """package pro.sketchware.core.importmodel;

import java.util.List;

public class ImportInjector {
    public String applyEdits(String source, List<ImportEdit> edits) {
        if (edits == null || edits.isEmpty()) return source;
        
        edits.sort((a, b) -> Integer.compare(b.startOffset, a.startOffset));
        
        StringBuilder sb = new StringBuilder(source);
        for (ImportEdit edit : edits) {
            sb.replace(edit.startOffset, edit.endOffset, edit.replacement);
        }
        return sb.toString();
    }
}
"""

files["ImportManager.java"] = """package pro.sketchware.core.importmodel;

import java.util.*;

public class ImportManager {
    private final SymbolIndex index;
    private final SourceAnalyzer analyzer;
    private final ImportFormatter formatter;
    private final ImportInjector injector;

    public ImportManager(SymbolIndex index) {
        this.index = index;
        this.analyzer = new SourceAnalyzer();
        this.formatter = new ImportFormatter();
        this.injector = new ImportInjector();
    }

    public ImportResolutionResult resolve(ImportRequest request) {
        ImportResolutionResult result = new ImportResolutionResult();
        result.originalSource = request.source;
        
        try {
            SourceAnalysis analysis = analyzer.analyze(request.source, request.language);
            
            Set<String> missingTypes = new HashSet<>(analysis.usedTypeNames);
            Set<String> existingImports = new HashSet<>();
            
            for (ImportDeclaration imp : analysis.imports) {
                if (imp.valid) {
                    existingImports.add(imp.getImportedName());
                }
            }
            
            missingTypes.removeAll(existingImports);
            missingTypes.removeAll(Arrays.asList("String", "Integer", "Boolean", "Object", "System", "Math"));
            
            List<ImportDeclaration> newImports = new ArrayList<>();
            for (String missing : missingTypes) {
                List<SymbolCandidate> candidates = index.querySimpleName(missing);
                if (candidates != null && !candidates.isEmpty()) {
                    candidates.sort((a, b) -> Integer.compare(b.priority, a.priority));
                    SymbolCandidate best = candidates.get(0);
                    
                    if (candidates.size() > 1 && candidates.get(0).priority == candidates.get(1).priority) {
                        result.ambiguousSymbols.add(missing);
                    } else {
                        ImportDeclaration newImp = new ImportDeclaration();
                        newImp.language = request.language;
                        newImp.qualifiedName = best.qualifiedName;
                        newImp.packageName = best.packageName;
                        newImp.simpleName = best.simpleName;
                        newImp.isStatic = best.requiresStaticImport;
                        newImports.add(newImp);
                        result.addedImports.add(best.qualifiedName);
                    }
                } else {
                    result.unresolvedSymbols.add(missing);
                }
            }
            
            List<ImportDeclaration> finalImports = new ArrayList<>(analysis.imports);
            finalImports.addAll(newImports);
            
            Set<String> seen = new HashSet<>();
            List<ImportDeclaration> uniqueImports = new ArrayList<>();
            for (ImportDeclaration imp : finalImports) {
                if (seen.add(imp.qualifiedName)) {
                    uniqueImports.add(imp);
                }
            }
            
            List<ImportDeclaration> sortedImports = formatter.sortImports(uniqueImports, request.language);
            String newImportBlock = formatter.renderImportBlock(sortedImports, analysis.lineSeparator);
            
            List<ImportEdit> edits = new ArrayList<>();
            if (analysis.importRegionStartOffset != -1) {
                edits.add(new ImportEdit(
                    analysis.importRegionStartOffset, 
                    analysis.importRegionEndOffset, 
                    newImportBlock.trim()
                ));
            } else if (analysis.packageEndOffset != -1) {
                String insertion = analysis.lineSeparator + analysis.lineSeparator + newImportBlock;
                edits.add(new ImportEdit(
                    analysis.packageEndOffset, 
                    analysis.packageEndOffset, 
                    insertion
                ));
            } else {
                edits.add(new ImportEdit(0, 0, newImportBlock + analysis.lineSeparator));
            }
            
            String updatedSource = injector.applyEdits(request.source, edits);
            
            result.success = true;
            result.changed = !updatedSource.equals(request.source);
            result.updatedSource = updatedSource;
            result.edits = edits;
            
        } catch (Exception e) {
            result.success = false;
            result.userSummary = "Error: " + e.getMessage();
        }
        
        return result;
    }

    public ImportResolutionResult organize(ImportRequest request) {
        ImportOptions opts = request.options;
        opts.resolveMissing = false;
        return resolve(request);
    }
}
"""

# Write files and zip
zip_name = "ImportModel.zip"
with zipfile.ZipFile(zip_name, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for filename, content in files.items():
        file_path = os.path.join(base_package_dir, filename)
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        zipf.write(file_path)

print(f"Successfully generated {zip_name} and the folder structure!")
