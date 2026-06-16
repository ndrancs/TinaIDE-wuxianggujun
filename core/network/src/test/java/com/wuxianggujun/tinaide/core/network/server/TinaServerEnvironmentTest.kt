package com.wuxianggujun.tinaide.core.network.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TinaServerEnvironmentTest {
    @Test
    fun initialize_shouldTrimSecretAndStoreSignatureRequirement() {
        TinaServerEnvironment.initialize(
            serverConfigHmacSecret = "  secret  ",
            serverConfigSignatureRequired = true
        )

        assertThat(TinaServerEnvironment.serverConfigHmacSecret).isEqualTo("secret")
        assertThat(TinaServerEnvironment.serverConfigSignatureRequired).isTrue()

        TinaServerEnvironment.initialize(serverConfigHmacSecret = "")
        assertThat(TinaServerEnvironment.serverConfigHmacSecret).isEmpty()
        assertThat(TinaServerEnvironment.serverConfigSignatureRequired).isFalse()
    }
}
