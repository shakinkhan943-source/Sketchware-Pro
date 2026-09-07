package importmodel;

import java.util.*;

public final class ImportFormatter {
    public String format(Collection<SourceAnalyzer.Import> imports,ImportLanguage language,String nl){
        List<SourceAnalyzer.Import> x=new ArrayList<>(imports);
        Collections.sort(x,new Comparator<SourceAnalyzer.Import>(){
            public int compare(SourceAnalyzer.Import a,SourceAnalyzer.Import b){return a.text(language).compareToIgnoreCase(b.text(language));}
        });
        StringBuilder b=new StringBuilder();for(SourceAnalyzer.Import i:x)b.append(i.text(language)).append(nl);
        return b.toString();
    }
}
