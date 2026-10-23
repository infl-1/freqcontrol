package io.github.freqctl.hook;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point. The only scope is Samsung's Device Health Manager (SDHMS): its process is
 * allowed to hold SemDvfsManager frequency requests, which is how the limits get applied.
 */
public class Entry implements IXposedHookLoadPackage {
    private static final String SDHMS_PACKAGE = "com.sec.android.sdhms";
    private static final String SDHMS_APPLICATION = "com.sec.android.sdhms.MainApplication";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SDHMS_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        final ClassLoader classLoader = lpparam.classLoader;
        try {
            XposedHelpers.findAndHookMethod(SDHMS_APPLICATION, classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof Context) {
                            FreqController.install((Context) param.thisObject, classLoader);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("FreqCtl: install failed");
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log("FreqCtl: hooked SDHMS MainApplication.onCreate");
        } catch (Throwable t) {
            XposedBridge.log("FreqCtl: could not hook SDHMS MainApplication.onCreate");
            XposedBridge.log(t);
        }
    }
}
