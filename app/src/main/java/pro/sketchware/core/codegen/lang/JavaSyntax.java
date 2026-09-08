package pro.sketchware.core.codegen.lang;

/**
 * Java syntax rules: statement terminators, {@code package}/{@code import} punctuation and
 * Java numeric literal suffixes.
 */
public final class JavaSyntax implements SyntaxRules {

    static final JavaSyntax INSTANCE = new JavaSyntax();

    private JavaSyntax() {
    }

    @Override
    public CodeGenerationLanguage language() {
        return CodeGenerationLanguage.JAVA;
    }

    @Override
    public String packageDeclaration(String packageName) {
        return "package " + packageName + ";";
    }

    @Override
    public String importStatement(String imported) {
        return "import " + normalizeImportName(imported) + ";";
    }

    private static String normalizeImportName(String imported) {
        String name = imported == null ? "" : imported.trim();
        while (name.endsWith(";")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        if (name.startsWith("import ")) {
            name = name.substring("import ".length()).trim();
        }
        return name;
    }

    @Override
    public String statement(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty() || trimmed.endsWith(";") || trimmed.endsWith("}")
                || trimmed.endsWith("{")) {
            return trimmed;
        }
        return trimmed + ";";
    }

    @Override
    public String stripStatementTerminator(String code) {
        // Java keeps its terminator; nothing to strip.
        return code;
    }

    @Override
    public String wholeNumberLiteral(String digits) {
        // Java implicitly widens integer literals to double.
        return digits;
    }

    @Override
    public String fractionalNumberLiteral(String digits) {
        String literal = digits == null ? "" : digits.trim();
        if (literal.endsWith("d") || literal.endsWith("D") || literal.endsWith("f")
                || literal.endsWith("F")) {
            return literal;
        }
        return literal + "d";
    }
}
