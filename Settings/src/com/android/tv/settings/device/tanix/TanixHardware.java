/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.tv.settings.device.tanix;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.text.format.DateFormat;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

import dalvik.system.PathClassLoader;

/**
 * Hardware access for the Tanix W2 ("mademoiselle") device settings.
 *
 * Vendor properties and the U-Boot environment are written through the DroidLogic
 * SystemControlManager (vendor library /vendor/framework/droidlogic.software.core.jar, loaded at
 * runtime so TvSettings has no build dependency on it). The vendor systemcontrol service may set
 * vendor properties; platform apps may not (Treble). Values are read back with SystemProperties.
 */
public final class TanixHardware {
    private static final String TAG = "TanixHardware";

    /** Set by the device tree; the Device settings entry is shown only when true. */
    private static final String PROP_SUPPORTED = "ro.tvsettings.tanix_device";

    private static final String SCM_JAR = "/vendor/framework/droidlogic.software.core.jar";
    private static final String SCM_CLASS = "com.droidlogic.app.SystemControlManager";

    // Front panel clock daemon (kernel/amlogic/kernel-modules/fd650).
    static final String PROP_PANEL_ENABLED = "persist.vendor.tanix.frontpanel.enabled";
    static final String PROP_PANEL_BRIGHTNESS = "persist.vendor.tanix.frontpanel.brightness";
    static final String PROP_PANEL_BLINK = "persist.vendor.tanix.frontpanel.blink";
    static final String PROP_PANEL_FORMAT = "persist.vendor.tanix.frontpanel.format";

    // Built-in root (device/tanix/mademoiselle/su).
    static final String PROP_ROOT = "persist.sys.tanix.root";
    private static final String ROOT_DAEMON = "/system_ext/bin/tanix_sud";

    // USB 2.0 port: U-Boot variable read by the kernel at boot ("otg_device=", 1 = device).
    private static final String ENV_OTG = "ubootenv.var.otg_device";
    static final int USB_UNKNOWN = -1;
    static final int USB_HOST = 0;
    static final int USB_DEVICE = 1;

    private static final String PREFS = "tanix_device";
    private static final String KEY_USB_USER_CHOICE = "usb_user_choice";
    private static final String KEY_USB_BOOT_MODE = "usb_boot_mode";

    private static Object sScm;

    private TanixHardware() {
    }

    public static boolean isSupported() {
        return SystemProperties.getBoolean(PROP_SUPPORTED, false);
    }

    static boolean hasRoot() {
        return new File(ROOT_DAEMON).exists();
    }

    // ---------------------------------------------------------------- properties

    static String getProp(String key, String def) {
        String v = SystemProperties.get(key, "");
        return v.isEmpty() ? def : v;
    }

    /** Sets a vendor property through systemcontrol; true when the new value is visible. */
    static boolean setVendorProp(String key, String value) {
        try {
            call("setProperty", new Class<?>[] {String.class, String.class}, key, value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.e(TAG, "setProperty " + key + " failed", e);
            return false;
        }
        return value.equals(SystemProperties.get(key, ""));
    }

    /**
     * Makes the front panel clock follow Settings > System > Date and time: the explicit 12/24-hour
     * choice, or the locale default when the user has not chosen (same rule as the status clock).
     */
    public static void syncClockFormat(Context context) {
        String wanted = DateFormat.is24HourFormat(context) ? "24" : "12";
        if (wanted.equals(getProp(PROP_PANEL_FORMAT, "24"))) return;
        if (!setVendorProp(PROP_PANEL_FORMAT, wanted)) {
            Log.w(TAG, "cannot set the front panel clock format to " + wanted);
        }
    }

    static boolean setRoot(boolean enabled) {
        String value = enabled ? "1" : "0";
        try {
            SystemProperties.set(PROP_ROOT, value);
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot set " + PROP_ROOT, e);
            return false;
        }
        return value.equals(SystemProperties.get(PROP_ROOT, ""));
    }

    // ---------------------------------------------------------------- USB 2.0 port mode

    /** Mode stored in the U-Boot environment, i.e. the one used from the next boot on. */
    static int getUsbMode() {
        String value;
        try {
            value = (String) call("getBootenv",
                    new Class<?>[] {String.class, String.class}, ENV_OTG, "");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.e(TAG, "getBootenv failed", e);
            return USB_UNKNOWN;
        }
        if ("1".equals(value)) return USB_DEVICE;
        if ("0".equals(value)) return USB_HOST;
        Log.w(TAG, "unexpected " + ENV_OTG + " value: '" + value + "'");
        return USB_UNKNOWN;
    }

    static boolean setUsbMode(int mode) {
        try {
            call("setBootenv", new Class<?>[] {String.class, String.class}, ENV_OTG,
                    mode == USB_DEVICE ? "1" : "0");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.e(TAG, "setBootenv failed", e);
            return false;
        }
        return getUsbMode() == mode;
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void rememberUsbUserChoice(Context context) {
        prefs(context).edit().putBoolean(KEY_USB_USER_CHOICE, true).apply();
    }

    /** Mode the running system booted with (recorded at boot), or USB_UNKNOWN. */
    static int getUsbBootMode(Context context) {
        return prefs(context).getInt(KEY_USB_BOOT_MODE, USB_UNKNOWN);
    }

    /**
     * At boot: record the mode this boot used and, until the user picks a mode, default the
     * port to device mode (a stock-firmware factory reset leaves the variable at host).
     */
    static void onBootCompleted(Context context) {
        int mode = getUsbMode();
        prefs(context).edit().putInt(KEY_USB_BOOT_MODE, mode).apply();
        if (!prefs(context).getBoolean(KEY_USB_USER_CHOICE, false) && mode == USB_HOST) {
            Log.i(TAG, "no user choice yet: defaulting USB 2.0 port to device mode"
                    + (setUsbMode(USB_DEVICE) ? "" : " FAILED"));
        }
    }

    // ---------------------------------------------------------------- SystemControlManager

    private static synchronized Object scm() throws ReflectiveOperationException {
        if (sScm == null) {
            ClassLoader loader = new PathClassLoader(SCM_JAR, TanixHardware.class.getClassLoader());
            sScm = Class.forName(SCM_CLASS, true, loader).getMethod("getInstance").invoke(null);
        }
        return sScm;
    }

    private static Object call(String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Object scm = scm();
        Method m = scm.getClass().getMethod(name, types);
        return m.invoke(scm, args);
    }
}
