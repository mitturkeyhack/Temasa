package com.erkam.temasa

import android.content.Context

object BuildConfigLite {
    fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
}
