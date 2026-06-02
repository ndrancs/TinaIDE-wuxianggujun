package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.git.GitService
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.ui.git.GIT_TEST_PROJECT_PATH
import com.wuxianggujun.tinaide.ui.git.GitMainDispatcherRule
import com.wuxianggujun.tinaide.ui.git.gitCommit
import com.wuxianggujun.tinaide.ui.git.stubGitRepositoryLoad
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
@OptIn(ExperimentalCoroutinesApi::class)
class GitViewModelCommitHistoryTest {

    @get:Rule
    val mainDispatcherRule = GitMainDispatcherRule()

    @Before
    fun setUp() {
        AppStrings.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `setProjectPath loads commit history and derives recent commit messages`() = runTest {
        val commits = listOf(
            gitCommit(
                hash = "1111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                author = "Alice",
                message = "Wire commit history",
                fullMessage = "Wire commit history\n\nExpose commits in the bottom panel",
            ),
            gitCommit(
                hash = "2222222bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                author = "Bob",
                message = "Refresh Git drawer",
                fullMessage = "",
            ),
            gitCommit(
                hash = "3333333ccccccccccccccccccccccccccccccccc",
                author = "Carol",
                message = "Wire commit history",
                fullMessage = "Wire commit history\n\nExpose commits in the bottom panel",
            ),
        )
        val gitService = mockk<GitService>(relaxed = true)
        stubGitRepositoryLoad(gitService, commits)
        val viewModel = GitViewModel(gitService)

        viewModel.setProjectPath(GIT_TEST_PROJECT_PATH)

        val loadedCommits = withTimeout(5.seconds) {
            viewModel.commitHistory.first { it == commits }
        }
        val recentMessages = withTimeout(5.seconds) {
            viewModel.recentCommitMessages.first { it.size == 2 }
        }

        assertThat(loadedCommits).containsExactlyElementsIn(commits).inOrder()
        assertThat(recentMessages).containsExactly(
            "Wire commit history\n\nExpose commits in the bottom panel",
            "Refresh Git drawer",
        ).inOrder()
        coVerify(exactly = 1) { gitService.getCommitHistory(GIT_TEST_PROJECT_PATH, 50) }
    }
}
