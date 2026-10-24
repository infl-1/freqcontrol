package io.github.freqctl.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import io.github.freqctl.Domain;

/**
 * Installed once inside the SDHMS process. Sets up one {@link DomainRunner} per DVFS domain, applies the
 * saved settings right away and re-applies whenever the UI changes a setting.
 */
final class FreqController {
    private static final Object LOCK = new Object();
    private static final long REPUBLISH_DELAY_MS = 30_000L;
    private static boolean installed;
    // Strong references so observers and runners are never garbage-collected.
    private static final List<Object> KEEP = new ArrayList<>();

    private FreqController() {
    }

    static void install(Context context, ClassLoader classLoader) {
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            installed = true;
        }
        final ContentResolver resolver = context.getContentResolver();
        final Handler handler = new Handler(Looper.getMainLooper());

        // One observer for the thermal guard: when it trips or recovers every runner re-evaluates.
        final List<DomainRunner> runners = new ArrayList<>();
        ContentObserver guardObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                for (DomainRunner runner : runners) {
                    runner.reload();
                }
            }
        };
        resolver.registerContentObserver(Settings.Global.getUriFor(Domain.KEY_GUARD_TRIPPED), false, guardObserver);
        synchronized (LOCK) {
            KEEP.add(guardObserver);
        }

        for (Domain domain : Domain.values()) {
            if (domain.mechanism == Domain.Mechanism.ROOT_SYSFS) {
                continue; // handled by the root helper script, not by SDHMS
            }
            try {
                final DomainRunner runner = new DomainRunner(domain, context, classLoader);
                runner.init();
                runners.add(runner);

                ContentObserver observer = new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        runner.reload();
                    }
                };
                resolver.registerContentObserver(Settings.Global.getUriFor(domain.keyEnabled()), false, observer);
                resolver.registerContentObserver(Settings.Global.getUriFor(domain.keyMin()), false, observer);
                resolver.registerContentObserver(Settings.Global.getUriFor(domain.keyMax()), false, observer);
                synchronized (LOCK) {
                    KEEP.add(runner);
                    KEEP.add(observer);
                }

                runner.reload();
                // The settings provider can still be starting up this early in boot; publish once more later.
                handler.postDelayed(runner::republish, REPUBLISH_DELAY_MS);
            } catch (Throwable t) {
                XposedBridge.log("FreqCtl: " + domain.id + " setup failed");
                XposedBridge.log(t);
            }
        }
    }
}
