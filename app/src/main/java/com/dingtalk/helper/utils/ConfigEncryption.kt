package com.dingtalk.helper.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置加密工具
 *
 * 功能：
 * 1. 配置值混淆 - String 使用 AES-256-GCM 加密，Boolean/Int 使用 XOR 混淆
 * 2. 配置文件名混淆 - 不使用 "dingtalk_helper_prefs" 等明显名称
 * 3. 密钥基于设备指纹生成 - 不同设备产生不同密钥
 *
 * 线程安全：所有方法均为线程安全
 */
object ConfigEncryption {

    private const val PREFS_NAME_PREFIX = "sys"
    private const val AES_ALGORITHM = "AES/GCM/NoPadding"
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_SIZE = 128
    private const val SALT = "DkH7pR3vQ9xZ2wY5"

    @Volatile
    private var secretKey: SecretKeySpec? = null

    @Volatile
    private var deviceFingerprint: String? = null

    @Volatile
    private var isInitialized = false
    private val lock = Any()

    /**
     * 初始化加密环境
     * 必须在使用任何加密/解密方法之前调用
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return
            try {
                deviceFingerprint = generateDeviceFingerprint(context)
                secretKey = generateKey(deviceFingerprint!!)
                isInitialized = true
            } catch (e: Exception) {
                isInitialized = false
            }
        }
    }

    /**
     * 获取加密的 SharedPreferences 实例
     * 返回一个代理对象，自动对存储的 String 值进行 AES-256-GCM 加密，
     * 对 Boolean/Int 值进行 XOR 混淆
     *
     * @param context 应用上下文
     * @param prefsName SharedPreferences 文件名（将被混淆）
     * @return 加密的 SharedPreferences 实例
     */
    fun getEncryptedPreferences(context: Context, prefsName: String): SharedPreferences {
        if (!isInitialized) {
            init(context)
        }
        val obfuscatedName = obfuscatePrefsName(prefsName)
        val delegate = context.getSharedPreferences(obfuscatedName, Context.MODE_PRIVATE)
        return EncryptedPreferences(delegate, secretKey!!)
    }

    // ==================== 内部实现 ====================

    /**
     * 生成设备指纹
     * 使用多个设备标识符的组合，确保唯一性和一致性
     */
    @SuppressLint("HardwareIds")
    private fun generateDeviceFingerprint(context: Context): String {
        val parts = mutableListOf<String>()

        // 1. Android ID（最可靠）
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                parts.add(androidId)
            }
        } catch (_: Exception) {}

        // 2. 应用包名（保证一致性）
        parts.add(context.packageName)

        // 3. 应用签名哈希（保证同签名设备一致）
        try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            if (signatures != null && signatures.isNotEmpty()) {
                val sigHash = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
                parts.add(Base64.encodeToString(sigHash, Base64.NO_WRAP).take(16))
            }
        } catch (_: Exception) {}

        // 4. 设备硬件信息（补充）
        parts.add(android.os.Build.BOARD)
        parts.add(android.os.Build.DEVICE)

        val combined = parts.joinToString("|")
        return if (combined.isNotEmpty()) {
            combined
        } else {
            "fallback_${context.packageName}"
        }
    }

    /**
     * 生成 AES-256 密钥
     * 使用 SHA-256 哈希设备指纹 + 盐值
     */
    private fun generateKey(fingerprint: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((fingerprint + SALT).toByteArray(Charsets.UTF_8))
        val keyBytes = digest.digest() // 32 字节 = 256 位
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * 混淆 SharedPreferences 文件名
     * 使用哈希生成不明显的文件名
     */
    private fun obfuscatePrefsName(originalName: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(
            (originalName + (deviceFingerprint ?: "")).toByteArray(Charsets.UTF_8)
        )
        val hashHex = hash.joinToString("") { "%02x".format(it) }.take(16)
        return "${PREFS_NAME_PREFIX}_$hashHex"
    }

    // ==================== 加密/解密方法 ====================

    /**
     * AES-256-GCM 加密字符串
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        val key = secretKey ?: return plaintext

        try {
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // 格式: IV + 密文
            val combined = iv + encrypted
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            return plaintext
        }
    }

    /**
     * AES-256-GCM 解密字符串
     */
    fun decrypt(encrypted: String): String {
        if (encrypted.isEmpty()) return encrypted
        val key = secretKey ?: return encrypted

        try {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            if (combined.size < GCM_IV_SIZE) return encrypted

            val iv = combined.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = combined.copyOfRange(GCM_IV_SIZE, combined.size)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_SIZE, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decrypted = cipher.doFinal(ciphertext)
            return String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            return encrypted
        }
    }

    /**
     * Boolean 混淆：与密钥派生的字节进行 XOR
     * true/false 不以明文形式存储
     */
    fun obfuscateBoolean(value: Boolean, keyHint: String): String {
        val keyBytes = secretKey?.encoded ?: return value.toString()
        val mask = keyBytes[(keyHint.hashCode() and 0xFF).mod(keyBytes.size)].toInt() and 0xFF
        val obfuscated = if (value) (1 xor mask) else (0 xor mask)
        return obfuscated.toString()
    }

    /**
     * Boolean 反混淆
     */
    fun deobfuscateBoolean(obfuscatedStr: String, keyHint: String): Boolean? {
        val keyBytes = secretKey?.encoded ?: return obfuscatedStr.toBooleanStrictOrNull()
        val mask = keyBytes[(keyHint.hashCode() and 0xFF).mod(keyBytes.size)].toInt() and 0xFF
        return try {
            val obfuscated = obfuscatedStr.toInt()
            val original = obfuscated xor mask
            original != 0
        } catch (_: Exception) {
            obfuscatedStr.toBooleanStrictOrNull()
        }
    }

    /**
     * Int 混淆：与密钥派生的掩码进行 XOR
     */
    fun obfuscateInt(value: Int, keyHint: String): String {
        val keyBytes = secretKey?.encoded ?: return value.toString()
        val mask = ((keyBytes[(keyHint.hashCode() and 0xFF).mod(keyBytes.size)].toInt() and 0xFF) shl 24) or
                   ((keyBytes[((keyHint.hashCode() + 1) and 0xFF).mod(keyBytes.size)].toInt() and 0xFF) shl 16) or
                   ((keyBytes[((keyHint.hashCode() + 2) and 0xFF).mod(keyBytes.size)].toInt() and 0xFF) shl 8) or
                   (keyBytes[((keyHint.hashCode() + 3) and 0xFF).mod(keyBytes.size)].toInt() and 0xFF)
        return (value xor mask).toString()
    }

    /**
     * Int 反混淆
     */
    fun deobfuscateInt(obfuscatedStr: String, keyHint: String): Int? {
        val keyBytes = secretKey?.encoded ?: return obfuscatedStr.toIntOrNull()
        val mask = ((keyBytes[(keyHint.hashCode() and 0xFF).mod(keyBytes.size)].toInt() and 0xFF) shl 24) or
                   ((keyBytes[((keyHint.hashCode() + 1) and 0xFF).mod(keyBytes.size)].toInt() and 0xFF) shl 16) or
                   ((keyBytes[((keyHint.hashCode() + 2) and 0xFF).mod(keyBytes.size)].toInt() and 0xFF) shl 8) or
                   (keyBytes[((keyHint.hashCode() + 3) and 0xFF).mod(keyBytes.size)].toInt() and 0xFF)
        return try {
            obfuscatedStr.toInt() xor mask
        } catch (_: Exception) {
            obfuscatedStr.toIntOrNull()
        }
    }

    // ==================== 加密 SharedPreferences 代理 ====================

    /**
     * SharedPreferences 加密代理
     *
     * 对 getString/putString 进行 AES-256-GCM 加密
     * 对 getBoolean/putBoolean 和 getInt/putInt 进行 XOR 混淆
     */
    private class EncryptedPreferences(
        private val delegate: SharedPreferences,
        private val key: SecretKeySpec
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> {
            return delegate.all.toMutableMap()
        }

        override fun getString(key: String, defValue: String?): String? {
            val raw = delegate.getString(key, null) ?: return defValue
            return try {
                decrypt(raw)
            } catch (_: Exception) {
                raw
            }
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            return delegate.getStringSet(key, defValues)
        }

        override fun getInt(key: String, defValue: Int): Int {
            val raw = delegate.getString(key, null) ?: return defValue
            return deobfuscateInt(raw, key) ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return delegate.getLong(key, defValue)
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return delegate.getFloat(key, defValue)
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            val raw = delegate.getString(key, null) ?: return defValue
            return deobfuscateBoolean(raw, key) ?: defValue
        }

        override fun contains(key: String?): Boolean {
            return delegate.contains(key)
        }

        override fun edit(): SharedPreferences.Editor {
            return Editor(delegate.edit())
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            delegate.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            delegate.unregisterOnSharedPreferenceChangeListener(listener)
        }

        /**
         * 加密 Editor 代理
         * putString 使用 AES-256-GCM 加密
         * putBoolean/putInt 使用 XOR 混淆
         */
        private inner class Editor(
            private val delegateEditor: SharedPreferences.Editor
        ) : SharedPreferences.Editor {

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                val encrypted = if (value != null) encrypt(value) else null
                delegateEditor.putString(key, encrypted)
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                delegateEditor.putStringSet(key, values)
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                delegateEditor.putString(key, obfuscateInt(value, key))
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                delegateEditor.putLong(key, value)
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                delegateEditor.putFloat(key, value)
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                delegateEditor.putString(key, obfuscateBoolean(value, key))
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                delegateEditor.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                delegateEditor.clear()
                return this
            }

            override fun commit(): Boolean {
                return delegateEditor.commit()
            }

            override fun apply() {
                delegateEditor.apply()
            }
        }
    }
}
