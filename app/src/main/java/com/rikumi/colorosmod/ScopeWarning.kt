package com.rikumi.colorosmod

import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun missingScopeList(context: android.content.Context): List<String> {
    val services = MainActivity.xposedServices.toList()
    if (services.isEmpty()) return emptyList()
    return runCatching {
        val recommended = ScopeRequirements::class.java.classLoader!!
            .getResourceAsStream("META-INF/xposed/scope.list")?.bufferedReader()?.use { reader ->
                reader.lineSequence().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            } ?: return@runCatching emptyList()
        val selected = services.flatMap { it.scope }.toSet()
        recommended.filter { name ->
            val present = name == "system" || try {
                context.packageManager.getApplicationInfo(name, 0).enabled
            } catch (_: PackageManager.NameNotFoundException) { false }
            present && name !in selected
        }
    }.getOrDefault(emptyList())
}

// 向框架动态申请作用域(反射调用, 兼容 requestScope 的 List / 单包名两种签名)。
private fun requestScopes(pkgs: List<String>, onEvent: (String, String) -> Unit): Boolean {
    val service = MainActivity.xposedServices.firstOrNull() ?: return false
    val m = service.javaClass.methods.firstOrNull {
        it.name == "requestScope" && it.parameterTypes.size == 2 && it.parameterTypes[1].isInterface
    } ?: return false
    val listenerType = m.parameterTypes[1]
    val listener = java.lang.reflect.Proxy.newProxyInstance(
        listenerType.classLoader, arrayOf(listenerType),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "ScopeListener"
            else -> { onEvent(method.name, (args?.getOrNull(0) as? String).orEmpty()); null }
        }
    }
    return runCatching {
        if (m.parameterTypes[0].isAssignableFrom(List::class.java)) m.invoke(service, pkgs, listener)
        else pkgs.forEach { m.invoke(service, it, listener) }
        true
    }.getOrDefault(false)
}

@Composable
internal fun rememberMissingScopes(resumeRequest: Int): Boolean {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    val refresh: () -> Unit = {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val services = MainActivity.xposedServices.toList()
                if (services.isEmpty()) return@withContext false
                runCatching {
                    val recommended = ScopeRequirements::class.java.classLoader!!
                        .getResourceAsStream("META-INF/xposed/scope.list")?.bufferedReader()?.use { reader ->
                            reader.lineSequence().map { it.trim() }
                                .filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
                        } ?: return@runCatching false
                    val installed = recommended.filterTo(mutableSetOf()) { name ->
                        if (name == "system") false else try {
                            context.packageManager.getApplicationInfo(name, 0).enabled
                        } catch (_: PackageManager.NameNotFoundException) { false }
                    }
                    val selected = services.flatMap { it.scope }.toSet()
                    ScopeRequirements.hasMissing(recommended, installed, selected)
                }.getOrDefault(false)
            }
            missing = result
        }
    }
    DisposableEffect(Unit) {
        val observer = Runnable { scope.launch { refresh() } }
        MainActivity.scopeObservers.add(observer)
        onDispose {
            MainActivity.scopeObservers.remove(observer)
            refreshJob?.cancel()
        }
    }
    LaunchedEffect(resumeRequest) { refresh() }
    return missing
}

@Composable
internal fun ScopeWarningCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<List<String>?>(null) }
    var requesting by remember { mutableStateOf(false) }
    CouixCard(backgroundColor = Color(0xFFFFBE0A).copy(alpha = 0.18f)) {
        CouixCategoryRow(
            icon = null,
            title = context.getString(R.string.ui_scope_missing),
            onClick = {
                scope.launch {
                    val list = withContext(Dispatchers.IO) { missingScopeList(context) }
                    if (list.isNotEmpty()) dialog = list
                }
            },
        )
    }
    dialog?.let { list ->
        val pm = context.packageManager
        CouixConfirmDialog(
            title = context.getString(R.string.ui_scope_request_title),
            text = AnnotatedString(context.getString(R.string.ui_scope_request_hint)),
            confirmLabel = context.getString(if (requesting) R.string.ui_scope_requesting else R.string.ui_scope_request_confirm),
            extra = {
                list.forEach { pkg ->
                    val info = remember(pkg) {
                        val ipkg = if (pkg == "system") "android" else pkg
                        runCatching {
                            val ai = pm.getApplicationInfo(ipkg, 0)
                            val label = if (pkg == "system") context.getString(R.string.ui_scope_system)
                            else pm.getApplicationLabel(ai).toString()
                            val d = pm.getApplicationIcon(ai)
                            val w = d.intrinsicWidth.coerceIn(1, 192)
                            val h = d.intrinsicHeight.coerceIn(1, 192)
                            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                            val c = android.graphics.Canvas(bmp)
                            d.setBounds(0, 0, w, h); d.draw(c)
                            label to bmp.asImageBitmap()
                        }.getOrNull() ?: (pkg to null)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        info.second?.let {
                            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(44.dp))
                        } ?: Box(Modifier.size(44.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            BasicText(info.first, style = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp))
                            BasicText(pkg, style = TextStyle(color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp))
                        }
                    }
                }
            },
            onDismiss = { dialog = null },
            onConfirm = {
                if (!requesting) {
                    requesting = true
                    val ok = requestScopes(list) { event, pkg ->
                        val msg = when (event) {
                            "onScopeRequestApproved" -> context.getString(R.string.ui_scope_approved, pkg)
                            "onScopeRequestDenied" -> context.getString(R.string.ui_scope_denied, pkg)
                            "onScopeRequestTimeout" -> context.getString(R.string.ui_scope_timeout, pkg)
                            "onScopeRequestFailed" -> context.getString(R.string.ui_scope_failed, pkg)
                            else -> null
                        }
                        if (msg != null) android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            MainActivity.scopeObservers.forEach { it.run() }
                        }
                    }
                    if (!ok) android.widget.Toast.makeText(
                        context, context.getString(R.string.ui_scope_request_unsupported), android.widget.Toast.LENGTH_LONG,
                    ).show()
                    requesting = false
                    dialog = null
                }
            },
        )
    }
}

@Composable
internal fun ModuleInactiveCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    CouixCard(backgroundColor = Color(0xFFFF5252).copy(alpha = 0.18f)) {
        CouixCategoryRow(
            icon = null,
            title = context.getString(R.string.ui_module_inactive),
            onClick = {
                if (!opening) {
                    opening = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { launchApp(context, lsposedLaunchCmd()) } }
                        finally { opening = false }
                    }
                }
            },
        )
    }
}
