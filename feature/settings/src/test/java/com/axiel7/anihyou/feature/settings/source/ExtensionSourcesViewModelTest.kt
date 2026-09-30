package com.axiel7.anihyou.feature.settings.source

import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionSourcesViewModelTest {
    private val repository = FakeExtensionSourceRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addForwardsTrimmedHttpsUrlAndClearsItAfterSuccess() = runTest {
        val viewModel = ExtensionSourcesViewModel(repository)
        viewModel.onUrlChanged("  https://example.test/repository.json  ")

        viewModel.addSource()

        assertEquals(listOf("https://example.test/repository.json"), repository.addedUrls)
        assertEquals("", viewModel.uiState.value.url)
        assertEquals(AddExtensionSourceResult.Added("source-id"), viewModel.uiState.value.addResult)
        assertFalse(viewModel.uiState.value.isAdding)
    }

    private class FakeExtensionSourceRepository : ExtensionSourceRepository {
        override val sources = MutableStateFlow<List<ExtensionSource>>(emptyList())
        val addedUrls = mutableListOf<String>()

        override suspend fun add(url: String): AddExtensionSourceResult {
            addedUrls += url
            return AddExtensionSourceResult.Added("source-id")
        }

        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun refresh(sourceId: String) = Unit
        override suspend fun refreshEnabled(): Boolean = false
        override suspend fun activate(sourceId: String, extensionId: String) = Unit
    }
}
