package ru.spgsroot.peachy.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.spgsroot.peachy.data.update.UpdateDownloadState
import ru.spgsroot.peachy.data.update.UpdateError

class UpdatePresentationTest {
    private val oldDownloadFailure = UpdateDownloadState.Failed(UpdateError.INTEGRITY)

    @Test fun latestNetworkCheckIsNotHiddenByAnOldDownloadFailure() {
        assertEquals(UpdateError.NETWORK, visibleUpdateError(null, oldDownloadFailure, UpdateCheckState.Failed(UpdateError.NETWORK)))
    }

    @Test fun latestRateLimitIsNotHiddenByAnOldDownloadFailure() {
        assertEquals(UpdateError.RATE_LIMIT, visibleUpdateError(null, oldDownloadFailure, UpdateCheckState.Failed(UpdateError.RATE_LIMIT)))
    }

    @Test fun successfulOrRunningRecheckDoesNotRepeatObsoleteDownloadFailure() {
        assertNull(visibleUpdateError(null, oldDownloadFailure, UpdateCheckState.Current))
        assertNull(visibleUpdateError(null, oldDownloadFailure, UpdateCheckState.Checking))
    }

    @Test fun restoredDownloadFailureRemainsVisibleBeforeAnyNewCheck() {
        assertEquals(UpdateError.INTEGRITY, visibleUpdateError(null, oldDownloadFailure, UpdateCheckState.Idle))
    }

    @Test fun explicitInstallationFailureRemainsVisibleAfterASuccessfulCheck() {
        assertEquals(UpdateError.INSTALL_UNAVAILABLE, visibleUpdateError(UpdateError.INSTALL_UNAVAILABLE, oldDownloadFailure, UpdateCheckState.Current))
    }
}
