package io.github.freqctl;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs and talks to the root helper script (assets/freqctl.sh). The script controls the CPU
 * clusters through cpufreq sysfs and runs the thermal guard. It keeps running without the app and is
 * started again at boot from /data/adb/service.d (Magisk, KernelSU, APatch).
 *
 * <p>The app never edits the script's state directly: it writes a small config file that the script
 * re-reads every few seconds.
 */
public final class RootControl {
    private static final String DIR = "/data/adb/freqctl";
    private static final String SERVICE_D = "/data/adb/service.d";
    private static final String SCRIPT = DIR + "/freqctl.sh";
    private static final long TIMEOUT_MS = 30_000L;

    private RootControl() {
    }

    /** Blocking. Installs the script, writes the config and (re)starts the helper. */
    public static boolean install(Context context) {
        try {
            File script = copyAsset(context, "freqctl.sh");
            File config = writeConfig(context);
            String launcher = "#!/system/bin/sh\\nsh " + SCRIPT + " start\\n";
            String command = "mkdir -p " + DIR + " " + SERVICE_D
                    + " && cp " + RootShell.quote(script.getAbsolutePath()) + " " + SCRIPT
                    + " && cp " + RootShell.quote(config.getAbsolutePath()) + " " + DIR + "/config"
                    + " && chmod 755 " + SCRIPT + " && chmod 600 " + DIR + "/config"
                    + " && printf '" + launcher + "' > " + SERVICE_D + "/freqctl.sh"
                    + " && chmod 755 " + SERVICE_D + "/freqctl.sh"
                    + " && sh " + SCRIPT + " stop; sh " + SCRIPT + " start";
            return RootShell.run(command, TIMEOUT_MS).exitCode == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Blocking. Pushes the current settings to the helper and makes sure it is running. */
    public static boolean sync(Context context) {
        try {
            File config = writeConfig(context);
            String command = "mkdir -p " + DIR
                    + " && cp " + RootShell.quote(config.getAbsolutePath()) + " " + DIR + "/config"
                    + " && chmod 600 " + DIR + "/config"
                    + " && sh " + SCRIPT + " start";
            return RootShell.run(command, TIMEOUT_MS).exitCode == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** The helper's config: shell variable assignments, sourced by the script. */
    static String buildConfig(ContentResolver resolver) {
        StringBuilder sb = new StringBuilder();
        sb.append("GUARD_EN=").append(Settings.Global.getInt(resolver, Domain.KEY_GUARD_ENABLED, 1)).append('\n');
        sb.append("GUARD_SOC=").append(Settings.Global.getInt(resolver, Domain.KEY_GUARD_SOC, Domain.DEFAULT_GUARD_SOC)).append('\n');
        sb.append("GUARD_BATT=").append(Settings.Global.getInt(resolver, Domain.KEY_GUARD_BATT, Domain.DEFAULT_GUARD_BATT)).append('\n');
        for (Domain domain : Domain.values()) {
            if (domain.mechanism != Domain.Mechanism.ROOT_SYSFS) {
                continue;
            }
            String name = domain.shellName();
            sb.append(name).append("_EN=").append(Settings.Global.getInt(resolver, domain.keyEnabled(), 0)).append('\n');
            sb.append(name).append("_MIN=").append(Settings.Global.getInt(resolver, domain.keyMin(), 0)).append('\n');
            sb.append(name).append("_MAX=").append(Settings.Global.getInt(resolver, domain.keyMax(), 0)).append('\n');
        }
        return sb.toString();
    }

    private static File writeConfig(Context context) throws IOException {
        File file = new File(context.getCacheDir(), "freqctl.conf");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(buildConfig(context.getContentResolver()).getBytes("UTF-8"));
        }
        file.setReadable(true, false);
        return file;
    }

    private static File copyAsset(Context context, String name) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (InputStream in = context.getAssets().open(name); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        file.setReadable(true, false);
        return file;
    }
}
