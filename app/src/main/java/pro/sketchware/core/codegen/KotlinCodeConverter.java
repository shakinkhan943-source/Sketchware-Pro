package pro.sketchware.core.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the deterministic Java snippets emitted by Sketchware's legacy block/component
 * registries to Kotlin syntax.
 *
 * <p>This is deliberately not advertised as a general Java-to-Kotlin transpiler. It handles the
 * shapes produced by {@link BlockCodeRegistry}, {@link EventCodeRegistry},
 * {@link ListenerCodeRegistry} and {@link ComponentCodeGenerator}: declarations, primitive casts,
 * constructor calls, loops, listener object expressions and method signatures. Keeping this
 * boundary in one class prevents Kotlin Activities from accidentally receiving Java syntax while
 * the registries are migrated gradually.</p>
 */
public final class KotlinCodeConverter {

    private static final Pattern CLASS_THIS = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\.this\\b");
    private static final Pattern SIMPLE_CAST = Pattern.compile(
            "\\((int|long|double|float|short|byte|char|boolean)\\)\\s*([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*(?:\\([^;{}]*?\\))?)?)");
    private static final Pattern REFERENCE_CAST = Pattern.compile(
            "\\(([A-Z][\\w.$]*(?:<[^(){};]+>)?)\\)\\s*([A-Za-z_$][\\w.$]*(?:\\([^;{}]*?\\))?)");
    private static final Pattern DECIMAL_SUFFIX = Pattern.compile(
            "(?<![A-Za-z0-9_$])([0-9]+(?:\\.[0-9]+)?)[dD](?![A-Za-z0-9_$])");
    private static final Pattern FOR_LOOP = Pattern.compile(
            "^(\\s*)for\\s*\\(\\s*int\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(.+?)\\s*;\\s*\\2\\s*(<|<=)\\s*(.+?)\\s*;\\s*\\2\\+\\+\\s*\\)\\s*\\{\\s*$");
    private static final Pattern FOR_EACH = Pattern.compile(
            "^(\\s*)for\\s*\\(\\s*(?:final\\s+)?(.+?)\\s+([A-Za-z_$][\\w$]*)\\s*:\\s*(.+?)\\s*\\)\\s*\\{\\s*$");
    private static final Pattern CATCH = Pattern.compile(
            "catch\\s*\\(\\s*([A-Za-z_$][\\w.$]*(?:<[^>]+>)?)\\s+([A-Za-z_$][\\w$]*)\\s*\\)");
    private static final Pattern METHOD = Pattern.compile(
            "^(\\s*)((?:(?:public|protected|private|static|final|synchronized|abstract)\\s+)*)([A-Za-z_$][\\w.$<>?, \\[\\]]*)\\s+([A-Za-z_$][\\w$]*)\\s*\\((.*)\\)\\s*(?:throws\\s+[^\\{]+)?\\{\\s*$");
    private static final Pattern OBJECT_EXPRESSION = Pattern.compile(
            "new\\s+([A-Za-z_$][\\w.$]*(?:<[^{};()]+>)?)\\s*\\(([^(){}]*)\\)\\s*\\{");
    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "\\bnew\\s+([A-Za-z_$][\\w.$]*(?:<[^{};()]+>)?)\\s*\\(");
    private static final Pattern TYPE_TOKEN = Pattern.compile(
            "new\\s+TypeToken<(.+?)>\\s*\\(\\s*\\)\\s*\\{\\s*}\\s*\\.getType\\(\\)");

    private KotlinCodeConverter() {
    }

    /** Converts one or more executable block statements/expressions. */
    public static String convertBlock(String javaCode) {
        if (javaCode == null || javaCode.isEmpty()) return javaCode == null ? "" : javaCode;

        String normalized = javaCode.replace("\r\n", "\n").replace('\r', '\n');
        normalized = convertSwitches(normalized);
        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder(normalized.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            String line = convertControlFlowLine(lines[i]);
            line = convertDeclarationLine(line, false);
            line = transformOutsideLiterals(line);
            line = line.replaceAll("\\breturn\\s*;", "return");
            result.append(line);
            if (i < lines.length - 1) result.append('\n');
        }
        return result.toString().replace("\n", ActivityCodeGenerator.EOL);
    }

    /** Converts generated fields, listener objects and Java method declarations to Kotlin members. */
    public static String convertMembers(String javaCode) {
        if (javaCode == null || javaCode.trim().isEmpty()) return "";

        String normalized = javaCode.replace("\r\n", "\n").replace('\r', '\n');
        normalized = convertSwitches(normalized);
        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder(normalized.length() + 64);
        boolean overrideNext = false;

        for (String original : lines) {
            String line = original;
            String trimmed = line.trim();
            if ("@Override".equals(trimmed)) {
                overrideNext = true;
                continue;
            }
            // Java nullability annotations on generated method parameters have no useful meaning in
            // Kotlin source. Nullability for Android lifecycle parameters is applied below.
            if ("@NonNull".equals(trimmed) || "@Nullable".equals(trimmed)) {
                continue;
            }

            String method = convertMethodLine(line, overrideNext);
            if (method != null) {
                line = method;
                overrideNext = false;
            } else {
                line = convertControlFlowLine(line);
                line = convertDeclarationLine(line, true);
            }
            line = transformOutsideLiterals(line);
            line = line.replaceAll("\\breturn\\s*;", "return");
            result.append(line).append('\n');
        }
        if (result.length() > 0) result.setLength(result.length() - 1);
        return result.toString().replace("\n", ActivityCodeGenerator.EOL);
    }

    /** Normalizes Java-style import statements supplied by Import blocks. */
    public static String convertImports(String imports) {
        if (imports == null || imports.trim().isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String raw : imports.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("import ")) {
                line = line.substring(0, line.length() - (line.endsWith(";") ? 1 : 0));
            }
            if (!line.startsWith("import ")) line = "import " + line;
            if (result.length() > 0) result.append(ActivityCodeGenerator.EOL);
            result.append(line);
        }
        return result.toString();
    }

    private static String convertControlFlowLine(String line) {
        Matcher loop = FOR_LOOP.matcher(line);
        if (loop.matches()) {
            String start = transformOutsideLiterals(loop.group(3).trim());
            String end = transformOutsideLiterals(loop.group(5).trim());
            String range = "<=".equals(loop.group(4))
                    ? start + ".." + end
                    : start + " until " + end;
            return loop.group(1) + "for (" + loop.group(2) + " in " + range + ") {";
        }

        Matcher each = FOR_EACH.matcher(line);
        if (each.matches() && !each.group(2).contains(";")) {
            return each.group(1) + "for (" + each.group(3) + " in "
                    + transformOutsideLiterals(each.group(4).trim()) + ") {";
        }

        Matcher catchMatcher = CATCH.matcher(line);
        if (catchMatcher.find()) {
            line = catchMatcher.replaceFirst("catch (" + catchMatcher.group(2) + ": "
                    + kotlinType(catchMatcher.group(1), false) + ")");
        }
        return line;
    }

    private static String convertMethodLine(String line, boolean overrideMethod) {
        Matcher matcher = METHOD.matcher(line);
        if (!matcher.matches()) return null;

        String modifiers = matcher.group(2);
        String returnType = matcher.group(3).trim();
        String name = matcher.group(4);
        // Exclude control-flow constructs that can satisfy the permissive signature regex.
        if ("if".equals(name) || "for".equals(name) || "while".equals(name)
                || "switch".equals(name) || "catch".equals(name)) return null;

        StringBuilder out = new StringBuilder(matcher.group(1));
        if (modifiers.contains("private ")) out.append("private ");
        else if (modifiers.contains("protected ")) out.append("protected ");
        if (overrideMethod) out.append("override ");
        out.append("fun ").append(name).append('(')
                .append(convertParameters(matcher.group(5))).append(')');
        if (!"void".equals(returnType)) {
            out.append(": ").append(kotlinType(returnType, false));
        }
        out.append(" {");
        return out.toString();
    }

    private static String convertParameters(String javaParameters) {
        if (javaParameters == null || javaParameters.trim().isEmpty()) return "";
        List<String> params = splitTopLevel(javaParameters, ',');
        StringBuilder result = new StringBuilder();
        for (String raw : params) {
            String parameter = raw.trim()
                    .replace("@NonNull ", "")
                    .replace("@Nullable ", "")
                    .replace("final ", "");
            boolean explicitlyNullable = raw.contains("@Nullable");
            int split = lastTypeSeparator(parameter);
            if (split < 0) {
                if (result.length() > 0) result.append(", ");
                result.append(parameter);
                continue;
            }
            String type = parameter.substring(0, split).trim();
            String name = parameter.substring(split + 1).trim();
            if (type.endsWith("...")) {
                type = type.substring(0, type.length() - 3);
                if (result.length() > 0) result.append(", ");
                result.append("vararg ").append(name).append(": ").append(kotlinType(type, false));
                continue;
            }
            boolean lifecycleNullable = "_savedInstanceState".equals(name)
                    || ("_data".equals(name) && "Intent".equals(type));
            if (result.length() > 0) result.append(", ");
            result.append(name).append(": ")
                    .append(kotlinType(type, explicitlyNullable || lifecycleNullable));
        }
        return result.toString();
    }

    private static int lastTypeSeparator(String parameter) {
        int genericDepth = 0;
        for (int i = parameter.length() - 1; i >= 0; i--) {
            char c = parameter.charAt(i);
            if (c == '>') genericDepth++;
            else if (c == '<') genericDepth--;
            else if (Character.isWhitespace(c) && genericDepth == 0) return i;
        }
        return -1;
    }

    private static String convertDeclarationLine(String line, boolean allowMemberModifiers) {
        String indentation = leadingWhitespace(line);
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")
                || trimmed.startsWith("return ") || trimmed.startsWith("throw ")
                || trimmed.startsWith("case ") || trimmed.startsWith("else ")
                || trimmed.startsWith("if ") || trimmed.startsWith("if(")
                || trimmed.startsWith("while ") || trimmed.startsWith("for ")
                || trimmed.startsWith("when ") || trimmed.startsWith("catch ")
                || trimmed.startsWith("package ") || trimmed.startsWith("import ")
                || trimmed.contains(" -> ") || !trimmed.endsWith(";")) {
            return line;
        }

        String statement = trimmed.substring(0, trimmed.length() - 1).trim();
        boolean isFinal = false;
        String visibility = "";
        boolean hadModifier = false;
        boolean removing = true;
        while (removing) {
            removing = false;
            for (String modifier : new String[]{"public", "protected", "private", "static", "final", "transient", "volatile"}) {
                String prefix = modifier + " ";
                if (statement.startsWith(prefix)) {
                    statement = statement.substring(prefix.length()).trim();
                    hadModifier = true;
                    if ("final".equals(modifier)) isFinal = true;
                    if (allowMemberModifiers && ("private".equals(modifier)
                            || "protected".equals(modifier) || "public".equals(modifier))) {
                        visibility = "public".equals(modifier) ? "" : modifier + " ";
                    }
                    removing = true;
                    break;
                }
            }
        }

        int equals = findTopLevel(statement, '=');
        String left = equals < 0 ? statement : statement.substring(0, equals).trim();
        String initializer = equals < 0 ? "" : statement.substring(equals + 1).trim();
        int separator = lastTypeSeparator(left);
        if (separator < 0) return line;
        String type = left.substring(0, separator).trim();
        String name = left.substring(separator + 1).trim();

        if (!name.matches("[A-Za-z_$][\\w$]*(?:\\[\\])?") || type.contains("(")
                || type.contains(")") || type.contains("=") || type.contains("." + name)) {
            return line;
        }
        if (!hadModifier && !looksLikeType(type)) return line;
        if (name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2);
            type += "[]";
        }

        boolean nullInitializer = "null".equals(initializer);
        String kotlinType = kotlinType(type, nullInitializer);
        StringBuilder converted = new StringBuilder(indentation).append(visibility);
        if (initializer.isEmpty()) {
            if (isPrimitiveKotlinType(kotlinType)) {
                converted.append("var ").append(name).append(": ").append(kotlinType)
                        .append(" = ").append(defaultValue(kotlinType));
            } else {
                converted.append("lateinit var ").append(name).append(": ").append(kotlinType);
            }
        } else {
            String convertedInitializer = transformOutsideLiterals(initializer);
            if ("Double".equals(kotlinType) && convertedInitializer.matches("[-+]?[0-9]+")) {
                convertedInitializer += ".0";
            } else if ("Float".equals(kotlinType)
                    && convertedInitializer.matches("[-+]?[0-9]+(?:\\.[0-9]+)?")) {
                convertedInitializer += "f";
            } else if ("Long".equals(kotlinType)
                    && convertedInitializer.matches("[-+]?[0-9]+")) {
                convertedInitializer += "L";
            }
            converted.append(isFinal ? "val " : "var ").append(name).append(": ")
                    .append(kotlinType).append(" = ").append(convertedInitializer);
        }
        if (trimmed.endsWith(";")) converted.append(';');
        return converted.toString();
    }

    private static boolean looksLikeType(String type) {
        String raw = type.trim();
        return raw.matches("(?:boolean|byte|short|int|long|float|double|char|String|Object)(?:\\[\\])?")
                || raw.contains("<") || raw.endsWith("[]")
                || (!raw.isEmpty() && (Character.isUpperCase(raw.charAt(0)) || raw.contains(".")));
    }

    private static String transformOutsideLiterals(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        input = convertTernaryExpression(input);
        input = convertArrayInitializers(input);
        StringBuilder result = new StringBuilder(input.length() + 16);
        StringBuilder code = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!inString && !inChar && c == '/' && i + 1 < input.length()
                    && input.charAt(i + 1) == '/') {
                result.append(transformCodeSegment(code.toString()));
                result.append(input.substring(i));
                return result.toString();
            }
            if (escaped) {
                (inString || inChar ? result : code).append(c);
                escaped = false;
                continue;
            }
            if ((inString || inChar) && c == '\\') {
                result.append(c);
                escaped = true;
                continue;
            }
            if (!inChar && c == '"') {
                if (inString) {
                    result.append(c);
                    inString = false;
                } else {
                    result.append(transformCodeSegment(code.toString()));
                    code.setLength(0);
                    result.append(c);
                    inString = true;
                }
                continue;
            }
            if (!inString && c == '\'') {
                if (inChar) {
                    result.append(c);
                    inChar = false;
                } else {
                    result.append(transformCodeSegment(code.toString()));
                    code.setLength(0);
                    result.append(c);
                    inChar = true;
                }
                continue;
            }
            if (inString || inChar) result.append(c);
            else code.append(c);
        }
        result.append(transformCodeSegment(code.toString()));
        return result.toString();
    }

    private static String convertArrayInitializers(String code) {
        return code.replaceAll("new\\s+String\\s*\\[\\s*]\\s*\\{([^{}]*)}", "arrayOf($1)")
                .replaceAll("new\\s+int\\s*\\[\\s*([^]{}]+)\\s*]", "IntArray($1)")
                .replaceAll("new\\s+long\\s*\\[\\s*([^]{}]+)\\s*]", "LongArray($1)")
                .replaceAll("new\\s+double\\s*\\[\\s*([^]{}]+)\\s*]", "DoubleArray($1)")
                .replaceAll("new\\s+float\\s*\\[\\s*([^]{}]+)\\s*]", "FloatArray($1)")
                .replaceAll("new\\s+boolean\\s*\\[\\s*([^]{}]+)\\s*]", "BooleanArray($1)");
    }

    private static String transformCodeSegment(String code) {
        if (code.isEmpty()) return code;
        code = convertBalancedPrimitiveCasts(code);
        code = TYPE_TOKEN.matcher(code).replaceAll("object : TypeToken<$1>() {}.type");
        code = replaceObjectExpressions(code);
        code = convertArrayInitializers(code);
        code = CONSTRUCTOR.matcher(code).replaceAll("$1(");
        code = code.replaceAll("<\\s*>", "")
                .replace(".length()", ".length")
                .replace(".size()", ".size")
                .replace(".doubleValue()", ".toDouble()")
                .replace(".floatValue()", ".toFloat()")
                .replace(".longValue()", ".toLong()")
                .replace(".intValue()", ".toInt()")
                .replace(" | ", " or ")
                .replaceAll("\\binstanceof\\b", "is")
                .replaceAll("([A-Za-z_$][\\w.$]*)\\.class\\b", "$1::class.java");
        code = CLASS_THIS.matcher(code).replaceAll("this@$1");
        code = DECIMAL_SUFFIX.matcher(code).replaceAll("$1");

        Matcher simpleCast = SIMPLE_CAST.matcher(code);
        StringBuffer castBuffer = new StringBuffer();
        while (simpleCast.find()) {
            simpleCast.appendReplacement(castBuffer, Matcher.quoteReplacement(
                    simpleCast.group(2) + ".to" + primitiveType(simpleCast.group(1)) + "()"));
        }
        simpleCast.appendTail(castBuffer);
        code = castBuffer.toString();

        Matcher referenceCast = REFERENCE_CAST.matcher(code);
        StringBuffer refBuffer = new StringBuffer();
        while (referenceCast.find()) {
            referenceCast.appendReplacement(refBuffer, Matcher.quoteReplacement(
                    referenceCast.group(2) + " as " + kotlinType(referenceCast.group(1), false)));
        }
        referenceCast.appendTail(refBuffer);
        return refBuffer.toString();
    }

    private static String replaceObjectExpressions(String code) {
        Matcher matcher = OBJECT_EXPRESSION.matcher(code);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String type = kotlinType(matcher.group(1), false);
            String args = matcher.group(2).trim();
            String constructor = !args.isEmpty() || needsSuperConstructor(type)
                    ? "(" + args + ")" : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement("object : " + type + constructor + " {"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean needsSuperConstructor(String type) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        int generic = simple.indexOf('<');
        if (generic >= 0) simple = simple.substring(0, generic);
        return simple.contains("Callback") || simple.endsWith("Adapter")
                || simple.equals("TimerTask") || simple.equals("WebViewClient")
                || simple.equals("TypeToken") || simple.equals("AsyncTask")
                || simple.equals("AdListener") || simple.equals("BroadcastReceiver")
                || simple.equals("PhoneStateListener") || simple.equals("SimpleOnGestureListener")
                || simple.equals("GenericTypeIndicator");
    }

    private static int findTopLevelQuestion(String expression) {
        int round = 0;
        int square = 0;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((string || character) && c == '\\') {
                escaped = true;
                continue;
            }
            if (!character && c == '"') {
                string = !string;
                continue;
            }
            if (!string && c == '\'') {
                character = !character;
                continue;
            }
            if (string || character) continue;
            if (c == '(') round++;
            else if (c == ')') round = Math.max(0, round - 1);
            else if (c == '[') square++;
            else if (c == ']') square = Math.max(0, square - 1);
            else if (c == '?' && round == 0 && square == 0) return i;
        }
        return -1;
    }

    private static String convertTernaryExpression(String code) {
        String leading = leadingWhitespace(code);
        String expression = code.substring(leading.length()).trim();
        boolean semicolon = expression.endsWith(";");
        if (semicolon) expression = expression.substring(0, expression.length() - 1).trim();

        int question = findTopLevelQuestion(expression);
        if (question < 0) return code;
        int nested = 0;
        int colon = -1;
        int round = 0;
        int square = 0;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int i = question + 1; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((string || character) && c == '\\') {
                escaped = true;
                continue;
            }
            if (!character && c == '"') {
                string = !string;
                continue;
            }
            if (!string && c == '\'') {
                character = !character;
                continue;
            }
            if (string || character) continue;
            if (c == '(') round++;
            else if (c == ')') round = Math.max(0, round - 1);
            else if (c == '[') square++;
            else if (c == ']') square = Math.max(0, square - 1);
            else if (round == 0 && square == 0 && c == '?') nested++;
            else if (round == 0 && square == 0 && c == ':') {
                if (nested == 0) {
                    colon = i;
                    break;
                }
                nested--;
            }
        }
        if (colon < 0) return code;
        String condition = expression.substring(0, question).trim();
        String whenTrue = expression.substring(question + 1, colon).trim();
        String whenFalse = expression.substring(colon + 1).trim();
        if (condition.isEmpty() || whenTrue.isEmpty() || whenFalse.isEmpty()) return code;
        String converted = leading + "if (" + condition + ") " + whenTrue + " else " + whenFalse;
        return semicolon ? converted + ";" : converted;
    }

    private static String convertBalancedPrimitiveCasts(String code) {
        String result = code;
        for (String type : new String[]{"int", "long", "double", "float", "short", "byte", "char"}) {
            String token = "(" + type + ")(";
            int from = 0;
            while (true) {
                int start = result.indexOf(token, from);
                if (start < 0) break;
                int open = start + token.length() - 1;
                int close = findMatchingParen(result, open);
                if (close < 0) break;
                String expression = result.substring(open + 1, close);
                String replacement = "(" + expression + ").to" + primitiveType(type) + "()";
                result = result.substring(0, start) + replacement + result.substring(close + 1);
                from = start + replacement.length();
            }
        }
        return result;
    }

    private static int findMatchingParen(String text, int open) {
        int depth = 0;
        boolean string = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (string && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') string = !string;
            if (string) continue;
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static String primitiveType(String javaType) {
        return switch (javaType.toLowerCase(Locale.ROOT)) {
            case "int" -> "Int";
            case "long" -> "Long";
            case "double" -> "Double";
            case "float" -> "Float";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "char" -> "Char";
            case "boolean" -> "Boolean";
            default -> javaType;
        };
    }

    public static String kotlinType(String javaType, boolean nullable) {
        if (javaType == null) return "Any" + (nullable ? "?" : "");
        String type = javaType.trim()
                .replace("? extends ", "out ")
                .replace("? super ", "in ")
                .replace("<?>", "<*>")
                .replace("Object", "Any");
        type = type.replaceAll("\\bboolean\\b", "Boolean")
                .replaceAll("\\bbyte\\b", "Byte")
                .replaceAll("\\bshort\\b", "Short")
                .replaceAll("\\bint\\b", "Int")
                .replaceAll("\\blong\\b", "Long")
                .replaceAll("\\bfloat\\b", "Float")
                .replaceAll("\\bdouble\\b", "Double")
                .replaceAll("\\bchar\\b", "Char");
        if (type.endsWith("[]")) {
            String element = type.substring(0, type.length() - 2).trim();
            type = switch (element) {
                case "Int" -> "IntArray";
                case "Long" -> "LongArray";
                case "Double" -> "DoubleArray";
                case "Float" -> "FloatArray";
                case "Boolean" -> "BooleanArray";
                case "Byte" -> "ByteArray";
                case "Short" -> "ShortArray";
                case "Char" -> "CharArray";
                default -> "Array<" + element + ">";
            };
        }
        return nullable && !type.endsWith("?") ? type + "?" : type;
    }

    private static boolean isPrimitiveKotlinType(String type) {
        return type.equals("Boolean") || type.equals("Byte") || type.equals("Short")
                || type.equals("Int") || type.equals("Long") || type.equals("Float")
                || type.equals("Double") || type.equals("Char");
    }

    private static String defaultValue(String type) {
        return switch (type) {
            case "Boolean" -> "false";
            case "Char" -> "'\\u0000'";
            case "Float" -> "0f";
            case "Double" -> "0.0";
            case "Long" -> "0L";
            default -> "0";
        };
    }

    private static int findTopLevel(String text, char target) {
        int angle = 0;
        int round = 0;
        int square = 0;
        boolean string = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (string && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                string = !string;
                continue;
            }
            if (string) continue;
            if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '(') round++;
            else if (c == ')') round = Math.max(0, round - 1);
            else if (c == '[') square++;
            else if (c == ']') square = Math.max(0, square - 1);
            else if (c == target && angle == 0 && round == 0 && square == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String input, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int angle = 0;
        int round = 0;
        int square = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '(') round++;
            else if (c == ')') round = Math.max(0, round - 1);
            else if (c == '[') square++;
            else if (c == ']') square = Math.max(0, square - 1);
            else if (c == separator && angle == 0 && round == 0 && square == 0) {
                parts.add(input.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(input.substring(start));
        return parts;
    }

    /** Converts the simple switch/case form used by generated component callbacks. */
    private static String convertSwitches(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder result = new StringBuilder(source.length() + 32);
        boolean inSwitch = false;
        boolean branchOpen = false;
        int switchBraceDepth = 0;
        int depth = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inSwitch && trimmed.startsWith("switch (") && trimmed.endsWith("{")) {
                int start = line.indexOf("switch (") + "switch (".length();
                int end = line.lastIndexOf(')');
                result.append(line, 0, line.indexOf("switch"))
                        .append("when (").append(line, start, end).append(") {").append('\n');
                inSwitch = true;
                switchBraceDepth = depth;
                depth++;
                continue;
            }
            if (inSwitch && (trimmed.startsWith("case ") || trimmed.equals("default:"))) {
                if (branchOpen) result.append(indentation(line)).append("}").append('\n');
                String label = trimmed.equals("default:") ? "else"
                        : trimmed.substring(5, trimmed.length() - 1).trim();
                result.append(indentation(line)).append(label).append(" -> {").append('\n');
                branchOpen = true;
                continue;
            }
            if (inSwitch && "break;".equals(trimmed)) continue;

            int delta = braceDelta(line);
            if (inSwitch && trimmed.equals("}") && depth + delta == switchBraceDepth) {
                if (branchOpen) result.append(indentation(line)).append("}").append('\n');
                result.append(line).append('\n');
                depth += delta;
                inSwitch = false;
                branchOpen = false;
                continue;
            }
            result.append(line).append('\n');
            depth += delta;
        }
        if (result.length() > 0) result.setLength(result.length() - 1);
        return result.toString();
    }

    private static int braceDelta(String line) {
        int delta = 0;
        boolean string = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (string && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') string = !string;
            else if (!string && c == '{') delta++;
            else if (!string && c == '}') delta--;
        }
        return delta;
    }

    private static String indentation(String line) {
        return leadingWhitespace(line);
    }

    private static String leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return line.substring(0, index);
    }
}
