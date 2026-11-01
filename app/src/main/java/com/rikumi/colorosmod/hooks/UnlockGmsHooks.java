package com.rikumi.colorosmod.hooks;

import android.content.pm.FeatureInfo;
import android.net.Uri;

import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 解锁国区 GMS: 对 Google 服务 / Play 商店隐藏国行标记 cn.google.services 与
 * com.google.android.feature.services_updater, 功能同 fei-ke/unlock-cn-gms 与 UnlockCNGMS。
 *
 * 三处 hook 只在 com.google.android.gms* / com.android.vending 进程内安装:
 *   ApplicationPackageManager#hasSystemFeature(String)     -> false
 *   ApplicationPackageManager#hasSystemFeature(String,int) -> false
 *   ApplicationPackageManager#getSystemAvailableFeatures() -> 过滤后的数组
 *
 * 与原始实现的差异只在外壳: 保留模块开关(缺省开启)、每个方法单独 try/catch、
 * 统计钩子数/调用数/命中数供模块首页自检。
 */
public final class UnlockGmsHooks {

    public static final String KEY = "unlock_cn_gms_enabled";

    private static final String TAG = "UnlockGms";
    private static final String PM_CLASS = "android.app.ApplicationPackageManager";
    private static final String FEATURE_CN_SERVICES = "cn.google.services";
    private static final String FEATURE_SERVICES_UPDATER = "com.google.android.feature.services_updater";

    // 自检计数: 钩子数 / hasSystemFeature 调用数 / 命中国行标记数 / 实际改写的返回数。
    private static final AtomicInteger INSTALLED = new AtomicInteger();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger MATCHED = new AtomicInteger();
    private static final AtomicInteger HITS = new AtomicInteger();

    private static volatile boolean sReportStarted;
    private static volatile String sProcessName = "";

    private UnlockGmsHooks() {}

    /** GMS / Play 商店及其子进程(com.google.android.gms.persistent 等)。 */
    public static boolean isTargetProcess(String processName) {
        if (processName == null) return false;
        return processName.equals("com.google.android.gms")
                || processName.startsWith("com.google.android.gms:")
                || processName.startsWith("com.google.android.gms.")
                || processName.equals("com.android.vending")
                || processName.startsWith("com.android.vending:");
    }

    private static boolean hidden(Object name) {
        return FEATURE_CN_SERVICES.equals(name) || FEATURE_SERVICES_UPDATER.equals(name);
    }

    // GMS 启动极早, 此时模块设置可能还没加载完: 读不到(-1)按默认开启处理, 只有明确写入 0 才放行。
    // 只读进程内缓存, 不阻塞调用线程。
    private static boolean enabled() {
        return XposedInit.readIntCached(KEY, -1) != 0;
    }

    /** 进程一加载就调用: 先上报一次"已注入/挂钩 0", 便于在模块首页区分没注入和挂钩失败。 */
    public static void onProcessLoaded(String processName) {
        if (!isTargetProcess(processName)) return;
        sProcessName = processName;
        startReporter();
        push();
    }

    public static boolean installed() {
        return INSTALLED.get() > 0;
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;
        synchronized (UnlockGmsHooks.class) {
            // 同一进程可能从 onPackageLoaded 和 onPackageReady 各来一次, 只装一套。
            if (installed()) return;
            installHooks(lpparam);
        }
    }

    private static void installHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.processName != null) sProcessName = lpparam.processName;
        ClassLoader cl = lpparam.classLoader;

        XC_MethodHook featureHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                CALLS.incrementAndGet();
                if (param.args == null || param.args.length == 0) return;
                if (!hidden(param.args[0])) return;
                MATCHED.incrementAndGet();
                if (!enabled()) return;
                HITS.incrementAndGet();
                param.setResult(Boolean.FALSE);
            }
        };

        int ok = 0;
        try {
            XposedHelpers.findAndHookMethod(PM_CLASS, cl, "hasSystemFeature", String.class, featureHook);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hasSystemFeature(String) 未挂上: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(PM_CLASS, cl, "hasSystemFeature", String.class, int.class, featureHook);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hasSystemFeature(String,int) 未挂上: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(PM_CLASS, cl, "getSystemAvailableFeatures", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof FeatureInfo[])) return;
                    if (!enabled()) return;
                    FeatureInfo[] all = (FeatureInfo[]) result;
                    List<FeatureInfo> kept = new ArrayList<>(all.length);
                    for (FeatureInfo feature : all) {
                        if (feature == null || hidden(feature.name)) continue;
                        kept.add(feature);
                    }
                    if (kept.size() == all.length) return;
                    HITS.incrementAndGet();
                    param.setResult(kept.toArray(new FeatureInfo[0]));
                }
            });
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getSystemAvailableFeatures 未挂上: " + t);
        }

        INSTALLED.set(ok);
        XposedBridge.log(TAG + ": 已挂钩 " + ok + "/3, process=" + sProcessName
                + ", available=" + (cl != null));
        startReporter();
        push();
    }

    /** 热重载后旧 hook 已随模块卸载, 计数清零并重新挂钩。 */
    public static void resetForHotReload() {
        INSTALLED.set(0);
        CALLS.set(0);
        MATCHED.set(0);
        HITS.set(0);
    }

    // 每 5 秒检查一次计数, 有变化才向模块 App 上报, 最多 20 分钟; 失败静默。
    private static void startReporter() {
        if (sReportStarted) return;
        synchronized (UnlockGmsHooks.class) {
            if (sReportStarted) return;
            sReportStarted = true;
        }
        Thread thread = new Thread(() -> {
            String last = "";
            for (int i = 0; i < 240; i++) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    return;
                }
                String current = INSTALLED.get() + ":" + CALLS.get() + ":" + MATCHED.get() + ":" + HITS.get();
                if (current.equals(last)) continue;
                last = current;
                push();
            }
        }, "ColorOSMod-UnlockGmsReport");
        thread.setDaemon(true);
        thread.start();
    }

    private static void push() {
        try {
            android.app.Application app = XposedInit.currentApplication();
            if (app == null) return;
            // 2 = 当前上报格式版本, 旧格式的历史记录由模块 App 忽略。
            String payload = sProcessName + "\t2\t" + INSTALLED.get() + "\t" + CALLS.get()
                    + "\t" + MATCHED.get() + "\t" + HITS.get();
            app.getContentResolver().call(
                    Uri.parse("content://com.rikumi.colorosmod.settings"), "gms_report", payload, null);
        } catch (Throwable ignored) {
        }
    }
}
