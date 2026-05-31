package com.wuxianggujun.tinaide.tutorial.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.TutorialProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import kotlinx.serialization.encodeToString

/**
 * Context 扩展属性，创建教程 DataStore 实例
 */
private val Context.tutorialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tutorial_preferences"
)

/**
 * 教程进度持久化存储
 *
 * 使用 DataStore 存储用户的教程学习进度
 */
class TutorialProgressStore(private val context: Context) {

    private val dataStore = context.tutorialDataStore
    private val json = JsonSerializer.default

    /**
     * 观察所有教程进度
     */
    val allProgressFlow: Flow<Map<String, TutorialProgress>> = dataStore.data.map { preferences ->
        val json = preferences[KEY_ALL_PROGRESS] ?: return@map emptyMap()
        parseProgressMap(json)
    }

    /**
     * 获取特定教程的进度
     */
    suspend fun getProgress(tutorialId: String): TutorialProgress? {
        return allProgressFlow.first()[tutorialId]
    }

    /**
     * 获取所有教程进度
     */
    suspend fun getAllProgress(): Map<String, TutorialProgress> {
        return allProgressFlow.first()
    }

    /**
     * 保存教程进度
     */
    suspend fun saveProgress(progress: TutorialProgress) {
        dataStore.edit { preferences ->
            val currentMap = parseProgressMap(preferences[KEY_ALL_PROGRESS]).toMutableMap()
            currentMap[progress.tutorialId] = progress.copy(
                lastAccessedAt = System.currentTimeMillis()
            )
            preferences[KEY_ALL_PROGRESS] = json.encodeToString(currentMap)
        }
    }

    /**
     * 开始教程
     */
    suspend fun startTutorial(tutorialId: String) {
        val existing = getProgress(tutorialId)
        val progress = existing?.copy(
            status = ProgressStatus.IN_PROGRESS,
            lastAccessedAt = System.currentTimeMillis()
        ) ?: TutorialProgress(
            tutorialId = tutorialId,
            status = ProgressStatus.IN_PROGRESS,
            currentStepIndex = 0,
            lastAccessedAt = System.currentTimeMillis()
        )
        saveProgress(progress)
    }

    /**
     * 更新教程步骤进度
     */
    suspend fun updateStepProgress(tutorialId: String, stepIndex: Int) {
        val existing = getProgress(tutorialId) ?: TutorialProgress(
            tutorialId = tutorialId,
            status = ProgressStatus.IN_PROGRESS,
            currentStepIndex = 0
        )
        saveProgress(
            existing.copy(
                currentStepIndex = stepIndex,
                status = ProgressStatus.IN_PROGRESS,
                lastAccessedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 完成教程
     */
    suspend fun completeTutorial(tutorialId: String) {
        val existing = getProgress(tutorialId) ?: TutorialProgress(
            tutorialId = tutorialId,
            status = ProgressStatus.NOT_STARTED,
            currentStepIndex = 0
        )
        saveProgress(
            existing.copy(
                status = ProgressStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 重置教程进度
     */
    suspend fun resetProgress(tutorialId: String) {
        dataStore.edit { preferences ->
            val currentMap = parseProgressMap(preferences[KEY_ALL_PROGRESS]).toMutableMap()
            currentMap.remove(tutorialId)
            preferences[KEY_ALL_PROGRESS] = json.encodeToString(currentMap)
        }
    }

    /**
     * 清除所有进度
     */
    suspend fun clearAllProgress() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_ALL_PROGRESS)
        }
    }

    /**
     * 观察是否已完成新手引导
     */
    val hasCompletedOnboardingFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_ONBOARDING_COMPLETED] == true
    }

    /**
     * 检查是否已完成新手引导
     */
    suspend fun hasCompletedOnboarding(): Boolean {
        return dataStore.data.first()[KEY_ONBOARDING_COMPLETED] == true
    }

    /**
     * 设置新手引导完成状态
     */
    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    /**
     * 观察是否已跳过新手引导
     */
    val hasSkippedOnboardingFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_ONBOARDING_SKIPPED] == true
    }

    /**
     * 设置跳过新手引导
     */
    suspend fun setOnboardingSkipped(skipped: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_SKIPPED] = skipped
        }
    }

    /**
     * 检查是否应该显示新手引导
     * 条件：未完成且未跳过
     */
    suspend fun shouldShowOnboarding(): Boolean {
        val preferences = dataStore.data.first()
        val completed = preferences[KEY_ONBOARDING_COMPLETED] == true
        val skipped = preferences[KEY_ONBOARDING_SKIPPED] == true
        return !completed && !skipped
    }

    /**
     * 获取已完成的教程数量
     */
    suspend fun getCompletedCount(): Int {
        return getAllProgress().values.count { it.status == ProgressStatus.COMPLETED }
    }

    /**
     * 获取进行中的教程数量
     */
    suspend fun getInProgressCount(): Int {
        return getAllProgress().values.count { it.status == ProgressStatus.IN_PROGRESS }
    }

    /**
     * 解析进度 Map JSON
     */
    private fun parseProgressMap(jsonString: String?): Map<String, TutorialProgress> {
        if (jsonString.isNullOrEmpty()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, TutorialProgress>>(jsonString)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        private val KEY_ALL_PROGRESS = stringPreferencesKey("all_tutorial_progress")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_ONBOARDING_SKIPPED = booleanPreferencesKey("onboarding_skipped")
    }
}
