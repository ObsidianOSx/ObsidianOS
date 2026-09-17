package obsidian.chat.security

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.UserManager

/**
 * The phone's own lock screen — the PIN that stops anyone picking the phone up and using it.
 *
 * OBSIDIAN ships this app as the phone's device owner, so it can insist on a lock being set and on
 * it being a decent one. Everything the phone encrypts at rest is tied to that lock, so without it
 * the storage encryption protects nothing against someone holding the phone.
 */
object DeviceLock {
    /** True when a PIN, pattern or password is set on the phone. */
    fun isSet(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Opens the phone's own "set a screen lock" screen. */
    fun setLockIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * What OBSIDIAN enforces on a phone it owns: location never turns on, nothing gets side-loaded,
     * and the screen lock has to be a decent one. As device owner these are not settings a user, or
     * an app pretending to be one, can undo. On an ordinary phone none of it applies and the app
     * simply carries on.
     */
    fun applyLockdown(context: Context) {
        val policy = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!policy.isDeviceOwnerApp(context.packageName)) return
        val admin = ComponentName(context, PanicDeviceAdmin::class.java)
        // GPS and network location stay off, and the switch is taken away
        runCatching { policy.setLocationEnabled(admin, false) }
        runCatching { policy.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION) }
        runCatching { policy.addUserRestriction(admin, UserManager.DISALLOW_SHARE_LOCATION) }
        // No side-loading: only what we ship is on the phone
        runCatching { policy.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
        runCatching { policy.setRequiredPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM) }
    }

    /** True when the phone reports location switched off. */
    fun locationIsOff(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true
}
