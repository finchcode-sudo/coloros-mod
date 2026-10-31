package com.rikumi.colorosmod

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class BanAppItem(val pkg: String, val name: String)

// 图标按需加载并缓存(参考 SDK Monitor): 列表先出文字, 图标在行进入屏幕时才取。
private val banIconCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

private fun loadBanIcon(context: Context, pkg: String): Bitmap? {
    banIconCache[pkg]?.let { return it }
    return runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(Canvas(bitmap))
        banIconCache[pkg] = bitmap
        bitmap
    }.getOrNull()
}

// 直接用 PackageManager 取应用列表(需要 QUERY_ALL_PACKAGES), 不再经过 root 命令。
private fun loadBanApps(context: Context, includeSystem: Boolean): List<BanAppItem> {
    val pm = context.packageManager
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    val systemMask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    @Suppress("DEPRECATION")
    var infos = pm.getInstalledApplications(0)
    if (infos.isEmpty()) {
        // 兜底: 没有包可见性时回退到 root 命令。
        val output = runRoot("cmd package list packages ${if (includeSystem) "" else "-3"} --user current").orEmpty()
        infos = output.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .mapNotNull { runCatching { pm.getApplicationInfo(it.removePrefix("package:"), 0) }.getOrNull() }.toList()
    }
    return infos.asSequence()
        .filter { includeSystem || it.flags and systemMask == 0 }
        .distinctBy { it.packageName }
        .map { BanAppItem(it.packageName, runCatching { pm.getApplicationLabel(it).toString().trim() }.getOrDefault(it.packageName)) }
        .sortedWith { a, b ->
            val result = collator.compare(a.name, b.name)
            if (result == 0) a.pkg.compareTo(b.pkg) else result
        }.toList()
}

/** 保护应用列表: 列出已安装应用, 点一下即勾选/取消保护(与原 BanUninstall 的列表一致)。 */
@Composable
internal fun BanAppsScreen(context: Context, onBack: () -> Unit) {
    val prefs = remember(context) { context.settingsPrefs() }
    var selected by remember { mutableStateOf(prefs.getStringSet(BanUninstallSettings.PACKAGES, emptySet()).orEmpty().toSet()) }
    var apps by remember { mutableStateOf<List<BanAppItem>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val overscroll = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(showSystem) {
        apps = null
        val result = withContext(Dispatchers.Default) { runCatching { loadBanApps(context, showSystem) }.getOrNull() }
        if (result == null) Toast.makeText(context, context.getString(R.string.ui_read_apps_failed), Toast.LENGTH_SHORT).show()
        apps = result ?: emptyList()
    }

    fun toggle(pkg: String) {
        val next = if (pkg in selected) selected - pkg else selected + pkg
        if (prefs.edit().putStringSet(BanUninstallSettings.PACKAGES, next).commit()) {
            selected = next
            SettingsProvider.notifySettingsChanged(context)
        } else Toast.makeText(context, context.getString(R.string.ui_save_failed), Toast.LENGTH_SHORT).show()
    }

    Scaffold(topBar = {
        CouixTopAppBar(
            title = context.getString(R.string.ui_ban_apps),
            dividerProgress = couixTopBarDividerProgress(listState, overscroll),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, context.getString(R.string.ui_back),
                        tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(COUIX_BACK_ICON))
                }
            },
            actions = {
                BasicText(context.getString(if (showSystem) R.string.ui_font_hide_system else R.string.ui_font_show_system),
                    style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.primary),
                    modifier = Modifier.clickable { showSystem = !showSystem }.padding(horizontal = 12.dp, vertical = 10.dp))
                RestartMenu(context)
            },
        )
    }) { padding ->
        val shown = apps?.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
        }?.sortedByDescending { it.pkg in selected }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).couixOverscroll(listState, overscroll)) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = COUIX_GROUP_HMARGIN, vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (query.isEmpty()) BasicText(context.getString(R.string.ui_ban_search),
                                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                            inner()
                        },
                    )
                }
            }
            when {
                shown == null -> item { CouixSmallTitle(context.getString(R.string.ui_loading)) }
                shown.isEmpty() -> item { CouixSmallTitle(context.getString(R.string.ui_no_apps)) }
                else -> itemsIndexed(shown, key = { _, app -> app.pkg }) { index, app ->
                    CouixCardRow(first = index == 0, last = index == shown.lastIndex) {
                        if (index > 0) CouixItemDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .clickable { toggle(app.pkg) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon by produceState<Bitmap?>(banIconCache[app.pkg], app.pkg) {
                                if (value == null) value = withContext(Dispatchers.IO) { loadBanIcon(context, app.pkg) }
                            }
                            icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }
                                ?: Box(Modifier.size(40.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f), RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                                BasicText(app.name, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
                                BasicText(app.pkg, style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                            }
                            CouixSwitch(checked = app.pkg in selected)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
