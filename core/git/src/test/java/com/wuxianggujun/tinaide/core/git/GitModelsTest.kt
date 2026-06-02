package com.wuxianggujun.tinaide.core.git

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitModelsTest {

    @Test
    fun notARepository_shouldRepresentCleanNonRepositoryState() {
        val status = GitStatus.NOT_A_REPOSITORY

        assertThat(status.isRepository).isFalse()
        assertThat(status.branch).isNull()
        assertThat(status.hasChanges).isFalse()
        assertThat(status.staged).isEmpty()
        assertThat(status.untracked).isEmpty()
    }

    @Test
    fun fileStatusSymbols_shouldRemainStableForUiBadges() {
        assertThat(FileStatus.MODIFIED.symbol).isEqualTo("M")
        assertThat(FileStatus.ADDED.symbol).isEqualTo("A")
        assertThat(FileStatus.DELETED.symbol).isEqualTo("D")
        assertThat(FileStatus.RENAMED.symbol).isEqualTo("R")
        assertThat(FileStatus.COPIED.symbol).isEqualTo("C")
        assertThat(FileStatus.UNTRACKED.symbol).isEqualTo("U")
        assertThat(FileStatus.IGNORED.symbol).isEqualTo("!")
    }

    @Test
    fun gitStatus_shouldPreserveChangeBucketsAndExplicitChangeFlag() {
        val staged = GitFileStatus(path = "src/Main.kt", status = FileStatus.ADDED)
        val unstaged = GitFileStatus(path = "README.md", status = FileStatus.MODIFIED)
        val status = GitStatus(
            isRepository = true,
            branch = "dev",
            staged = listOf(staged),
            unstaged = listOf(unstaged),
            untracked = listOf("new-file.txt"),
            hasChanges = true
        )

        assertThat(status.isRepository).isTrue()
        assertThat(status.branch).isEqualTo("dev")
        assertThat(status.staged).containsExactly(staged)
        assertThat(status.unstaged).containsExactly(unstaged)
        assertThat(status.untracked).containsExactly("new-file.txt")
        assertThat(status.hasChanges).isTrue()
    }

    @Test
    fun gitFileStatus_shouldPreserveRenameSourcePath() {
        val renamed = GitFileStatus(
            path = "src/NewName.kt",
            status = FileStatus.RENAMED,
            oldPath = "src/OldName.kt"
        )

        assertThat(renamed.path).isEqualTo("src/NewName.kt")
        assertThat(renamed.status).isEqualTo(FileStatus.RENAMED)
        assertThat(renamed.oldPath).isEqualTo("src/OldName.kt")
    }

    @Test
    fun gitDiff_shouldPreserveLineNumbersAndLineTypes() {
        val diff = GitDiff(
            filePath = "main.cpp",
            hunks = listOf(
                DiffHunk(
                    oldStart = 1,
                    oldCount = 1,
                    newStart = 1,
                    newCount = 2,
                    lines = listOf(
                        DiffLine(DiffLineType.CONTEXT, "int main() {", 1, 1),
                        DiffLine(DiffLineType.ADDED, "  return 0;", null, 2)
                    )
                )
            )
        )

        assertThat(diff.filePath).isEqualTo("main.cpp")
        assertThat(diff.hunks.single().lines.map { it.type })
            .containsExactly(DiffLineType.CONTEXT, DiffLineType.ADDED)
            .inOrder()
        assertThat(diff.hunks.single().lines.last().oldLineNumber).isNull()
        assertThat(diff.hunks.single().lines.last().newLineNumber).isEqualTo(2)
    }

    @Test
    fun gitCommit_shouldPreserveShortAndFullMessagesSeparately() {
        val commit = GitCommit(
            hash = "abcdef1234567890",
            shortHash = "abcdef1",
            author = "Tina",
            authorEmail = "tina@example.com",
            date = "2026-06-03",
            message = "Fix workspace state",
            fullMessage = "Fix workspace state\n\nKeep package progress stable."
        )

        assertThat(commit.hash).isEqualTo("abcdef1234567890")
        assertThat(commit.shortHash).isEqualTo("abcdef1")
        assertThat(commit.message).isEqualTo("Fix workspace state")
        assertThat(commit.fullMessage).contains("Keep package progress stable.")
    }

    @Test
    fun gitBranch_shouldDistinguishLocalCurrentAndRemoteBranches() {
        val current = GitBranch(name = "dev", isCurrent = true)
        val remote = GitBranch(name = "origin/dev", isCurrent = false, isRemote = true)

        assertThat(current.isCurrent).isTrue()
        assertThat(current.isRemote).isFalse()
        assertThat(remote.isCurrent).isFalse()
        assertThat(remote.isRemote).isTrue()
    }
}
