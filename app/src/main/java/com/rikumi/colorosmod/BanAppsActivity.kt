package com.rikumi.colorosmod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** "禁止卸载 / 清除数据"的应用列表页(入口在 mod 设置里), 复用强制沉浸应用列表的选择界面。 */
class BanAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val context = LocalContext.current
            val navigationEventOwner = remember {
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = NavigationEventDispatcher()
                }
            }
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navigationEventOwner,
            ) {
                MiuixTheme(
                    controller = remember {
                        ThemeController(
                            colorSchemeMode = ColorSchemeMode.System,
                            lightColors = lightColorScheme(
                                primary = colorOSAccentColor(context, 0xFF00B4D8)
                            ),
                            darkColors = darkColorScheme(
                                primary = colorOSAccentColor(context, 0xFF00B4D8)
                            ),
                        )
                    },
                    textStyles = couixTextStyles(),
                ) {
                    CouixStatusBar()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.surface),
                    ) {
                        CouixOverscrollHost {
                            HiddenAppsScreen(
                                context = context,
                                onBack = { finish() },
                                banApplications = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
