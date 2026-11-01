package com.rikumi.colorosmod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun rootSourceText(): String {
    val ksu = runRoot("su -v; echo ---; su -V")
    val kv = ksu?.substringBefore("---")?.trim().orEmpty()
    val kc = ksu?.substringAfter("---", "")?.trim().orEmpty()
    if (kv.isNotEmpty() && kv.length < 40 && !kv.contains("usage", true) && !kv.contains("not found", true)) {
        return if (kc.isNotEmpty() && kc.length < 20) "$kv ($kc)" else kv
    }
    val mg = runRoot("magisk -v; echo ---; magisk -V")
    val mv = mg?.substringBefore("---")?.trim().orEmpty()
    val mc = mg?.substringAfter("---", "")?.trim().orEmpty()
    if (mv.isNotEmpty() && mv.length < 40 && !mv.contains("not found", true)) return "$mv ($mc)"
    return "Other"
}

// 解锁国区 GMS 自检: 读取 GMS/Play 商店进程自报的状态(钩子数/调用数/命中数)。
// 三种状态要能区分: 进程没被注入 / 注入了但挂钩失败 / 已生效。
private fun gmsStatusText(ctx: android.content.Context): String {
    val sp = ctx.createDeviceProtectedStorageContext()
        .getSharedPreferences("gms_state", android.content.Context.MODE_PRIVATE)
    val rows = sp.all.entries.mapNotNull { (key, value) ->
        val p = (value as? String)?.split("\t").orEmpty()
        if (p.size != 5 || p[0] != "2") return@mapNotNull null
        val name = key.removePrefix("com.google.android.").removePrefix("com.android.")
        val hook = p[1].toIntOrNull() ?: 0
        val calls = p[2].toIntOrNull() ?: 0
        val hits = p[4].toIntOrNull() ?: 0
        if (hook == 0) "$name 挂钩失败" else "$name 钩$hook 调$calls 中$hits"
    }
    if (rows.isEmpty()) return "GMS：未注入（需在 LSPosed 勾选 gms / vending 作用域）"
    return "GMS：" + rows.sorted().joinToString("；")
}

// 横幅内状态行: 实时随框架服务绑定/断开刷新(模块启用后无需重启即显示已激活)。
@Composable
internal fun rememberModuleStatusLines(): List<String> {
    val ctx = LocalContext.current
    val active = MainActivity.moduleActive.value
    var root by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { rootSourceText() } }
    val module = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
    }
    val framework = if (!active) "—" else runCatching {
        val s = MainActivity.xposedServices.first()
        "${s.frameworkName} ${s.frameworkVersion} (${s.frameworkVersionCode})"
    }.getOrDefault("?")
    var gms by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            gms = withContext(Dispatchers.IO) { gmsStatusText(ctx) }
            kotlinx.coroutines.delay(2000)
        }
    }
    return listOfNotNull(
        ctx.getString(if (active) R.string.ui_module_active else R.string.ui_module_inactive_short) +
            "  " + ctx.getString(R.string.ui_module_version, module),
        ctx.getString(R.string.ui_root_source, root),
        ctx.getString(R.string.ui_framework_version, framework),
        gms.ifEmpty { null },
    )
}
