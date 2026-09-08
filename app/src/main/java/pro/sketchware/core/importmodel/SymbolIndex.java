package pro.sketchware.core.importmodel;

import java.util.*;

public final class SymbolIndex {
    public enum Kind { TYPE, FUNCTION, PROPERTY, ANNOTATION, STATIC_MEMBER }
    public enum Origin { PROJECT, DEPENDENCY, ANDROID_SDK, ANDROIDX, KOTLIN, OTHER }

    public static final class Candidate {
        public final String simpleName, qualifiedName, owner;
        public final ImportLanguage language;
        public final Kind kind; public final Origin origin;
        public final boolean staticMember, extension;
        public final int priority;
        public Candidate(String simpleName, String qualifiedName, String owner,
                         ImportLanguage language, Kind kind, Origin origin,
                         boolean staticMember, boolean extension, int priority) {
            this.simpleName=simpleName; this.qualifiedName=qualifiedName; this.owner=owner;
            this.language=language; this.kind=kind; this.origin=origin;
            this.staticMember=staticMember; this.extension=extension; this.priority=priority;
        }
        public String importTarget(ImportLanguage lang) {
            if (staticMember && lang == ImportLanguage.JAVA) return "static " + owner + "." + simpleName;
            return qualifiedName;
        }
    }

    private final Map<String,List<Candidate>> byName = new HashMap<>();
    public synchronized void add(Candidate c) {
        List<Candidate> l=byName.get(c.simpleName);
        if(l==null){l=new ArrayList<>();byName.put(c.simpleName,l);}
        for(Candidate x:l) if(x.qualifiedName.equals(c.qualifiedName) && x.kind==c.kind &&
            x.staticMember==c.staticMember && x.language==c.language) return;
        l.add(c);
    }
    public synchronized void addType(String q, ImportLanguage lang, Origin origin, int priority) {
        int p=q.lastIndexOf('.'); String n=p<0?q:q.substring(p+1);
        add(new Candidate(n,q,null,lang,Kind.TYPE,origin,false,false,priority));
    }
    public synchronized List<Candidate> find(String name, ImportLanguage lang) {
        List<Candidate> out=new ArrayList<>(), l=byName.get(name);
        if(l!=null) for(Candidate c:l) if(c.language==lang) out.add(c);
        Collections.sort(out, new Comparator<Candidate>() {
            public int compare(Candidate a,Candidate b){ return Integer.compare(a.priority,b.priority); }
        });
        return out;
    }
    public synchronized int size(){int n=0;for(List<Candidate> x:byName.values())n+=x.size();return n;}
}
