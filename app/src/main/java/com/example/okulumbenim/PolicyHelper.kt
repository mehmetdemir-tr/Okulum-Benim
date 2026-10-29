package com.example.okulumbenim

import android.app.admin.DevicePolicyManager

object PolicyHelper {

    fun isOwner(dpm: DevicePolicyManager, pkg: String): Boolean =
        try {
            dpm.isDeviceOwnerApp(pkg)
        } catch (e: Exception) {
            false
        }

    fun isManaged(dpm: DevicePolicyManager, pkg: String): Boolean =
        try {
            dpm.isDeviceOwnerApp(pkg) || dpm.isProfileOwnerApp(pkg)
        } catch (e: Exception) {
            false
        }

    fun modeName(dpm: DevicePolicyManager, pkg: String): String =
        try {
            when {
                dpm.isDeviceOwnerApp(pkg) -> "DEVICE_OWNER"
                dpm.isProfileOwnerApp(pkg) -> "PROFILE_OWNER"
                else -> "UNMANAGED"
            }
        } catch (e: Exception) {
            "UNMANAGED"
        }
}
