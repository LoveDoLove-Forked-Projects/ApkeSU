package me.weishu.kernelsu.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import me.weishu.kernelsu.IKsuInterface
import rikka.parcelablelist.ParcelableListSlice
import java.lang.reflect.Method

/**
 * @author weishu
 * @date 2023/4/18.
 */

class KsuService : RootService() {

    companion object {
        private const val TAG = "KsuService"
    }

    override fun onBind(intent: Intent): IBinder {
        return Stub()
    }

    private fun getAllUserIds(): IntArray {
        val um = getSystemService(USER_SERVICE) as UserManager
        // getAliveUsers() was added in API 31
        val users = runCatching {
            val method = findNoArgMethod(um.javaClass, "getAliveUsers")
                ?: error("getAliveUsers is unavailable")
            makeAccessible(method)
            method.invoke(um) as? List<*>
        }.onFailure {
            Log.w(TAG, "getAliveUsers reflection failed", it)
        }.getOrNull()

        val ids = extractUserIds(users)
        if (ids.isNotEmpty()) return ids

        // Some vendor builds hide getAliveUsers but still expose user profiles.
        // Keep this as a best-effort fallback; a failure must not block user 0.
        val profileUsers = runCatching {
            val method = findNoArgMethod(um.javaClass, "getUserProfiles")
                ?: error("getUserProfiles is unavailable")
            makeAccessible(method)
            method.invoke(um) as? List<*>
        }.onFailure {
            Log.w(TAG, "getUserProfiles reflection failed", it)
        }.getOrNull()
        val profileIds = extractUserIds(profileUsers)
        return if (profileIds.isEmpty()) intArrayOf(0) else profileIds
    }

    private fun extractUserIds(users: List<*>?): IntArray {
        return users.orEmpty()
            .mapNotNull(::extractUserId)
            .distinct()
            .sorted()
            .toIntArray()
    }

    private fun extractUserId(user: Any?): Int? {
        if (user == null) return null

        val fieldId = runCatching {
            user.javaClass.getField("id").getInt(user)
        }.recoverCatching {
            user.javaClass.getDeclaredField("id").apply(::makeAccessible).getInt(user)
        }.getOrNull()
        if (fieldId != null) return fieldId

        return runCatching {
            val method = user.javaClass.getMethod("getIdentifier")
            (method.invoke(user) as Number).toInt()
        }.getOrNull()
    }

    private fun getInstalledPackagesAll(flags: Int): ArrayList<PackageInfo> {
        val packages = ArrayList<PackageInfo>()
        val seen = HashSet<String>()
        for (userId in getAllUserIds()) {
            Log.i(TAG, "getInstalledPackagesAll: $userId")
            getInstalledPackagesAsUser(flags, userId).forEach { packageInfo ->
                val uid = packageInfo.applicationInfo?.uid ?: -1
                if (seen.add("${packageInfo.packageName}:$uid")) {
                    packages.add(packageInfo)
                }
            }
        }
        return packages
    }

    @Suppress("UNCHECKED_CAST")
    private fun getInstalledPackagesAsUser(flags: Int, userId: Int): List<PackageInfo> {
        val pm: PackageManager = packageManager
        var lastError: Throwable? = null

        // Android releases have exposed this hidden method with both int and long
        // flags. Resolve the method by shape instead of assuming one signature.
        val methods = (pm.javaClass.methods.asSequence() + pm.javaClass.declaredMethods.asSequence())
            .filter { method ->
                method.name == "getInstalledPackagesAsUser" &&
                    method.parameterTypes.size == 2 &&
                    isIntType(method.parameterTypes[1])
            }
            .distinctBy { it.parameterTypes.toList() }

        for (method in methods) {
            val flagsArgument = packageFlagsArgument(method.parameterTypes[0], flags) ?: continue
            try {
                makeAccessible(method)
                val result = method.invoke(pm, flagsArgument, userId) as? List<PackageInfo>
                if (result != null) return result
            } catch (error: Throwable) {
                lastError = error
            }
        }

        // The public API remains a useful fallback for the primary user. This
        // also handles devices that hide the user-scoped method completely.
        if (userId == 0) {
            try {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(flags)
                }
            } catch (error: Throwable) {
                lastError = error
            }
        } else {
            // A user-scoped Context can work on builds where the hidden method
            // is blocked, while still preserving the requested user boundary.
            try {
                val userPm = createPackageManagerAsUser(userId)
                    ?: throw IllegalStateException("user-scoped PackageManager is unavailable")
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    userPm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    userPm.getInstalledPackages(flags)
                }
            } catch (error: Throwable) {
                lastError = error
            }
        }

        Log.w(TAG, "getInstalledPackagesAsUser failed for user $userId", lastError)
        return emptyList()
    }

    private fun createPackageManagerAsUser(userId: Int): PackageManager? {
        return runCatching {
            val userHandleFactory = UserHandle::class.java.getDeclaredMethod(
                "of",
                Int::class.javaPrimitiveType,
            )
            makeAccessible(userHandleFactory)
            val userHandle = userHandleFactory.invoke(null, userId) as? UserHandle
                ?: return@runCatching null

            val contextMethod = (Context::class.java.methods.asSequence() +
                Context::class.java.declaredMethods.asSequence())
                .firstOrNull { method ->
                    method.name == "createContextAsUser" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == UserHandle::class.java &&
                        isIntType(method.parameterTypes[1])
                }
                ?: return@runCatching null
            makeAccessible(contextMethod)
            val userContext = contextMethod.invoke(this, userHandle, 0) as? Context
                ?: return@runCatching null
            userContext.packageManager
        }.onFailure {
            Log.w(TAG, "create user-scoped PackageManager failed for $userId", it)
        }.getOrNull()
    }

    private fun findNoArgMethod(type: Class<*>, name: String): Method? {
        return (type.methods.asSequence() + type.declaredMethods.asSequence())
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
    }

    private fun makeAccessible(method: Method) {
        runCatching { method.isAccessible = true }
    }

    private fun makeAccessible(field: java.lang.reflect.Field) {
        runCatching { field.isAccessible = true }
    }

    private fun isIntType(type: Class<*>): Boolean =
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType

    private fun packageFlagsArgument(type: Class<*>, flags: Int): Any? = when {
        isIntType(type) -> flags
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> flags.toLong()
        type.name == "android.content.pm.PackageManager\$PackageInfoFlags" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            PackageManager.PackageInfoFlags.of(flags.toLong())
        else -> null
    }

    private inner class Stub : IKsuInterface.Stub() {
        override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo> {
            val list = getInstalledPackagesAll(flags)
            Log.i(TAG, "getPackages: ${list.size}")
            return ParcelableListSlice(list)
        }

        override fun getUserIds(): IntArray {
            val ids = getAllUserIds()
            Log.i(TAG, "getUserIds: ${ids.contentToString()}")
            return ids
        }
    }
}
