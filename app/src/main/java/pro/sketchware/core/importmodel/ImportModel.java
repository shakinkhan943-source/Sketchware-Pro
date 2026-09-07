package importmodel;

import java.util.*;

public final class ImportModel {
    private ImportModel() {}

    public static final class Context {
        public final String packageName;
        public final Set<String> localNames;
        public final Set<String> defaultImports;
        public final Set<String> implicitPackages;
        public final Set<String> existingImports;
        public Context(String packageName, Set<String> localNames, Set<String> defaultImports,
                       Set<String> implicitPackages, Set<String> existingImports) {
            this.packageName = packageName == null ? "" : packageName;
            this.localNames = Collections.unmodifiableSet(new HashSet<>(localNames));
            this.defaultImports = Collections.unmodifiableSet(new HashSet<>(defaultImports));
            this.implicitPackages = Collections.unmodifiableSet(new HashSet<>(implicitPackages));
            this.existingImports = Collections.unmodifiableSet(new HashSet<>(existingImports));
        }
        public static Context empty() {
            return new Context("", Collections.<String>emptySet(), Collections.<String>emptySet(),
                    Collections.<String>emptySet(), Collections.<String>emptySet());
        }
    }

    public static final class Options {
        public final boolean resolveMissing, removeUnused, removeDuplicates, sortImports;
        public final boolean allowWildcards, allowAliases, allowAmbiguousBestCandidate;
        public final int maxCandidatesPerSymbol;
        public Options(boolean resolveMissing, boolean removeUnused, boolean removeDuplicates,
                       boolean sortImports, boolean allowWildcards, boolean allowAliases,
                       boolean allowAmbiguousBestCandidate, int maxCandidatesPerSymbol) {
            this.resolveMissing=resolveMissing; this.removeUnused=removeUnused;
            this.removeDuplicates=removeDuplicates; this.sortImports=sortImports;
            this.allowWildcards=allowWildcards; this.allowAliases=allowAliases;
            this.allowAmbiguousBestCandidate=allowAmbiguousBestCandidate;
            this.maxCandidatesPerSymbol=Math.max(1,maxCandidatesPerSymbol);
        }
        public static Options resolveDefaults() { return new Options(true,true,true,true,false,true,false,8); }
        public static Options organizeDefaults() { return new Options(false,true,true,true,false,true,false,8); }
    }

    public static final class Request {
        public final String source; public final ImportLanguage language;
        public final Context context; public final Options options;
        public Request(String source, ImportLanguage language, Context context, Options options) {
            this.source=source == null ? "" : source;
            this.language=Objects.requireNonNull(language);
            this.context=context == null ? Context.empty() : context;
            this.options=options == null ? Options.resolveDefaults() : options;
        }
    }

    public static final class Result {
        public final String source; public final boolean changed;
        public final List<String> added, removed, unresolved, ambiguous, warnings;
        public Result(String source, boolean changed, List<String> added, List<String> removed,
                      List<String> unresolved, List<String> ambiguous, List<String> warnings) {
            this.source=source; this.changed=changed;
            this.added=Collections.unmodifiableList(new ArrayList<>(added));
            this.removed=Collections.unmodifiableList(new ArrayList<>(removed));
            this.unresolved=Collections.unmodifiableList(new ArrayList<>(unresolved));
            this.ambiguous=Collections.unmodifiableList(new ArrayList<>(ambiguous));
            this.warnings=Collections.unmodifiableList(new ArrayList<>(warnings));
        }
    }
}
