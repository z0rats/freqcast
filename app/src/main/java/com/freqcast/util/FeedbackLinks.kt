package com.freqcast.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Contact points for user-reported problems: a public GitHub issue, or a direct email to the maintainer. */
object FeedbackLinks {
    private const val SUPPORT_EMAIL = "z0rats.dev@gmail.com"
    private const val GITHUB_NEW_ISSUE_URL = "https://github.com/z0rats/freqcast/issues/new"

    fun githubIssueIntent(
        title: String = "",
        body: String = "",
    ): Intent {
        val uri =
            Uri
                .parse(GITHUB_NEW_ISSUE_URL)
                .buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("body", body)
                .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    fun emailIntent(
        subject: String,
        body: String,
    ): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

    /**
     * A report body starting with [extra] (e.g. what the user was trying to do) followed by
     * app/device diagnostics, so bug reports carry useful context without the user typing it.
     */
    fun reportBody(
        context: Context,
        extra: String? = null,
    ): String {
        val diagnostics =
            "App version: ${appVersionName(context)}\n" +
                "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        return if (extra.isNullOrBlank()) diagnostics else "$extra\n\n---\n$diagnostics"
    }

    fun appVersionName(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.orEmpty()
}
