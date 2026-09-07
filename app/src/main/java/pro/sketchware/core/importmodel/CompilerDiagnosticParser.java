package pro.sketchware.core.importmodel;

import java.util.*;
import java.util.regex.*;

public final class CompilerDiagnosticParser {
    private static final Pattern K=Pattern.compile("Unresolved reference:\\s*([A-Za-z_]\\w*)");
    private static final Pattern J=Pattern.compile("cannot find symbol[\\s\\S]*?symbol:\\s*(?:class|variable|method)\\s+([A-Za-z_]\\w*)");
    public List<CompilerDiagnostic> parse(String text,ImportLanguage lang){
        List<CompilerDiagnostic> out=new ArrayList<>();if(text==null)return out;
        Matcher m=(lang==ImportLanguage.KOTLIN?K:J).matcher(text);
        while(m.find())out.add(new CompilerDiagnostic(m.group(1),-1,-1,m.group(),lang));
        return out;
    }
}
