package obsidian.chat.ui

import obsidian.chat.BuildConfig

/**
 * Keeps the server's onion address off the screen. Errors from the XMPP library often quote the
 * address they were talking to, and there is no reason for a user (or anyone reading over their
 * shoulder, or a screenshot) to see it.
 */
fun String.withoutServerName(): String = replace(BuildConfig.SERVER_DOMAIN, "our server")
