package pro.sketchware.core.importmodel.integration;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import pro.sketchware.core.importmodel.ImportLanguage;
import pro.sketchware.core.importmodel.SymbolIndex;

/**
 * Indexes Kotlin top-level functions and properties found in compiled file facades.
 *
 * <p>{@link pro.sketchware.core.importmodel.SymbolIndexer} intentionally does not guess members of
 * {@code ...Kt} facade classes, but Kotlin code imports top-level functions by
 * {@code package.functionName} — e.g. {@code androidx.compose.foundation.layout.Column}, which is
 * a static method of {@code androidx.compose.foundation.layout.ColumnKt}. This indexer reads only
 * the public static method names of classes whose name ends in {@code Kt} straight from the class
 * file's constant pool, so the resolver can offer them as real, classpath-derived candidates
 * instead of a hardcoded map.</p>
 *
 * <p>Only {@code *Kt} entries are parsed, which keeps the extra work small, and it runs exactly
 * once per index build (i.e. only when the classpath fingerprint changed).</p>
 */
final class KotlinFacadeIndexer {

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_SYNTHETIC = 0x1000;

    private KotlinFacadeIndexer() {
    }

    static void indexJar(SymbolIndex index, File jar, SymbolIndex.Origin origin, int priority) throws IOException {
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String path = entry.getName();
                if (!path.endsWith("Kt.class")) continue;

                String binaryName = path.substring(0, path.length() - 6).replace('/', '.');
                int lastDot = binaryName.lastIndexOf('.');
                String packageName = lastDot < 0 ? "" : binaryName.substring(0, lastDot);

                try (InputStream in = jarFile.getInputStream(entry)) {
                    for (String method : readPublicStaticMethodNames(in)) {
                        String qualified = packageName.isEmpty() ? method : packageName + "." + method;
                        index.add(new SymbolIndex.Candidate(method, qualified, binaryName,
                                ImportLanguage.KOTLIN, SymbolIndex.Kind.FUNCTION, origin,
                                false, false, priority + 1));
                    }
                } catch (Throwable ignored) {
                    // A single unreadable facade must not abort the whole index build.
                }
            }
        }
    }

    /** Minimal class-file reader: constant pool, then the method table's names. */
    private static java.util.List<String> readPublicStaticMethodNames(InputStream stream) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<>();
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(stream));

        if (in.readInt() != 0xCAFEBABE) return names;
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major

        int constantPoolCount = in.readUnsignedShort();
        String[] utf8 = new String[constantPoolCount];
        for (int i = 1; i < constantPoolCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: // Utf8
                    utf8[i] = in.readUTF();
                    break;
                case 7:  // Class
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    in.skipBytes(2);
                    break;
                case 15: // MethodHandle
                    in.skipBytes(3);
                    break;
                case 3:  // Integer
                case 4:  // Float
                case 9:  // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    in.skipBytes(4);
                    break;
                case 5: // Long
                case 6: // Double
                    in.skipBytes(8);
                    i++; // these take two constant pool slots
                    break;
                default:
                    return names; // unknown tag: give up on this class, safely
            }
        }

        in.readUnsignedShort(); // access flags
        in.readUnsignedShort(); // this class
        in.readUnsignedShort(); // super class
        int interfaces = in.readUnsignedShort();
        in.skipBytes(interfaces * 2);

        int fieldCount = in.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) skipMember(in);

        int methodCount = in.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            int accessFlags = in.readUnsignedShort();
            int nameIndex = in.readUnsignedShort();
            in.readUnsignedShort(); // descriptor
            int attributeCount = in.readUnsignedShort();
            for (int a = 0; a < attributeCount; a++) skipAttribute(in);

            boolean isPublicStatic = (accessFlags & ACC_PUBLIC) != 0 && (accessFlags & ACC_STATIC) != 0;
            if (!isPublicStatic || (accessFlags & ACC_SYNTHETIC) != 0) continue;

            String name = nameIndex < utf8.length ? utf8[nameIndex] : null;
            if (name == null || name.isEmpty() || name.charAt(0) == '<') continue;
            if (name.indexOf('$') >= 0) continue; // synthetic/default/lambda helpers
            if (!Character.isJavaIdentifierStart(name.charAt(0))) continue;
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }

    private static void skipMember(DataInputStream in) throws IOException {
        in.skipBytes(6); // access flags, name index, descriptor index
        int attributeCount = in.readUnsignedShort();
        for (int i = 0; i < attributeCount; i++) skipAttribute(in);
    }

    private static void skipAttribute(DataInputStream in) throws IOException {
        in.skipBytes(2); // name index
        int length = in.readInt();
        int skipped = 0;
        while (skipped < length) {
            int step = (int) in.skip(length - skipped);
            if (step <= 0) {
                if (in.read() < 0) return;
                step = 1;
            }
            skipped += step;
        }
    }
}
