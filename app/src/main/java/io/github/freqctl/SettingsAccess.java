package io.github.freqctl;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.Map;

/**
 * Writes Settings.Global, which is how the UI talks to the hook running inside SDHMS.
 *
 * <p>Same approach as OneLab: write directly when the app holds WRITE_SECURE_SETTINGS; otherwise
 * use root once to grant that permission (and to write the values), then verify. After the grant,
 * root is not needed again.
 */
public final class SettingsAccess {
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private static final long ROOT_TIMEOUT_MS = 20_000L;

    private SettingsAccess() {
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Blocking. Asks root to grant WRITE_SECURE_SETTINGS to this app. */
    public static boolean grantViaRoot(Context context) {
        String command = "pm grant " + RootShell.quote(context.getPackageName()) + " " + PERMISSION;
        RootShell.Result result = RootShell.run(command, ROOT_TIMEOUT_MS);
        return result.exitCode == 0 && hasPermission(context);
    }

    /** Blocking. Returns true only if every value ended up stored. */
    public static boolean putAll(Context context, Map<String, String> values) {
        ContentResolver resolver = context.getContentResolver();
        if (writeDirect(resolver, values)) {
            return true;
        }
        StringBuilder command = new StringBuilder("(pm grant ")
                .append(RootShell.quote(context.getPackageName())).append(' ').append(PERMISSION)
                .append(" >/dev/null 2>&1 || true)");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            command.append(" && settings put global ")
                    .append(RootShell.quote(entry.getKey())).append(' ')
                    .append(RootShell.quote(entry.getValue()));
        }
        if (RootShell.run(command.toString(), ROOT_TIMEOUT_MS).exitCode != 0) {
            return false;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getValue().equals(Settings.Global.getString(resolver, entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean writeDirect(ContentResolver resolver, Map<String, String> values) {
        try {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!Settings.Global.putString(resolver, entry.getKey(), entry.getValue())) {
                    return false;
                }
            }
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
