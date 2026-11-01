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
    return listOf(
        ctx.getString(if (active) R.string.ui_module_active else R.string.ui_module_inactive_short) +
            "  " + ctx.getString(R.string.ui_module_version, module),
        ctx.getString(R.string.ui_root_source, root),
        ctx.getString(R.string.ui_framework_version, framework),
    )
}
