/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.tv.settings.device.tanix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Records the USB 2.0 port mode of this boot and applies the device-mode default. */
public class TanixBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !TanixHardware.isSupported()) {
            return;
        }
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                TanixHardware.onBootCompleted(app);
            } finally {
                result.finish();
            }
        }, "TanixBootReceiver").start();
    }
}
