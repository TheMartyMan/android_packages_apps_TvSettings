/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.tv.settings.device.tanix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.widget.Toast;

import com.android.tv.settings.R;

/**
 * Tanix W2 housekeeping:
 * - at boot: record the USB 2.0 port mode of this boot and restore the wanted one if it was lost;
 * - at boot and on locale changes: copy the 12/24-hour format to the front panel. The Date and
 *   time switch updates the panel directly (DateTimeFragment), because the implicit
 *   ACTION_TIME_CHANGED it sends is not delivered to manifest receivers.
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
            int mode = TanixHardware.USB_UNKNOWN;
            try {
                // The port belongs to the box: do it once, not again for every user.
                if (boot && app.getSystemService(UserManager.class).isSystemUser()) {
                    mode = TanixHardware.onBootCompleted(app);
                }
                TanixHardware.syncClockFormat(app);
            } finally {
                finish(app, result, mode);
            }
        }, "TanixBootReceiver").start();
    }

    /** Tells the user about a restored USB mode, then lets the broadcast end. */
    private static void finish(Context app, PendingResult result, int usbMode) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (usbMode != TanixHardware.USB_UNKNOWN) {
                    String name = app.getString(usbMode == TanixHardware.USB_DEVICE
                            ? R.string.tanix_usb_mode_device : R.string.tanix_usb_mode_host);
                    Toast.makeText(app, app.getString(R.string.tanix_usb_mode_restored, name),
                            Toast.LENGTH_LONG).show();
                }
            } finally {
                result.finish();
            }
        });
    }
}
