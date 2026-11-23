package io.github.detach

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telecom.TelecomManager

@Suppress("DEPRECATION")
object FocusAppPicker {
    fun show(activity: Activity, saved: () -> Unit) {
        if (!FocusSession.active(activity)) PaymentAppPicker.showFocusApps(activity, saved)
    }

    internal fun pickableApps(context: Context, saved: Set<String>): List<PaymentAppPicker.App> {
        val settings = Settings(context)
        val manager = context.packageManager
        val apps = PaymentAppPicker.loadApps(manager, context.packageName, saved, blocking = true).filter { app ->
            val packageName = app.info.packageName
            settings.canAllowInFocus(packageName) && launchIntent(manager, packageName) != null
        }
        return collapseEssentialAliases(context, settings, apps)
    }

    internal fun allowedApps(context: Context): List<PaymentAppPicker.App> {
        val settings = Settings(context)
        val apps = PaymentAppPicker.appsFromPackages(context.packageManager, settings.focusAllowedPackages, blocking = true)
            .filter { launchIntent(context.packageManager, it.info.packageName) != null }
        return collapseEssentialAliases(context, settings, apps)
    }

    private fun collapseEssentialAliases(
        context: Context,
        settings: Settings,
        apps: List<PaymentAppPicker.App>,
    ): List<PaymentAppPicker.App> {
        // Keep separate apps with identical names. Collapse only known essential variants
        // that show the same name, while leaving both packages on the allowlist for calls.
        return apps.groupBy { app ->
            val packageName = app.info.packageName
            (settings.focusEssential(packageName)?.name ?: packageName) to app.searchName
        }.values.map { aliases ->
            val first = aliases.first()
            if (aliases.size == 1) first else {
                val preferredPackage = runCatching {
                    when (settings.focusEssential(first.info.packageName)) {
                        Settings.FocusEssential.PHONE ->
                            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
                        Settings.FocusEssential.CONTACTS -> context.packageManager.resolveActivity(
                            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
                            PackageManager.MATCH_DEFAULT_ONLY,
                        )?.activityInfo?.packageName
                        else -> null
                    }
                }.getOrNull()
                aliases.firstOrNull { it.info.packageName == preferredPackage } ?: first
            }
        }
    }

    internal fun launchIntent(manager: PackageManager, packageName: String): Intent? = runCatching {
        manager.getLaunchIntentForPackage(packageName) ?: manager.getLeanbackLaunchIntentForPackage(packageName)
    }.getOrNull()
}
