package com.steamaple.greenbirdkidslock;

import android.app.admin.DeviceAdminReceiver;

/**
 * 设备管理器：仅用于提高“被孩子直接卸载”的门槛。
 * 普通 App 无法真正禁止卸载；启用后卸载前必须先停用设备管理器。
 */
public class KidsLockDeviceAdminReceiver extends DeviceAdminReceiver {
}
