package pro.sketchware.core.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Myers line diff used to align the Java source the user edited with the source the synchronization
 * layer generated.
 * <p>
 * Lines are compared <b>trimmed</b>, so pure indentation/formatting changes never look like an edit;
 * this is what makes the mapping resilient against re-formatting and moved code.
 */
public final class LineDiff {

    public enum Type {
        EQUAL,
        DELETE,
        INSERT
    }

    public static class Op {
        public final Type type;
        /**
         * Index in the original (baseline) list, {@code -1} for {@link Type#INSERT}.
         */
        public final int oldIndex;
        /**
         * Index in the new (edited) list, {@code -1} for {@link Type#DELETE}.
         */
        public final int newIndex;

        Op(Type type, int oldIndex, int newIndex) {
            this.type = type;
            this.oldIndex = oldIndex;
            this.newIndex = newIndex;
        }

        @Override
        public String toString() {
            return type + "(" + oldIndex + "," + newIndex + ")";
        }
    }

    public static class Result {
        public final List<Op> ops;
        /**
         * {@code false} when the two versions were too different to be diffed reliably. Callers must
         * then refuse to synchronize automatically instead of destroying data.
         */
        public final boolean reliable;

        Result(List<Op> ops, boolean reliable) {
            this.ops = ops;
            this.reliable = reliable;
        }
    }

    /**
     * Hard limit on the edit distance. Beyond that the two files have almost nothing in common and
     * an automatic synchronization would not be trustworthy.
     */
    private static final int MAX_EDIT_DISTANCE = 6000;

    private LineDiff() {
    }

    public static Result diff(List<String> oldLines, List<String> newLines) {
        List<String> a = normalize(oldLines);
        List<String> b = normalize(newLines);

        List<Op> ops = new ArrayList<>();

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            ops.add(new Op(Type.EQUAL, prefix, prefix));
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }

        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);

        List<Op> midOps = new ArrayList<>();
        boolean reliable = myers(midA, midB, prefix, midOps);
        ops.addAll(midOps);

        for (int i = 0; i < suffix; i++) {
            int oldIndex = a.size() - suffix + i;
            int newIndex = b.size() - suffix + i;
            ops.add(new Op(Type.EQUAL, oldIndex, newIndex));
        }
        return new Result(ops, reliable);
    }

    private static List<String> normalize(List<String> lines) {
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) {
            normalized.add(line == null ? "" : line.trim());
        }
        return normalized;
    }

    private static boolean myers(List<String> a, List<String> b, int offset, List<Op> out) {
        int n = a.size();
        int m = b.size();
        if (n == 0 && m == 0) {
            return true;
        }
        if (n == 0) {
            for (int j = 0; j < m; j++) {
                out.add(new Op(Type.INSERT, -1, offset + j));
            }
            return true;
        }
        if (m == 0) {
            for (int i = 0; i < n; i++) {
                out.add(new Op(Type.DELETE, offset + i, -1));
            }
            return true;
        }

        int max = Math.min(n + m, MAX_EDIT_DISTANCE);
        int size = 2 * max + 2;
        int[] v = new int[size];
        List<int[]> trace = new ArrayList<>();
        int offsetK = max;

        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int index = k + offsetK;
                if (index < 0 || index + 1 >= size) {
                    continue;
                }
                int x;
                if (k == -d || (k != d && v[index - 1] < v[index + 1])) {
                    x = v[index + 1];
                } else {
                    x = v[index - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[index] = x;
                if (x >= n && y >= m) {
                    backtrack(a, b, trace, d, offsetK, offset, out);
                    return true;
                }
            }
        }
        // Too different: report every line as replaced but tell the caller it isn't reliable.
        for (int i = 0; i < n; i++) {
            out.add(new Op(Type.DELETE, offset + i, -1));
        }
        for (int j = 0; j < m; j++) {
            out.add(new Op(Type.INSERT, -1, offset + j));
        }
        return false;
    }

    private static void backtrack(List<String> a, List<String> b, List<int[]> trace, int d,
                                  int offsetK, int offset, List<Op> out) {
        List<Op> reversed = new ArrayList<>();
        int x = a.size();
        int y = b.size();
        for (int step = d; step > 0; step--) {
            int[] v = trace.get(step);
            int k = x - y;
            int index = k + offsetK;
            int prevK;
            if (k == -step || (k != step && v[index - 1] < v[index + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = v[prevK + offsetK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                reversed.add(new Op(Type.EQUAL, offset + x - 1, offset + y - 1));
                x--;
                y--;
            }
            if (x > prevX) {
                reversed.add(new Op(Type.DELETE, offset + x - 1, -1));
                x--;
            } else if (y > prevY) {
                reversed.add(new Op(Type.INSERT, -1, offset + y - 1));
                y--;
            }
        }
        while (x > 0 && y > 0) {
            reversed.add(new Op(Type.EQUAL, offset + x - 1, offset + y - 1));
            x--;
            y--;
        }
        while (x > 0) {
            reversed.add(new Op(Type.DELETE, offset + x - 1, -1));
            x--;
        }
        while (y > 0) {
            reversed.add(new Op(Type.INSERT, -1, offset + y - 1));
            y--;
        }
        Collections.reverse(reversed);
        out.addAll(reversed);
    }
}
