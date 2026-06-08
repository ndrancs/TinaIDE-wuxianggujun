package com.wuxianggujun.tinaide.core.git.ssh

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.sshd.JGitKeyCache
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import timber.log.Timber

/** SSH passphrase 错误标记，用于 ViewModel 层识别 */
const val TINA_GIT_SSH_PASSPHRASE_MARKER = "[TINA_SSH_PASSPHRASE]"

/**
 * SSH 密钥管理器 — 纯 JGit 实现
 *
 * 职责：
 * 1. 管理 SSH 密钥的生成、导入、删除
 * 2. 管理 Host → 密钥绑定
 * 3. 为 Git 远程操作构建 JGit SshdSessionFactory
 *
 * 不依赖 PRoot、外部 ssh CLI 或 ssh-agent。
 * 所有 SSH 认证通过 JGit 内置的 Apache MINA SSHD 完成。
 */
class GitSshManager(context: Context) {

    private val appContext = context.applicationContext
    private val sshDir = File(appContext.filesDir, "ssh")
    private val store = GitSshStore(appContext)
    private val keyCache = JGitKeyCache()

    /** 内存中的 passphrase 缓存（keyName → passphrase），不持久化 */
    private val passphraseCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "GitSshManager"
        private const val DEFAULT_KEY_NAME = "id_ed25519"
    }

    init {
        sshDir.mkdirs()
    }

    // ── 密钥查询 ──

    suspend fun listKeys(): List<GitSshKeyMeta> = store.read().keys

    suspend fun listHostBindings(): List<GitSshHostBinding> = store.read().hostBindings

    suspend fun getDefaultKeyName(): String? = store.read().defaultKeyName

    suspend fun setDefaultKeyName(name: String?) {
        val state = store.read()
        store.write(state.copy(defaultKeyName = name))
    }

    // ── 密钥生成 ──

    /**
     * 生成 Ed25519 密钥对，保存到 sshDir，并更新元数据
     */
    suspend fun generateEd25519Key(
        keyName: String = DEFAULT_KEY_NAME,
        comment: String? = null,
    ): Result<GitSshKeyMeta> = withContext(Dispatchers.IO) {
        runCatching {
            validateKeyName(keyName)

            val kpg = KeyPairGenerator.getInstance("Ed25519")
            val keyPair = kpg.generateKeyPair()
            writeKeyPairToFiles(keyName, keyPair, comment.orEmpty())

            val meta = GitSshKeyMeta(
                name = keyName,
                type = "ed25519",
                comment = comment?.trim()?.takeIf { it.isNotEmpty() }
            )
            val state = store.read()
            val updatedKeys = state.keys.filter { it.name != keyName } + meta
            val newDefault = state.defaultKeyName ?: keyName
            store.write(state.copy(keys = updatedKeys, defaultKeyName = newDefault))
            meta
        }
    }

    // ── 密钥导入 ──

    /**
     * 导入已有私钥（PEM 格式文本）
     */
    suspend fun importPrivateKey(
        keyName: String,
        privateKeyContent: String,
        comment: String? = null,
    ): Result<GitSshKeyMeta> = withContext(Dispatchers.IO) {
        runCatching {
            validateKeyName(keyName)

            val privateFile = File(sshDir, keyName)
            privateFile.writeText(privateKeyContent.trimEnd() + "\n", Charsets.UTF_8)
            setFilePermissions(privateFile)

            val meta = GitSshKeyMeta(
                name = keyName,
                type = "imported",
                comment = comment?.trim()?.takeIf { it.isNotEmpty() }
            )
            val state = store.read()
            val updatedKeys = state.keys.filter { it.name != keyName } + meta
            val newDefault = state.defaultKeyName ?: keyName
            store.write(state.copy(keys = updatedKeys, defaultKeyName = newDefault))
            meta
        }
    }

    // ── 密钥删除 ──

    suspend fun deleteKey(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            File(sshDir, name).delete()
            File(sshDir, "$name.pub").delete()

            val state = store.read()
            val updatedKeys = state.keys.filter { it.name != name }
            val updatedBindings = state.hostBindings.filter { it.keyName != name }
            val newDefault = if (state.defaultKeyName == name) {
                updatedKeys.firstOrNull()?.name
            } else {
                state.defaultKeyName
            }
            store.write(
                state.copy(
                    keys = updatedKeys,
                    hostBindings = updatedBindings,
                    defaultKeyName = newDefault
                )
            )
        }
    }

    // ── 公钥读取 ──

    suspend fun readPublicKey(name: String): String? = withContext(Dispatchers.IO) {
        val pubFile = File(sshDir, "$name.pub")
        if (pubFile.exists()) {
            runCatching { pubFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        } else {
            null
        }
    }

    // ── Host 绑定 ──

    suspend fun upsertHostBinding(binding: GitSshHostBinding) {
        val state = store.read()
        val updated = state.hostBindings.filter { it.host != binding.host } + binding
        store.write(state.copy(hostBindings = updated))
    }

    suspend fun deleteHostBinding(host: String) {
        val state = store.read()
        store.write(state.copy(hostBindings = state.hostBindings.filter { it.host != host }))
    }

    // ── SSH URL 解析 ──

    /**
     * 解析 SSH URL 中的 host 和 port
     * 支持格式：
     * - ssh://git@github.com/user/repo.git
     * - git@github.com:user/repo.git
     */
    fun parseSshTarget(url: String): ParsedSshTarget? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null
        return try {
            if (trimmed.startsWith("ssh://") || trimmed.startsWith("git+ssh://")) {
                val uri = URI(trimmed)
                val host = uri.host ?: return null
                ParsedSshTarget(host, uri.port.takeIf { it > 0 })
            } else {
                // git@github.com:user/repo.git 格式
                val atIdx = trimmed.indexOf('@')
                if (atIdx < 0) return null
                val rest = trimmed.substring(atIdx + 1)
                val colonIdx = rest.indexOf(':')
                if (colonIdx < 0) return null
                val host = rest.substring(0, colonIdx)
                ParsedSshTarget(host, null)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "parseSshTarget failed for: $trimmed")
            null
        }
    }

    // ── Passphrase 缓存 ──

    /**
     * 缓存密钥的 passphrase（仅内存，不持久化）
     */
    fun cachePassphrase(keyName: String, passphrase: String) {
        passphraseCache[keyName] = passphrase
    }

    /**
     * 清除指定密钥的 passphrase 缓存
     */
    fun clearPassphrase(keyName: String) {
        passphraseCache.remove(keyName)
    }

    /**
     * 清除所有 passphrase 缓存
     */
    fun clearAllPassphrases() {
        passphraseCache.clear()
    }

    // ── JGit SshdSessionFactory ──

    /**
     * 为远程操作构建 JGit SshdSessionFactory。
     * 根据 URL 中的 host 查找绑定的密钥，或使用默认密钥。
     * 如果密钥有 passphrase 保护，会从内存缓存中获取。
     */
    suspend fun buildSshSessionFactory(url: String): SshdSessionFactory = withContext(Dispatchers.IO) {
        val target = parseSshTarget(url)
        val state = store.read()

        val keyName = if (target != null) {
            state.hostBindings
                .firstOrNull { it.host.equals(target.host, ignoreCase = true) }
                ?.keyName
        } else {
            null
        }
        val resolvedKeyName = keyName ?: state.defaultKeyName ?: DEFAULT_KEY_NAME

        val keyFile = File(sshDir, resolvedKeyName)
        val cachedPassphrase = passphraseCache[resolvedKeyName]
        Timber.tag(TAG).d("buildSshSessionFactory: url=$url, key=$resolvedKeyName, exists=${keyFile.exists()}, hasPassphrase=${cachedPassphrase != null}")

        object : SshdSessionFactory(keyCache, null) {
            override fun getHomeDirectory(): File = sshDir
            override fun getSshDirectory(): File = sshDir

            override fun getDefaultKeys(sshDir: File): Iterable<KeyPair> {
                if (!keyFile.exists()) return emptyList()
                return try {
                    val provider = FileKeyPairProvider(keyFile.toPath())
                    if (cachedPassphrase != null) {
                        provider.setPasswordFinder { _, _, _ -> cachedPassphrase }
                    }
                    provider.loadKeys(null).toList()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to load key: $resolvedKeyName")
                    // 如果加载失败且可能是 passphrase 问题，抛出带标记的异常
                    if (isPassphraseError(e)) {
                        throw RuntimeException(
                            "$TINA_GIT_SSH_PASSPHRASE_MARKER keyName=$resolvedKeyName\n${e.message}",
                            e
                        )
                    }
                    emptyList()
                }
            }

            override fun getServerKeyDatabase(
                homeDir: File,
                sshDir: File
            ): ServerKeyDatabase = object : ServerKeyDatabase {
                override fun lookup(
                    connectAddress: String,
                    remoteAddress: InetSocketAddress,
                    config: ServerKeyDatabase.Configuration
                ): List<PublicKey> = emptyList()

                override fun accept(
                    connectAddress: String,
                    remoteAddress: InetSocketAddress,
                    serverKey: PublicKey,
                    config: ServerKeyDatabase.Configuration,
                    provider: CredentialsProvider?
                ): Boolean = true // 信任所有 host key
            }
        }
    }

    private fun isPassphraseError(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase(Locale.ROOT)
        return "passphrase" in msg ||
            "encrypted" in msg ||
            "password" in msg ||
            "failed to decrypt" in msg ||
            "cannot read" in msg
    }

    fun getSshDir(): File = sshDir

    // ── 内部工具方法 ──

    private fun writeKeyPairToFiles(keyName: String, keyPair: KeyPair, comment: String) {
        val privateFile = File(sshDir, keyName)
        val publicFile = File(sshDir, "$keyName.pub")

        // 写私钥（PKCS8 PEM 格式）
        val privateEncoded = keyPair.private.encoded
        val privatePem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            Base64.getMimeEncoder(76, "\n".toByteArray())
                .encodeToString(privateEncoded)
                .lines()
                .forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
        privateFile.writeText(privatePem, Charsets.UTF_8)
        setFilePermissions(privateFile)

        // 写公钥（OpenSSH 格式）
        val publicEncoded = keyPair.public.encoded
        val pubBase64 = Base64.getEncoder().encodeToString(publicEncoded)
        val pubLine = "ssh-ed25519 $pubBase64${if (comment.isNotBlank()) " $comment" else ""}"
        publicFile.writeText(pubLine + "\n", Charsets.UTF_8)
    }

    private fun setFilePermissions(file: File) {
        try {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.setExecutable(false, false)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "setFilePermissions failed for ${file.name}")
        }
    }

    private fun validateKeyName(name: String) {
        require(name.isNotBlank()) { "Key name must not be blank" }
        require(!name.contains('/') && !name.contains('\\')) { "Key name must not contain path separators" }
        require(!name.startsWith('.')) { "Key name must not start with '.'" }
    }
}

/**
 * 从错误信息中解析 SSH passphrase 需求
 */
fun parseGitSshPassphraseRequired(message: String): Pair<String, String?>? {
    if ("passphrase" !in message.lowercase(Locale.ROOT)) return null
    val keyNameRegex = Regex("for key '([^']+)'|for (.+\\.pem)")
    val match = keyNameRegex.find(message)
    val keyName = match?.groupValues?.firstOrNull { it.isNotBlank() && it != match.value }
    return "passphrase_required" to keyName
}
