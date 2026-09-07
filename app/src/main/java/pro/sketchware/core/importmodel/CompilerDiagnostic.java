package importmodel;

public final class CompilerDiagnostic {
    public final String symbol; public final int line,column; public final String message;
    public final ImportLanguage language;
    public CompilerDiagnostic(String symbol,int line,int column,String message,ImportLanguage language){
        this.symbol=symbol;this.line=line;this.column=column;this.message=message;this.language=language;
    }
}
