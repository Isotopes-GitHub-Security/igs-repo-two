package devai.modules.autoreview.service

import devai.modules.autoreview.config.redis.AutoreviewConfigWhitelistUrls
import devai.modules.autoreview.service.DefaultAutoreviewConfigService.Companion.AUTOREVIEW_CONFIG_CONTAINER
import devai.modules.autoreview.service.DefaultAutoreviewConfigService.Companion.AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY
import devai.modules.autoreview.service.DefaultAutoreviewConfigService.Companion.AUTOREVIEW_CONFIG_ENABLED_WORKSPACE_ARIS_KEY
import devai.modules.pantry.shared.client.PantryManager
import devai.modules.pantry.shared.entity.PantryEntry
import devai.modules.shared.config.JacksonConfig
import devai.modules.shared.utils.enabledRepositoryUrls
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime

@ExtendWith(MockKExtension::class)
class DefaultAutoreviewRedisConfigServiceTest {
    private val pantryManager = mockk<PantryManager>(relaxed = true)
    private val objectMapper = JacksonConfig().objectMapper()
    private lateinit var autoreviewConfigService: AutoreviewConfigService

    @BeforeEach
    fun setup() {
        autoreviewConfigService = DefaultAutoreviewConfigService(pantryManager = pantryManager, objectMapper = objectMapper)
    }

    @Test
    fun `should retrieve AR onboarded repos successfully`() {
        runTest {
            val testUrls = setOf("https://url1.com", "https://url2.com")
            val mockWhitelistUrls =
                AutoreviewConfigWhitelistUrls(
                    urls = testUrls,
                    lastUpdatedTime = OffsetDateTime.now(),
                )
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns
                PantryEntry(
                    container = AUTOREVIEW_CONFIG_CONTAINER,
                    key = AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY,
                    value = mockWhitelistUrls,
                )

            val repoUrls = autoreviewConfigService.getOnboardedRepoUrls()

            repoUrls?.urls shouldBe testUrls
            repoUrls?.lastUpdatedTime shouldNotBe null
        }
    }

    @Test
    fun `should fallback to default onboardedRepoUrls when Pantry throws error`() {
        runTest {
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } throws Exception()

            val repoUrls = autoreviewConfigService.getOnboardedRepoUrls()

            repoUrls?.urls shouldBe enabledRepositoryUrls
        }
    }

    @Test
    fun `should fallback to default onboardedRepoUrls value when Pantry returns null value`() {
        runTest {
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns null

            val repoUrls = autoreviewConfigService.getOnboardedRepoUrls()

            repoUrls?.urls shouldBe enabledRepositoryUrls
        }
    }

    @Test
    fun `addOnboardedRepoUrls should returns success appended repos`() {
        runTest {
            val existingUrls = setOf("https://url1.com")
            val existingPantryEntryValue =
                AutoreviewConfigWhitelistUrls(
                    urls = existingUrls,
                    lastUpdatedTime = OffsetDateTime.now(),
                )
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns
                PantryEntry(
                    container = AUTOREVIEW_CONFIG_CONTAINER,
                    key = AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY,
                    value = existingPantryEntryValue,
                )

            val result = autoreviewConfigService.addOnboardedRepoUrls(setOf("https://url2.com"))

            result.urls shouldBe setOf("https://url2.com")
        }
    }

    @Test
    fun `addOnboardedRepoUrls should return empty if adding the same repos`() {
        runTest {
            val existingUrls = setOf("https://url1.com")
            val existingPantryEntryValue =
                AutoreviewConfigWhitelistUrls(
                    urls = existingUrls,
                    lastUpdatedTime = OffsetDateTime.now(),
                )
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns
                PantryEntry(
                    container = AUTOREVIEW_CONFIG_CONTAINER,
                    key = AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY,
                    value = existingPantryEntryValue,
                )

            val result = autoreviewConfigService.addOnboardedRepoUrls(setOf("https://url1.com"))

            result.urls shouldBe emptySet()
        }
    }

    @Test
    fun `addOnboardedRepoUrls should return success added new repos`() {
        runTest {
            val testUrls = setOf("https://url1.com", "https://url2.com")
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns null

            val result = autoreviewConfigService.addOnboardedRepoUrls(testUrls)

            result.urls shouldBe testUrls
        }
    }

    @Test
    fun `deleteOnboardedRepoUrls should return successful removed repos`() {
        runTest {
            val existingUrls = setOf("https://url1.com", "https://url2.com")
            val existingPantryEntryValue =
                AutoreviewConfigWhitelistUrls(
                    urls = existingUrls,
                    lastUpdatedTime = OffsetDateTime.now(),
                )
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns
                PantryEntry(
                    container = AUTOREVIEW_CONFIG_CONTAINER,
                    key = AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY,
                    value = existingPantryEntryValue,
                )

            val result = autoreviewConfigService.deleteOnboardedRepoUrls(setOf("https://url2.com"))

            result.urls shouldBe setOf("https://url2.com")
        }
    }

    @Test
    fun `deleteOnboardedRepoUrls should return empty when reposUrl not existed within onboarded repos`() {
        runTest {
            val existingUrls = setOf("https://url1.com", "https://url2.com")
            val existingPantryEntryValue =
                AutoreviewConfigWhitelistUrls(
                    urls = existingUrls,
                    lastUpdatedTime = OffsetDateTime.now(),
                )
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns
                PantryEntry(
                    container = AUTOREVIEW_CONFIG_CONTAINER,
                    key = AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY,
                    value = existingPantryEntryValue,
                )

            val result = autoreviewConfigService.deleteOnboardedRepoUrls(setOf("https://url3.com"))

            result.urls shouldBe emptySet()
        }
    }

    @Test
    fun `deleteOnboardedRepoUrls should return empty when no AR config onboarded repos`() {
        runTest {
            coEvery {
                pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY)
            } returns null

            val result = autoreviewConfigService.deleteOnboardedRepoUrls(setOf("https://url3.com"))

            result.urls shouldBe emptySet()
        }
    }

    @Nested
    inner class GraphWorkspaceAriConfigTest {
        @Test
        fun `addOnboardedGraphWorkspaceAris should returns success appended urls`() {
            runTest {
                val existingUrls = setOf("https://url1.com")
                val existingPantryEntryValue =
                    AutoreviewConfigWhitelistUrls(
                        urls = existingUrls,
                        lastUpdatedTime = OffsetDateTime.now(),
                    )
                coEvery {
                    pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_WORKSPACE_ARIS_KEY)
                } returns
                    PantryEntry(
                        container = AUTOREVIEW_CONFIG_CONTAINER,
                        key = AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY,
                        value = existingPantryEntryValue,
                    )

                val result = autoreviewConfigService.addOnboardedGraphWorkspaceAris(setOf("https://url2.com"))

                result.urls shouldBe setOf("https://url2.com")
            }
        }

        @Test
        fun `deleteOnboardedRepoUrls should return successful removed urls`() {
            runTest {
                val existingUrls = setOf("https://url1.com", "https://url2.com")
                val existingPantryEntryValue =
                    AutoreviewConfigWhitelistUrls(
                        urls = existingUrls,
                        lastUpdatedTime = OffsetDateTime.now(),
                    )
                coEvery {
                    pantryManager.getEntry(AUTOREVIEW_CONFIG_CONTAINER, AUTOREVIEW_CONFIG_ENABLED_WORKSPACE_ARIS_KEY)
                } returns
                    PantryEntry(
                        container = AUTOREVIEW_CONFIG_CONTAINER,
                        key = AUTOREVIEW_CONFIG_ENABLED_REPOSITORIES_KEY,
                        value = existingPantryEntryValue,
                    )

                val result = autoreviewConfigService.deleteOnboardedGraphWorkspaceAris(setOf("https://url2.com"))

                result.urls shouldBe setOf("https://url2.com")
            }
        }
    }
}
