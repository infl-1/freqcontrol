package io.github.freqctl.ui;

import android.view.View;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import io.github.freqctl.R;

/** Thermal guard card: on/off switch, SoC and battery limits, and whether it is tripped right now. */
final class GuardCard {
    interface Listener {
        void onGuardCommit(boolean enabled, int socLimit, int battLimit);
    }

    private final Listener listener;
    private final MaterialSwitch toggle;
    private final Slider socSlider;
    private final Slider battSlider;
    private final TextView socLabel;
    private final TextView battLabel;
    private final TextView stateText;

    private boolean binding;

    GuardCard(View root, Listener listener) {
        this.listener = listener;
        toggle = root.findViewById(R.id.guard_switch);
        socSlider = root.findViewById(R.id.guard_soc_slider);
        battSlider = root.findViewById(R.id.guard_batt_slider);
        socLabel = root.findViewById(R.id.guard_soc_label);
        battLabel = root.findViewById(R.id.guard_batt_label);
        stateText = root.findViewById(R.id.guard_state);

        socSlider.addOnChangeListener((s, value, fromUser) -> updateLabels());
        battSlider.addOnChangeListener((s, value, fromUser) -> updateLabels());
        Slider.OnSliderTouchListener touch = new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                commit();
            }
        };
        socSlider.addOnSliderTouchListener(touch);
        battSlider.addOnSliderTouchListener(touch);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) {
                commit();
            }
        });
    }

    /**
     * @param rootReady true when the root helper published a table during this boot (the guard runs there)
     * @param tripped   true while the guard has released every limit
     */
    void bind(boolean enabled, int socLimit, int battLimit, boolean rootReady, boolean tripped) {
        binding = true;
        try {
            toggle.setChecked(enabled);
            socSlider.setValue(clamp(socSlider, socLimit));
            battSlider.setValue(clamp(battSlider, battLimit));
            socSlider.setEnabled(enabled);
            battSlider.setEnabled(enabled);
            updateLabels();
            int state;
            if (!enabled) {
                state = R.string.guard_state_off;
            } else if (!rootReady) {
                state = R.string.guard_state_no_root;
            } else if (tripped) {
                state = R.string.guard_state_tripped;
            } else {
                state = R.string.guard_state_ok;
            }
            stateText.setText(state);
        } finally {
            binding = false;
        }
    }

    private static float clamp(Slider slider, int value) {
        return Math.max(slider.getValueFrom(), Math.min(value, slider.getValueTo()));
    }

    private void updateLabels() {
        socLabel.setText(socLabel.getContext().getString(R.string.guard_soc_label, Math.round(socSlider.getValue())));
        battLabel.setText(battLabel.getContext().getString(R.string.guard_batt_label, Math.round(battSlider.getValue())));
    }

    private void commit() {
        if (binding) {
            return;
        }
        listener.onGuardCommit(toggle.isChecked(), Math.round(socSlider.getValue()), Math.round(battSlider.getValue()));
    }
}
