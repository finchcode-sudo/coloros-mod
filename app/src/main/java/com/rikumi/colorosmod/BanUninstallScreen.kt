package com.rikumi.colorosmod

import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BanRecord(
    val time: Long,
    val clearData: Boolean,
    val pkg: String,
    val label: String,
    val caller: String,
    val blocked: Boolean,
)

private const val CLEARED_AT_KEY = "ban_uninstall_records_cleared_at"

private fun banState(context: Context) = context.createDeviceProtectedStorageContext()
    .getSharedPreferences(SettingsProvider.BAN_STATE, Context.MODE_PRIVATE)

// system_server 里的 hook 的报告: 开机序号 / 安装的拦截点数 / 失败说明。
internal data class BanStatus(val working: Boolean, val detail: String)

internal fun banStatus(context: Context): BanStatus {
    val raw = runCatching { banState(context).getString("alive", null) }.getOrNull()
        ?: return BanStatus(false, context.getString(R.string.ui_ban_status_none))
    val parts = raw.split('\n', limit = 3)
    val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    val installed = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val note = parts.getOrNull(2).orEmpty()
    val detail = context.getString(R.string.ui_ban_status_installed, installed) + if (note.isEmpty()) "" else "\n$note"
    return BanStatus(parts.getOrNull(0)?.toIntOrNull() == boot && boot >= 0 && installed > 0, detail)
}

internal fun readBanRecords(context: Context): List<BanRecord> {
    val clearedAt = context.settingsPrefs().getLong(CLEARED_AT_KEY, 0L)
    val raw = runCatching { banState(context).getString("records", "") }.getOrNull().orEmpty()
    return raw.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        val time = parts.getOrNull(0)?.toLongOrNull()
        if (parts.size < 6 || time == null || time < clearedAt) null
        else BanRecord(time, parts[1] == "C", parts[2], parts[3], parts[4], parts[5] == "P")
    }.toList()
}

@Composable
internal fun BanUninstallScreen(
    context: Context,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val prefs = remember(context) { context.settingsPrefs() }
    var uninstall by remember { mutableStateOf(prefs.getBoolean(BanUninstallSettings.UNINSTALL, false)) }
    var clearData by remember { mutableStateOf(prefs.getBoolean(BanUninstallSettings.CLEAR_DATA, false)) }
    var useList by remember { mutableStateOf(prefs.getBoolean(BanUninstallSettings.USE_LIST, false)) }
    val status = remember { banStatus(context) }
    val working = status.working
    val listState = rememberLazyListState()
    val overscroll = remember { mutableFloatStateOf(0f) }

    fun change(key: String, value: Boolean, apply: (Boolean) -> Unit) {
        if (prefs.edit().putBoolean(key, value).commit()) {
            apply(value)
            SettingsProvider.notifySettingsChanged(context)
        } else Toast.makeText(context, context.getString(R.string.ui_save_failed), Toast.LENGTH_SHORT).show()
    }

    Scaffold(topBar = {
        CouixTopAppBar(
            title = context.getString(R.string.ui_ban_entry),
            dividerProgress = couixTopBarDividerProgress(listState, overscroll),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, context.getString(R.string.ui_back),
                        tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(COUIX_BACK_ICON))
                }
            },
            actions = { RestartMenu(context) },
        )
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).couixOverscroll(listState, overscroll)) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                val tint = if (working) MiuixTheme.colorScheme.primary else Color(0xFFE53935)
                Column(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = COUIX_GROUP_HMARGIN, vertical = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(tint.copy(alpha = 0.16f))
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BasicText(context.getString(R.string.ui_ban_status),
                        style = MiuixTheme.textStyles.title3.copy(color = MiuixTheme.colorScheme.onSurface))
                    BasicText(context.getString(if (working) R.string.ui_ban_status_working else R.string.ui_ban_status_idle),
                        style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                    BasicText(status.detail,
                        style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp))
                    if (!working) BasicText(context.getString(R.string.ui_ban_status_hint),
                        style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp))
                }
            }
            item { CouixSmallTitle(context.getString(R.string.ui_ban_section_function)) }
            item {
                CouixCardRow(first = true, last = false) {
                    CouixSwitchPreference(checked = uninstall,
                        onCheckedChange = { change(BanUninstallSettings.UNINSTALL, it) { v -> uninstall = v } },
                        title = context.getString(R.string.ui_ban_uninstall),
                        subtitle = context.getString(R.string.ui_ban_uninstall_hint))
                }
            }
            item {
                CouixCardRow(first = false, last = true) {
                    CouixItemDivider()
                    CouixSwitchPreference(checked = clearData,
                        onCheckedChange = { change(BanUninstallSettings.CLEAR_DATA, it) { v -> clearData = v } },
                        title = context.getString(R.string.ui_ban_clear_data),
                        subtitle = context.getString(R.string.ui_ban_clear_data_hint))
                }
            }
            item { CouixSmallTitle(context.getString(R.string.ui_ban_section_advanced)) }
            item {
                CouixCardRow(first = true, last = false) {
                    CouixSwitchPreference(checked = useList,
                        onCheckedChange = { change(BanUninstallSettings.USE_LIST, it) { v -> useList = v } },
                        title = context.getString(R.string.ui_ban_use_list),
                        subtitle = context.getString(R.string.ui_ban_use_list_hint))
                }
            }
            item {
                CouixCardRow(first = false, last = false) {
                    CouixItemDivider()
                    CouixCategoryRow(icon = null, title = context.getString(R.string.ui_ban_apps), onClick = onOpenApps)
                }
            }
            item {
                CouixCardRow(first = false, last = true) {
                    CouixItemDivider()
                    CouixCategoryRow(icon = null, title = context.getString(R.string.ui_ban_records), onClick = onOpenRecords)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
internal fun BanRecordsScreen(context: Context, onBack: () -> Unit) {
    val prefs = remember(context) { context.settingsPrefs() }
    var records by remember { mutableStateOf(readBanRecords(context)) }
    val listState = rememberLazyListState()
    val overscroll = remember { mutableFloatStateOf(0f) }
    val format = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Scaffold(topBar = {
        CouixTopAppBar(
            title = context.getString(R.string.ui_ban_records),
            dividerProgress = couixTopBarDividerProgress(listState, overscroll),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, context.getString(R.string.ui_back),
                        tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(COUIX_BACK_ICON))
                }
            },
            actions = {
                BasicText(context.getString(R.string.ui_ban_records_clear),
                    style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.primary),
                    modifier = Modifier.clickable {
                        prefs.edit().putLong(CLEARED_AT_KEY, System.currentTimeMillis()).commit()
                        records = emptyList()
                    }.padding(horizontal = 12.dp, vertical = 10.dp))
            },
        )
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).couixOverscroll(listState, overscroll)) {
            item { Spacer(Modifier.height(12.dp)) }
            if (records.isEmpty()) item { CouixSmallTitle(context.getString(R.string.ui_ban_records_empty)) }
            itemsIndexed(records, key = { index, record -> "${record.time}:$index" }) { index, record ->
                CouixCardRow(first = index == 0, last = index == records.lastIndex) {
                    if (index > 0) CouixItemDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        BasicText(
                            context.getString(if (record.clearData) R.string.ui_ban_op_clear else R.string.ui_ban_op_uninstall) +
                                " · " + record.label,
                            style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
                        BasicText(
                            context.getString(if (record.blocked) R.string.ui_ban_result_blocked else R.string.ui_ban_result_allowed) +
                                " · " + format.format(Date(record.time)),
                            style = MiuixTheme.textStyles.body2.copy(
                                color = if (record.blocked) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary))
                        BasicText(record.pkg.substringBeforeLast(':') + (if (record.caller.isEmpty()) ""
                            else "  ←  " + record.caller),
                            style = MiuixTheme.textStyles.body2.copy(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 11.sp))
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
