package io.github.freqctl;

/**
 * A frequency domain the app can limit. Also defines the Settings.Global keys that the UI
 * (writer) and the SDHMS hook (reader) share.
 */
public enum Domain {
    GPU("gpu", "FreqCtl_GPU_FREQ_MIN", "FreqCtl_GPU_FREQ_MAX"),
    CPU("cpu", "FreqCtl_CPU_FREQ_MIN", "FreqCtl_CPU_FREQ_MAX");

    public final String id;
    /** Tags handed to SemDvfsManager.createInstance (free-form labels). */
    public final String tagMin;
    public final String tagMax;

    Domain(String id, String tagMin, String tagMax) {
        this.id = id;
        this.tagMin = tagMin;
        this.tagMax = tagMax;
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

    /** Published by the hook: "&lt;boot_count&gt;:&lt;f1&gt;,&lt;f2&gt;,...". */
    public String keyTable() {
        return "fcl_" + id + "_table";
    }

    /** Published by the hook: one of the {@link Status} constants. */
    public String keyStatus() {
        return "fcl_" + id + "_status";
    }
}
