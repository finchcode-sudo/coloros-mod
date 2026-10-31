package com.rikumi.colorosmod;

/** 禁止卸载 / 禁止清除数据(移植自 BanUninstall)的设置键。应用列表沿用"强制沉浸应用列表"的存取方式。 */
public final class BanUninstallSettings {
    public static final String UNINSTALL = "ban_uninstall_enabled";
    public static final String CLEAR_DATA = "ban_clear_data_enabled";
    public static final String USE_LIST = "ban_uninstall_use_list";
    public static final String PACKAGES = "ban_uninstall_packages";
    public static final String PREFIX = "ban_uninstall_package:";
    // system_server 写入 Settings.Global, 设置页读取: 本次开机内 hook 是否已加载 / 操作记录。
    public static final String ALIVE = "ban_uninstall_alive";
    public static final String RECORDS = "ban_uninstall_records";

    private BanUninstallSettings() {}
}
