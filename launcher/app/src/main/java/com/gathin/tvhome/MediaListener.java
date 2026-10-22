package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app see other apps' media sessions (AppInfoLine's "Playing now")
 * when it has an enabled notification listener, so this empty one exists to be granted that access over adb:
 *   cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
 * It reads no notifications.
 */
public class MediaListener extends NotificationListenerService {
}
