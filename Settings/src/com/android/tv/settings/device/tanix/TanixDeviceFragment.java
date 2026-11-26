/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.tv.settings.device.tanix;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.TwoStatePreference;

import com.android.tv.settings.R;
import com.android.tv.settings.SettingsPreferenceFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings > System > Device settings on the Tanix W2: front panel clock, USB 2.0 port mode and
 * built-in root. The clock's 12/24-hour format follows Settings > System > Date and time
 * (see {@link TanixBootReceiver}). Changes are applied immediately and reverted in the UI only
 * if a write fails.
 */
@Keep
public class TanixDeviceFragment extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_PANEL_ENABLED = "tanix_panel_enabled";
    private static final String KEY_PANEL_BRIGHTNESS = "tanix_panel_brightness";
    private static final String KEY_PANEL_BLINK = "tanix_panel_blink";
    private static final String KEY_MOUSE_BACK = "tanix_mouse_back";
    private static final String KEY_USB_MODE = "tanix_usb_mode";
    private static final String KEY_ROOT = "tanix_root";
    private static final String KEY_RESTART = "tanix_restart";

    private static final String USB_DEVICE = "device";
    private static final String USB_HOST = "host";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TwoStatePreference mPanelEnabled;
    private SeekBarPreference mPanelBrightness;
    private TwoStatePreference mPanelBlink;
    private TwoStatePreference mMouseBack;
    private ListPreference mUsbMode;
    private TwoStatePreference mRoot;
    private Preference mRestart;

    public static TanixDeviceFragment newInstance() {
        return new TanixDeviceFragment();
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.tanix_device, null);
        mPanelEnabled = findPreference(KEY_PANEL_ENABLED);
        mPanelBrightness = findPreference(KEY_PANEL_BRIGHTNESS);
        mPanelBlink = findPreference(KEY_PANEL_BLINK);
        mMouseBack = findPreference(KEY_MOUSE_BACK);
        mUsbMode = findPreference(KEY_USB_MODE);
        mRoot = findPreference(KEY_ROOT);
        mRestart = findPreference(KEY_RESTART);

        for (Preference p : new Preference[] {mPanelEnabled, mPanelBrightness, mPanelBlink,
                mMouseBack, mUsbMode, mRoot}) {
            p.setOnPreferenceChangeListener(this);
        }
        mRestart.setOnPreferenceClickListener(p -> {
            requireContext().getSystemService(PowerManager.class).reboot(null);
            return true;
        });
        mRestart.setVisible(false);
        mRoot.setVisible(TanixHardware.hasRoot());
        mUsbMode.setEnabled(false);

        updatePanel();
        updateRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadUsbMode();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- state -> UI

    private void updatePanel() {
        mPanelEnabled.setChecked(!"0".equals(
                TanixHardware.getProp(TanixHardware.PROP_PANEL_ENABLED, "1")));
        int level;
        try {
            level = Integer.parseInt(
                    TanixHardware.getProp(TanixHardware.PROP_PANEL_BRIGHTNESS, "2"));
        } catch (NumberFormatException e) {
            level = 2;
        }
        // The FD650 takes 0..7; the slider shows the stock levels 1..8.
        mPanelBrightness.setValue(Math.max(0, Math.min(7, level)) + 1);
        mPanelBlink.setChecked(!"0".equals(
                TanixHardware.getProp(TanixHardware.PROP_PANEL_BLINK, "1")));
        mMouseBack.setChecked(!"0".equals(
                TanixHardware.getProp(TanixHardware.PROP_MOUSE_BACK, "1")));
    }

    private void updateRoot() {
        mRoot.setChecked("1".equals(TanixHardware.getProp(TanixHardware.PROP_ROOT, "0")));
    }

    /** The U-Boot environment is read through a binder call: off the main thread. */
    private void loadUsbMode() {
        final Context app = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            final int mode = TanixHardware.getUsbMode();
            final int bootMode = TanixHardware.getUsbBootMode(app);
            mHandler.post(() -> {
                if (isAdded()) showUsbMode(mode, bootMode);
            });
        });
    }

    private void showUsbMode(int mode, int bootMode) {
        if (mode == TanixHardware.USB_UNKNOWN) {
            mUsbMode.setEnabled(false);
            mUsbMode.setSummary(R.string.tanix_usb_mode_unavailable);
            mRestart.setVisible(false);
            return;
        }
        mUsbMode.setEnabled(true);
        mUsbMode.setValue(mode == TanixHardware.USB_DEVICE ? USB_DEVICE : USB_HOST);
        boolean pending = bootMode != TanixHardware.USB_UNKNOWN && bootMode != mode;
        mUsbMode.setSummary(pending ? R.string.tanix_usb_mode_restart_needed
                : mode == TanixHardware.USB_DEVICE ? R.string.tanix_usb_mode_device_summary
                : R.string.tanix_usb_mode_host_summary);
        mRestart.setVisible(pending);
    }

    // ---------------------------------------------------------------- UI -> state

    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        switch (pref.getKey()) {
            case KEY_PANEL_ENABLED:
                writeVendorProp(TanixHardware.PROP_PANEL_ENABLED, (Boolean) newValue ? "1" : "0");
                return true;
            case KEY_PANEL_BRIGHTNESS:
                writeVendorProp(TanixHardware.PROP_PANEL_BRIGHTNESS,
                        String.valueOf((Integer) newValue - 1));
                return true;
            case KEY_PANEL_BLINK:
                writeVendorProp(TanixHardware.PROP_PANEL_BLINK, (Boolean) newValue ? "1" : "0");
                return true;
            case KEY_MOUSE_BACK:
                writeVendorProp(TanixHardware.PROP_MOUSE_BACK, (Boolean) newValue ? "1" : "0");
                return true;
            case KEY_USB_MODE:
                writeUsbMode(USB_DEVICE.equals(newValue)
                        ? TanixHardware.USB_DEVICE : TanixHardware.USB_HOST);
                return true;
            case KEY_ROOT:
                if (!TanixHardware.setRoot((Boolean) newValue)) {
                    toast(R.string.tanix_save_failed);
                    return false;
                }
                return true;
            default:
                return false;
        }
    }

    private void writeVendorProp(String prop, String value) {
        mExecutor.execute(() -> {
            if (TanixHardware.setVendorProp(prop, value)) return;
            mHandler.post(() -> {
                if (!isAdded()) return;
                toast(R.string.tanix_save_failed);
                updatePanel(); // show the real state again
            });
        });
    }

    private void writeUsbMode(int mode) {
        final Context app = requireContext().getApplicationContext();
        mUsbMode.setEnabled(false);
        mExecutor.execute(() -> {
            final boolean ok = TanixHardware.setWantedUsbMode(app, mode);
            final int now = TanixHardware.getUsbMode();
            final int boot = TanixHardware.getUsbBootMode(app);
            mHandler.post(() -> {
                if (!isAdded()) return;
                if (!ok) toast(R.string.tanix_save_failed);
                showUsbMode(now, boot);
            });
        });
    }

    private void toast(int res) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
    }
}
