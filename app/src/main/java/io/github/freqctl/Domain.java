package io.github.freqctl;

import java.util.Locale;

/**
 * A frequency domain the app can control. Also defines the Settings.Global keys that the UI
 * (writer) and the hooks / root helper (readers) share.
 *
 * <p>Three ways to control a domain:
 * <ul>
 *   <li>{@link Mechanism#DVFS_TYPED}: SemDvfsManager instance with a min type and a max type (GPU).</li>
 *   <li>{@link Mechanism#DVFS_RESOURCE}: SemDvfsManager instance + addResourceValue(resource, value)
 *       (DDR, NPU); the ids were read from SDHMS itself.</li>
 *   <li>{@link Mechanism#ROOT_SYSFS}: cpufreq policy files written by the root helper script. SDHMS
 *       only exposes a "big" and a "little" CPU limit, so this is the way to reach every cluster.</li>
 * </ul>
 */
public enum Domain {
    GPU("gpu", Mechanism.DVFS_TYPED, -1, 16, 17, new int[0], new int[0],
            "FreqCtl_GPU_FREQ_MIN", "SIOP_GPU_FREQ_MAX"),
    DDR("ddr", Mechanism.DVFS_RESOURCE, -1, 0, 0,
            new int[]{0x30001001}, new int[]{0x30001002, 0x30002000},
            "SIOP_DDR_FREQ_MAX", "SIOP_DDR_FREQ_MAX"),
    NPU("npu", Mechanism.DVFS_RESOURCE, -1, 0, 0,
            new int[]{0x50001001}, new int[]{0x50001002},
            "SIOP_NPU_STATE_MAX", "SIOP_NPU_STATE_MAX"),
    CPU0("cpu0", Mechanism.ROOT_SYSFS, 0),
    CPU2("cpu2", Mechanism.ROOT_SYSFS, 2),
    CPU5("cpu5", Mechanism.ROOT_SYSFS, 5),
    CPU7("cpu7", Mechanism.ROOT_SYSFS, 7);

    public enum Mechanism {
        DVFS_TYPED, DVFS_RESOURCE, ROOT_SYSFS
    }

    /** Written by the UI: guard switch, SoC limit (deg C), battery limit (deg C). */
    public static final String KEY_GUARD_ENABLED = "fcl_guard_enabled";
    public static final String KEY_GUARD_SOC = "fcl_guard_soc";
    public static final String KEY_GUARD_BATT = "fcl_guard_batt";
    /** Written by the root helper: "1" while the guard has released every limit. */
    public static final String KEY_GUARD_TRIPPED = "fcl_guard_tripped";

    public static final int DEFAULT_GUARD_SOC = 85;
    public static final int DEFAULT_GUARD_BATT = 45;

    public final String id;
    public final Mechanism mechanism;
    /** cpufreq policy number (ROOT_SYSFS only). */
    public final int policy;
    /** SemDvfsManager type ids (DVFS_TYPED only). */
    public final int typeMin;
    public final int typeMax;
    /** SemDvfsManager resource ids (DVFS_RESOURCE only). */
    public final int[] resMin;
    public final int[] resMax;
    /** Tags handed to SemDvfsManager.createInstance: the same names SDHMS itself uses for its SIOP limits. */
    public final String tagMin;
    public final String tagMax;

    Domain(String id, Mechanism mechanism, int policy, int typeMin, int typeMax,
           int[] resMin, int[] resMax, String tagMin, String tagMax) {
        this.id = id;
        this.mechanism = mechanism;
        this.policy = policy;
        this.typeMin = typeMin;
        this.typeMax = typeMax;
        this.resMin = resMin;
        this.resMax = resMax;
        this.tagMin = tagMin;
        this.tagMax = tagMax;
    }

    Domain(String id, Mechanism mechanism, int policy) {
        this(id, mechanism, policy, 0, 0, new int[0], new int[0], "", "");
    }

    /** Name used for this domain in the root helper's config file (CPU0, CPU2, ...). */
    public String shellName() {
        return id.toUpperCase(Locale.ROOT);
    }

    /** "1" when the limit should be applied. */
    public String keyEnabled() {
        return "fcl_" + id + "_enabled";
    }

    /** Lowest allowed frequency (raw value from the supported table). */
    public String keyMin() {
        return "fcl_" + id + "_min";
    }

    /** Highest allowed frequency (raw value from the supported table). */
    public String keyMax() {
        return "fcl_" + id + "_max";
    }

    /** Published by the hook / root helper: "&lt;boot_count&gt;:&lt;f1&gt;,&lt;f2&gt;,...". */
    public String keyTable() {
        return "fcl_" + id + "_table";
    }

    /** Published by the hook / root helper: one of the {@link Status} constants. */
    public String keyStatus() {
        return "fcl_" + id + "_status";
    }
}
