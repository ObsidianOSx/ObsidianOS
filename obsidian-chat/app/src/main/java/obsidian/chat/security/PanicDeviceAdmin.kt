package obsidian.chat.security

import android.app.admin.DeviceAdminReceiver

/**
 * Exists only so the phone will let [PanicWipe] factory reset it. It takes no other powers: the
 * policy it declares (res/xml/device_admin.xml) is wipe-data and nothing else.
 */
class PanicDeviceAdmin : DeviceAdminReceiver()
