package io.github.freqctl;

import java.util.TreeSet;

/**
 * Pure helpers for supported-frequency tables. No Android dependencies, so the same code runs
 * in the SDHMS process (hook) and in the UI process.
 */
public final class FreqTable {
    private FreqTable() {
    }

    /** A validated min/max pair; both values are entries of the table. */
    public static final class Range {
        public final int min;
        public final int max;

        Range(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    /** What the hook published: the boot it was published in, and the table. */
    public static final class Snapshot {
        public final int bootCount;
        public final int[] table;

        Snapshot(int bootCount, int[] table) {
            this.bootCount = bootCount;
            this.table = table;
        }
    }

    /** Sorted, unique, positive values present in both arrays. Empty if either is null. */
    public static int[] intersect(int[] a, int[] b) {
        if (a == null || b == null) {
            return new int[0];
        }
        TreeSet<Integer> inB = new TreeSet<>();
        for (int v : b) {
            if (v > 0) {
                inB.add(v);
            }
        }
        TreeSet<Integer> both = new TreeSet<>();
        for (int v : a) {
            if (v > 0 && inB.contains(v)) {
                both.add(v);
            }
        }
        int[] out = new int[both.size()];
        int i = 0;
        for (int v : both) {
            out[i++] = v;
        }
        return out;
    }

    /** Closest table entry to {@code value}; on a tie the larger entry wins. Table must be non-empty. */
    public static int nearest(int[] table, int value) {
        int best = table[0];
        long bestDist = Math.abs((long) value - best);
        for (int f : table) {
            long d = Math.abs((long) value - f);
            if (d < bestDist || (d == bestDist && f > best)) {
                best = f;
                bestDist = d;
            }
        }
        return best;
    }

    /**
     * Snaps min and max to the table; if min ends up above max, max is raised to min.
     * Returns null when the table has fewer than two entries.
     */
    public static Range range(int min, int max, int[] table) {
        if (table == null || table.length < 2) {
            return null;
        }
        int lo = nearest(table, min);
        int hi = nearest(table, max);
        if (lo > hi) {
            hi = lo;
        }
        return new Range(lo, hi);
    }

    /** Index of the table entry nearest to {@code value} (0 for an empty table). */
    public static int indexOf(int[] table, int value) {
        if (table == null || table.length == 0) {
            return 0;
        }
        int target = nearest(table, value);
        for (int i = 0; i < table.length; i++) {
            if (table[i] == target) {
                return i;
            }
        }
        return 0;
    }

    public static String encode(int bootCount, int[] table) {
        StringBuilder sb = new StringBuilder().append(bootCount).append(':');
        for (int i = 0; i < table.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(table[i]);
        }
        return sb.toString();
    }

    /** Parses {@link #encode}; null if the text is missing or malformed. */
    public static Snapshot decode(String text) {
        if (text == null) {
            return null;
        }
        int colon = text.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            int boot = Integer.parseInt(text.substring(0, colon).trim());
            String rest = text.substring(colon + 1).trim();
            if (rest.isEmpty()) {
                return new Snapshot(boot, new int[0]);
            }
            String[] parts = rest.split(",");
            int[] table = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                table[i] = Integer.parseInt(parts[i].trim());
            }
            return new Snapshot(boot, table);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Raw table value as MHz text. Values of 100000 and up are treated as kHz. */
    public static String formatMhz(int raw) {
        return String.valueOf(raw >= 100000 ? (raw + 500) / 1000 : raw);
    }
}
