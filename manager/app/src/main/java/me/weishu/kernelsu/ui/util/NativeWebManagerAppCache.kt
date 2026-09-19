package me.weishu.kernelsu.ui.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ksuApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "NativeWebManagerApps"
private const val CACHE_SCHEMA_VERSION = 1
private const val ICON_SIZE_PX = 96
private const val LOCAL_CACHE_DIR = "native-web-manager-apps"
private const val ROOT_CACHE_DIR = "/data/adb/ksu/web_manager_apps"

/**
 * Persists PackageManager labels and icons for the ksud-hosted PWA. The root-side
 * copy intentionally survives APK removal so an installed PWA keeps its last
 * known application names and icons while ksud remains available.
 */
internal fun syncNativeWebManagerAppCache(): Boolean = runCatching {
    val packageManager = ksuApp.packageManager
    val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0)
    }.asSequence()
        .filter { info ->
            info.uid >= 10_000 &&
                (info.flags and ApplicationInfo.FLAG_HAS_CODE) != 0 &&
                info.packageName.matches(Regex("[A-Za-z0-9._]+"))
        }
        .sortedBy { info -> info.loadLabel(packageManager).toString().lowercase() }
        .toList()

    val localRoot = File(ksuApp.cacheDir, LOCAL_CACHE_DIR).apply { mkdirs() }
    val localIcons = File(localRoot, "icons").apply { mkdirs() }
    val records = JSONArray()
    applications.forEach { info ->
        val packageName = info.packageName
        val label = runCatching { info.loadLabel(packageManager).toString() }
            .getOrDefault(packageName)
            .ifBlank { packageName }
        val iconFile = File(localIcons, "$packageName.png")
        val packageUpdatedAt = File(info.sourceDir.orEmpty()).lastModified()
        if (!iconFile.isFile || iconFile.lastModified() < packageUpdatedAt) {
            writeApplicationIcon(packageManager, info, iconFile)
        }
        records.put(
            JSONObject()
                .put("packageName", packageName)
                .put("label", label)
                .put("iconAvailable", iconFile.isFile),
        )
    }

    val metadata = File(localRoot, "apps.json")
    metadata.writeText(
        JSONObject()
            .put("schemaVersion", CACHE_SCHEMA_VERSION)
            .put("generatedAt", System.currentTimeMillis())
            .put("apps", records)
            .toString(),
    )

    val root = shellQuote(ROOT_CACHE_DIR)
    val sourceMetadata = shellQuote(metadata.absolutePath)
    val sourceIcons = shellQuote(localIcons.absolutePath + "/.")
    withNewRootShell {
        check(isRoot) { "root shell unavailable" }
        ShellUtils.fastCmdResult(
            this,
            "mkdir -p $root/icons && " +
                "cp -f $sourceMetadata $root/apps.json.new && " +
                "cp -Rf $sourceIcons $root/icons/ && " +
                "chmod 0700 $root $root/icons && " +
                "chmod 0600 $root/apps.json.new && " +
                "mv -f $root/apps.json.new $root/apps.json",
        )
    }
}.onFailure { error ->
    Log.w(TAG, "failed to synchronize native web manager app metadata", error)
}.getOrDefault(false)

private fun writeApplicationIcon(
    packageManager: PackageManager,
    info: ApplicationInfo,
    target: File,
) {
    runCatching {
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        try {
            val drawable = info.loadIcon(packageManager)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            target.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "failed to encode $target"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }.onFailure { error ->
        Log.w(TAG, "failed to cache icon for ${info.packageName}", error)
        target.delete()
    }
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
