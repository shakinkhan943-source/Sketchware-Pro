package pro.sketchware.core.codegen.lang;

/**
 * The source language a generator has to emit.
 *
 * <p>This is the single decision point for language-specific generation: every code generator
 * (Java Activity generator, Kotlin/Compose Activity generator, block interpreter, component
 * generator) asks its {@link SyntaxRules} instead of hard-coding Java or Kotlin syntax in
 * shared code. Shared code stays language-neutral; everything that differs between the two
 * languages lives in {@link JavaSyntax} and {@link KotlinSyntax}.</p>
 */
public enum CodeGenerationLanguage {

    JAVA,
    KOTLIN;

    public static CodeGenerationLanguage fromKotlin(boolean isKotlin) {
        return isKotlin ? KOTLIN : JAVA;
    }

    public boolean isKotlin() {
        return this == KOTLIN;
    }

    public boolean isJava() {
        return this == JAVA;
    }

    /** The syntax rules of this language. */
    public SyntaxRules syntax() {
        return this == KOTLIN ? KotlinSyntax.INSTANCE : JavaSyntax.INSTANCE;
    }
}
