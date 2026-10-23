package io.github.freqctl.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.freqctl.Domain;
import io.github.freqctl.FreqTable;
import io.github.freqctl.R;
import io.github.freqctl.SettingsAccess;

public class MainActivity extends AppCompatActivity implements DomainCard.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private DomainCard gpuCard;
    private DomainCard cpuCard;
    private TextView accessText;
    private ContentObserver observer;
    /** Writes in progress; the screen is not refreshed from half-written settings meanwhile. */
    private int writesInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        accessText = findViewById(R.id.access_status);
        accessText.setText(R.string.access_checking);
        gpuCard = new DomainCard(findViewById(R.id.card_gpu), Domain.GPU, R.string.card_gpu_title, this);
        cpuCard = new DomainCard(findViewById(R.id.card_cpu), Domain.CPU, R.string.card_cpu_title, this);
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };
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
        bindCard(gpuCard, Domain.GPU, resolver, boot);
        bindCard(cpuCard, Domain.CPU, resolver, boot);
    }

    private void bindCard(DomainCard card, Domain domain, ContentResolver resolver, int boot) {
        FreqTable.Snapshot snapshot = FreqTable.decode(Settings.Global.getString(resolver, domain.keyTable()));
        // A table from an earlier boot means the module did not run this time.
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
    }

    // ---- writing state ----------------------------------------------------------------------

    @Override
    public void onCommit(Domain domain, boolean enabled, int minValue, int maxValue) {
        // "enabled" goes last so the limit is applied once, with the final range.
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(domain.keyMin(), String.valueOf(minValue));
        values.put(domain.keyMax(), String.valueOf(maxValue));
        values.put(domain.keyEnabled(), enabled ? "1" : "0");
        final Context app = getApplicationContext();
        writesInFlight++;
        io.execute(() -> {
            final boolean saved = SettingsAccess.putAll(app, values);
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

    /** Makes sure the app can write settings; uses root once to grant the permission if needed. */
    private void checkAccess() {
        final Context app = getApplicationContext();
        io.execute(() -> {
            boolean granted = SettingsAccess.hasPermission(app);
            if (!granted) {
                main.post(() -> accessText.setText(R.string.access_granting));
                granted = SettingsAccess.grantViaRoot(app);
            }
            final boolean ok = granted;
            main.post(() -> {
                if (ok) {
                    accessText.setText(R.string.access_ok);
                } else {
                    accessText.setText(getString(R.string.access_missing, getPackageName()));
                }
            });
        });
    }
}
