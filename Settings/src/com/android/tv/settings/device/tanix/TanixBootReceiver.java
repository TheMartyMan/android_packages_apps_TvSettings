/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.tv.settings.device.tanix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Tanix W2 housekeeping:
 * - at boot: record the USB 2.0 port mode of this boot and apply the device-mode default;
 * - at boot, on time-format changes (ACTION_TIME_CHANGED, also sent by Date and time when the
 *   24-hour switch changes) and on locale changes: copy the 12/24-hour format to the front panel.
 */
public class TanixBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (!TanixHardware.isSupported() || action == null) return;
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        if (!boot && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_LOCALE_CHANGED.equals(action)) {
            return;
        }
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (boot) TanixHardware.onBootCompleted(app);
                TanixHardware.syncClockFormat(app);
            } finally {
                result.finish();
            }
        }, "TanixBootReceiver").start();
    }
}
