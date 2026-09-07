package pro.sketchware.core.importmodel;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public final class SymbolIndexer {
    private SymbolIndexer() {}

    public static void indexClasspath(SymbolIndex index, List<File> entries,
                                      ImportLanguage language, SymbolIndex.Origin origin, int priority)
            throws IOException {
        for(File f:entries) {
            if(f==null || !f.isFile()) continue;
            String n=f.getName().toLowerCase(Locale.US);
            if(n.endsWith(".jar")) indexJar(index,f,language,origin,priority);
            else if(n.endsWith(".aar")) indexAar(index,f,language,origin,priority);
        }
    }

    public static void indexJar(SymbolIndex index, File jar, ImportLanguage lang,
                                SymbolIndex.Origin origin, int priority) throws IOException {
        JarFile jf=new JarFile(jar);
        try {
            Enumeration<JarEntry> e=jf.entries();
            while(e.hasMoreElements()){
                String p=e.nextElement().getName();
                if(!p.endsWith(".class") || p.equals("module-info.class") || p.endsWith("package-info.class")) continue;
                String q=p.substring(0,p.length()-6).replace('/','.');
                if(q.endsWith("Kt")) {
                    // The Kt class itself is useful as a type; functions/properties require
                    // compiler metadata or diagnostics and are intentionally not guessed here.
                }
                index.addType(q,lang,origin,priority);
            }
        } finally { jf.close(); }
    }

    public static void indexAar(SymbolIndex index, File aar, ImportLanguage lang,
                                SymbolIndex.Origin origin, int priority) throws IOException {
        ZipFile z=new ZipFile(aar);
        try {
            ZipEntry e=z.getEntry("classes.jar");
            if(e==null)return;
            PathTemp tmp=new PathTemp();
            File f=tmp.file;
            InputStream in=z.getInputStream(e); OutputStream out=new FileOutputStream(f);
            try{byte[] b=new byte[8192];int r;while((r=in.read(b))!=-1)out.write(b,0,r);}
            finally{in.close();out.close();}
            try{indexJar(index,f,lang,origin,priority);}finally{f.delete();}
        } finally { z.close(); }
    }

    private static final class PathTemp {
        final File file;
        PathTemp() throws IOException { file=File.createTempFile("importmodel",".jar"); }
    }

    public static void indexSource(SymbolIndex index, File root, ImportLanguage lang,
                                   SymbolIndex.Origin origin, int priority) throws IOException {
        if(root==null || !root.exists()) return;
        if(root.isFile()){scanSource(index,root,lang,origin,priority);return;}
        ArrayDeque<File> q=new ArrayDeque<>();q.add(root);
        while(!q.isEmpty()){
            File d=q.remove();
            File[] fs=d.listFiles(); if(fs==null)continue;
            for(File f:fs){
                if(f.isDirectory())q.add(f);
                else if(f.getName().endsWith(lang==ImportLanguage.KOTLIN?".kt":".java"))
                    scanSource(index,f,lang,origin,priority);
            }
        }
    }

    private static void scanSource(SymbolIndex index, File f, ImportLanguage lang,
                                   SymbolIndex.Origin origin, int priority) throws IOException {
        String s=read(f);
        String pkg="";
        java.util.regex.Matcher pm=java.util.regex.Pattern.compile("\\bpackage\\s+([\\w.]+)").matcher(s);
        if(pm.find())pkg=pm.group(1)+".";
        java.util.regex.Matcher cm=java.util.regex.Pattern.compile(
            "\\b(class|interface|enum|object|record|annotation\\s+class)\\s+([A-Za-z_]\\w*)").matcher(s);
        while(cm.find()) index.addType(pkg+cm.group(2),lang,origin,priority);
        if(lang==ImportLanguage.KOTLIN){
            java.util.regex.Matcher fm=java.util.regex.Pattern.compile(
                "(?m)^\\s*(?:public\\s+|private\\s+|internal\\s+|protected\\s+|inline\\s+|suspend\\s+|operator\\s+|infix\\s+)*fun\\s+([A-Za-z_]\\w*)\\s*\\(").matcher(s);
            while(fm.find()) {
                String n=fm.group(1);
                index.add(new SymbolIndex.Candidate(n,pkg+n,null,lang,
                    SymbolIndex.Kind.FUNCTION,origin,false,false,priority+2));
            }
        }
    }
    private static String read(File f)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        InputStream in=new FileInputStream(f);try{byte[] x=new byte[8192];int r;while((r=in.read(x))!=-1)b.write(x,0,r);}
        finally{in.close();}return new String(b.toByteArray(),"UTF-8");
    }
}
