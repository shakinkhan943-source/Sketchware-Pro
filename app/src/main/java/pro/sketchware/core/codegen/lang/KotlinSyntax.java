package pro.sketchware.core.codegen.lang;

/**
 * Kotlin syntax rules: optional semicolons, {@code package}/{@code import} without
 * punctuation and Kotlin numeric literal forms.
 */
public final class KotlinSyntax implements SyntaxRules {

    static final KotlinSyntax INSTANCE = new KotlinSyntax();

    private KotlinSyntax() {
    }

    @Override
    public CodeGenerationLanguage language() {
        return CodeGenerationLanguage.KOTLIN;
    }

    @Override
    public String packageDeclaration(String packageName) {
        return "package " + packageName;
    }

    @Override
    public String importStatement(String imported) {
        return "import " + normalizeImportName(imported);
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
        // Kotlin does not end statements with a semicolon. Block bodies that arrive from
        // legacy Java templates are normalized by the Kotlin template renderer
        // (KotlinCodeConverter); anything the Kotlin generator composes itself is emitted
        // without one from the start.
        String trimmed = code == null ? "" : code.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    @Override
    public String stripStatementTerminator(String code) {
        return statement(code);
    }

    @Override
    public String wholeNumberLiteral(String digits) {
        // Sketchware numbers are doubles; Kotlin has no implicit int -> double widening.
        String literal = digits == null ? "" : digits.trim();
        if (literal.isEmpty()) {
            return "0.0";
        }
        return literal.endsWith(".0") ? literal : literal + ".0";
    }

    @Override
    public String fractionalNumberLiteral(String digits) {
        String literal = digits == null ? "" : digits.trim();
        while (literal.endsWith("d") || literal.endsWith("D") || literal.endsWith("f")
                || literal.endsWith("F")) {
            literal = literal.substring(0, literal.length() - 1).trim();
        }
        return literal;
    }
}
