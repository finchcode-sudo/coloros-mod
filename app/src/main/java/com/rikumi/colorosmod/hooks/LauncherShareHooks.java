package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.KEY_LAUNCHER_SHARE_ENABLED;
import static com.rikumi.colorosmod.XposedInit.readBoolCached;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 桌面(com.android.launcher)分享 APK / 批量卸载。移植自 LShareMod v6.9。
 *
 * <ul>
 *   <li>多选模式底部栏注入「分享」按钮, 把选中应用的 APK 以「应用名_版本号.apk」分享出去;</li>
 *   <li>长按图标菜单里的「分享」改由本模块接管, 同样使用「应用名_版本号.apk」;</li>
 *   <li>多选模式下长按「移除」弹出系统批量卸载面板, 单击保持原生行为。</li>
 * </ul>
 *
 * 钩子始终安装, 运行时按 {@link com.rikumi.colorosmod.XposedInit#KEY_LAUNCHER_SHARE_ENABLED} 门控。
 */
final class LauncherShareHooks {
    private static final String TAG = "launcher share: ";
    private static final int ID_SHARE_BTN = 0x7ffe0f01;
    /** ApplicationInfo.FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP */
    private static final int FLAG_SYSTEM_APP = 0x81;
    private static final long CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    /** 三个按钮之间的间距(dp) */
    private static final int GAP_DP = 32;

    private LauncherShareHooks() {
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> container = XposedHelpers.findClass(
                    "com.android.launcher.pagepreview.PagePreviewButtonContainer", lp.classLoader);
            XposedBridge.hookAllMethods(container, "onButtonLayoutInflateFinished", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled() || !(p.thisObject instanceof ViewGroup)) return;
                    final ViewGroup root = (ViewGroup) p.thisObject;
                    root.post(() -> {
                        try { inject(root); }
                        catch (Throwable t) { XposedBridge.log(TAG + "inject failed: " + t); }
                    });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "multi-select hook unavailable: " + t);
        }

        try {
            Class<?> appShare = XposedHelpers.findClass(
                    "com.android.launcher3.popup.OplusBaseSystemShortcut$AppShare", lp.classLoader);
            XposedBridge.hookAllMethods(appShare, "onClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || p.thisObject == null) return;
                    try {
                        Object itemInfo = getField(p.thisObject, "mItemInfo");
                        Object target = getField(p.thisObject, "mTarget");
                        if (itemInfo == null || !(target instanceof Activity)) return;
                        Activity act = (Activity) target;
                        String pkg = packageOf(itemInfo);
                        if (pkg == null) return;
                        File apk = apkOf(act, pkg);
                        if (apk == null) return;
                        // 复制 APK 涉及 IO, 放子线程; 原生逻辑直接跳过。
                        shareAsync(act, new ArrayList<>(java.util.Collections.singletonList(pkg)));
                        Class<?> rt = ((Method) p.method).getReturnType();
                        p.setResult(rt == boolean.class ? (Object) Boolean.TRUE : null);
                    } catch (Throwable t) {
                        // 失败则不拦截, 走原生分享。
                        XposedBridge.log(TAG + "AppShare takeover failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "long-press share hook unavailable: " + t);
        }
    }

    private static boolean enabled() {
        return readBoolCached(KEY_LAUNCHER_SHARE_ENABLED, false);
    }

    // ------------------------------------------------------------------ 注入按钮

    private static void inject(final ViewGroup root) {
        if (root.findViewById(ID_SHARE_BTN) != null) return;

        final Context ctx = root.getContext();
        int idFolder = resId(ctx, "ll_form_folder", "id");
        int idRemove = resId(ctx, "ll_remove", "id");
        if (idFolder == 0 || idRemove == 0) { XposedBridge.log(TAG + "button ids missing"); return; }
        final View llFolder = root.findViewById(idFolder);
        final View llRemove = root.findViewById(idRemove);
        if (llFolder == null || llRemove == null) { XposedBridge.log(TAG + "button views missing"); return; }

        // 真正的 ConstraintLayout 是 llFolder 的 parent, 不是 root(LinearLayout)。
        ViewGroup host = root;
        if (llFolder.getParent() instanceof ViewGroup) host = (ViewGroup) llFolder.getParent();
        final ViewGroup container = host;

        int layoutItem = resId(ctx, "toggle_bar_main_ui_item", "layout");
        if (layoutItem == 0) { XposedBridge.log(TAG + "item layout missing"); return; }

        View btn = LayoutInflater.from(ctx).inflate(layoutItem, root, false);
        btn.setId(ID_SHARE_BTN);
        int idIcon = resId(ctx, "item_icon", "id");
        int drawable = resId(ctx, "launcher_system_shortcut_ic_app_share", "drawable");
        if (idIcon != 0 && drawable != 0) {
            View iv = btn.findViewById(idIcon);
            if (iv instanceof ImageView) {
                try { ((ImageView) iv).setImageDrawable(ctx.getDrawable(drawable)); }
                catch (Throwable ignored) { }
            }
        }
        int idTitle = resId(ctx, "title", "id");
        if (idTitle != 0) {
            View tv = btn.findViewById(idTitle);
            if (tv instanceof TextView) {
                int strId = resId(ctx, "share_action", "string");
                ((TextView) tv).setText(strId != 0 ? ctx.getString(strId) : tr("分享", "Share"));
            }
        }
        btn.setOnClickListener(v -> doShare(root));

        try {
            Class<?> lpClass = Class.forName(
                    "androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", false,
                    container.getClass().getClassLoader());
            Object lp = lpClass.getConstructor(int.class, int.class)
                    .newInstance(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            setField(lp, "startToEnd", llFolder.getId());
            setField(lp, "leftToRight", llFolder.getId());
            setField(lp, "endToStart", llRemove.getId());
            setField(lp, "rightToLeft", llRemove.getId());
            setField(lp, "topToTop", 0);
            container.addView(btn, (ViewGroup.LayoutParams) lp);

            int fId = llFolder.getId(), sId = btn.getId(), rId = llRemove.getId();
            applyPackedLayout(container, fId, sId, rId);
            watchLayout(container, llFolder, btn, fId, sId, rId);
        } catch (Throwable e) {
            XposedBridge.log(TAG + "add button failed: " + e);
            int sw = root.getResources().getDisplayMetrics().widthPixels;
            container.addView(btn, new ViewGroup.LayoutParams(sw / 4, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 长按「移除」= 批量卸载; 单击保持原生行为。
        llRemove.setLongClickable(true);
        llRemove.setOnLongClickListener(v -> {
            if (!enabled()) return false;
            doUninstall(root);
            return true;
        });
    }

    /** 三个按钮做成 packed 水平链居中靠拢: [文件夹][分享][移除]。 */
    private static boolean applyPackedLayout(ViewGroup container, int fId, int sId, int rId) {
        try {
            ClassLoader cl = container.getClass().getClassLoader();
            Class<?> setClass = Class.forName("androidx.constraintlayout.widget.ConstraintSet", false, cl);
            Class<?> layoutClass = Class.forName("androidx.constraintlayout.widget.ConstraintLayout", false, cl);
            Object cs = setClass.getDeclaredConstructor().newInstance();
            Method clone = setClass.getDeclaredMethod("clone", layoutClass);
            clone.setAccessible(true);
            clone.invoke(cs, container);

            Method connect = setClass.getMethod("connect", int.class, int.class, int.class, int.class, int.class);
            Method constrainWidth = setClass.getMethod("constrainWidth", int.class, int.class);
            Method setMargin = setClass.getMethod("setMargin", int.class, int.class, int.class);

            final int START = 6, END = 7, TOP = 3, PARENT = 0;
            int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
            constrainWidth.invoke(cs, fId, wrap);
            constrainWidth.invoke(cs, sId, wrap);
            constrainWidth.invoke(cs, rId, wrap);

            connect.invoke(cs, fId, START, PARENT, START, 0);
            connect.invoke(cs, fId, END, sId, START, 0);
            connect.invoke(cs, sId, START, fId, END, 0);
            connect.invoke(cs, sId, END, rId, START, 0);
            connect.invoke(cs, rId, START, sId, END, 0);
            connect.invoke(cs, rId, END, PARENT, END, 0);
            connect.invoke(cs, sId, TOP, PARENT, TOP, 0);

            setClass.getMethod("setHorizontalChainStyle", int.class, int.class).invoke(cs, fId, 2 /* PACKED */);
            setClass.getMethod("setHorizontalBias", int.class, float.class).invoke(cs, fId, 0.5f);

            int gap = (int) (container.getResources().getDisplayMetrics().density * GAP_DP);
            setMargin.invoke(cs, sId, START, gap);
            setMargin.invoke(cs, rId, START, gap);

            setClass.getMethod("applyTo", layoutClass).invoke(cs, container);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "packed layout failed: " + t);
            return false;
        }
    }

    /**
     * PagePreviewButtonContainer.setItemMaxWidth() 会在布局完成后重设宽度/边距, 把间距冲掉;
     * 发现间距又变大就重新应用(最多 6 次, 防止死循环)。
     */
    private static void watchLayout(final ViewGroup container, final View folder, final View share,
                                    final int fId, final int sId, final int rId) {
        final int[] left = {6};
        final boolean[] busy = {false};
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (busy[0] || left[0] <= 0) return;
            if (folder.getWidth() == 0 || share.getWidth() == 0) return;
            int tol = (int) (container.getResources().getDisplayMetrics().density * (GAP_DP + 6));
            if (share.getLeft() - folder.getRight() <= tol) return;
            busy[0] = true;
            left[0]--;
            container.post(() -> {
                try { applyPackedLayout(container, fId, sId, rId); }
                finally { busy[0] = false; }
            });
        });
    }

    private static void setField(Object lp, String name, int value) {
        for (Class<?> c = lp.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(lp, value);
                return;
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ 批量卸载

    private static void doUninstall(ViewGroup root) {
        Activity act = activityOf(root.getContext());
        try {
            Method target = null;
            Object owner = null;
            // 容器上的 uninstallApps 声明在父类链上, 必须用 getMethods() / 逐级遍历。
            for (Method m : root.getClass().getMethods()) {
                if (m.getName().equals("uninstallApps") && m.getParameterTypes().length == 0) {
                    target = m; owner = root; break;
                }
            }
            for (Class<?> c = root.getClass(); c != null && target == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("uninstallApps") && m.getParameterTypes().length == 0) {
                        target = m; owner = root; break;
                    }
                }
            }
            // 备选: Launcher.showUninstallPanel()
            if (target == null && act != null) {
                for (Method m : act.getClass().getMethods()) {
                    if (m.getName().equals("showUninstallPanel") && m.getParameterTypes().length == 0) {
                        target = m; owner = act; break;
                    }
                }
            }
            if (target == null) {
                toast(act, tr("当前桌面不支持批量卸载", "Batch uninstall is not supported by this launcher"));
                return;
            }
            target.setAccessible(true);
            target.invoke(owner);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "uninstall failed: " + t);
            toast(act, tr("卸载调用失败", "Uninstall failed"));
        }
    }

    // ------------------------------------------------------------------ 分享

    private static void doShare(ViewGroup root) {
        final Activity act = activityOf(root.getContext());
        if (act == null) return;
        Object mgr = call(act, "getBatchDragViewManager");
        if (mgr == null) { toast(act, tr("无法获取选中列表", "Cannot read the selection")); return; }
        Object raw = call(mgr, "getSelectedViewList");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            toast(act, tr("请先选择应用", "Select apps first"));
            return;
        }
        List<?> sel = (List<?>) raw;
        // 主线程只收集包名(纯内存操作)。
        ArrayList<String> pkgs = new ArrayList<>();
        for (Object view : sel) {
            Object tag = call(view, "getTag");
            if (tag == null) continue;
            if (tag.getClass().getName().endsWith("ShortcutContainerInfo")) {
                Object app = call(tag, "getApplicationItem");
                if (app != null) tag = app;
            }
            String pkg = packageOf(tag);
            if (pkg != null && !pkgs.contains(pkg)) pkgs.add(pkg);
        }
        if (pkgs.isEmpty()) { toast(act, tr("所选项目不含应用", "No apps in the selection")); return; }
        shareAsync(act, pkgs);
    }

    /** 子线程复制 APK + 生成 URI, 回主线程拉起分享面板。 */
    private static void shareAsync(final Activity act, final List<String> pkgs) {
        new Thread(() -> {
            final ArrayList<Uri> uris = new ArrayList<>();
            try {
                String authority = findAuthority(act);
                if (authority == null) { toast(act, tr("未找到 FileProvider", "FileProvider not found")); return; }
                Class<?> fp = Class.forName("androidx.core.content.FileProvider", false, act.getClassLoader());
                Method getUri = null;
                for (Method m : fp.getDeclaredMethods()) {
                    if (m.getName().equals("getUriForFile") && m.getParameterTypes().length == 3) {
                        getUri = m; break;
                    }
                }
                if (getUri == null) { toast(act, tr("FileProvider 不可用", "FileProvider unavailable")); return; }
                getUri.setAccessible(true);

                File dir = resolveShareDir(act);
                if (dir == null) { toast(act, tr("无法创建分享目录", "Cannot create share dir")); return; }
                cleanCache(dir);

                for (String pkg : pkgs) {
                    File src = apkOf(act, pkg);
                    if (src == null) continue;
                    File f = src;
                    try {
                        File copy = renamedCopy(act, dir, pkg, src);
                        if (copy != null) f = copy;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "copy failed " + pkg + ": " + t);
                    }
                    try {
                        Uri uri = (Uri) getUri.invoke(null, act, authority, f);
                        if (uri != null) uris.add(uri);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "uri failed " + pkg + ": " + t);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "share worker failed: " + t);
            }
            act.runOnUiThread(() -> {
                if (uris.isEmpty()) {
                    toast(act, tr("所选应用均不可分享（系统应用或无 APK）",
                            "None of the selected apps can be shared (system apps or no APK)"));
                    return;
                }
                try {
                    Intent intent;
                    if (uris.size() == 1) {
                        intent = new Intent(Intent.ACTION_SEND);
                        intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    } else {
                        intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    }
                    intent.setType("application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    int titleId = resId(act, "share_action", "string");
                    String title = titleId != 0 ? act.getString(titleId) : tr("分享", "Share");
                    act.startActivity(Intent.createChooser(intent, title));
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "startActivity failed: " + t);
                }
            });
        }, "colorosmod-share-copy").start();
    }

    /** base.apk → cache/share_apk/<包名>/<应用名>_<版本号>.apk; 失败返回 null(调用方回退原路径)。 */
    private static File renamedCopy(Activity act, File root, String pkg, File src) throws Exception {
        PackageManager pm = act.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        CharSequence label = pm.getApplicationLabel(ai);
        String appName = safeName(label == null ? pkg : label.toString());
        String ver = appVersion(pm, pkg);
        String fileName = (ver == null ? appName : appName + "_" + ver) + ".apk";
        // 包名单独子目录, 防同名不同包(含分身)互相覆盖。
        File pkgDir = new File(root, safeName(pkg));
        if (!pkgDir.exists()) pkgDir.mkdirs();
        File dst = new File(pkgDir, fileName);
        if (!dst.exists() || dst.length() != src.length()) {
            if (!copyFile(src, dst)) { dst.delete(); return null; }
        }
        return dst.length() == src.length() ? dst : null;
    }

    private static String packageOf(Object itemInfo) {
        Object comp = call(itemInfo, "getTargetComponent");
        if (comp == null) return null;
        Object pkg = call(comp, "getPackageName");
        return pkg instanceof String ? (String) pkg : null;
    }

    /** 非系统应用的 base.apk; 系统应用或不存在返回 null。 */
    private static File apkOf(Activity act, String pkg) {
        try {
            ApplicationInfo ai = act.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai == null || ai.sourceDir == null || (ai.flags & FLAG_SYSTEM_APP) != 0) return null;
            File f = new File(ai.sourceDir);
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 必须匹配 androidx FileProvider 的 authority, 用 OplusShareFileProvider 的会因路径表不同而解析失败。 */
    private static String findAuthority(Activity act) {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(
                    act.getPackageName(), PackageManager.GET_PROVIDERS);
            if (pi == null || pi.providers == null) return null;
            for (ProviderInfo p : pi.providers) {
                if ("androidx.core.content.FileProvider".equals(p.name)) return p.authority;
            }
            for (ProviderInfo p : pi.providers) {
                if (p.name != null && !p.name.endsWith("OplusShareFileProvider")
                        && p.name.endsWith("FileProvider")) {
                    return p.authority;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "authority probe failed: " + t);
        }
        return null;
    }

    /**
     * 分享目录必须在 CE 存储(/data/user/0): OplusShareFileProvider 的 paths 白名单基于 CE 路径,
     * 而桌面是 Direct Boot 感知应用, getCacheDir() 默认落在 DE 存储。
     */
    private static File resolveShareDir(Activity act) {
        File[] candidates = {
                new File("/data/user/0/" + act.getPackageName() + "/cache/share_apk"),
                new File("/data/data/" + act.getPackageName() + "/cache/share_apk"),
                new File(act.getCacheDir(), "share_apk"),
        };
        for (File d : candidates) {
            try {
                if (!d.exists() && !d.mkdirs()) continue;
                if (d.isDirectory() && d.canWrite()) return d;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void cleanCache(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (f.isDirectory()) {
                    cleanCache(f);
                    String[] left = f.list();
                    if (left == null || left.length == 0) f.delete();
                } else if (now - f.lastModified() > CACHE_MAX_AGE_MS) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) { }
    }

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return dst.length() == src.length();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "copyFile failed: " + t);
            return false;
        }
    }

    private static String appVersion(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            if (pi == null) return null;
            if (pi.versionName != null && !pi.versionName.isEmpty()) return safeName(pi.versionName);
            long code = pi.getLongVersionCode();
            if (code > 0) return String.valueOf(code);
        } catch (Throwable ignored) { }
        return null;
    }

    private static String safeName(String in) {
        if (in == null || in.isEmpty()) return "app";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            boolean bad = c == '\\' || c == '/' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 32;
            sb.append(bad ? '_' : c);
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? "app" : r;
    }

    // ------------------------------------------------------------------ 工具

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static Object getField(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 调用无参方法(含父类私有方法), 失败返回 null。 */
    private static Object call(Object obj, String name) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    try {
                        m.setAccessible(true);
                        return m.invoke(obj);
                    } catch (Throwable t) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int resId(Context ctx, String name, String type) {
        try { return ctx.getResources().getIdentifier(name, type, ctx.getPackageName()); }
        catch (Throwable t) { return 0; }
    }

    private static void toast(final Activity act, final String msg) {
        if (act == null) return;
        act.runOnUiThread(() -> {
            try { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) { }
        });
    }

    /** hook 运行在桌面进程里, 取不到模块资源, 这里按系统语言在中英文之间选。 */
    private static String tr(String zh, String en) {
        return Locale.getDefault().getLanguage().startsWith("zh") ? zh : en;
    }
}
