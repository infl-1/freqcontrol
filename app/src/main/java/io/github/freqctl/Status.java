package io.github.freqctl;

/** Runtime state the hook / root helper publishes for each domain. */
public final class Status {
    private Status() {
    }

    public static final String DISABLED = "disabled";
    public static final String ACTIVE = "active";
    public static final String MIN_UNAVAILABLE = "min_unavailable";
    public static final String MAX_UNAVAILABLE = "max_unavailable";
    public static final String FREQUENCIES_UNAVAILABLE = "frequencies_unavailable";
    public static final String FAILED = "failed";
    public static final String UNSUPPORTED = "unsupported";
    /** The thermal guard tripped and released the limit. */
    public static final String GUARD = "guard_tripped";
}
