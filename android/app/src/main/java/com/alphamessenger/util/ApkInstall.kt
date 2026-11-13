package com.alphamessenger.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.alphamessenger.R
import java.io.File

/**
 * An .apk received in a chat goes to the SYSTEM installer, which shows the app
 * and asks the user to confirm. The installer accepts a package only from an
 * app the user has allowed to install apps; until then we open that switch.
 */
object ApkInstall {
    fun isApk(file: File): Boolean = file.name.endsWith(".apk", ignoreCase = true)

    fun open(ctx: Context, file: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, ctx.getString(R.string.apk_allow_install), Toast.LENGTH_LONG).show()
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
