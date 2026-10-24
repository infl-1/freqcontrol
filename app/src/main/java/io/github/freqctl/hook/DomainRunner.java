package io.github.freqctl.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.freqctl.Domain;
import io.github.freqctl.FreqTable;
import io.github.freqctl.Status;

/**
 * Owns the SemDvfsManager requests of one DVFS domain (GPU, DDR or NPU) inside the SDHMS process.
 *
 * <p>Two mechanisms, both copied from what SDHMS itself does:
 * <ul>
 *   <li><b>typed</b> (GPU): two instances, one of the "min" type and one of the "max" type; for each
 *       {@code setDvfsValue(value)} then {@code acquire()}.</li>
 *   <li><b>resource</b> (DDR, NPU): one instance created from a tag; {@code addResourceValue(resource,
 *       value)} for every min / max resource, then {@code acquire()}. A resource is only used when the
 *       system lists the wanted value in that resource's own frequency table.</li>
 * </ul>
 * {@code release()} drops the limits again. While the thermal guard is tripped everything is released.
 */
final class DomainRunner {
    private static final String DVFS_CLASS = "com.samsung.android.os.SemDvfsManager";

    private final Domain domain;
    private final Context context;
    private final ContentResolver resolver;
    private final ClassLoader classLoader;
    private final boolean typed;

    private Class<?> dvfsClass;
    private boolean supported;
    private int[] table = new int[0];

    // typed
    private Object minHandle;
    private Object maxHandle;
    // resource
    private Object resHandle;
    private final Map<Integer, int[]> resTables = new HashMap<>();

    private boolean applied;
    private int appliedMin;
    private int appliedMax;
    private String lastStatus;
    private final StringBuilder debug = new StringBuilder();

    DomainRunner(Domain domain, Context context, ClassLoader classLoader) {
        this.domain = domain;
        this.context = context;
        this.resolver = context.getContentResolver();
        this.classLoader = classLoader;
        this.typed = domain.mechanism == Domain.Mechanism.DVFS_TYPED;
    }

    /** Reads the supported frequencies and publishes them. */
    synchronized void init() {
        try {
            dvfsClass = XposedHelpers.findClass(DVFS_CLASS, classLoader);
            supported = true;
            if (typed) {
                ensureTypedHandles();
                table = FreqTable.intersect(supportedFrequencies(minHandle), supportedFrequencies(maxHandle));
            } else {
                ensureResourceHandle();
                loadResourceTables();
            }
            log("supported frequencies " + Arrays.toString(table));
        } catch (Throwable t) {
            XposedBridge.log("FreqCtl: " + domain.id + " init failed");
            XposedBridge.log(t);
            if (dvfsClass == null) {
                supported = false;
            }
        }
        publishTable();
    }

    /** Reads the saved settings and brings the DVFS requests in line with them. */
    synchronized void reload() {
        if (!supported) {
            publishStatus(Status.UNSUPPORTED);
            return;
        }
        boolean enabled = Settings.Global.getInt(resolver, domain.keyEnabled(), 0) == 1;
        boolean tripped = Settings.Global.getInt(resolver, Domain.KEY_GUARD_TRIPPED, 0) == 1;
        FreqTable.Range range = null;
        if (table.length >= 2) {
            int min = Settings.Global.getInt(resolver, domain.keyMin(), table[0]);
            int max = Settings.Global.getInt(resolver, domain.keyMax(), table[table.length - 1]);
            range = FreqTable.range(min, max, table);
        } else if (enabled) {
            log("frequency table unavailable");
        }

        String status;
        if (enabled && tripped) {
            apply(false, null);
            status = Status.GUARD;
        } else if (enabled && range != null) {
            status = apply(true, range);
        } else {
            apply(false, null);
            status = enabled ? Status.FREQUENCIES_UNAVAILABLE : Status.DISABLED;
        }
        publishStatus(status);
    }

    /** Publishes table and status again (used once, shortly after boot). */
    synchronized void republish() {
        lastStatus = null;
        publishTable();
        reload();
    }

    // ---- applying -------------------------------------------------------------------------

    private String apply(boolean enable, FreqTable.Range range) {
        try {
            if (!enable || range == null) {
                release();
                applied = false;
                appliedMin = 0;
                appliedMax = 0;
                return Status.DISABLED;
            }
            if (applied && appliedMin == range.min && appliedMax == range.max) {
                return Status.ACTIVE;
            }
            if (applied) {
                release();
                applied = false;
                appliedMin = 0;
                appliedMax = 0;
            }
            String status = typed ? applyTyped(range) : applyResource(range);
            if (Status.ACTIVE.equals(status)) {
                applied = true;
                appliedMin = range.min;
                appliedMax = range.max;
            }
            return status;
        } catch (Throwable t) {
            XposedBridge.log("FreqCtl: " + domain.id + " apply failed");
            XposedBridge.log(t);
            release();
            applied = false;
            appliedMin = 0;
            appliedMax = 0;
            return Status.FAILED;
        }
    }

    private String applyTyped(FreqTable.Range range) {
        ensureTypedHandles();
        if (!setAndAcquire(minHandle, range.min)) {
            release();
            return Status.MIN_UNAVAILABLE;
        }
        if (!setAndAcquire(maxHandle, range.max)) {
            release();
            return Status.MAX_UNAVAILABLE;
        }
        return Status.ACTIVE;
    }

    private String applyResource(FreqTable.Range range) {
        ensureResourceHandle();
        callQuietly(resHandle, "release");
        int minAdded = 0;
        int maxAdded = 0;
        for (int res : domain.resMin) {
            if (contains(resTables.get(res), range.min)) {
                XposedHelpers.callMethod(resHandle, "addResourceValue", Integer.valueOf(res), Integer.valueOf(range.min));
                minAdded++;
            }
        }
        for (int res : domain.resMax) {
            if (contains(resTables.get(res), range.max)) {
                XposedHelpers.callMethod(resHandle, "addResourceValue", Integer.valueOf(res), Integer.valueOf(range.max));
                maxAdded++;
            }
        }
        boolean wantMin = range.min > table[0];
        boolean wantMax = range.max < table[table.length - 1];
        if (minAdded + maxAdded > 0) {
            XposedHelpers.callMethod(resHandle, "acquire");
        }
        if (wantMin && minAdded == 0) {
            return Status.MIN_UNAVAILABLE;
        }
        if (wantMax && maxAdded == 0) {
            return Status.MAX_UNAVAILABLE;
        }
        return minAdded + maxAdded > 0 ? Status.ACTIVE : Status.DISABLED;
    }

    // ---- typed handles --------------------------------------------------------------------

    private void ensureTypedHandles() {
        if (minHandle == null) {
            minHandle = XposedHelpers.callStaticMethod(dvfsClass, "createInstance",
                    context, domain.tagMin, Integer.valueOf(domain.typeMin));
        }
        if (maxHandle == null) {
            maxHandle = XposedHelpers.callStaticMethod(dvfsClass, "createInstance",
                    context, domain.tagMax, Integer.valueOf(domain.typeMax));
        }
    }

    private static int[] supportedFrequencies(Object handle) {
        Object result = XposedHelpers.callMethod(handle, "getSupportedFrequencyForSsrm");
        return result instanceof int[] ? (int[]) result : null;
    }

    /** Sets and holds {@code value} on the handle, but only if the system lists it as supported. */
    private static boolean setAndAcquire(Object handle, int value) {
        if (!contains(supportedFrequencies(handle), value)) {
            return false;
        }
        XposedHelpers.callMethod(handle, "setDvfsValue", Integer.valueOf(value));
        XposedHelpers.callMethod(handle, "acquire");
        return true;
    }

    // ---- resource handle ------------------------------------------------------------------

    private void ensureResourceHandle() {
        if (resHandle == null) {
            resHandle = XposedHelpers.callStaticMethod(dvfsClass, "createInstance", context, domain.tagMax);
        }
    }

    private void loadResourceTables() {
        resTables.clear();
        debug.setLength(0);
        for (int res : domain.resMin) {
            resTables.put(res, tableFor(res));
        }
        for (int res : domain.resMax) {
            resTables.put(res, tableFor(res));
        }
        int[] minTable = firstNonEmpty(domain.resMin);
        int[] maxTable = firstNonEmpty(domain.resMax);
        if (minTable.length > 0 && maxTable.length > 0) {
            int[] both = FreqTable.intersect(minTable, maxTable);
            table = both.length >= 2 ? both : maxTable;
        } else {
            table = maxTable.length > 0 ? maxTable : minTable;
        }
        for (Map.Entry<Integer, int[]> entry : resTables.entrySet()) {
            log("resource 0x" + Integer.toHexString(entry.getKey()) + " table " + Arrays.toString(entry.getValue()));
        }
    }

    private int[] firstNonEmpty(int[] resources) {
        for (int res : resources) {
            int[] t = resTables.get(res);
            if (t != null && t.length > 0) {
                return t;
            }
        }
        return new int[0];
    }

    /**
     * Frequency table of one resource; empty if the system does not know the resource.
     *
     * <p>SDHMS itself asks for the NPU table differently from DDR: it first registers the resource
     * on the instance ({@code addResourceValue(res, 0)}) and then calls
     * {@code getSupportedFrequency(res)} and {@code getSupportedFrequency(res, 2)}. So every variant
     * is tried, first as is and then after registering the resource. What each call returned is
     * kept in {@link #debug} and published, because the module log is not always readable.
     */
    private int[] tableFor(int res) {
        int[] found = queryTable(res, "plain");
        if (found.length > 0) {
            return found;
        }
        try {
            XposedHelpers.callMethod(resHandle, "addResourceValue", Integer.valueOf(res), Integer.valueOf(0));
            debug.append(" [registered 0x").append(Integer.toHexString(res)).append("]");
        } catch (Throwable t) {
            debug.append(" [register failed ").append(t.getClass().getSimpleName()).append("]");
        }
        found = queryTable(res, "registered");
        if (found.length > 0) {
            return found;
        }
        return new int[0];
    }

    private int[] queryTable(int res, String phase) {
        Object[][] calls = {
                {"getSupportedFrequencyForSsrm", Integer.valueOf(res)},
                {"getSupportedFrequency", Integer.valueOf(res)},
                {"getSupportedFrequency", Integer.valueOf(res), Integer.valueOf(2)},
                {"getSupportedFrequency", Integer.valueOf(res), Integer.valueOf(0)},
                {"getSupportedFrequency", Integer.valueOf(res), Integer.valueOf(1)},
        };
        for (Object[] call : calls) {
            String name = (String) call[0];
            Object[] args = Arrays.copyOfRange(call, 1, call.length);
            String label = phase + ":" + name + Arrays.toString(args);
            try {
                Object result = XposedHelpers.callMethod(resHandle, name, args);
                if (result instanceof int[]) {
                    int[] raw = (int[]) result;
                    int[] clean = FreqTable.intersect(raw, raw);
                    debug.append(" ").append(label).append("=").append(raw.length).append("/").append(clean.length);
                    if (clean.length > 0) {
                        return clean;
                    }
                } else {
                    debug.append(" ").append(label).append("=null");
                }
            } catch (Throwable t) {
                debug.append(" ").append(label).append("=").append(t.getClass().getSimpleName());
                log("resource 0x" + Integer.toHexString(res) + " " + label + " failed: " + t);
            }
        }
        return new int[0];
    }

    // ---- helpers --------------------------------------------------------------------------

    private static boolean contains(int[] values, int wanted) {
        if (values == null) {
            return false;
        }
        for (int candidate : values) {
            if (candidate == wanted) {
                return true;
            }
        }
        return false;
    }

    private static void callQuietly(Object handle, String method) {
        if (handle == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(handle, method);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void release() {
        if (typed) {
            minHandle = releaseHandle(minHandle);
            maxHandle = releaseHandle(maxHandle);
        } else {
            resHandle = releaseHandle(resHandle);
        }
    }

    /** Returns null once released; keeps the handle if releasing threw. */
    private static Object releaseHandle(Object handle) {
        if (handle == null) {
            return null;
        }
        try {
            XposedHelpers.callMethod(handle, "release");
            return null;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return handle;
        }
    }

    // ---- publishing -----------------------------------------------------------------------

    private void publishTable() {
        int boot = Settings.Global.getInt(resolver, "boot_count", -1);
        if (!put(domain.keyTable(), FreqTable.encode(boot, table))) {
            log("table publication failed");
        }
        if (debug.length() > 0) {
            put("fcl_" + domain.id + "_debug", debug.toString());
        }
    }

    private void publishStatus(String status) {
        if (status.equals(lastStatus)) {
            return;
        }
        if (put(domain.keyStatus(), status)) {
            lastStatus = status;
            if (Status.ACTIVE.equals(status)) {
                log("active " + FreqTable.formatMhz(appliedMin) + "-" + FreqTable.formatMhz(appliedMax) + " MHz");
            } else {
                log("status " + status);
            }
        }
    }

    private boolean put(String key, String value) {
        try {
            return Settings.Global.putString(resolver, key, value);
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    private void log(String message) {
        XposedBridge.log("FreqCtl[" + domain.id + "]: " + message);
    }
}
