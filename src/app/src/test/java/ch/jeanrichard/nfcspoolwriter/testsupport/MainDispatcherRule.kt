package ch.jeanrichard.nfcspoolwriter.testsupport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Replaces `Dispatchers.Main` with a test dispatcher, which `viewModelScope` needs in a JVM test.
 *
 * Unconfined so that work launched in a ViewModel's `init` has already run by the time the test
 * inspects `state.value` — these ViewModels load on construction, and asserting on the state a user
 * would actually see is the point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: kotlinx.coroutines.test.TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
