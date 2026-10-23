package de.robv.android.xposed;

/** Stub: logging only. */
public final class XposedBridge {
    private XposedBridge() {
    }

    public static synchronized void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static synchronized void log(Throwable t) {
        throw new UnsupportedOperationException("stub");
    }
}
