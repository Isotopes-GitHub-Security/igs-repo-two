package devai.modules.autoreview.service

import com.atlassian.ari.principled.ARI
import com.atlassian.ari.principled.CloudIdLike
import com.atlassian.ari.principled.bitbucket.BitbucketRepositoryARI
import com.atlassian.ari.principled.bitbucket.BitbucketWorkspaceARI
import com.atlassian.usercontext.api.AccountType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import devai.modules.settings.model.AutoreviewDevAIWorkSpaceSettingAttributes
import devai.modules.settings.model.AutoreviewRepositorySettingAttributes
import devai.modules.settings.model.AutoreviewWorkspaceSettingAttributes
import devai.modules.settings.model.SettingKey
import devai.modules.settings.model.SettingUpdateContext
import devai.modules.settings.model.SettingValue
import devai.modules.settings.model.SettingValueUpdatedEvent
import devai.modules.shared.analytics.service.AutoreviewAnalyticsService
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.UserContext
import devai.modules.shared.model.settings.SettingContainerType
import devai.modules.shared.sal.SalSharedUtil
import devai.modules.tenant.model.TransactionContext
import devai.modules.tenant.model.WorkspaceContext
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.OffsetDateTime
import java.util.stream.Stream

private const val TEST_TRANSACTION_CONTEXT_CLOUD_ID = "922168f0-256f-49e0-ac04-4db48b68d2ea"
private const val TEST_BILLING_CLOUD_ID = "something"
private const val TEST_ACCOUNT_ID = "557058:5f3f4e1e-7f8e-4a2d-8c6b-1c3e5f6a7b8c"

private const val TEST_BITBUCKET_WORKSPACE_CONTAINER_ID = "ari:cloud:bitbucket::workspace/4cff3fd9-28ef-40f9-8ae4-376d15c05228"
private const val TEST_BITBUCKET_REPOSITORY_CONTAINER_ID = "ari:cloud:bitbucket::repository/4000784a-6cf8-40d6-8fb0-b75c2aabcc63"

private const val TEST_GITHUB_WORKSPACE_NAME = "exampleorg"
private const val TEST_GITHUB_REPOSITORY_NAME = "examplerepo"
private const val TEST_GITHUB_WORKSPACE_CONTAINER_ID = "https://github.com/exampleorg"
private const val TEST_GITHUB_REPOSITORY_CONTAINER_ID = "https://github.com/exampleorg/examplerepo"

class AutoreviewAiInstallEventServiceTest {
    private val analyticsService = mockk<AutoreviewAnalyticsService>(relaxed = true)
    private val featureService = mockk<DevAiCoreFeatureService>()
    private val objectMapper = jacksonObjectMapper()

    private lateinit var subject: AutoreviewAiInstallEventService

    @BeforeEach
    fun setUp() {
        subject =
            AutoreviewAiInstallEventService(
                analyticsService,
                featureService,
                objectMapper,
                SalSharedUtil(meterRegistry = mockk<MeterRegistry>(relaxed = true)),
            )
    }

    @Nested
    inner class BitBucketWorkspace {
        @ParameterizedTest
        @MethodSource("devai.modules.autoreview.service.AutoreviewAiInstallEventServiceTest#cloudIdCombinations")
        fun `should send analytics event when bitbucket workspace becomes autoreview_activated and feature gate is enabled`(
            transactionContextCloudId: String?,
            billingCloudId: String?,
            expectedEventCloudId: String?,
        ) = runTest {
            coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

            val event =
                createBitbucketWorkspaceActivationEvent(
                    wasActivated = false,
                    isActivated = true,
                    transactionContextCloudId = transactionContextCloudId,
                    billingCloudId = billingCloudId,
                )

            subject.processSettingValueUpdated(event)

            verify(exactly = 1) {
                analyticsService.sendAutoreviewBitbucketAiInstallEvent(
                    action = "initiated",
                    accountId = TEST_ACCOUNT_ID,
                    orgId = null,
                    cloudId = expectedEventCloudId,
                    bitbucketWorkspaceARI = BitbucketWorkspaceARI.from("4cff3fd9-28ef-40f9-8ae4-376d15c05228"),
                )
            }
        }

        @Test
        fun `should handle null transaction context and send event when feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createBitbucketWorkspaceActivationEvent(
                        wasActivated = false,
                        isActivated = true,
                    )

                subject.processSettingValueUpdated(
                    event.copy(
                        settingUpdateContext =
                            event.settingUpdateContext.copy(
                                transactionContext = null,
                            ),
                    ),
                )

                verify(exactly = 1) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(
                        action = "initiated",
                        accountId = null,
                        orgId = null,
                        cloudId = TEST_BILLING_CLOUD_ID,
                        bitbucketWorkspaceARI = BitbucketWorkspaceARI.from("4cff3fd9-28ef-40f9-8ae4-376d15c05228"),
                    )
                }
            }

        @Test
        fun `should not send analytics event when feature gate is disabled - bitbucket workspace`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns false

                val event =
                    createBitbucketWorkspaceActivationEvent(
                        wasActivated = false,
                        isActivated = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should not send analytics event when workspace was already activated - bitbucket`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createBitbucketWorkspaceActivationEvent(
                        wasActivated = true,
                        isActivated = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should not send analytics event when workspace becomes deactivated - bitbucket workspace`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createBitbucketWorkspaceActivationEvent(
                        wasActivated = true,
                        isActivated = false,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should handle null previous setting value for bitbucket workspace activation`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val context =
                    createSettingUpdateContext(
                        containerType = SettingContainerType.BITBUCKET_WORKSPACE,
                        containerId = "ari:cloud:bitbucket::workspace/4cff3fd9-28ef-40f9-8ae4-376d15c05228",
                        transactionContextCloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                    )

                val event =
                    SettingValueUpdatedEvent(
                        settingUpdateContext = context,
                        settingValue =
                            SettingValue(
                                value = AutoreviewWorkspaceSettingAttributes(autoreview_activated = true),
                                lastUpdatedTime = OffsetDateTime.now(),
                            ),
                        previousSettingValue = null, // No previous value
                    )

                subject.processSettingValueUpdated(event)

                // Then
                verify(exactly = 1) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(
                        action = "initiated",
                        accountId = TEST_ACCOUNT_ID,
                        bitbucketWorkspaceARI = BitbucketWorkspaceARI.from("4cff3fd9-28ef-40f9-8ae4-376d15c05228"),
                        orgId = null,
                        cloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                    )
                }
            }
    }

    @Nested
    inner class GitHubWorkspace {
        @ParameterizedTest
        @MethodSource("devai.modules.autoreview.service.AutoreviewAiInstallEventServiceTest#cloudIdCombinations")
        fun `should send analytics event when github workspace becomes autoreview_activated and feature gate is enabled`(
            transactionContextCloudId: String?,
            billingCloudId: String?,
            expectedEventCloudId: String?,
        ) = runTest {
            coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

            val event =
                createGitHubWorkspaceActivationEvent(
                    wasActivated = false,
                    isActivated = true,
                    transactionContextCloudId = transactionContextCloudId,
                    billingCloudId = billingCloudId,
                )

            subject.processSettingValueUpdated(event)

            verify(exactly = 1) {
                analyticsService.sendAutoreviewGitHubAiInstallEvent(
                    action = "initiated",
                    accountId = TEST_ACCOUNT_ID,
                    orgId = null,
                    cloudId = expectedEventCloudId,
                    gitHubWorkspaceName = TEST_GITHUB_WORKSPACE_NAME,
                )
            }
        }

        @Test
        fun `should handle null transaction context and send event when feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createGitHubWorkspaceActivationEvent(
                        wasActivated = false,
                        isActivated = true,
                    )

                subject.processSettingValueUpdated(
                    event.copy(
                        settingUpdateContext =
                            event.settingUpdateContext.copy(
                                transactionContext = null,
                            ),
                    ),
                )

                verify(exactly = 1) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(
                        action = "initiated",
                        accountId = null,
                        orgId = null,
                        cloudId = TEST_BILLING_CLOUD_ID,
                        gitHubWorkspaceName = TEST_GITHUB_WORKSPACE_NAME,
                    )
                }
            }

        @Test
        fun `should not send analytics event when feature gate is disabled - github workspace`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns false

                val event =
                    createGitHubWorkspaceActivationEvent(
                        wasActivated = false,
                        isActivated = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should not send analytics event when workspace was already activated - github workspace`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createGitHubWorkspaceActivationEvent(
                        wasActivated = true,
                        isActivated = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(any(), any(), any(), any(), any())
                }
            }
    }

    @Nested
    inner class BitBucketRepository {
        @Test
        fun `should send analytics event when bitbucket repository becomes autoreview_enabled and feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createBitbucketRepositoryEnabledEvent(
                        wasEnabled = false,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 1) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(
                        action = "completed",
                        accountId = TEST_ACCOUNT_ID,
                        bitbucketWorkspaceARI = BitbucketWorkspaceARI.from("4cff3fd9-28ef-40f9-8ae4-376d15c05228"),
                        orgId = null,
                        cloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                        bitbucketRepositoryARI = BitbucketRepositoryARI.from("4000784a-6cf8-40d6-8fb0-b75c2aabcc63"),
                    )
                }
            }

        @Test
        fun `should handle null transaction context and send event when feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createBitbucketRepositoryEnabledEvent(
                        wasEnabled = false,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(
                    event.copy(
                        settingUpdateContext =
                            event.settingUpdateContext.copy(
                                transactionContext = null,
                            ),
                    ),
                )

                verify(exactly = 1) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(
                        action = "completed",
                        accountId = null,
                        bitbucketWorkspaceARI = BitbucketWorkspaceARI.from("4cff3fd9-28ef-40f9-8ae4-376d15c05228"),
                        orgId = null,
                        cloudId = null,
                        bitbucketRepositoryARI = BitbucketRepositoryARI.from("4000784a-6cf8-40d6-8fb0-b75c2aabcc63"),
                    )
                }
            }

        @Test
        fun `should not send analytics event when feature gate is disabled - bitbucket repository`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns false

                val event =
                    createBitbucketRepositoryEnabledEvent(
                        wasEnabled = false,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should not send analytics event when repository was already enabled - bitbucket repository`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createBitbucketRepositoryEnabledEvent(
                        wasEnabled = true,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }
    }

    @Nested
    inner class GitHubRepository {
        @Test
        fun `should send analytics event when repository becomes enabled and FG is true`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createGitHubRepositoryEnabledEvent(
                        wasEnabled = false,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 1) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(
                        action = "completed",
                        accountId = TEST_ACCOUNT_ID,
                        orgId = null,
                        cloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                        gitHubWorkspaceName = TEST_GITHUB_WORKSPACE_NAME,
                        gitHubRepositoryName = TEST_GITHUB_REPOSITORY_NAME,
                    )
                }
            }

        @Test
        fun `should handle null transaction context and send event when feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createGitHubRepositoryEnabledEvent(
                        wasEnabled = false,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(
                    event.copy(
                        settingUpdateContext =
                            event.settingUpdateContext.copy(
                                transactionContext = null,
                            ),
                    ),
                )

                verify(exactly = 1) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(
                        action = "completed",
                        accountId = null,
                        orgId = null,
                        cloudId = null,
                        gitHubWorkspaceName = TEST_GITHUB_WORKSPACE_NAME,
                        gitHubRepositoryName = TEST_GITHUB_REPOSITORY_NAME,
                    )
                }
            }

        @Test
        fun `should not send analytics event when feature gate is false`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns false

                val event =
                    createGitHubRepositoryEnabledEvent(
                        wasEnabled = false,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should not send analytics event when github repository already enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createGitHubRepositoryEnabledEvent(
                        wasEnabled = true,
                        isEnabled = true,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should not send analytics event when 'enabled' is changed to false`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val event =
                    createGitHubRepositoryEnabledEvent(
                        wasEnabled = true,
                        isEnabled = false,
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 0) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(any(), any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should handle null previous setting value for github repository`() =
            runTest {
                coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

                val context =
                    createSettingUpdateContext(
                        containerType = SettingContainerType.REPOSITORY,
                        containerId = TEST_GITHUB_REPOSITORY_CONTAINER_ID,
                        parentId = null,
                        transactionContextCloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                    )

                val event =
                    SettingValueUpdatedEvent(
                        settingUpdateContext = context,
                        settingValue =
                            SettingValue(
                                value = AutoreviewRepositorySettingAttributes(autoreview_enabled = true),
                                lastUpdatedTime = OffsetDateTime.now(),
                            ),
                        previousSettingValue = null, // No previous value
                    )

                subject.processSettingValueUpdated(event)

                verify(exactly = 1) {
                    analyticsService.sendAutoreviewGitHubAiInstallEvent(
                        "completed",
                        TEST_ACCOUNT_ID,
                        null,
                        TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                        TEST_GITHUB_WORKSPACE_NAME,
                        TEST_GITHUB_REPOSITORY_NAME,
                    )
                }
            }
    }

    @Test
    fun `should not send analytics event for wrong setting key`() =
        runTest {
            coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

            val event =
                createBitbucketWorkspaceActivationEvent(
                    wasActivated = false,
                    isActivated = true,
                    settingKey = "wrong_setting_key",
                )

            subject.processSettingValueUpdated(event)

            verify(exactly = 0) {
                analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `should not send analytics for autoreview devai workspace settings type`() =
        runTest {
            coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

            val context =
                createSettingUpdateContext(
                    containerType = SettingContainerType.DEVAI_WORKSPACE, // Unsupported type
                    containerId = "test-container-id",
                    transactionContextCloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                )

            val event =
                SettingValueUpdatedEvent(
                    settingUpdateContext = context,
                    settingValue =
                        SettingValue(
                            value = AutoreviewDevAIWorkSpaceSettingAttributes(autoreview_enabled = true),
                            lastUpdatedTime = OffsetDateTime.now(),
                        ),
                    previousSettingValue =
                        SettingValue(
                            value = AutoreviewDevAIWorkSpaceSettingAttributes(autoreview_enabled = false),
                            lastUpdatedTime = OffsetDateTime.now(),
                        ),
                )

            subject.processSettingValueUpdated(event)

            verify(exactly = 0) {
                analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
            }
            verify(exactly = 0) {
                analyticsService.sendAutoreviewGitHubAiInstallEvent(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `should not send analytics event for unexpected container type`() =
        runTest {
            coEvery { featureService.isAutoreviewSendAiInstallEvents(any()) } returns true

            val context =
                createSettingUpdateContext(
                    containerType = SettingContainerType.PLATFORM_ORG, // Unsupported type
                    containerId = "test-container-id",
                    transactionContextCloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                )

            val event =
                SettingValueUpdatedEvent(
                    settingUpdateContext = context,
                    settingValue =
                        SettingValue(
                            value = AutoreviewWorkspaceSettingAttributes(autoreview_activated = true),
                            lastUpdatedTime = OffsetDateTime.now(),
                        ),
                    previousSettingValue =
                        SettingValue(
                            value = AutoreviewWorkspaceSettingAttributes(autoreview_activated = false),
                            lastUpdatedTime = OffsetDateTime.now(),
                        ),
                )

            subject.processSettingValueUpdated(event)

            verify(exactly = 0) {
                analyticsService.sendAutoreviewBitbucketAiInstallEvent(any(), any(), any(), any(), any(), any())
            }
            verify(exactly = 0) {
                analyticsService.sendAutoreviewGitHubAiInstallEvent(any(), any(), any(), any(), any())
            }
        }

    private fun createBitbucketWorkspaceActivationEvent(
        wasActivated: Boolean,
        isActivated: Boolean,
        settingKey: String = SettingKey.AUTOREVIEW_SETTING_KEY.key,
        transactionContextCloudId: String? = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
        billingCloudId: String? = TEST_BILLING_CLOUD_ID,
    ): SettingValueUpdatedEvent {
        val context =
            createSettingUpdateContext(
                containerType = SettingContainerType.BITBUCKET_WORKSPACE,
                containerId = TEST_BITBUCKET_WORKSPACE_CONTAINER_ID,
                key = settingKey,
                transactionContextCloudId = transactionContextCloudId,
            )

        return SettingValueUpdatedEvent(
            settingUpdateContext = context,
            settingValue =
                SettingValue(
                    value =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = isActivated,
                            autoreview_cloud_id_association = billingCloudId,
                        ),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
            previousSettingValue =
                SettingValue(
                    value =
                        mapOf(
                            "autoreview_activated" to wasActivated,
                        ),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
        )
    }

    private fun createGitHubWorkspaceActivationEvent(
        wasActivated: Boolean,
        isActivated: Boolean,
        settingKey: String = SettingKey.AUTOREVIEW_SETTING_KEY.key,
        transactionContextCloudId: String? = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
        billingCloudId: String? = TEST_BILLING_CLOUD_ID,
    ): SettingValueUpdatedEvent {
        val context =
            createSettingUpdateContext(
                containerType = SettingContainerType.WORKSPACE,
                containerId = TEST_GITHUB_WORKSPACE_CONTAINER_ID,
                key = settingKey,
                transactionContextCloudId = transactionContextCloudId,
            )

        return SettingValueUpdatedEvent(
            settingUpdateContext = context,
            settingValue =
                SettingValue(
                    value =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = isActivated,
                            autoreview_cloud_id_association = billingCloudId,
                        ),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
            previousSettingValue =
                SettingValue(
                    value =
                        mapOf(
                            "autoreview_activated" to wasActivated,
                        ),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
        )
    }

    private fun createBitbucketRepositoryEnabledEvent(
        wasEnabled: Boolean,
        isEnabled: Boolean,
        settingKey: String = SettingKey.AUTOREVIEW_SETTING_KEY.key,
    ): SettingValueUpdatedEvent {
        val context =
            createSettingUpdateContext(
                containerType = SettingContainerType.BITBUCKET_REPOSITORY,
                containerId = TEST_BITBUCKET_REPOSITORY_CONTAINER_ID,
                parentId = TEST_BITBUCKET_WORKSPACE_CONTAINER_ID,
                key = settingKey,
                transactionContextCloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
            )

        return SettingValueUpdatedEvent(
            settingUpdateContext = context,
            settingValue =
                SettingValue(
                    value = AutoreviewRepositorySettingAttributes(autoreview_enabled = isEnabled),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
            previousSettingValue =
                SettingValue(
                    value =
                        mapOf(
                            "autoreview_enabled" to wasEnabled,
                        ),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
        )
    }

    private fun createGitHubRepositoryEnabledEvent(
        wasEnabled: Boolean,
        isEnabled: Boolean,
        settingKey: String = SettingKey.AUTOREVIEW_SETTING_KEY.key,
    ): SettingValueUpdatedEvent {
        val context =
            createSettingUpdateContext(
                containerType = SettingContainerType.REPOSITORY, // GitHub repositories use REPOSITORY type
                containerId = TEST_GITHUB_REPOSITORY_CONTAINER_ID,
                parentId = null, // Not in use for GitHub Repository Settings event
                key = settingKey,
                transactionContextCloudId = TEST_TRANSACTION_CONTEXT_CLOUD_ID,
            )

        return SettingValueUpdatedEvent(
            settingUpdateContext = context,
            settingValue =
                SettingValue(
                    value = AutoreviewRepositorySettingAttributes(autoreview_enabled = isEnabled),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
            previousSettingValue =
                SettingValue(
                    value =
                        mapOf(
                            "autoreview_enabled" to wasEnabled,
                        ),
                    lastUpdatedTime = OffsetDateTime.now(),
                ),
        )
    }

    private fun createSettingUpdateContext(
        containerType: SettingContainerType,
        containerId: String,
        parentId: String? = null,
        key: String = SettingKey.AUTOREVIEW_SETTING_KEY.key,
        transactionContextCloudId: String?,
    ): SettingUpdateContext =
        SettingUpdateContext(
            containerType = containerType,
            containerId = containerId,
            parentId = parentId,
            key = key,
            transactionContext =
                TransactionContext(
                    workspace =
                        WorkspaceContext(
                            cloudId = transactionContextCloudId?.let { CloudIdLike.fromString(it) },
                            workspaceAri = ARI.valueOf("ari:cloud:devai::workspace/8aced8d2-975d-4627-a669-f09e45376b20"),
                            workspaceId = null,
                        ),
                    traceId = "something-trace-id",
                    userContext =
                        UserContext(
                            accountId =
                                com.atlassian.usercontext.api.AccountId
                                    .of(TEST_ACCOUNT_ID),
                            accountType = AccountType.ATLASSIAN,
                            userContextToken = "some-token",
                            tokenExpiration = null,
                            authType = null,
                        ),
                ),
        )

    companion object {
        @JvmStatic
        fun cloudIdCombinations(): Stream<Arguments> =
            Stream.of(
                Arguments.argumentSet(
                    "Prefer Transaction Context CloudId over Billing Cloud Id",
                    TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                    TEST_BILLING_CLOUD_ID,
                    TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                ),
                Arguments.argumentSet(
                    "Use Transaction Context CloudId when Billing Cloud Id is null",
                    TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                    null,
                    TEST_TRANSACTION_CONTEXT_CLOUD_ID,
                ),
                Arguments.argumentSet(
                    "Use Billing Cloud Id when Transaction Context Cloud Id not available",
                    null,
                    TEST_BILLING_CLOUD_ID,
                    TEST_BILLING_CLOUD_ID,
                ),
                Arguments.argumentSet("Use null CloudId when neither options available", null, null, null),
            )
    }
}
