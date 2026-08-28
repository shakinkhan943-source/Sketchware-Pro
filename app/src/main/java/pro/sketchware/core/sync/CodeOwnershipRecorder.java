package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-scoped recorder that lets the regular code generation pipeline annotate which
 * parts of the generated Java source were produced by which block.
 * <p>
 * The recorder is <b>off by default</b>. Only the Java editor / synchronization layer turns it on
 * (see {@link JavaSourceMapper}), so the normal build pipeline is completely unaffected and keeps
 * producing byte-identical output.
 * <p>
 * While a recorder is active, {@link pro.sketchware.core.codegen.BlockInterpreter} wraps the code of
 * every top level block of an event/More Block chain into a pair of line comments:
 * <pre>
 * //sketchware-sync-begin:12
 * t1.setText("");
 * //sketchware-sync-end:12
 * </pre>
 * The markers are plain single line comments, so they survive every later stage of the pipeline
 * (indentation via {@code CodeFormatter}, command blocks, string post-processing …) without changing
 * the semantics of the generated code. {@link JavaSourceMapper} removes them again and converts the
 * marker positions into a {@link MappedSource} — a clean source file plus an exact line range for
 * every block that took part in generating it.
 */
public final class CodeOwnershipRecorder {

    public static final String BEGIN_PREFIX = "//sketchware-sync-begin:";
    public static final String END_PREFIX = "//sketchware-sync-end:";

    private static final ThreadLocal<CodeOwnershipRecorder> ACTIVE = new ThreadLocal<>();

    private final List<Owner> owners = new ArrayList<>();

    private CodeOwnershipRecorder() {
    }

    /**
     * Starts recording on the current thread.
     * Callers <b>must</b> call {@link #stop()} in a finally block.
     */
    public static CodeOwnershipRecorder start() {
        CodeOwnershipRecorder recorder = new CodeOwnershipRecorder();
        ACTIVE.set(recorder);
        return recorder;
    }

    public static void stop() {
        ACTIVE.remove();
    }

    /**
     * @return the recorder of the current thread or {@code null} when ownership recording is off
     * (the default, e.g. during a normal build).
     */
    public static CodeOwnershipRecorder active() {
        return ACTIVE.get();
    }

    /**
     * Registers a piece of generated code and surrounds it with ownership markers.
     *
     * @param ownerKey the block entry key the code belongs to, e.g. {@code "button1_onClick"},
     *                 {@code "onCreate_initializeLogic"} or {@code "myMoreBlock_moreBlock"}
     * @param blockId  the {@link pro.sketchware.beans.BlockBean#id} of the top level block
     * @param opCode   the block's opCode, used to decide later how an edit can be pushed back
     * @param code     the generated code of that single block (including its sub stacks)
     * @return {@code code} surrounded by marker comments
     */
    public String wrap(String ownerKey, String blockId, String opCode, String code) {
        if (code == null || code.trim().isEmpty()) {
            return code;
        }
        int token = owners.size();
        owners.add(new Owner(token, ownerKey, blockId, opCode));
        return BEGIN_PREFIX + token + "\r\n" + code + "\r\n" + END_PREFIX + token;
    }

    public Owner get(int token) {
        if (token < 0 || token >= owners.size()) {
            return null;
        }
        return owners.get(token);
    }

    public int size() {
        return owners.size();
    }

    /**
     * Parses a marker line. Returns the token or {@code -1} when the line isn't a marker.
     *
     * @param line  a single (possibly indented) source line
     * @param begin {@code true} to match begin markers, {@code false} for end markers
     */
    public static int parseMarker(String line, boolean begin) {
        String trimmed = line.trim();
        String prefix = begin ? BEGIN_PREFIX : END_PREFIX;
        if (!trimmed.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(trimmed.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean isMarker(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith(BEGIN_PREFIX) || trimmed.startsWith(END_PREFIX);
    }

    /**
     * Metadata of a single generated code region.
     */
    public static final class Owner {
        public final int token;
        public final String ownerKey;
        public final String blockId;
        public final String opCode;

        Owner(int token, String ownerKey, String blockId, String opCode) {
            this.token = token;
            this.ownerKey = ownerKey;
            this.blockId = blockId;
            this.opCode = opCode;
        }
    }
}
