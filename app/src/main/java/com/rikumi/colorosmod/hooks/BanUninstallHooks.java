package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.content.pm.ApplicationInfo;
import android.provider.Settings;
import android.text.TextUtils;

import com.rikumi.colorosmod.BanUninstallSettings;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * system_server: 禁止卸载应用 / 禁止清除应用数据(移植自 BanUninstall, 作者 tinyhai)。
 * 开关与应用列表通过 SettingsProvider 快照读取, 每次调用时判断, 改设置后无需重启。
 * 操作记录写入 Settings.Global(BanUninstallSettings.RECORDS), 设置页直接读取。
 * 未移植: 确认弹窗、独立密码。
 */
public final class BanUninstallHooks {

    private static final String PMS = "com.android.server.pm.PackageManagerService";
    private static final String PMS_IMPL = "com.android.server.pm.PackageManagerService$IPackageManagerImpl";
    private static final String AMS = "com.android.server.am.ActivityManagerService";

    private static final int RECORD_LIMIT = 80;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ban-uninstall");
        thread.setDaemon(true);
        return thread;
    });
    private static final ArrayList<String> RECORDS = new ArrayList<>();
    private static boolean recordsLoaded;

    private BanUninstallHooks() {}

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("BanUninstall: hook() start sdk=" + Build.VERSION.SDK_INT);
        ClassLoader cl = lpparam.classLoader;

        int installed = 0;
        Class<?> pms = XposedHelpers.findClassIfExists(PMS, cl);
        if (pms != null) {
            XC_MethodHook delete = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    handleDelete(param);
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                installed += hookAll(pms, "deletePackageVersioned", delete);
            } else {
                installed += hookAll(pms, "deletePackage", delete);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installed += hookAll(pms, "deleteExistingPackageAsUser", delete);
            }
        } else {
            XposedBridge.log("BanUninstall: PackageManagerService not found");
        }

        XC_MethodHook clearPm = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                handleClear(param, false);
            }
        };
        XC_MethodHook clearAm = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                handleClear(param, true);
            }
        };
        String pmClearHost = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? PMS_IMPL : PMS;
        installed += hookFirst(XposedHelpers.findClassIfExists(pmClearHost, cl), "clearApplicationUserData", clearPm);
        installed += hookFirst(XposedHelpers.findClassIfExists(AMS, cl), "clearApplicationUserData", clearAm);
        XposedBridge.log("BanUninstall: hooks installed count=" + installed);
        // 只要有一个拦截点装上就标记"工作中"。
        if (installed > 0) markAlive();
    }

    private static int hookAll(Class<?> clazz, String name, XC_MethodHook callback) {
        try {
            return XposedHelpers.hookAllMethods(clazz, name, callback).size();
        } catch (Throwable error) {
            XposedBridge.log("BanUninstall: hook " + clazz.getName() + "#" + name + " failed: " + error);
            return 0;
        }
    }

    // 系统设置提供者就绪后写入本次开机序号, 设置页据此显示"工作中"。
    private static void markAlive() {
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 120; i++) {
                try {
                    Context context = systemContext();
                    if (context != null) {
                        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
                        if (boot >= 0) {
                            boolean ok = Settings.Global.putString(context.getContentResolver(),
                                    BanUninstallSettings.ALIVE, String.valueOf(boot));
                            XposedBridge.log("BanUninstall: mark alive boot=" + boot + " ok=" + ok);
                            String now = Settings.Global.getString(context.getContentResolver(), BanUninstallSettings.ALIVE);
                            if (String.valueOf(boot).equals(now)) return;
                        }
                    }
                    Thread.sleep(5000);
                } catch (Throwable error) {
                    if (i % 12 == 0) XposedBridge.log("BanUninstall: mark alive error " + error);
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "ban-uninstall-alive");
        thread.setDaemon(true);
        thread.start();
    }

    private static int hookFirst(Class<?> clazz, String name, XC_MethodHook callback) {
        if (clazz == null) {
            XposedBridge.log("BanUninstall: class missing for " + name);
            return 0;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                try {
                    XposedBridge.hookMethod(method, callback);
                    return 1;
                } catch (Throwable error) {
                    XposedBridge.log("BanUninstall: hook " + clazz.getName() + "#" + name + " failed: " + error);
                    return 0;
                }
            }
        }
        XposedBridge.log("BanUninstall: method missing " + clazz.getName() + "#" + name);
        return 0;
    }

    private static boolean isProtected(String packageName) {
        if (!XposedInit.readBoolCached(BanUninstallSettings.USE_LIST, false)) return true;
        return XposedInit.readBoolCached(BanUninstallSettings.PREFIX + packageName, false);
    }

    private static void handleDelete(XC_MethodHook.MethodHookParam param) {
        try {
            if (!XposedInit.readBoolCached(BanUninstallSettings.UNINSTALL, false)) return;
            Object[] args = param.args;
            if (args == null || args.length == 0) return;
            String packageName = null;
            if (args[0] instanceof VersionedPackage) packageName = ((VersionedPackage) args[0]).getPackageName();
            else if (args[0] instanceof String) packageName = (String) args[0];
            if (packageName == null) return;
            Object observer = args.length > 1 ? args[1] : null;
            int userId = args.length > 2 && args[2] instanceof Integer ? (Integer) args[2] : 0;
            String label = lookupLabel(packageName, userId);
            if (label == null) return;
            boolean block = isProtected(packageName);
            record(false, packageName, userId, label, Binder.getCallingPid(), block);
            if (!block) return;
            XposedBridge.log("BanUninstall: blocked uninstall " + packageName + ":" + userId);
            notifyDeleteObserver(observer, packageName);
            param.setResult(blockedResult(param.method, false));
        } catch (Throwable error) {
            XposedBridge.log(error);
        }
    }

    private static void handleClear(XC_MethodHook.MethodHookParam param, boolean fromAm) {
        try {
            if (!XposedInit.readBoolCached(BanUninstallSettings.CLEAR_DATA, false)) return;
            // 系统自身发起的清除(如卸载流程)不拦截。
            if (Binder.getCallingPid() == Process.myPid()) return;
            Object[] args = param.args;
            if (args == null || args.length == 0 || !(args[0] instanceof String)) return;
            String packageName = (String) args[0];
            Object observer = null;
            Integer userId = null;
            Class<?> observerType = Class.forName("android.content.pm.IPackageDataObserver");
            for (Object arg : args) {
                if (observer == null && arg != null && observerType.isInstance(arg)) observer = arg;
                if (userId == null && arg instanceof Integer) userId = (Integer) arg;
            }
            int user = userId == null ? 0 : userId;
            boolean block = isProtected(packageName);
            String label = lookupLabel(packageName, user);
            if (label == null) label = packageName;
            record(true, packageName, user, label, Binder.getCallingPid(), block);
            if (!block) return;
            XposedBridge.log("BanUninstall: blocked clear data " + packageName + ":" + user
                    + (fromAm ? " (am)" : ""));
            notifyClearObserver(observer, packageName);
            param.setResult(blockedResult(param.method, true));
        } catch (Throwable error) {
            XposedBridge.log(error);
        }
    }

    // 被拦截的方法按返回类型给出"未执行"的结果。
    private static Object blockedResult(Member member, boolean booleanResult) {
        if (!(member instanceof Method)) return null;
        Class<?> type = ((Method) member).getReturnType();
        if (type == boolean.class) return booleanResult;
        if (type == int.class) return -1;
        return null;
    }

    private static Context systemContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object current = thread.getMethod("currentActivityThread").invoke(null);
            return (Context) thread.getMethod("getSystemContext").invoke(current);
        } catch (Throwable error) {
            return null;
        }
    }

    // 返回应用名; 静态共享库等没有 ApplicationInfo 的包返回 null(不是应用, 不处理); 读取失败时按应用处理。
    private static String lookupLabel(String packageName, int userId) {
        Context context = systemContext();
        if (context == null) return packageName;
        long identity = Binder.clearCallingIdentity();
        try {
            Method method = PackageManager.class.getMethod("getApplicationInfoAsUser",
                    String.class, int.class, int.class);
            PackageManager pm = context.getPackageManager();
            Object info = method.invoke(pm, packageName, 0, userId);
            if (!(info instanceof ApplicationInfo)) return null;
            CharSequence label = ((ApplicationInfo) info).loadLabel(pm);
            return label == null ? packageName : label.toString();
        } catch (Throwable error) {
            Throwable cause = error.getCause();
            return cause instanceof PackageManager.NameNotFoundException ? null : packageName;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static String callerPackage(int pid) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/cmdline"))) {
            String line = reader.readLine();
            if (line == null) return "";
            int end = line.indexOf('\0');
            if (end >= 0) line = line.substring(0, end);
            int colon = line.indexOf(':');
            return colon >= 0 ? line.substring(0, colon) : line;
        } catch (Throwable error) {
            return "";
        }
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String text = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        return text.length() > max ? text.substring(0, max) : text;
    }

    // 记录一次卸载/清除请求; 格式: 时间\t类型(U/C)\t包名:用户\t应用名\t发起方\t结果(P拦截/A放行)。
    private static void record(boolean clearData, String packageName, int userId, String label,
                               int callingPid, boolean blocked) {
        final long time = System.currentTimeMillis();
        final String line = time + "\t" + (clearData ? "C" : "U") + "\t" + packageName + ":" + userId + "\t"
                + clean(label, 24) + "\t" + clean(callerPackage(callingPid), 40) + "\t" + (blocked ? "P" : "A");
        WORKER.execute(() -> {
            try {
                Context context = systemContext();
                if (context == null) return;
                synchronized (RECORDS) {
                    if (!recordsLoaded) {
                        String old = Settings.Global.getString(context.getContentResolver(), BanUninstallSettings.RECORDS);
                        if (old != null && !old.isEmpty()) {
                            for (String item : old.split("\n")) if (!item.isEmpty()) RECORDS.add(item);
                        }
                        recordsLoaded = true;
                    }
                    RECORDS.add(0, line);
                    while (RECORDS.size() > RECORD_LIMIT) RECORDS.remove(RECORDS.size() - 1);
                    Settings.Global.putString(context.getContentResolver(), BanUninstallSettings.RECORDS,
                            TextUtils.join("\n", RECORDS));
                }
            } catch (Throwable error) {
                XposedBridge.log(error);
            }
        });
    }

    private static void notifyDeleteObserver(final Object observer, final String packageName) {
        if (observer == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Method method = Class.forName("android.content.pm.IPackageDeleteObserver2")
                        .getMethod("onPackageDeleted", String.class, int.class, String.class);
                method.invoke(observer, packageName, -1, null);
            } catch (Throwable error) {
                XposedBridge.log(error);
            }
        });
    }

    private static void notifyClearObserver(final Object observer, final String packageName) {
        if (observer == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Method method = Class.forName("android.content.pm.IPackageDataObserver")
                        .getMethod("onRemoveCompleted", String.class, boolean.class);
                method.invoke(observer, packageName, false);
            } catch (Throwable error) {
                XposedBridge.log(error);
            }
        });
    }
}
