package pro.sketchware.core.importmodel;

import java.util.*;

public final class ImportProcessor {
    private final SourceAnalyzer analyzer;
    public ImportProcessor(SourceAnalyzer analyzer){this.analyzer=analyzer;}

    public ImportModel.Result process(ImportModel.Request r, SymbolIndex index){
        SourceAnalyzer.Analysis a=analyzer.analyze(r.source,r.language);
        LinkedHashMap<String,SourceAnalyzer.Import> map=new LinkedHashMap<>();
        List<String> removed=new ArrayList<>(),added=new ArrayList<>(),unresolved=new ArrayList<>(),ambiguous=new ArrayList<>(),warnings=new ArrayList<>();
        for(SourceAnalyzer.Import i:a.imports) {
            if(r.options.removeDuplicates && map.containsKey(i.identity())) {removed.add(i.text(r.language));continue;}
            map.put(i.identity(),i);
        }

        if(r.options.resolveMissing){
            for(String n:a.names){
                if(a.declarations.contains(n)||isKnown(n,map,a,r))continue;
                List<SymbolIndex.Candidate> cs=index.find(n,r.language);
                cs=filter(cs,r,n);
                if(cs.isEmpty()){unresolved.add(n);continue;}
                SymbolIndex.Candidate best=cs.get(0);
                int count=0;for(SymbolIndex.Candidate c:cs)if(c.priority==best.priority)count++;
                if(count>1&&!r.options.allowAmbiguousBestCandidate){ambiguous.add(n);continue;}
                SourceAnalyzer.Import ni=toImport(best,r.language);
                if(conflict(ni,map,a,r)) {
                    if(r.language==ImportLanguage.KOTLIN&&r.options.allowAliases&&!ni.statik) {
                        String alias=uniqueAlias(n,map);ni=new SourceAnalyzer.Import(ni.qualified,alias,false,false);
                    } else {ambiguous.add(n);continue;}
                }
                if(!map.containsKey(ni.identity())){map.put(ni.identity(),ni);added.add(ni.text(r.language));}
            }
        }

        if(r.options.removeUnused){
            Iterator<Map.Entry<String,SourceAnalyzer.Import>> it=map.entrySet().iterator();
            while(it.hasNext()){
                SourceAnalyzer.Import i=it.next().getValue();
                if(i.wildcard||i.alias!=null)continue;
                String n=i.simpleName();
                if(!a.names.contains(n)&&!r.context.existingImports.contains(i.qualified)){
                    it.remove();removed.add(i.text(r.language));
                }
            }
        }

        List<SourceAnalyzer.Import> out=new ArrayList<>(map.values());
        if(r.options.sortImports)Collections.sort(out,new Comparator<SourceAnalyzer.Import>(){
            public int compare(SourceAnalyzer.Import x,SourceAnalyzer.Import y){return x.text(r.language).compareToIgnoreCase(y.text(r.language));}
        });
        String ns=rewrite(r.source,a,out,r.language);
        return new ImportModel.Result(ns,!ns.equals(r.source),added,removed,unresolved,ambiguous,warnings);
    }

    private static List<SymbolIndex.Candidate> filter(List<SymbolIndex.Candidate> in,ImportModel.Request r,String n){
        List<SymbolIndex.Candidate> o=new ArrayList<>();
        for(SymbolIndex.Candidate c:in){
            if(c.kind==SymbolIndex.Kind.TYPE && r.context.localNames.contains(n))continue;
            // Symbols of an implicitly imported package (java.lang, kotlin.*, the file's own
            // package) are already in scope and must never produce an import statement.
            if(isImplicit(c,r))continue;
            if(c.origin==SymbolIndex.Origin.OTHER && c.priority>80)continue;
            o.add(c);if(o.size()>=r.options.maxCandidatesPerSymbol)break;
        }return o;
    }
    private static boolean isImplicit(SymbolIndex.Candidate c,ImportModel.Request r){
        String q=c.qualifiedName;int p=q.lastIndexOf('.');
        if(p<0)return true;
        return r.context.implicitPackages.contains(q.substring(0,p));
    }
    private static boolean isKnown(String n,Map<String,SourceAnalyzer.Import> m,SourceAnalyzer.Analysis a,ImportModel.Request r){
        if(r.context.localNames.contains(n)||r.context.defaultImports.contains(n))return true;
        if(a.names.contains(n)&&m.values().stream().anyMatch(i->i.simpleName().equals(n)))return true;
        for(SourceAnalyzer.Import i:m.values())if(i.wildcard&&n.length()>0)return true;
        return false;
    }
    private static boolean conflict(SourceAnalyzer.Import n,Map<String,SourceAnalyzer.Import> m,SourceAnalyzer.Analysis a,ImportModel.Request r){
        for(SourceAnalyzer.Import x:m.values())if(x.simpleName().equals(n.simpleName())&&!x.identity().equals(n.identity()))return true;
        return false;
    }
    private static SourceAnalyzer.Import toImport(SymbolIndex.Candidate c,ImportLanguage l){
        String q=c.importTarget(l);
        boolean st=l==ImportLanguage.JAVA&&q.startsWith("static ");
        if(st)q=q.substring(7);
        return new SourceAnalyzer.Import(q,null,false,st);
    }
    private static String uniqueAlias(String n,Map<String,SourceAnalyzer.Import> m){
        String a=n+"Alias";int i=2;Set<String>x=new HashSet<>();for(SourceAnalyzer.Import z:m.values())x.add(z.simpleName());
        while(x.contains(a))a=n+"Alias"+i++;return a;
    }
    private static String rewrite(String s,SourceAnalyzer.Analysis a,List<SourceAnalyzer.Import> im,ImportLanguage l){
        String nl=a.newline;StringBuilder b=new StringBuilder();
        int start=a.importStart>=0?a.importStart:(a.packageEnd>0?a.packageEnd:0);
        int end=a.importStart>=0?a.importEnd:start;
        if(start>0)b.append(s,0,start);
        if(!im.isEmpty()){
            // Keep one blank line between the package declaration and the import block.
            if(a.importStart<0 && a.packageEnd>0)b.append(nl);
            for(SourceAnalyzer.Import x:im)b.append(x.text(l)).append(nl);
            b.append(nl);
        }
        int body=end;
        // Only normalize the blank line that follows an import block; a file that has (and keeps)
        // no imports at all must stay byte-identical.
        if(!im.isEmpty() || a.importStart>=0){
            if(body<s.length() && (s.startsWith(nl,body)))body+=nl.length();
            while(body<s.length()&&(s.charAt(body)==' '||s.charAt(body)=='\t'))body++;
        }
        b.append(s,body,s.length());
        return b.toString();
    }
}
