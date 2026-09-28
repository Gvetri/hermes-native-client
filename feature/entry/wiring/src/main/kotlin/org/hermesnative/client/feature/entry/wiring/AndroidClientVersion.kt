package org.hermesnative.client.feature.entry.wiring

import android.content.Context

/** Fallback client version for an installation without a version name. */
const val UNKNOWN_CLIENT_VERSION = "unknown"

/** Installed client version recorded in an export's metadata record. */
@Suppress("DEPRECATION")
fun androidClientVersion(context: Context): String =
    runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
    }.getOrNull().orEmpty().takeIf(String::isNotBlank) ?: UNKNOWN_CLIENT_VERSION
