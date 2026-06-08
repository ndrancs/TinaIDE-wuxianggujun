package com.wuxianggujun.tinaide.core.git

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitOperationModelsTest {

    @Test
    fun gitResult_shouldRepresentSuccessAndErrorPayloads() {
        val success = GitResult.Success(GitBranch(name = "main", isCurrent = true))
        val error = GitResult.Error("not a repository")

        assertThat(success.data.name).isEqualTo("main")
        assertThat(success.data.isCurrent).isTrue()
        assertThat(error.message).isEqualTo("not a repository")
    }

    @Test
    fun gitProgress_shouldCarryTransferCounters() {
        val progress = listOf(
            GitProgress.Counting(current = 3, total = 10),
            GitProgress.Compressing(current = 4, total = 10),
            GitProgress.Receiving(current = 5, total = 10),
            GitProgress.Resolving(current = 6, total = 10)
        )

        assertThat(progress.map { it.currentCount() }).containsExactly(3, 4, 5, 6).inOrder()
        assertThat(progress.map { it.totalCount() }).containsExactly(10, 10, 10, 10).inOrder()
    }

    @Test
    fun gitCredential_shouldPreserveProtocolHostAndSecretSeparately() {
        val credential = GitCredential(
            protocol = "https",
            host = "github.com",
            username = "octocat",
            password = "token"
        )

        assertThat(credential.protocol).isEqualTo("https")
        assertThat(credential.host).isEqualTo("github.com")
        assertThat(credential.username).isEqualTo("octocat")
        assertThat(credential.password).isEqualTo("token")
    }

    private fun GitProgress.currentCount(): Int? = when (this) {
        is GitProgress.Counting -> current
        is GitProgress.Compressing -> current
        is GitProgress.Receiving -> current
        is GitProgress.Resolving -> current
        GitProgress.Indeterminate -> null
    }

    private fun GitProgress.totalCount(): Int? = when (this) {
        is GitProgress.Counting -> total
        is GitProgress.Compressing -> total
        is GitProgress.Receiving -> total
        is GitProgress.Resolving -> total
        GitProgress.Indeterminate -> null
    }
}
