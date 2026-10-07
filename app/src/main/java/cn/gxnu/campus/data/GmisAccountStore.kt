package cn.gxnu.campus.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.ui.AccountVault
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 研究生系统的账号。它和校园网账号分开存 —— 两个系统可以是不同的密码，注销其中一个不该动另一个。
 *
 * 加密方式与 [CredentialStore] 完全一致（Android Keystore 里的 AES-GCM），只是换了存储文件与
 * 密钥别名，所以两份凭据互不可见。
 */
class GmisAccountStore(context: Context) : AccountVault {
    private val vault = CredentialStore(GmisStorage(context), GmisKey())

    override fun save(credentials: Credentials) = vault.save(credentials)
    override fun load(): Credentials? = vault.load()
    override fun clear() = vault.clear()
}

private class GmisStorage(context: Context) : CiphertextStorage {
    private val applicationContext = context.applicationContext
    private val preferences by lazy {
        applicationContext.getSharedPreferences("gmis_credentials", Context.MODE_PRIVATE)
    }

    override fun read(): ByteArray? =
        preferences.getString("encrypted_payload", null)?.let { Base64.getDecoder().decode(it) }

    override fun write(value: ByteArray) {
        check(preferences.edit().putString("encrypted_payload", Base64.getEncoder().encodeToString(value)).commit())
    }

    override fun remove() { check(preferences.edit().clear().commit()) }
}

private class GmisKey : CredentialKeyAccess {
    private val alias = "cn.gxnu.campus.gmis.v1"

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun key(): SecretKey {
        val existing = keystore().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    override fun delete() { keystore().deleteEntry(alias) }
}
