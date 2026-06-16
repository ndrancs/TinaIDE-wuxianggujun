package com.wuxianggujun.tinaide.core.compile.artifact

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 基于每个 buildDir 一份 JSON 文件的简单产物存储。
 *
 * 存储路径:`<buildDir>/.tina/artifacts/<targetName>.<variant>.json`
 *
 * 设计约束:
 * - 单 JSON 文件,无 CAS 层,无跨项目共享(YAGNI)
 * - 所有 IO 调用切到指定 dispatcher,避免 block 调用线程
 * - schema 变更时(BuildFingerprint.SCHEMA_VERSION +1)旧文件反序列化失败 → 视为不存在
 */
class JsonArtifactStore(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ArtifactStore {

    companion object {
        private const val TAG = "JsonArtifactStore"
        private const val STORE_DIR = ".tina/artifacts"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    override suspend fun find(id: ArtifactId, buildDir: File): Artifact? = withContext(ioDispatcher) {
        val file = fileFor(id, buildDir).takeIf { it.isFile } ?: return@withContext null
        runCatching { json.decodeFromString<Artifact>(file.readText()) }
            .onFailure { Timber.tag(TAG).w(it, "decode failed, treating as missing: %s", file.absolutePath) }
            .getOrNull()
    }

    override suspend fun register(artifact: Artifact, buildDir: File) = withContext(ioDispatcher) {
        val file = fileFor(artifact.id, buildDir)
        file.parentFile?.mkdirs()
        runCatching { file.writeText(json.encodeToString(Artifact.serializer(), artifact)) }
            .onFailure { Timber.tag(TAG).w(it, "register failed: %s", file.absolutePath) }
        Unit
    }

    override suspend fun invalidate(id: ArtifactId, buildDir: File) = withContext(ioDispatcher) {
        fileFor(id, buildDir).takeIf { it.isFile }?.delete()
        Unit
    }

    override suspend fun listAll(buildDir: File): List<Artifact> = withContext(ioDispatcher) {
        val dir = storeDir(buildDir).takeIf { it.isDirectory } ?: return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<Artifact>(f.readText()) }.getOrNull()
            }.orEmpty()
    }

    override suspend fun clearAll(buildDir: File) = withContext(ioDispatcher) {
        storeDir(buildDir).takeIf { it.isDirectory }?.deleteRecursively()
        Unit
    }

    private fun storeDir(buildDir: File): File = File(buildDir, STORE_DIR)

    private fun fileFor(id: ArtifactId, buildDir: File): File = File(storeDir(buildDir), "${id.storageKey()}.json")
}
