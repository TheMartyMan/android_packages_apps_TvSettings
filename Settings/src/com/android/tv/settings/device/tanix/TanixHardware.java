/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.tv.settings.device.tanix;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
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

    // Mouse right button reported as Back (kernel hid.right_click_back, init.tanix_w2.rc).
    static final String PROP_MOUSE_BACK = "persist.vendor.tanix.mouse_back";

    // Built-in root (device/tanix/mademoiselle/su).
    static final String PROP_ROOT = "persist.sys.tanix.root";
    private static final String ROOT_DAEMON = "/system_ext/bin/tanix_sud";

    // USB 2.0 port: U-Boot variable that the kernel reads at boot ("otg_device="). Only 2 selects host
    // mode: 0 is U-Boot's default, which it also restores on the first boot after an update.
    private static final String ENV_OTG = "ubootenv.var.otg_device";
    static final int USB_UNKNOWN = -1;
    private static final String ENV_OTG_DEFAULT = "0";
    private static final String ENV_OTG_DEVICE = "1";
    private static final String ENV_OTG_HOST = "2";
    static final int USB_HOST = 0;
    static final int USB_DEVICE = 1;

    // Global settings, because all users share the port: the mode the user wants, the mode the
    // running system booted with and the boot for which that was recorded.
    private static final String SETTING_USB_MODE = "tanix_usb_mode";
    private static final String SETTING_USB_BOOT_MODE = "tanix_usb_boot_mode";
    private static final String SETTING_USB_BOOT_COUNT = "tanix_usb_boot_count";

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
        if (ENV_OTG_DEFAULT.equals(value) || ENV_OTG_DEVICE.equals(value)) return USB_DEVICE;
        if (ENV_OTG_HOST.equals(value)) return USB_HOST;
        Log.w(TAG, "unexpected " + ENV_OTG + " value: '" + value + "'");
        return USB_UNKNOWN;
    }

    private static boolean setUsbMode(int mode) {
        try {
            call("setBootenv", new Class<?>[] {String.class, String.class}, ENV_OTG,
                    mode == USB_DEVICE ? ENV_OTG_DEVICE : ENV_OTG_HOST);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.e(TAG, "setBootenv failed", e);
            return false;
        }
        return getUsbMode() == mode;
    }

    /** Sets the mode the user picked and remembers it for {@link #onBootCompleted}. */
    static synchronized boolean setWantedUsbMode(Context context, int mode) {
        final ContentResolver resolver = context.getContentResolver();
        recordUsbBootMode(resolver);
        int old = Settings.Global.getInt(resolver, SETTING_USB_MODE, USB_DEVICE);
        if (!Settings.Global.putInt(resolver, SETTING_USB_MODE, mode)) return false;
        if (setUsbMode(mode)) return true;
        Settings.Global.putInt(resolver, SETTING_USB_MODE, old);
        return false;
    }

    /** Mode the running system booted with, or USB_UNKNOWN. */
    static synchronized int getUsbBootMode(Context context) {
        final ContentResolver resolver = context.getContentResolver();
        recordUsbBootMode(resolver);
        return Settings.Global.getInt(resolver, SETTING_USB_BOOT_MODE, USB_UNKNOWN);
    }

    /** The variable still has the mode of this boot until this class changes it. */
    private static void recordUsbBootMode(ContentResolver resolver) {
        int boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0);
        if (Settings.Global.getInt(resolver, SETTING_USB_BOOT_COUNT, -1) == boot) return;
        Settings.Global.putInt(resolver, SETTING_USB_BOOT_MODE, getUsbMode());
        Settings.Global.putInt(resolver, SETTING_USB_BOOT_COUNT, boot);
    }

    /**
     * At boot: record the mode this boot used and set host mode again if the user picked it and U-Boot
     * has lost it: the first boot after an update starts from U-Boot's default environment.
     *
     * @return the mode that was set and applies after the next restart, or USB_UNKNOWN
     */
    static synchronized int onBootCompleted(Context context) {
        final ContentResolver resolver = context.getContentResolver();
        recordUsbBootMode(resolver);
        int mode = getUsbMode();
        int wanted = Settings.Global.getInt(resolver, SETTING_USB_MODE, USB_DEVICE);
        if (mode == USB_UNKNOWN || mode == wanted) return USB_UNKNOWN;
        boolean ok = setUsbMode(wanted);
        Log.i(TAG, "USB 2.0 port mode was " + mode + ", setting " + wanted + (ok ? "" : " FAILED"));
        return ok ? wanted : USB_UNKNOWN;
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
