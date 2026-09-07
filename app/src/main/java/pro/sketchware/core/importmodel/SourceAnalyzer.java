package pro.sketchware.core.importmodel;

import java.util.*;

public final class SourceAnalyzer {
    public static final class Import {
        public final String qualified, alias; public final boolean wildcard, statik;
        Import(String q,String a,boolean w,boolean s){qualified=q;alias=a;wildcard=w;statik=s;}
        public String simpleName(){
            if(alias!=null)return alias;
            if(wildcard)return "*";
            int p=qualified.lastIndexOf('.');return p<0?qualified:qualified.substring(p+1);
        }
        public String identity(){return (statik?"S:":"I:")+qualified+(alias==null?"":" AS "+alias);}
        public String text(ImportLanguage l){
            // Java statements are terminated, Kotlin ones are not.
            return "import "+(statik&&l==ImportLanguage.JAVA?"static ":"")+qualified+
                (alias==null?"": " as "+alias)+(l==ImportLanguage.JAVA?";":"");
        }
    }
    public static final class Analysis {
        public final String packageName,newline; public final List<Import> imports;
        public final Set<String> names, declarations; public final int importStart,importEnd,packageEnd;
        Analysis(String p,String n,List<Import> i,Set<String> names,Set<String>d,int s,int e,int pe){
            packageName=p;newline=n;imports=i;this.names=names;declarations=d;
            importStart=s;importEnd=e;packageEnd=pe;
        }
    }

    public Analysis analyze(String source, ImportLanguage lang) {
        String nl=source.contains("\r\n")?"\r\n":"\n";
        List<Import> imports=new ArrayList<>();Set<String> names=new LinkedHashSet<>(),decl=new HashSet<>();
        int importStart=-1,importEnd=-1,packageEnd=0;
        String masked=mask(source,lang);
        java.util.regex.Matcher pm=java.util.regex.Pattern.compile("(?m)^[ \\t]*package\\s+([\\w.]+)").matcher(masked);
        String pkg="";if(pm.find()){pkg=pm.group(1);packageEnd=lineEnd(source,pm.end(),nl);}
        java.util.regex.Matcher im=java.util.regex.Pattern.compile("(?m)^[ \\t]*import\\s+([^\\r\\n]+)").matcher(masked);
        while(im.find()){
            String raw=im.group(1).trim();if(importStart<0)importStart=lineStart(source,im.start());
            importEnd=lineEnd(source,im.end(),nl);
            imports.add(parseImport(raw,lang));
        }
        // Exclude package/import declarations from symbol-use analysis.
        String scan = masked;
        StringBuilder scanBuf = new StringBuilder(scan);
        java.util.regex.Matcher skip = java.util.regex.Pattern.compile(
            "(?m)^[ \\t]*(?:package\\s+[^\\r\\n]+|import\\s+[^\\r\\n]+)").matcher(scan);
        while(skip.find()) {
            for(int k=skip.start(); k<skip.end(); k++) scanBuf.setCharAt(k,' ');
        }
        scan = scanBuf.toString();

        java.util.regex.Matcher dm=java.util.regex.Pattern.compile(
            "\\b(class|interface|enum|object|record|typealias|annotation\\s+class)\\s+([A-Za-z_]\\w*)").matcher(scan);
        while(dm.find())decl.add(dm.group(2));
        java.util.regex.Matcher fdm=java.util.regex.Pattern.compile("(?m)\\bfun\\s+([A-Za-z_]\\w*)").matcher(masked);
        while(fdm.find())decl.add(fdm.group(1));
        java.util.regex.Matcher tm=java.util.regex.Pattern.compile("[A-Za-z_]\\w*").matcher(scan);
        while(tm.find()){
            String n=tm.group(); if(KEYWORDS.contains(n)||decl.contains(n))continue;
            int p=tm.start()-1;while(p>=0&&Character.isWhitespace(scan.charAt(p)))p--;
            if((!n.isEmpty()&&Character.isUpperCase(n.charAt(0))) ||
               (p>=0&&scan.charAt(p)=='@') ||
               (tm.end()<scan.length() && (nextNonSpace(scan,tm.end())=='(' || nextNonSpace(scan,tm.end())=='{')))
                names.add(n);
        }
        return new Analysis(pkg,nl,imports,names,decl,importStart,importEnd,packageEnd);
    }

    private static Import parseImport(String s,ImportLanguage l){
        // Strip the statement terminator and any trailing comment so the qualified name is clean.
        s=s.trim();
        int sc=s.indexOf(';'); if(sc>=0)s=s.substring(0,sc).trim();
        boolean st=l==ImportLanguage.JAVA&&s.startsWith("static ");
        if(st)s=s.substring(7).trim();
        String alias=null;
        if(l==ImportLanguage.KOTLIN){
            int p=s.lastIndexOf(" as ");if(p>0){alias=s.substring(p+4).trim();s=s.substring(0,p).trim();}
        }
        boolean w=s.endsWith(".*");
        return new Import(s,alias,w,st);
    }
    private static int lineStart(String s,int p){while(p>0&&s.charAt(p-1)!='\n'&&s.charAt(p-1)!='\r')p--;return p;}
    private static int lineEnd(String s,int p,String nl){while(p<s.length()&&s.charAt(p)!='\n'&&s.charAt(p)!='\r')p++;if(p<s.length()){if(nl.equals("\r\n")&&p+1<s.length()&&s.charAt(p)=='\r'&&s.charAt(p+1)=='\n')return p+2;return p+1;}return p;}
    private static char nextNonSpace(String s,int p){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;return p<s.length()?s.charAt(p):0;}

    private static String mask(String s,ImportLanguage l){
        StringBuilder b=new StringBuilder(s);boolean line=false,block=false,str=false,chr=false,triple=false,esc=false;
        for(int i=0;i<b.length();i++){char c=b.charAt(i),n=i+1<b.length()?b.charAt(i+1):0;
            if(line){if(c=='\n')line=false;else if(c!='\r')b.setCharAt(i,' ');continue;}
            if(block){if(c=='*'&&n=='/'){b.setCharAt(i,' ');b.setCharAt(i+1,' ');i++;block=false;}else if(c!='\n'&&c!='\r')b.setCharAt(i,' ');continue;}
            if(triple){if(c=='"'&&i+2<b.length()&&b.charAt(i+1)=='"'&&b.charAt(i+2)=='"'){b.setCharAt(i,' ');b.setCharAt(i+1,' ');b.setCharAt(i+2,' ');i+=2;triple=false;}else if(c!='\n'&&c!='\r')b.setCharAt(i,' ');continue;}
            if(str){if(c=='"'&&!esc)str=false;else if(c!='\n'&&c!='\r')b.setCharAt(i,' ');esc=c=='\\'&&!esc;if(c!='\\')esc=false;continue;}
            if(chr){if(c=='\''&&!esc)chr=false;else if(c!='\n'&&c!='\r')b.setCharAt(i,' ');esc=c=='\\'&&!esc;if(c!='\\')esc=false;continue;}
            if(c=='/'&&n=='/'){b.setCharAt(i,' ');b.setCharAt(i+1,' ');i++;line=true;continue;}
            if(c=='/'&&n=='*'){b.setCharAt(i,' ');b.setCharAt(i+1,' ');i++;block=true;continue;}
            if(l==ImportLanguage.KOTLIN&&c=='"'&&i+2<b.length()&&b.charAt(i+1)=='"'&&b.charAt(i+2)=='"'){b.setCharAt(i,' ');b.setCharAt(i+1,' ');b.setCharAt(i+2,' ');i+=2;triple=true;continue;}
            if(c=='"'){b.setCharAt(i,' ');str=true;continue;} if(c=='\''){b.setCharAt(i,' ');chr=true;}
        } return b.toString();
    }
    private static final Set<String> KEYWORDS=new HashSet<>(Arrays.asList(
        "package","import","class","interface","enum","object","record","fun","val","var","if","else","for","while","when",
        "return","new","this","super","true","false","null","public","private","protected","internal","static","final",
        "abstract","override","open","data","sealed","const","suspend","inline","operator","infix","in","is","as","try",
        "catch","finally","throw","throws","extends","implements","instanceof","void","int","long","short","byte","char",
        "float","double","boolean","typealias","where","get","set","by","constructor","init","companion"
    ));
}
