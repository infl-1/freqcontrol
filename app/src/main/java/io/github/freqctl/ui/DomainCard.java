package io.github.freqctl.ui;

import android.view.View;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.RangeSlider;

import java.util.Collections;
import java.util.List;

import io.github.freqctl.Domain;
import io.github.freqctl.FreqTable;
import io.github.freqctl.R;
import io.github.freqctl.Status;

/** One card: switch, "min - max MHz" text, range slider (one step per supported frequency) and status. */
final class DomainCard {
    interface Listener {
        void onCommit(Domain domain, boolean enabled, int minValue, int maxValue);
    }

    private final Domain domain;
    private final Listener listener;
    private final MaterialSwitch toggle;
    private final RangeSlider slider;
    private final TextView rangeText;
    private final TextView statusText;

    private int[] table = new int[0];
    private boolean binding;
    private boolean dragging;

    DomainCard(View root, Domain domain, int titleRes, Listener listener) {
        this.domain = domain;
        this.listener = listener;
        ((TextView) root.findViewById(R.id.card_title)).setText(titleRes);
        toggle = root.findViewById(R.id.card_switch);
        slider = root.findViewById(R.id.card_slider);
        rangeText = root.findViewById(R.id.card_range);
        statusText = root.findViewById(R.id.card_status);

        slider.setLabelFormatter(value -> label((int) value) + " MHz");
        slider.addOnChangeListener((s, value, fromUser) -> updateRangeText());
        slider.addOnSliderTouchListener(new RangeSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(RangeSlider s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(RangeSlider s) {
                dragging = false;
                commit();
            }
        });
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) {
                commit();
            }
        });
    }

    /**
     * Shows the current state.
     *
     * @param moduleLoaded true when the hook (or the root helper) published a table during this boot
     * @param status       last status the hook published (may be null)
     */
    void bind(boolean moduleLoaded, int[] newTable, String status, boolean enabled, int min, int max) {
        binding = true;
        try {
            table = newTable;
            boolean usable = moduleLoaded && table.length >= 2;
            toggle.setEnabled(usable);
            slider.setEnabled(usable);
            if (usable) {
                if (!dragging) {
                    slider.setValueFrom(0f);
                    slider.setValueTo(table.length - 1);
                    slider.setStepSize(1f);
                    int lo = FreqTable.indexOf(table, min);
                    int hi = Math.max(lo, FreqTable.indexOf(table, max));
                    slider.setValues((float) lo, (float) hi);
                }
                toggle.setChecked(enabled);
                updateRangeText();
            } else {
                toggle.setChecked(false);
                rangeText.setText(R.string.range_unknown);
            }
            statusText.setText(statusRes(moduleLoaded, status, enabled));
        } finally {
            binding = false;
        }
    }

    private int statusRes(boolean moduleLoaded, String status, boolean enabled) {
        if (!moduleLoaded) {
            return domain.mechanism == Domain.Mechanism.ROOT_SYSFS
                    ? R.string.status_root_missing : R.string.status_module_missing;
        }
        if (Status.UNSUPPORTED.equals(status)) {
            return R.string.status_unsupported;
        }
        if (table.length < 2) {
            return R.string.status_frequencies_unavailable;
        }
        if (status == null) {
            return enabled ? R.string.status_pending : R.string.status_disabled;
        }
        switch (status) {
            case Status.ACTIVE:
                return R.string.status_active;
            case Status.MIN_UNAVAILABLE:
                return R.string.status_min_unavailable;
            case Status.MAX_UNAVAILABLE:
                return R.string.status_max_unavailable;
            case Status.FREQUENCIES_UNAVAILABLE:
                return R.string.status_frequencies_unavailable;
            case Status.FAILED:
                return R.string.status_failed;
            case Status.GUARD:
                return R.string.status_guard;
            default:
                return R.string.status_disabled;
        }
    }

    private String label(int index) {
        return index >= 0 && index < table.length ? FreqTable.formatMhz(table[index]) : "?";
    }

    private int lowIndex() {
        List<Float> values = slider.getValues();
        return clamp(Math.round(Collections.min(values)));
    }

    private int highIndex() {
        List<Float> values = slider.getValues();
        return clamp(Math.round(Collections.max(values)));
    }

    private int clamp(int index) {
        return Math.max(0, Math.min(index, Math.max(0, table.length - 1)));
    }

    private void updateRangeText() {
        if (table.length < 2) {
            return;
        }
        rangeText.setText(rangeText.getContext().getString(
                R.string.range_format, label(lowIndex()), label(highIndex())));
    }

    private void commit() {
        if (binding || table.length < 2) {
            return;
        }
        listener.onCommit(domain, toggle.isChecked(), table[lowIndex()], table[highIndex()]);
    }
}
