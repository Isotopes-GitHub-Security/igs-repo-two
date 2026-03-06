package devai.modules.autoreview.service

import com.atlassian.ari.principled.CloudIdLike
import com.atlassian.ari.principled.devai.DevaiWorkspaceARI
import com.fasterxml.jackson.databind.ObjectMapper
import devai.modules.acra.shared.model.artifacts.PullRequestInfoArtifact
import devai.modules.autoreview.model.AutoreviewProcessStatus
import devai.modules.checks.service.UserPermissionsService
import devai.modules.sal.service.SalService
import devai.modules.settings.model.SettingValue
import devai.modules.settings.service.AutoreviewSettingsService
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.GLOBAL_WORKSPACE_ARI
import devai.modules.shared.model.PrDetailsEntity
import devai.modules.shared.model.settings.SettingContainerType
import devai.modules.shared.service.tcs.TcsService
import devai.modules.shared.usage.metering.RovoDevCTALinks
import io.atlassian.tcs.model.cloud.CloudURL
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DefaultAutoreviewUtilityServiceTest {
    private val autoreviewValidationService = mockk<AutoreviewValidationService>()
    private val autoreviewSettingsService = mockk<AutoreviewSettingsService>()
    private val objectMapper = ObjectMapper()
    private val tcsService = mockk<TcsService>()
    private val rovoDevCTALinks = RovoDevCTALinks()
    private val userPermissionsService = mockk<UserPermissionsService>()
    private val featureService = mockk<DevAiCoreFeatureService>()
    private val autoreviewGHWorkflowStateService = mockk<AutoreviewGHWorkflowStateService>()
    private val autoreviewWorkflowsStorageService = mockk<AutoreviewWorkflowsStorageService>()
    private val salService = mockk<SalService>()

    private val service =
        DefaultAutoreviewUtilityService(
            autoreviewValidationService = autoreviewValidationService,
            autoreviewSettingsService = autoreviewSettingsService,
            objectMapper = objectMapper,
            tcsService = tcsService,
            rovoDevCTALinks = rovoDevCTALinks,
            userPermissionsService = userPermissionsService,
            featureService = featureService,
            autoreviewGHWorkflowStateService = autoreviewGHWorkflowStateService,
            autoreviewWorkflowsStorageService = autoreviewWorkflowsStorageService,
            salService = salService,
        )

    private val testCloudId = CloudIdLike.fromString("1166191d-3f28-475c-a121-7edd297f6b9e")
    private val testDevaiActivationId = "ce66191d-3f28-475c-a121-7edd297f6b9e"
    private val testBbcWorkspaceUUID = "ec5d87df-f989-429a-8c8e-181913d839ed"
    private val testBbcWorkspaceAri = "ari:cloud:bitbucket::workspace/$testBbcWorkspaceUUID"
    private val testGithubOrgUrl = "https://github.com/test-org"
    private val testAccountId = "1234"

    @Test
    fun `getDevAiWorkspaceForCodeReviewProcess returns DevaiWorkspaceARI when billingCloudId is provided and activation exists`() =
        runTest {
            // Given
            coEvery { autoreviewValidationService.getDevAiCloudActivation(testCloudId) } returns
                ValidatedDevAICloudActivation(
                    cloudId = testCloudId,
                    activationId = testDevaiActivationId,
                )

            // When
            val result =
                service.getDevAiWorkspaceForCodeReviewProcess(
                    billingCloudId = testCloudId,
                )

            // Then
            assertEquals(DevaiWorkspaceARI.from(testDevaiActivationId), result)
        }

    @Test
    fun `getDevAiWorkspaceForCodeReviewProcess throws IllegalArgumentException when activation does not exist`() =
        runTest {
            // Given
            coEvery { autoreviewValidationService.getDevAiCloudActivation(testCloudId) } returns null

            // When & Then
            val exception =
                assertThrows<IllegalArgumentException> {
                    service.getDevAiWorkspaceForCodeReviewProcess(
                        billingCloudId = testCloudId,
                    )
                }
            assertEquals("Devai Workspace ARI not found", exception.message)
        }

    @Test
    fun `getDevAiWorkspaceForCodeReviewProcess throws IllegalArgumentException when cloud activation is null`() =
        runTest {
            // Given
            coEvery { autoreviewValidationService.getDevAiCloudActivation(testCloudId) } returns null

            // When & Then
            val exception =
                assertThrows<IllegalArgumentException> {
                    service.getDevAiWorkspaceForCodeReviewProcess(
                        billingCloudId = testCloudId,
                    )
                }
            assertEquals("Devai Workspace ARI not found", exception.message)
        }

    @Test
    fun `getDevAiWorkspaceForCodeReviewProcess returns global workspace ARI when billingCloudId is null`() =
        runTest {
            // When
            val result =
                service.getDevAiWorkspaceForCodeReviewProcess(
                    billingCloudId = null,
                )

            // Then
            assertEquals(DevaiWorkspaceARI.valueOf(GLOBAL_WORKSPACE_ARI.toString()), result)
        }

    @Test
    fun `getBillingCloudId returns CloudIdLike when bbcWorkspaceUUID is provided and settings exist`() =
        runTest {
            // Given
            val testCloudIdString = "test-billing-cloud-id"
            val workspaceSetting = mockk<SettingValue>()
            every { workspaceSetting.value } returns mapOf("autoreview_cloud_id_association" to testCloudIdString)

            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    testBbcWorkspaceAri,
                )
            } returns workspaceSetting

            // When
            val result = service.getBillingCloudId(bbcWorkspaceUUID = testBbcWorkspaceUUID)

            // Then
            assertEquals(CloudIdLike.fromString(testCloudIdString), result)
        }

    @Test
    fun `getBillingCloudId returns CloudIdLike when githubOrgUrl is provided and settings exist`() =
        runTest {
            // Given
            val testCloudIdString = "test-billing-cloud-id"
            val workspaceSetting = mockk<SettingValue>()
            every { workspaceSetting.value } returns mapOf("autoreview_cloud_id_association" to testCloudIdString)

            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.WORKSPACE,
                    testGithubOrgUrl,
                )
            } returns workspaceSetting

            // When
            val result = service.getBillingCloudId(githubOrgUrl = testGithubOrgUrl)

            // Then
            assertEquals(CloudIdLike.fromString(testCloudIdString), result)
        }

    @Test
    fun `getBillingCloudId prefers bbcWorkspaceUUID when both parameters are provided`() =
        runTest {
            // Given
            val testCloudIdString = "test-billing-cloud-id"
            val workspaceSetting = mockk<SettingValue>()
            every { workspaceSetting.value } returns mapOf("autoreview_cloud_id_association" to testCloudIdString)

            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    testBbcWorkspaceAri,
                )
            } returns workspaceSetting

            // When
            val result =
                service.getBillingCloudId(
                    bbcWorkspaceUUID = testBbcWorkspaceUUID,
                    githubOrgUrl = testGithubOrgUrl,
                )

            // Then
            assertEquals(CloudIdLike.fromString(testCloudIdString), result)
        }

    @Test
    fun `getBillingCloudId returns null when workspace settings do not exist`() =
        runTest {
            // Given
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    testBbcWorkspaceAri,
                )
            } returns null

            // When
            val result = service.getBillingCloudId(bbcWorkspaceUUID = testBbcWorkspaceUUID)

            // Then
            assertNull(result)
        }

    @Test
    fun `getBillingCloudId returns null when workspace settings exist but value is null`() =
        runTest {
            // Given
            val workspaceSetting = mockk<SettingValue>()
            every { workspaceSetting.value } returns null

            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    testBbcWorkspaceAri,
                )
            } returns workspaceSetting

            // When
            val result = service.getBillingCloudId(bbcWorkspaceUUID = testBbcWorkspaceUUID)

            // Then
            assertNull(result)
        }

    @Test
    fun `getBillingCloudId throws IllegalArgumentException when both parameters are null`() =
        runTest {
            // When & Then
            val exception =
                assertThrows<IllegalArgumentException> {
                    service.getBillingCloudId(bbcWorkspaceUUID = null, githubOrgUrl = null)
                }
            assertEquals("Either bbcWorkspaceUUID or githubOrgUrl must be provided", exception.message)
        }

    @Test
    fun `getCtaLinkForProcessStatus returns usage link for billing statuses`() =
        runTest {
            // Given
            val cloudUrl = "https://example.atlassian.net"
            val mockCloudURL = mockk<CloudURL>()
            every { mockCloudURL.cloudUrl } returns cloudUrl
            coEvery { tcsService.getCloudUrlFromCloudIdAsync(testCloudId) } returns mockCloudURL

            val billingStatuses =
                listOf(
                    AutoreviewProcessStatus.BILLING_FREE_TIER_EXHAUSTED,
                    AutoreviewProcessStatus.BILLING_PAID_LIMIT_EXCEEDED,
                    AutoreviewProcessStatus.BILLING_OUT_OF_CREDIT,
                )

            billingStatuses.forEach { status ->
                val result = service.getCtaLinkForProcessStatus(testCloudId, status, testAccountId)
                assertEquals(listOf("$cloudUrl/rovodev/your-usage"), result)
            }
        }

    @Test
    fun `getCtaLinkForProcessStatus returns request access link for USER_NOT_AUTHORIZED status when user is not org admin`() =
        runTest {
            // Given
            val cloudUrl = "https://example.atlassian.net"
            val mockCloudURL = mockk<CloudURL>()
            every { mockCloudURL.cloudUrl } returns cloudUrl
            coEvery { tcsService.getCloudUrlFromCloudIdAsync(testCloudId) } returns mockCloudURL
            val testAccountId = "test-account-id"
            coEvery { userPermissionsService.isUserOrgAdmin(testCloudId.toString(), testAccountId) } returns false

            // When
            val result =
                service.getCtaLinkForProcessStatus(
                    testCloudId,
                    AutoreviewProcessStatus.USER_NOT_AUTHORIZED,
                    testAccountId,
                )

            // Then
            assertEquals(1, result.size)
            assertTrue(
                result[0].contains("id.atlassian.com/join/user-access") ||
                    result[0].contains("id.stg.internal.atlassian.com/join/user-access"),
            )
            assertTrue(result[0].contains("resource="))
            assertTrue(result[0].contains(testCloudId.toString()))
        }

    @Test
    fun `getCtaLinkForProcessStatus returns add user link for USER_NOT_AUTHORIZED status when user is org admin`() =
        runTest {
            // Given
            val cloudUrl = "https://example.atlassian.net"
            val mockCloudURL = mockk<CloudURL>()
            every { mockCloudURL.cloudUrl } returns cloudUrl
            coEvery { tcsService.getCloudUrlFromCloudIdAsync(testCloudId) } returns mockCloudURL
            val testOrgId = "test-org-id"
            coEvery { userPermissionsService.isUserOrgAdmin(testCloudId.toString(), testAccountId) } returns true
            coEvery { tcsService.getOrgByCloudIdAsyncWithRetry(testCloudId) } returns
                mockk {
                    every { orgId } returns testOrgId
                }

            // When
            val result =
                service.getCtaLinkForProcessStatus(
                    testCloudId,
                    AutoreviewProcessStatus.USER_NOT_AUTHORIZED,
                    testAccountId,
                )

            // Then
            assertEquals(1, result.size)
            assertTrue(result[0].contains("/o/$testOrgId/atlassian-apps/devai/$testCloudId"))
        }

    @Test
    fun `getCtaLinkForProcessStatus returns empty list for USER_NOT_AUTHORIZED when user is org admin but orgId is null`() =
        runTest {
            // Given
            val cloudUrl = "https://example.atlassian.net"
            val mockCloudURL = mockk<CloudURL>()
            every { mockCloudURL.cloudUrl } returns cloudUrl
            coEvery { tcsService.getCloudUrlFromCloudIdAsync(testCloudId) } returns mockCloudURL
            coEvery { userPermissionsService.isUserOrgAdmin(testCloudId.toString(), testAccountId) } returns true
            coEvery { tcsService.getOrgByCloudIdAsyncWithRetry(testCloudId) } returns null

            // When
            val result =
                service.getCtaLinkForProcessStatus(
                    testCloudId,
                    AutoreviewProcessStatus.USER_NOT_AUTHORIZED,
                    testAccountId,
                )

            // Then
            assertEquals(emptyList<String>(), result)
        }

    @Test
    fun `getCtaLinkForProcessStatus returns empty list when cloudUrl is null`() =
        runTest {
            // Given
            coEvery { userPermissionsService.isUserOrgAdmin(testCloudId.toString(), testAccountId) } returns false
            coEvery { tcsService.getCloudUrlFromCloudIdAsync(testCloudId) } returns null

            // When
            val result =
                service.getCtaLinkForProcessStatus(
                    testCloudId,
                    AutoreviewProcessStatus.USER_NOT_AUTHORIZED,
                    testAccountId,
                )

            // Then
            assertEquals(emptyList<String>(), result)
        }

    @Test
    fun `getCtaLinkForProcessStatus returns empty list for unrecognised process statuses`() =
        runTest {
            // Given
            val nonBillingStatuses = AutoreviewProcessStatus.ERROR_PR_SIZE_LIMIT

            // When
            val result = service.getCtaLinkForProcessStatus(testCloudId, nonBillingStatuses, testAccountId)

            // Then
            assertEquals(emptyList<String>(), result)
        }

    @Nested
    inner class UpdateWorkflowStateForGitHub {
        private val testTraceId = "test-trace-id"
        private val testPrUrl = "https://github.com/test-owner/test-repo/pull/123"
        private val testPrDescription = "Test PR Description"

        @BeforeEach
        fun setup() {
            coEvery { salService.getPullRequestDetails(any()) } returns
                PrDetailsEntity(
                    id = 123,
                    state = "open",
                    title = "Test PR",
                    body = testPrDescription,
                )

            coEvery { featureService.isAutoreviewGitHubStatefulContainerEnabled(any()) } returns true
        }

        @Test
        fun `updates workflow state when feature flag is enabled and PR is from GitHub`() =
            runTest {
                // Given
                val workflowRun = createTestWorkflowRun(testPrUrl, testPrDescription)
                val processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED

                coEvery {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        status = processStatus,
                        workspace = "test-owner",
                        repositoryName = "test-repo",
                        pullRequestId = "123",
                        description = testPrDescription,
                        user = any(),
                    )
                } returns mockk()

                // When
                service.updateWorkflowStateForGitHub(workflowRun, processStatus)

                // Then
                coVerify(exactly = 1) {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        status = processStatus,
                        workspace = "test-owner",
                        repositoryName = "test-repo",
                        pullRequestId = "123",
                        description = testPrDescription,
                        user = any(),
                    )
                }
            }

        @Test
        fun `does not update workflow state when feature flag is disabled`() =
            runTest {
                // Given
                val workflowRun = createTestWorkflowRun(testPrUrl, testPrDescription)
                val processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED

                coEvery { featureService.isAutoreviewGitHubStatefulContainerEnabled(any()) } returns false

                // When
                service.updateWorkflowStateForGitHub(workflowRun, processStatus)

                // Then
                coVerify(exactly = 0) {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `does not update workflow state when PR is not from GitHub`() =
            runTest {
                // Given
                val bbcPrUrl = "https://bitbucket.org/test-workspace/test-repo/pull-requests/123"
                val workflowRun = createTestWorkflowRun(bbcPrUrl, testPrDescription)
                val processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED

                // When
                service.updateWorkflowStateForGitHub(workflowRun, processStatus)

                // Then
                coVerify(exactly = 0) {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `method won't call GH action when pull request artifact is missing`() =
            runTest {
                // Given
                val workflowRun = createTestWorkflowRunWithoutPrArtifact()
                val processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED

                // When
                service.updateWorkflowStateForGitHub(workflowRun, processStatus)

                // Assert
                coVerify(exactly = 0) {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `method won't call GH action when fetching latest PR details failed`() =
            runTest {
                // Given
                val workflowRun = createTestWorkflowRun(testPrUrl, testPrDescription)
                val processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED
                coEvery { salService.getPullRequestDetails(any()) } returns null

                // When
                service.updateWorkflowStateForGitHub(workflowRun, processStatus)

                // Assert
                coVerify(exactly = 0) {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `method won't throw exception when integration service for updating description has an error`() =
            runTest {
                // Given
                val workflowRun = createTestWorkflowRun(testPrUrl, testPrDescription)
                val processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED

                coEvery {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } throws Exception("Some integration error")

                // When
                service.updateWorkflowStateForGitHub(workflowRun, processStatus)

                // Assert
                coVerify(exactly = 1) {
                    autoreviewGHWorkflowStateService.updateWithProcessStatus(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `correctly parses GitHub URL with different formats`() =
            runTest {
                // Given
                val prUrls =
                    listOf(
                        "https://github.com/owner1/repo1/pull/456" to Triple("owner1", "repo1", "456"),
                        "https://github.com/org-name/repo-name/pull/789" to Triple("org-name", "repo-name", "789"),
                        "https://github.com/user_123/my-repo_test/pull/1" to Triple("user_123", "my-repo_test", "1"),
                    )

                prUrls.forEach { (prUrl, expected) ->
                    val (expectedOwner, expectedRepo, expectedPrId) = expected
                    val workflowRun = createTestWorkflowRun(prUrl, testPrDescription)

                    coEvery {
                        autoreviewGHWorkflowStateService.updateWithProcessStatus(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                        )
                    } returns mockk()

                    // When
                    service.updateWorkflowStateForGitHub(workflowRun, AutoreviewProcessStatus.WORKFLOW_COMPLETED)

                    // Then
                    coVerify {
                        autoreviewGHWorkflowStateService.updateWithProcessStatus(
                            any(),
                            expectedOwner,
                            expectedRepo,
                            expectedPrId,
                            any(),
                            any(),
                        )
                    }
                }
            }

        private fun createTestWorkflowRun(
            prUrl: String,
            prDescription: String,
            cloudId: String? = "1166191d-3f28-475c-a121-7edd297f6b9e",
            accountId: String? = testAccountId,
        ): devai.modules.acra.shared.model.WorkflowRun {
            val pullRequestInfoArtifact =
                PullRequestInfoArtifact(
                    pullRequestUrl = prUrl,
                    prTitle = "Test PR",
                    prDescription = prDescription,
                )

            return devai.modules.acra.shared.model.WorkflowRun(
                id = java.util.UUID.randomUUID(),
                issueAri =
                    com.atlassian.ari.principled.jira.JiraIssueARI
                        .from("test-site", "10000"),
                repoUrl = java.net.URI("https://github.com/test/repo").toURL(),
                rootWorkflow = devai.modules.acra.shared.model.RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                currentWorkflow = null,
                status = devai.modules.acra.shared.model.WorkflowRunStatus.IN_PROGRESS,
                artifacts =
                    listOf(
                        devai.modules.acra.shared.model.WorkflowRunArtifact(
                            id = java.util.UUID.randomUUID(),
                            name = "pull-request-info.json",
                            data = pullRequestInfoArtifact,
                        ),
                    ),
                createdTimestamp = java.time.Instant.now(),
                updatedTimestamp = java.time.Instant.now(),
                cloudId = cloudId,
                workspaceARI = mockk<com.atlassian.ari.principled.ARI>(relaxed = true),
                traceId = testTraceId,
                accountId = accountId,
            )
        }

        private fun createTestWorkflowRunWithoutPrArtifact(): devai.modules.acra.shared.model.WorkflowRun =
            devai.modules.acra.shared.model.WorkflowRun(
                id = java.util.UUID.randomUUID(),
                issueAri =
                    com.atlassian.ari.principled.jira.JiraIssueARI
                        .from("test-site", "10000"),
                repoUrl = java.net.URI("https://github.com/test/repo").toURL(),
                rootWorkflow = devai.modules.acra.shared.model.RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                currentWorkflow = null,
                status = devai.modules.acra.shared.model.WorkflowRunStatus.IN_PROGRESS,
                artifacts = emptyList(),
                createdTimestamp = java.time.Instant.now(),
                updatedTimestamp = java.time.Instant.now(),
                cloudId = "1166191d-3f28-475c-a121-7edd297f6b9e",
                workspaceARI = mockk<com.atlassian.ari.principled.ARI>(relaxed = true),
                traceId = testTraceId,
                accountId = testAccountId,
            )
    }
}
