package dev.getpostingboard.reader

import dev.getpostingboard.reader.data.*
import dev.getpostingboard.reader.state.ReaderStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RegistrationStoreTest {
    @Test fun registrationSavesTheIssuedKeyAndOpensTheNamedBoard() = runTest {
        val credentials = MemoryCredentials()
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest): RegisteredAccount {
                assertEquals("reader-test", request.name)
                assertEquals("My reader", request.description)
                return super.register(request)
            }
        }.apply { feedHandler = { query, _ ->
            if (query.board == Board.NAMED) assertEquals("gpb_test_key", credentials.key)
            PostPage()
        } }
        val store = ReaderStore(ReaderRepository(service, NoCache), credentials, backgroundScope)
        runCurrent(); store.register(" reader-test ", " My reader "); runCurrent()
        assertTrue(store.state.value.connected)
        assertFalse(store.state.value.connecting)
        assertFalse(store.state.value.keyNeedsSaving)
        assertEquals("reader-test", store.state.value.registeredName)
        assertEquals(Board.NAMED, store.state.value.query.board)
        assertEquals("gpb_test_key", store.availableApiKey())
        assertFalse(store.state.value.toString().contains("gpb_test_key"))
        store.disconnect(); runCurrent()
        assertNull(store.availableApiKey())
        assertNull(store.state.value.registeredName)
    }

    @Test fun doubleSubmitCreatesOnlyOneAccount() = runTest {
        var calls = 0
        val receipt = CompletableDeferred<RegisteredAccount>()
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest): RegisteredAccount { calls++; return receipt.await() }
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), backgroundScope)
        store.register("reader", ""); runCurrent()
        store.register("another", ""); store.connect("existing-key"); runCurrent()
        assertEquals(1, calls)
        receipt.complete(RegisteredAccount(ROOT_ID, "reader", "gpb_new")); runCurrent()
        assertEquals("gpb_new", store.availableApiKey())
    }

    @Test fun storageFailureRetainsTheKeyAndRetryDoesNotRegisterAgain() = runTest {
        var canSave = false
        var calls = 0
        var saved: String? = null
        val credentials = object : CredentialStore {
            override fun read() = saved
            override fun write(key: String?) { check(canSave); saved = key }
        }
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest): RegisteredAccount { calls++; return super.register(request) }
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), credentials, backgroundScope)
        store.register("reader", ""); runCurrent()
        assertFalse(store.state.value.connected)
        assertTrue(store.state.value.keyNeedsSaving)
        assertEquals("gpb_test_key", store.availableApiKey())
        assertNotNull(store.state.value.connectionFailure)
        store.register("another", ""); store.connect("other-key"); runCurrent()
        canSave = true
        store.saveRegisteredKey(); runCurrent()
        assertEquals(1, calls)
        assertEquals("gpb_test_key", saved)
        assertTrue(store.state.value.connected)
        assertFalse(store.state.value.keyNeedsSaving)
        assertNull(store.state.value.connectionFailure)
    }

    @Test fun rejectedRegistrationKeepsTheFormAvailableAndDoesNotSaveAKey() = runTest {
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest): RegisteredAccount =
                throw BoardException(ReaderFailure("Name taken", 190))
        }
        val credentials = MemoryCredentials()
        val store = ReaderStore(ReaderRepository(service, NoCache), credentials, backgroundScope)
        store.register("reader", ""); runCurrent()
        assertNull(credentials.key)
        assertFalse(store.state.value.connected)
        assertFalse(store.state.value.connecting)
        assertFalse(store.state.value.registrationUncertain)
        assertEquals(190L, store.state.value.connectionFailure?.retryAt)
    }

    @Test fun uncertainRegistrationCannotBeRepeatedButAnExistingKeyCanConnect() = runTest {
        var calls = 0
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest): RegisteredAccount {
                calls++; throw RegistrationOutcomeUnknownException()
            }
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), backgroundScope)
        store.register("reader", ""); runCurrent()
        assertTrue(store.state.value.registrationUncertain)
        store.register("another", ""); runCurrent()
        assertEquals(1, calls)
        store.connect("existing-key"); runCurrent()
        assertTrue(store.state.value.connected)
    }

    @Test fun invalidDetailsAndConnectedAccountsCannotRegister() = runTest {
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest): RegisteredAccount = error("Must not register")
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), backgroundScope)
        for ((name, description) in listOf("UPPER" to "", "ok" to "", "reader" to "x".repeat(241))) {
            store.register(name, description); runCurrent()
            assertNotNull(store.state.value.connectionFailure)
        }
        store.connect("existing-key"); runCurrent()
        store.register("reader", ""); runCurrent()
        assertEquals("existing-key", store.availableApiKey())
    }

    @Test fun lateRegistrationCannotReconnectAfterDisconnect() = runTest {
        val receipt = CompletableDeferred<RegisteredAccount>()
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest) = withContext(NonCancellable) { receipt.await() }
        }
        val credentials = MemoryCredentials()
        val store = ReaderStore(ReaderRepository(service, NoCache), credentials, backgroundScope)
        store.register("reader", ""); runCurrent()
        store.disconnect(); runCurrent()
        receipt.complete(RegisteredAccount(ROOT_ID, "reader", "gpb_late")); runCurrent()
        assertNull(credentials.key)
        assertFalse(store.state.value.connected)
        assertNull(store.state.value.registeredName)
    }
}
