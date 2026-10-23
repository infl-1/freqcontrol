package io.github.freqctl.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.freqctl.Domain;
import io.github.freqctl.FreqTable;
import io.github.freqctl.Status;

/**
 * Owns the SemDvfsManager requests of one domain (GPU or CPU) inside the SDHMS process.
 *
 * <p>Mechanism (taken from OneLab 1.3): two SemDvfsManager instances per domain, one of the
 * "min" type and one of the "max" type. For each: check the wanted value is in
 * {@code getSupportedFrequencyForSsrm()}, then {@code setDvfsValue(value)} and {@code acquire()}.
 * {@code release()} drops the limit again.
 */
final class DomainRunner {
    private static final String DVFS_CLASS = "com.samsung.android.os.SemDvfsManager";
    // SemDvfsManager type ids read from OneLab 1.3: 16 = GPU minimum, 17 = GPU maximum.
    private static final int GPU_TYPE_MIN = 16;
    private static final int GPU_TYPE_MAX = 17;

    private final Domain domain;
    private final Context context;
    private final ContentResolver resolver;
    private final ClassLoader classLoader;

    private Class<?> dvfsClass;
    private boolean supported;
    private int typeMin = -1;
    private int typeMax = -1;
    private Object minHandle;
    private Object maxHandle;
    private int[] table = new int[0];

    private boolean applied;
    private int appliedMin;
    private int appliedMax;
    private String lastStatus;

    DomainRunner(Domain domain, Context context, ClassLoader classLoader) {
        this.domain = domain;
        this.context = context;
        this.resolver = context.getContentResolver();
        this.classLoader = classLoader;
    }

    /** Resolves the DVFS types, reads the supported frequencies and publishes them. */
    synchronized void init() {
        try {
            dvfsClass = XposedHelpers.findClass(DVFS_CLASS, classLoader);
            supported = resolveTypes();
            if (supported) {
                ensureHandles();
                int[] minTable = supportedFrequencies(minHandle);
                int[] maxTable = supportedFrequencies(maxHandle);
                table = FreqTable.intersect(minTable, maxTable);
                log("supported frequencies " + Arrays.toString(table));
            }
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
        FreqTable.Range range = null;
        if (table.length >= 2) {
            int min = Settings.Global.getInt(resolver, domain.keyMin(), table[0]);
            int max = Settings.Global.getInt(resolver, domain.keyMax(), table[table.length - 1]);
            range = FreqTable.range(min, max, table);
        } else if (enabled) {
            log("frequency table unavailable");
        }

        String status;
        if (enabled && range != null) {
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
            ensureHandles();
            if (!setAndAcquire(minHandle, range.min)) {
                release();
                return Status.MIN_UNAVAILABLE;
            }
            if (!setAndAcquire(maxHandle, range.max)) {
                release();
                return Status.MAX_UNAVAILABLE;
            }
            applied = true;
            appliedMin = range.min;
            appliedMax = range.max;
            return Status.ACTIVE;
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

    private void ensureHandles() {
        if (minHandle == null) {
            minHandle = createHandle(domain.tagMin, typeMin);
        }
        if (maxHandle == null) {
            maxHandle = createHandle(domain.tagMax, typeMax);
        }
    }

    private Object createHandle(String tag, int type) {
        return XposedHelpers.callStaticMethod(dvfsClass, "createInstance", context, tag, Integer.valueOf(type));
    }

    private static int[] supportedFrequencies(Object handle) {
        Object result = XposedHelpers.callMethod(handle, "getSupportedFrequencyForSsrm");
        return result instanceof int[] ? (int[]) result : null;
    }

    /** Sets and holds {@code value} on the handle, but only if the system lists it as supported. */
    private static boolean setAndAcquire(Object handle, int value) {
        int[] supportedValues = supportedFrequencies(handle);
        if (supportedValues == null) {
            return false;
        }
        for (int candidate : supportedValues) {
            if (candidate == value) {
                XposedHelpers.callMethod(handle, "setDvfsValue", Integer.valueOf(value));
                XposedHelpers.callMethod(handle, "acquire");
                return true;
            }
        }
        return false;
    }

    private void release() {
        minHandle = releaseHandle(minHandle);
        maxHandle = releaseHandle(maxHandle);
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

    // ---- type ids -------------------------------------------------------------------------

    private boolean resolveTypes() {
        if (domain == Domain.GPU) {
            typeMin = GPU_TYPE_MIN;
            typeMax = GPU_TYPE_MAX;
            return true;
        }
        // CPU ids are not present in OneLab. Learn how this device names the (verified) GPU ids,
        // then look up the same names with CPU in place of GPU.
        String gpuMinName = null;
        String gpuMaxName = null;
        StringBuilder seen = new StringBuilder();
        for (Field field : dvfsClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                int value = field.getInt(null);
                String name = field.getName();
                seen.append(name).append('=').append(value).append(' ');
                String upper = name.toUpperCase(Locale.ROOT);
                if (!upper.contains("GPU")) {
                    continue;
                }
                if (value == GPU_TYPE_MIN && upper.contains("MIN") && gpuMinName == null) {
                    gpuMinName = name;
                }
                if (value == GPU_TYPE_MAX && upper.contains("MAX") && gpuMaxName == null) {
                    gpuMaxName = name;
                }
            } catch (Throwable ignored) {
                // skip fields we cannot read
            }
        }
        if (gpuMinName != null && gpuMaxName != null
                && gpuMinName.contains("GPU") && gpuMaxName.contains("GPU")) {
            Integer cpuMin = readStaticInt(gpuMinName.replace("GPU", "CPU"));
            Integer cpuMax = readStaticInt(gpuMaxName.replace("GPU", "CPU"));
            if (cpuMin != null && cpuMax != null
                    && cpuMin.intValue() != cpuMax.intValue()
                    && cpuMin.intValue() != GPU_TYPE_MIN && cpuMin.intValue() != GPU_TYPE_MAX
                    && cpuMax.intValue() != GPU_TYPE_MIN && cpuMax.intValue() != GPU_TYPE_MAX) {
                typeMin = cpuMin.intValue();
                typeMax = cpuMax.intValue();
                log("DVFS types min=" + typeMin + " max=" + typeMax);
                return true;
            }
        }
        log("CPU DVFS type ids not found. SemDvfsManager int fields: " + seen);
        return false;
    }

    private Integer readStaticInt(String fieldName) {
        try {
            Field field = dvfsClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            return Integer.valueOf(field.getInt(null));
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- publishing -----------------------------------------------------------------------

    private void publishTable() {
        int boot = Settings.Global.getInt(resolver, "boot_count", -1);
        if (!put(domain.keyTable(), FreqTable.encode(boot, table))) {
            log("table publication failed");
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
