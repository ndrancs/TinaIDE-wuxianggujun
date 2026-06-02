package com.wuxianggujun.tinaide.ui.git

import com.wuxianggujun.tinaide.core.git.GitBranch
import com.wuxianggujun.tinaide.core.git.GitCommit
import com.wuxianggujun.tinaide.core.git.GitRemote
import com.wuxianggujun.tinaide.core.git.GitResult
import com.wuxianggujun.tinaide.core.git.GitService
import com.wuxianggujun.tinaide.core.git.GitStatus
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

internal const val GIT_TEST_PROJECT_PATH = "C:/workspace/tina-git-project"

internal fun gitCommit(
    hash: String,
    shortHash: String = hash.take(7),
    author: String = "Tina Developer",
    authorEmail: String = "dev@example.com",
    date: String = "2026-06-02 10:30:00",
    message: String,
    fullMessage: String = message,
): GitCommit = GitCommit(
    hash = hash,
    shortHash = shortHash,
    author = author,
    authorEmail = authorEmail,
    date = date,
    message = message,
    fullMessage = fullMessage,
)

internal fun stubGitRepositoryLoad(
    gitService: GitService,
    commits: List<GitCommit>,
    status: GitStatus = GitStatus(isRepository = true, branch = "main"),
    branches: List<GitBranch> = listOf(GitBranch(name = "main", isCurrent = true)),
    remotes: List<GitRemote> = emptyList(),
) {
    coEvery { gitService.getStatus(GIT_TEST_PROJECT_PATH) } returns status
    coEvery { gitService.getCommitHistory(GIT_TEST_PROJECT_PATH, any()) } returns GitResult.Success(commits)
    coEvery { gitService.getBranches(GIT_TEST_PROJECT_PATH) } returns GitResult.Success(branches)
    coEvery { gitService.getRemotes(GIT_TEST_PROJECT_PATH) } returns GitResult.Success(remotes)
}

@OptIn(ExperimentalCoroutinesApi::class)
class GitMainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
