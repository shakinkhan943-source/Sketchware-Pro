package pro.sketchware.core.codegen.lang;

/**
 * Language-specific source rules shared by the generators.
 *
 * <p>Implementations ({@link JavaSyntax}, {@link KotlinSyntax}) own every construct that is
 * spelled differently between Java and Kotlin: package/import statements, statement
 * termination and numeric literal forms. Generators and shared template code call these
 * methods instead of concatenating Java punctuation, so the same neutral template can be
 * rendered for either language without post-generation string surgery.</p>
 */
public interface SyntaxRules {

    CodeGenerationLanguage language();

    /** e.g. {@code package com.example;} / {@code package com.example} */
    String packageDeclaration(String packageName);

    /**
     * Renders one import declaration. Accepts raw names ({@code java.util.HashMap}) as well as
     * already-written import lines ({@code import java.util.HashMap;}) and normalizes them to the
     * language's punctuation.
     */
    String importStatement(String imported);

    /**
     * Appends the language's statement terminator to an executable statement
     * (and never duplicates one).
     */
    String statement(String code);

    /**
     * Removes a trailing statement terminator if the language does not use one,
     * leaving other lines untouched.
     */
    String stripStatementTerminator(String code);

    /**
     * Renders a number the user typed as a whole Sketchware number (Sketchware models
     * every Number as {@code double}): Java accepts the bare digits, Kotlin needs a
     * Double literal.
     */
    String wholeNumberLiteral(String digits);

    /** Renders a fractional number literal (Java keeps the {@code d} suffix, Kotlin drops it). */
    String fractionalNumberLiteral(String digits);
}
