package de.robv.android.xposed.callbacks;

/** Stub: only the fields this module reads. */
public abstract class XC_LoadPackage {
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}
