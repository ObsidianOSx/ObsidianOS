package obsidian.chat.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import obsidian.chat.data.SecureStore

/**
 * The panic wipe behind the hold-to-wipe button.
 *
 * It erases what this app holds first — that always works and needs no network — and then asks the
 * phone to factory reset. The reset only happens if the app has been made a device administrator,
 * which OBSIDIAN does for its own apps; on an ordinary Android phone the app's own data is still
 * destroyed, including the Keystore key, so what is left on disk can never be decrypted.
 */
object PanicWipe {
    /** Erases every file and secret this app holds. */
    fun wipeLocalData(context: Context, store: SecureStore) {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively()
        store.wipe()
    }

    /** Factory resets the phone. Returns false if this app was never allowed to. */
    fun factoryReset(context: Context): Boolean {
        val policy = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = ComponentName(context, PanicDeviceAdmin::class.java)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                policy.isDeviceOwnerApp(context.packageName) -> {
                policy.wipeDevice(0)
                true
            }
            policy.isAdminActive(admin) -> {
                policy.wipeData(0)
                true
            }
            else -> false
        }
    }
}
