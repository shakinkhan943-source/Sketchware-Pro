package importmodel;

public final class ImportManager {
    private final SourceAnalyzer analyzer;
    private final ImportProcessor processor;
    private final SymbolIndex index;
    public ImportManager(SymbolIndex index){this.index=index;this.analyzer=new SourceAnalyzer();this.processor=new ImportProcessor(analyzer);}
    public ImportModel.Result resolve(ImportModel.Request r){
        return processor.process(new ImportModel.Request(r.source,r.language,r.context,
            r.options==null?ImportModel.Options.resolveDefaults():r.options),index);
    }
    public ImportModel.Result organize(ImportModel.Request r){
        return processor.process(new ImportModel.Request(r.source,r.language,r.context,
            r.options==null?ImportModel.Options.organizeDefaults():r.options),index);
    }
}
