package com.rikumi.colorosmod.hooks;

import android.content.pm.FeatureInfo;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.List;

/**
 * 解锁国区 GMS(移植自 UnlockCNGMS, 功能同 fei-ke/unlock-cn-gms):
 * 对 Google 服务 / Play 商店隐藏国行标记 cn.google.services 与 services_updater, 解除国区限制。
 */
public final class UnlockCnGmsHooks {
    public static final String KEY = "unlock_cn_gms_enabled";
    private static final String CLASS = "android.app.ApplicationPackageManager";

    private UnlockCnGmsHooks() {}

    private static boolean hidden(Object name) {
        return "cn.google.services".equals(name) || "com.google.android.feature.services_updater".equals(name);
    }

    private static boolean enabled() {
        return XposedInit.readBoolCached(KEY, false);
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        XC_MethodHook hasFeature = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && hidden(param.args[0]) && enabled()) param.setResult(false);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(CLASS, cl, "hasSystemFeature", String.class, hasFeature);
        } catch (Throwable error) {
            XposedBridge.log("UnlockCnGms: hasSystemFeature(String) failed: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(CLASS, cl, "hasSystemFeature", String.class, int.class, hasFeature);
        } catch (Throwable error) {
            XposedBridge.log("UnlockCnGms: hasSystemFeature(String,int) failed: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(CLASS, cl, "getSystemAvailableFeatures", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof FeatureInfo[]) || !enabled()) return;
                    FeatureInfo[] features = (FeatureInfo[]) result;
                    List<FeatureInfo> kept = new ArrayList<>(features.length);
                    for (FeatureInfo feature : features) {
                        if (feature == null || hidden(feature.name)) continue;
                        kept.add(feature);
                    }
                    param.setResult(kept.toArray(new FeatureInfo[0]));
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("UnlockCnGms: getSystemAvailableFeatures failed: " + error);
        }
    }
}
