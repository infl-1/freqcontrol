package io.github.freqctl.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.freqctl.Domain;
import io.github.freqctl.FreqTable;
import io.github.freqctl.R;
import io.github.freqctl.RootControl;
import io.github.freqctl.SettingsAccess;

public class MainActivity extends AppCompatActivity implements DomainCard.Listener, GuardCard.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<Domain, DomainCard> cards = new EnumMap<>(Domain.class);

    private GuardCard guardCard;
    private TextView accessText;
    private ContentObserver observer;
    /** Writes in progress; the screen is not refreshed from half-written settings meanwhile. */
    private int writesInFlight;
    /** The root helper is installed once per app process; later checks only make sure it is running. */
    private static boolean helperInstalled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        accessText = findViewById(R.id.access_status);
        accessText.setText(R.string.access_checking);
        guardCard = new GuardCard(findViewById(R.id.card_guard), this);

        LinearLayout container = findViewById(R.id.cards_container);
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = Math.round(16 * getResources().getDisplayMetrics().density);
        for (Domain domain : Domain.values()) {
            View view = inflater.inflate(R.layout.card_domain, container, false);
            ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).topMargin = gap;
            container.addView(view);
            cards.put(domain, new DomainCard(view, domain, titleFor(domain), this));
        }

        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };
    }

    private static int titleFor(Domain domain) {
        switch (domain) {
            case GPU:
                return R.string.card_gpu_title;
            case DDR:
                return R.string.card_ddr_title;
            case NPU:
                return R.string.card_npu_title;
            case CPU0:
                return R.string.card_cpu0_title;
            case CPU2:
                return R.string.card_cpu2_title;
            case CPU5:
                return R.string.card_cpu5_title;
            default:
                return R.string.card_cpu7_title;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContentResolver resolver = getContentResolver();
        for (Domain domain : Domain.values()) {
            String[] keys = {domain.keyTable(), domain.keyStatus(), domain.keyEnabled(), domain.keyMin(), domain.keyMax()};
            for (String key : keys) {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            }
        }
        String[] guardKeys = {Domain.KEY_GUARD_ENABLED, Domain.KEY_GUARD_SOC, Domain.KEY_GUARD_BATT, Domain.KEY_GUARD_TRIPPED};
        for (String key : guardKeys) {
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer);
        }
        refresh();
        checkAccess();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(observer);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---- reading state ----------------------------------------------------------------------

    private void refresh() {
        if (writesInFlight > 0) {
            return;
        }
        ContentResolver resolver = getContentResolver();
        int boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
        boolean rootReady = false;
        for (Domain domain : Domain.values()) {
            boolean loaded = bindCard(cards.get(domain), domain, resolver, boot);
            if (loaded && domain.mechanism == Domain.Mechanism.ROOT_SYSFS) {
                rootReady = true;
            }
        }
        guardCard.bind(
                Settings.Global.getInt(resolver, Domain.KEY_GUARD_ENABLED, 1) == 1,
                Settings.Global.getInt(resolver, Domain.KEY_GUARD_SOC, Domain.DEFAULT_GUARD_SOC),
                Settings.Global.getInt(resolver, Domain.KEY_GUARD_BATT, Domain.DEFAULT_GUARD_BATT),
                rootReady,
                Settings.Global.getInt(resolver, Domain.KEY_GUARD_TRIPPED, 0) == 1);
    }

    /** Returns true when the hook / root helper published a table during this boot. */
    private boolean bindCard(DomainCard card, Domain domain, ContentResolver resolver, int boot) {
        FreqTable.Snapshot snapshot = FreqTable.decode(Settings.Global.getString(resolver, domain.keyTable()));
        // A table from an earlier boot means the module / helper did not run this time.
        boolean loaded = snapshot != null && (boot < 0 || snapshot.bootCount == boot);
        int[] table = loaded ? snapshot.table : new int[0];
        int first = table.length > 0 ? table[0] : 0;
        int last = table.length > 0 ? table[table.length - 1] : 0;
        card.bind(
                loaded,
                table,
                Settings.Global.getString(resolver, domain.keyStatus()),
                Settings.Global.getInt(resolver, domain.keyEnabled(), 0) == 1,
                Settings.Global.getInt(resolver, domain.keyMin(), first),
                Settings.Global.getInt(resolver, domain.keyMax(), last));
        return loaded;
    }

    // ---- writing state ----------------------------------------------------------------------

    @Override
    public void onCommit(Domain domain, boolean enabled, int minValue, int maxValue) {
        // "enabled" goes last so the limit is applied once, with the final range.
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(domain.keyMin(), String.valueOf(minValue));
        values.put(domain.keyMax(), String.valueOf(maxValue));
        values.put(domain.keyEnabled(), enabled ? "1" : "0");
        write(values, domain.mechanism == Domain.Mechanism.ROOT_SYSFS);
    }

    @Override
    public void onGuardCommit(boolean enabled, int socLimit, int battLimit) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(Domain.KEY_GUARD_SOC, String.valueOf(socLimit));
        values.put(Domain.KEY_GUARD_BATT, String.valueOf(battLimit));
        values.put(Domain.KEY_GUARD_ENABLED, enabled ? "1" : "0");
        write(values, true);
    }

    /** Saves settings, then (when the root helper reads them) pushes them to the helper. */
    private void write(final Map<String, String> values, final boolean syncRoot) {
        final Context app = getApplicationContext();
        writesInFlight++;
        io.execute(() -> {
            final boolean saved = SettingsAccess.putAll(app, values);
            if (saved && syncRoot) {
                RootControl.sync(app);
            }
            main.post(() -> {
                writesInFlight--;
                if (!saved) {
                    Toast.makeText(this, R.string.write_failed, Toast.LENGTH_LONG).show();
                }
                refresh();
                checkAccess();
            });
        });
    }

    /**
     * Makes sure the app can write settings (root grants the permission once) and that the root
     * helper script is installed and running.
     */
    private void checkAccess() {
        final Context app = getApplicationContext();
        io.execute(() -> {
            boolean granted = SettingsAccess.hasPermission(app);
            if (!granted) {
                main.post(() -> accessText.setText(R.string.access_granting));
                granted = SettingsAccess.grantViaRoot(app);
            }
            final boolean ok = granted;
            if (ok && !helperInstalled) {
                helperInstalled = RootControl.install(app);
            }
            main.post(() -> {
                if (ok) {
                    accessText.setText(helperInstalled ? R.string.access_ok : R.string.access_ok_no_helper);
                } else {
                    accessText.setText(getString(R.string.access_missing, getPackageName()));
                }
            });
        });
    }
}
