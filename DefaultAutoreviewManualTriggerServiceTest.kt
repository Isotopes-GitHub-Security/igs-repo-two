package devai.modules.autoreview.service

import com.atlassian.ari.principled.ARI
import com.atlassian.ari.principled.CloudIdLike
import com.atlassian.ari.principled.devai.DevaiWorkspaceARI
import com.atlassian.ari.principled.graph.GraphPullRequestARI
import com.atlassian.ari.principled.graph.GraphWorkspaceARI
import com.atlassian.ari.principled.jira.JiraIssueARI
import com.atlassian.usercontext.api.AccountId
import com.atlassian.usercontext.api.AccountType
import com.fasterxml.jackson.databind.ObjectMapper
import devai.modules.acra.client.DEV_AI_WORKSPACE_ID
import devai.modules.autoreview.model.AutoreviewPantryItem
import devai.modules.autoreview.model.AutoreviewProcessStatus
import devai.modules.autoreview.model.AutoreviewWorkflow
import devai.modules.autoreview.model.JiraIssueDetails
import devai.modules.autoreview.model.RepositoryIdentifiers
import devai.modules.autoreview.model.WorkflowRunStatus
import devai.modules.autoreview.queue.model.AutoreviewSalPayload
import devai.modules.autoreview.queue.model.PRState
import devai.modules.clients.datadepot.DataDepotClient
import devai.modules.clients.datadepot.EntityTypeEntry
import devai.modules.clients.datadepot.EntityTypesMapResult
import devai.modules.clients.datadepot.InternalEntityResult
import devai.modules.sal.service.SALDataDepotStreamhubEventsHandler
import devai.modules.sal.service.SalService
import devai.modules.settings.model.AutoreviewDevAIWorkSpaceSettingAttributes
import devai.modules.settings.model.AutoreviewRepositorySettingAttributes
import devai.modules.settings.model.AutoreviewWorkspaceSettingAttributes
import devai.modules.settings.model.SettingValue
import devai.modules.settings.service.AutoreviewSettingsService
import devai.modules.shared.client.AtlassianProxyClient
import devai.modules.shared.client.Fields
import devai.modules.shared.client.IssueDetails
import devai.modules.shared.client.RenderedFields
import devai.modules.shared.client.idgatekeeper.ResourceType
import devai.modules.shared.client.streamhub.Association
import devai.modules.shared.client.streamhub.AutoreviewEventType
import devai.modules.shared.datadepot.Associations
import devai.modules.shared.datadepot.Branch
import devai.modules.shared.datadepot.PullRequestObjectDataDepot
import devai.modules.shared.datadepot.User
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.AVI_DEVOPS_UPDATED_PULL_REQUEST
import devai.modules.shared.model.BadRequestException
import devai.modules.shared.model.CodeReviewExperience
import devai.modules.shared.model.CreditResult
import devai.modules.shared.model.CreditStatus
import devai.modules.shared.model.PrDetailsEntity
import devai.modules.shared.model.ReviewTriggerType
import devai.modules.shared.model.ScmUrlType
import devai.modules.shared.model.UrlDetails
import devai.modules.shared.model.UserContext
import devai.modules.shared.model.UserCreditResult
import devai.modules.shared.model.bitbucketScm
import devai.modules.shared.model.githubScm
import devai.modules.shared.model.settings.SettingContainerType
import devai.modules.shared.model.tcs.WorkspaceId
import devai.modules.shared.redis.DataDepotPRDedupService
import devai.modules.shared.sal.SalSharedUtil
import devai.modules.tenant.model.TransactionContext
import devai.modules.tenant.model.WorkspaceContext
import io.atlassian.tcs.model.cloud.ActivationIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.OffsetDateTime
import java.util.Optional
import kotlin.jvm.optionals.getOrNull

class DefaultAutoreviewManualTriggerServiceTest {
    companion object {
        const val TEST_PR_URL = "https://bitbucket.org/workspace/repo/pull-requests/1"
        const val TEST_DEFAULT_AI_WORKSPACE_ID = DEV_AI_WORKSPACE_ID
        const val TEST_DEVAI_WORKSPACE_ARI = "ari:cloud:devai::workspace/00000000-0000-0000-0000-000000000000"
        const val TEST_AUTHOR_ACCOUNT_ID = "123456789013"
        const val TEST_CLOUD_ID = "922168f0-256f-49e0-ac04-4db48b68d2ea"
        const val TEST_ISSUE_ID = "3456"
        const val TEST_JIRA_ISSUE_ARI = "ari:cloud:jira:${TEST_CLOUD_ID}:issue/${TEST_ISSUE_ID}"
    }

    private val salService = mockk<SalService>(relaxed = true)
    private val atlassianProxyClient = mockk<AtlassianProxyClient>(relaxed = true)
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val autoreviewService = mockk<AutoreviewService>(relaxed = true)
    private val autoreviewValidationService = mockk<AutoreviewValidationService>(relaxed = true)
    private val autoreviewUtilityService = mockk<AutoreviewUtilityService>(relaxed = true)
    private val autoreviewWorkflowsStorageService = mockk<AutoreviewWorkflowsStorageService>(relaxed = true)
    private val salSharedUtil = mockk<SalSharedUtil>(relaxed = true)
    private val autoreviewSettingsService = mockk<AutoreviewSettingsService>(relaxed = true)
    private val autoreviewJiraIssueService = mockk<AutoreviewJiraIssueService>(relaxed = true)
    private val dataDepotPRDedupService = mockk<DataDepotPRDedupService>(relaxed = true)
    private val salDataDepotStreamhubEventsHandler = mockk<SALDataDepotStreamhubEventsHandler>(relaxed = true)
    private val dataDepotClient = mockk<DataDepotClient>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private lateinit var autoreviewManualTriggerService: DefaultAutoreviewManualTriggerService

    private fun createTestTransactionContext(): TransactionContext =
        TransactionContext(
            workspace =
                WorkspaceContext(
                    workspaceId = WorkspaceId(TEST_DEFAULT_AI_WORKSPACE_ID),
                    cloudId = CloudIdLike.fromString(TEST_CLOUD_ID),
                    workspaceAri = ARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                ),
            traceId = "test-trace-id",
            userContext =
                UserContext(
                    accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID),
                    accountType = AccountType.ATLASSIAN,
                    userContextToken = "UCT",
                    tokenExpiration = null,
                    authType = null,
                ),
        )

    val pullRequestUrl = TEST_PR_URL
    val repositoryUuid = "69e083ba-5364-4d74-bee6-c922fe824ada"
    val bitbucketWorkspaceUuid = "60321907-6af4-4917-bb98-df3a70383bc3"

    val billingCloudId = TEST_CLOUD_ID
    val billingGraphWorkspaceId = "fe8476b1-3847-4d78-87ca-abf898208d75"
    val billingDevAIWorkspaceId = "8a306162-1287-4c4b-baa1-d4fa93e31518"
    val nonBillingCloudId = "23122f32-6564-4181-8f3d-52700af1d6ea"
    val nonBillingGraphWorkspaceId = "60bdc91c-1e03-4014-8cea-0a90a22df0cb"
    val nonBillingDevAIWorkspaceId = "ff9437f1-ab7e-4bc6-ad71-a210b46fd38c"

    val transactionContext = createTestTransactionContext()
    val graphWorkspaceIds =
        mapOf(
            billingCloudId to billingGraphWorkspaceId,
            nonBillingCloudId to nonBillingGraphWorkspaceId,
        )
    val devaiWorkspaceIds =
        mapOf(
            billingCloudId to billingDevAIWorkspaceId,
            nonBillingCloudId to nonBillingDevAIWorkspaceId,
        )

    @BeforeEach
    fun setup() {
        autoreviewManualTriggerService =
            DefaultAutoreviewManualTriggerService(
                salSharedUtil,
                salService,
                autoreviewValidationService,
                autoreviewUtilityService,
                autoreviewSettingsService,
                autoreviewJiraIssueService,
                objectMapper,
                autoreviewWorkflowsStorageService,
                featureService,
                autoreviewService,
                salDataDepotStreamhubEventsHandler,
                dataDepotClient,
            )
        coEvery { salSharedUtil.determineMatchingScm(any(), ScmUrlType.PR) } returns bitbucketScm
        coEvery { salService.getPullRequestDetails(any()) } returns
            PrDetailsEntity(
                id = 1,
                title = "Test PR",
                state = "OPEN",
                sourceBranch = "source-branch",
                destinationBranch = "destination-branch",
                authorAccountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID).toString(),
            )
        coEvery { salSharedUtil.validateAndExtractPullRequestDetails(any()) } returns
            UrlDetails(
                scm = "bitbucket",
                domain = "bitbucket.org",
                workspaceName = "test",
                repoSlug = "test",
                branchName = "test-branch",
            )
        coEvery {
            salSharedUtil.createRepoUrl(
                "bitbucket",
                "bitbucket.org",
                "test",
                "test",
            )
        } returns "https://bitbucket.org/test/test"
        coEvery { autoreviewValidationService.getConnectedCloudIdsByBitbucketWorkspaceId(any()) } returns
            listOf(CloudIdLike.fromString(billingCloudId), CloudIdLike.fromString(nonBillingCloudId))
        coEvery { autoreviewValidationService.isPullRequestReviewable(PRState.OPEN) } returns true
        coEvery { autoreviewValidationService.isPullRequestReviewable(PRState.UNKNOWN) } returns false
        coEvery { autoreviewValidationService.isRepoAccepted(any(), any(), any()) } returns true
        coEvery { autoreviewValidationService.isAcceptanceCriteriaEnabled(any(), any(), any(), any()) } returns true
        coEvery {
            autoreviewValidationService.getCloudActivationByCloudId(
                any(),
                setOf(DEVAI_ACTIVATION_KEY, DEVOPS_ACTIVATION_KEY),
            )
        } answers {
            val cloudIdLike = firstArg<CloudIdLike>()
            val devAiWorkspaceId = devaiWorkspaceIds[cloudIdLike.toString()]
            val graphWorkspaceId = graphWorkspaceIds[cloudIdLike.toString()]

            CloudActivation(
                cloudIdLike,
                mapOf(
                    "devai" to ActivationIds(devAiWorkspaceId, listOf("1", "2", "3")),
                    "devops" to ActivationIds(graphWorkspaceId, listOf("1", "2", "3")),
                ),
            )
        }
        coEvery { autoreviewValidationService.isWorkspaceAllowedWithoutDevAiActivation(any()) } returns true

        coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns false
        coEvery { autoreviewSettingsService.getWorkspaceAutoreviewSettings(any(), any()) } returns
            SettingValue(
                value =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_cloud_id_association = TEST_CLOUD_ID,
                    ),
                lastUpdatedTime = OffsetDateTime.now(),
            )
        coEvery { autoreviewSettingsService.getRepositoryAutoreviewSettings(any(), any()) } returns
            SettingValue(
                value = AutoreviewRepositorySettingAttributes(true),
                lastUpdatedTime = OffsetDateTime.now(),
            )

        coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) } returns
            AutoreviewPantryItem(
                autoreviewWorkflows =
                    listOf(
                        AutoreviewWorkflow(
                            jobId = "existing-job-id",
                            createdDate = OffsetDateTime.now().minusHours(1).toString(),
                            sourceCommit = "source-commit",
                            workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                        ),
                    ),
            )
        coEvery {
            autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                SettingContainerType.BITBUCKET_WORKSPACE,
                any(),
            )
        } returns
            SettingValue(
                AutoreviewWorkspaceSettingAttributes(
                    autoreview_activated = true,
                    autoreview_ip_allowlist_enabled = true,
                    autoreview_cloud_id_association = billingCloudId,
                ),
                OffsetDateTime.now(),
            )

        coEvery { autoreviewValidationService.isSiteHasRovodevProduct(any(), any(), any()) } returns true
        coEvery { featureService.isAutoreviewCustomGeneratorEnabled(any()) } returns true
        coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true
        coEvery { autoreviewSettingsService.getDevAiWorkspaceAutoreviewSettingsValue(any()) } returns
            AutoreviewDevAIWorkSpaceSettingAttributes(
                autoreview_enabled = true,
            )
        coEvery {
            autoreviewValidationService.checkCredit(
                accountId = any(),
                cloudId = any(),
                experience = any(),
                isExistingBetaUser = any(),
                friendlyPullRequestUrl = any(),
                scmWorkspaceId = any(),
                scmRepositoryId = any(),
            )
        } returns UserCreditResult(creditResult = CreditResult(status = CreditStatus.OK))

        // Default mocks for autoreviewService methods (can be overridden in individual tests)
        coEvery { autoreviewService.submitAutoreviewWorkflow(any(), any(), any()) } returns Unit
        coEvery { autoreviewService.submitIncrementalReview(any(), any(), any()) } returns Unit
        coEvery { autoreviewService.submitAcceptanceCriteria(any(), any()) } returns Unit
        coEvery { autoreviewJiraIssueService.getJiraIssuesBySalPayload(any()) } returns
            listOf(
                JiraIssueDetails(
                    issueAri = TEST_JIRA_ISSUE_ARI,
                    description = "Test description",
                    summary = "Test summary",
                ),
            )
        coEvery {
            autoreviewValidationService.getSupportedEventTypes(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN)

        coEvery {
            autoreviewWorkflowsStorageService.getWorkflowStatusByJobId(any(), any())
        } returns WorkflowRunStatus.COMPLETED
        coEvery {
            autoreviewUtilityService.shouldSkipPullRequest(any(), any(), any())
        } returns false
    }

    @Test
    fun `submitManualReview runs first review only when no prior workflows and no jira issues`() =
        runTest {
            // Arrange
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl) } returns null

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            coVerify(exactly = 1) {
                autoreviewService.submitAutoreviewWorkflow(any(), emptyList(), ReviewTriggerType.MANUAL)
            }
        }

    @Test
    fun `submitManualReview runs first review with ac when no prior workflows and jira issues present`() =
        runTest {
            // Arrange
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl) } returns null
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    GraphWorkspaceARI.from(billingGraphWorkspaceId),
                    any(),
                    any(),
                )
            } returns
                listOf(JiraIssueARI.valueOf(TEST_JIRA_ISSUE_ARI))
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.ACCEPTANCE_CRITERIA)

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            coVerify(exactly = 1) {
                autoreviewService.submitAutoreviewWorkflow(any(), any(), ReviewTriggerType.MANUAL)
            }
            coVerify(exactly = 1) {
                autoreviewService.submitAcceptanceCriteria(any(), any())
            }
        }

    @Test
    fun `submitManualReview runs first review with bracketed ID's`() =
        runTest {
            // Arrange
            val bracketedWorkspaceUuid = "{$bitbucketWorkspaceUuid}"
            val bracketedRepositoryUuid = "{$repositoryUuid}"
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl) } returns null

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    bracketedRepositoryUuid,
                    bracketedWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            coVerify(exactly = 1) {
                autoreviewService.submitAutoreviewWorkflow(any(), emptyList(), ReviewTriggerType.MANUAL)
            }
        }

    @Test
    fun `submitManualReview returns PENDING and triggers incremental review with correct artifacts`() {
        runTest {
            coEvery {
                autoreviewValidationService.isAcceptanceCriteriaEnabled(
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns false
            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert status
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING

            // Verify SAL calls and repo URL extraction
            coVerify(exactly = 1) { salService.getPullRequestDetails(URI.create(pullRequestUrl).toURL()) }
            coVerify(exactly = 1) { salSharedUtil.validateAndExtractPullRequestDetails(pullRequestUrl) }
            coVerify(exactly = 1) { salSharedUtil.createRepoUrl("bitbucket", "bitbucket.org", "test", "test") }
            coVerify(exactly = 1) { autoreviewValidationService.isPullRequestReviewable(PRState.OPEN) }

            // Verify ACRA workflow creation payload
            val requestSalPayloadSlot = slot<AutoreviewSalPayload>()
            coVerify(exactly = 1) {
                autoreviewService.submitIncrementalReview(
                    capture(requestSalPayloadSlot),
                    any(),
                    ReviewTriggerType.MANUAL,
                )
            }
            val prInfo = requestSalPayloadSlot.captured.prDetail
            prInfo.url.toString() shouldBe pullRequestUrl
            prInfo.sourceBranch.name shouldBe "source-branch"
            prInfo.destinationBranch.name shouldBe "destination-branch"
            prInfo.title shouldBe "Test PR"
            prInfo.author.accountId.toString() shouldBe AccountId.of(TEST_AUTHOR_ACCOUNT_ID).toString()
            prInfo.bbcRepoUUID shouldBe repositoryUuid
        }
    }

    @ValueSource(booleans = [true, false])
    @ParameterizedTest
    fun `submitManualReview runs first review using new repository settings if supported type exists`(isSupported: Boolean) =
        runTest {
            // Arrange
            coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns true
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns if (isSupported) listOf(AutoreviewEventType.AUTOREVIEW_MAIN) else emptyList()

            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl) } returns null

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe if (isSupported) AutoreviewProcessStatus.WORKFLOW_PENDING else AutoreviewProcessStatus.ERROR_GENERIC

            coVerify(exactly = 0) { autoreviewValidationService.isRepoAccepted(any(), any(), any()) }
            coVerify(exactly = if (isSupported) 1 else 0) {
                autoreviewService.submitAutoreviewWorkflow(
                    any(),
                    any(),
                    ReviewTriggerType.MANUAL,
                )
            }
        }

    @Test
    fun `submitManualReview will run Acceptance criteria if present in supported types`() =
        runTest {
            // Arrange
            coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns true
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    GraphWorkspaceARI.from(billingGraphWorkspaceId),
                    any(),
                    any(),
                )
            } returns
                listOf(JiraIssueARI.valueOf(TEST_JIRA_ISSUE_ARI))
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN, AutoreviewEventType.ACCEPTANCE_CRITERIA)

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            coVerify(exactly = 1) {
                autoreviewService.submitAcceptanceCriteria(
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `submitManualReview will not run Acceptance criteria if no associated jira issues`() =
        runTest {
            // Arrange
            coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns true
            coEvery { dataDepotPRDedupService.isDuplicatePR(any(), any(), any(), any(), any()) } returns false
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    any(),
                    any(),
                    any(),
                )
            } returns emptyList()

            val associations = slot<List<Association>>()

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            coVerify(exactly = 0) {
                autoreviewService.submitAcceptanceCriteria(
                    any(),
                    any(),
                )
            }

            coVerify(exactly = 1) {
                autoreviewValidationService.getSupportedEventTypes(
                    eventType = AVI_DEVOPS_UPDATED_PULL_REQUEST,
                    eventSettings = any<AutoreviewEventSettings>(),
                    friendlyPullRequestUrl = URI(pullRequestUrl).toURL(),
                    lastUpdatedTimestamp = any(),
                    graphWorkspaceAri = any(),
                    associations = capture(associations),
                    logPayload = any(),
                    transactionContext = any(),
                )
            }

            associations.captured shouldBe emptyList()

            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
        }

    @Test
    fun `Won't run a manual review if AUTOREVIEW_MAIN not found in supportedEventTypes`() =
        runTest {
            coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns true
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.ACCEPTANCE_CRITERIA)

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.WORKFLOW_COMPLETED // returns latest status instead
            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `returns FAILED status when pull request state is unknown`() =
        runTest {
            // Arrange
            val pullRequestUrl = "https://example.com/pull/123"
            val transactionContext = createTestTransactionContext()
            coEvery { salService.getPullRequestDetails(any()) } returns
                PrDetailsEntity(
                    id = 1,
                    title = "Test PR",
                    state = "UNKNOWN",
                    sourceBranch = "source-branch",
                    destinationBranch = "destination-branch",
                    authorAccountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID).toString(),
                )

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.ERROR_GENERIC
            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `throws IllegalArgumentException when pull request details cannot be retrieved`() =
        runTest {
            coEvery { salService.getPullRequestDetails(any()) } returns null

            shouldThrow<IllegalArgumentException> {
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )
            }.message shouldContain "Could not retrieve pull request details"

            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `Should return FAILED when requestor is not PR Author`() =
        runTest {
            // Arrange: PR author is different from requestor
            coEvery { salService.getPullRequestDetails(any()) } returns
                PrDetailsEntity(
                    id = 1,
                    title = "Test PR",
                    state = "OPEN",
                    sourceBranch = "source-branch",
                    destinationBranch = "destination-branch",
                    authorAccountId = "someone-else",
                )

            // Act + Assert
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.ERROR_GENERIC
            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `throws BadRequestException when traceId is missing`() =
        runTest {
            val tx = transactionContext.copy(traceId = null)

            shouldThrow<BadRequestException> {
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    tx,
                )
            }

            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `throws IllegalArgumentException when authorAccountId is missing`() =
        runTest {
            coEvery { salService.getPullRequestDetails(any()) } returns
                PrDetailsEntity(
                    id = 1,
                    title = "Test PR",
                    state = "OPEN",
                    sourceBranch = "source-branch",
                    destinationBranch = "destination-branch",
                    authorAccountId = null,
                )

            shouldThrow<IllegalArgumentException> {
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )
            }

            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `throws IllegalArgumentException when source or destination branch is missing`() =
        runTest {
            coEvery { salService.getPullRequestDetails(any()) } returns
                PrDetailsEntity(
                    id = 1,
                    title = "Test PR",
                    state = "OPEN",
                    sourceBranch = null,
                    destinationBranch = "destination-branch",
                    authorAccountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID).toString(),
                )

            shouldThrow<IllegalArgumentException> {
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )
            }

            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `skips pull request when source commit matches a previous run`() =
        runTest {
            val sourceCommit = "source-commit"
            val prUrl = "https://example.com/pull/123"
            val jobId = "existing-job-id"

            coEvery { salService.getPullRequestDetails(any()) } returns
                PrDetailsEntity(
                    id = 1,
                    title = "Test PR",
                    state = "OPEN",
                    sourceBranch = "source-branch",
                    destinationBranch = "destination-branch",
                    sourceCommit = sourceCommit,
                    authorAccountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID).toString(),
                )
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(prUrl) } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = jobId,
                                createdDate = OffsetDateTime.now().minusHours(1).toString(),
                                sourceCommit = sourceCommit,
                                workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                            ),
                        ),
                )
            coEvery { autoreviewUtilityService.shouldSkipPullRequest(prUrl, sourceCommit, any()) } returns true
            coEvery { autoreviewWorkflowsStorageService.getWorkflowStatusByJobId(jobId, any()) } returns
                WorkflowRunStatus.COMPLETED
            coEvery { featureService.isAutoreviewGetLatestStatusPermissionCheckEnabled(any()) } returns false

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    prUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 1) {
                autoreviewWorkflowsStorageService.getWorkflowStatusByJobId(
                    any(),
                    any(),
                )
            }
            result shouldBe AutoreviewProcessStatus.WORKFLOW_COMPLETED
        }

    @Test
    fun `Should return FAILED when repo is not turned on`() =
        runTest {
            coEvery { autoreviewValidationService.isRepoAccepted(any(), any(), any()) } returns false

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.SETTING_DISABLED_REPOSITORY
            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `Should correctly filter non Bitbucket SCM's`() =
        runTest {
            coEvery { salSharedUtil.determineMatchingScm(any(), any()) } returns githubScm

            shouldThrow<IllegalArgumentException> {
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )
            }

            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `Should return FAILED when CloudActivation is empty`() =
        runTest {
            coEvery { autoreviewValidationService.getCloudActivationByCloudId(any(), any()) } returns
                CloudActivation(
                    CloudIdLike.any(),
                    emptyMap(),
                )
            coEvery { autoreviewValidationService.isWorkspaceAllowedWithoutDevAiActivation(any()) } returns false

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.UNCONFIGURED_SITE_ROVO_DEV
            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `Should return FAILED when CloudActivation contains non relevant id's`() =
        runTest {
            coEvery { autoreviewValidationService.getCloudActivationByCloudId(any(), any()) } returns
                CloudActivation(
                    CloudIdLike.any(),
                    mapOf(
                        "some-fun-thing" to ActivationIds("123457890"),
                    ),
                )
            coEvery { autoreviewValidationService.isWorkspaceAllowedWithoutDevAiActivation(any()) } returns false

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.UNCONFIGURED_SITE_ROVO_DEV
            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Should run when workspace is not allowed without dev ai activation`(acceptanceCriteriaEnabled: Boolean) =
        runTest {
            coEvery { autoreviewValidationService.isWorkspaceAllowedWithoutDevAiActivation(any()) } returns false
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    GraphWorkspaceARI.from(billingGraphWorkspaceId),
                    any(),
                    any(),
                )
            } returns
                listOf(
                    JiraIssueARI.from(billingCloudId, TEST_ISSUE_ID),
                )
            coEvery { autoreviewValidationService.isAcceptanceCriteriaEnabled(any(), any(), any(), any()) } returns
                acceptanceCriteriaEnabled
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN, AutoreviewEventType.ACCEPTANCE_CRITERIA)

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING

            coVerify(exactly = 1) { autoreviewService.submitIncrementalReview(any(), any(), ReviewTriggerType.MANUAL) }
            coVerify(
                exactly =
                    if (acceptanceCriteriaEnabled) 1 else 0,
            ) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Should run when workspace is allowed without dev ai activation`(acceptanceCriteriaEnabled: Boolean) =
        runTest {
            coEvery { autoreviewValidationService.getCloudActivationByCloudId(any(), any()) } returns
                CloudActivation(CloudIdLike.any(), emptyMap())
            coEvery { autoreviewValidationService.isWorkspaceAllowedWithoutDevAiActivation(any()) } returns true
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    GraphWorkspaceARI.from(billingGraphWorkspaceId),
                    any(),
                    any(),
                )
            } returns
                listOf(
                    JiraIssueARI.tryParse(TEST_JIRA_ISSUE_ARI).getOrNull(),
                ).filterNotNull()
            coEvery { autoreviewValidationService.isAcceptanceCriteriaEnabled(any(), any(), any(), any()) } returns
                acceptanceCriteriaEnabled
            coEvery {
                autoreviewValidationService.getCloudActivationByCloudId(
                    CloudIdLike.fromString(billingCloudId),
                    setOf(DEVAI_ACTIVATION_KEY, DEVOPS_ACTIVATION_KEY),
                )
            } returns
                CloudActivation(
                    CloudIdLike.fromString(billingCloudId),
                    mapOf(
                        "devai" to ActivationIds(billingDevAIWorkspaceId, listOf("1", "2", "3")),
                        "devops" to ActivationIds(billingGraphWorkspaceId, listOf("1", "2", "3")),
                    ),
                )
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN, AutoreviewEventType.ACCEPTANCE_CRITERIA)

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            coVerify(exactly = 1) { autoreviewService.submitIncrementalReview(any(), any(), ReviewTriggerType.MANUAL) }
            coVerify(
                exactly =
                    if (acceptanceCriteriaEnabled) 1 else 0,
            ) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `Should handle Exception thrown by getJiraIssueArisByBitbucketPullRequestARI and continue`() =
        runTest {
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    any(),
                    any(),
                    any(),
                )
            } throws
                Exception("Not Good")

            coEvery { autoreviewValidationService.getCloudActivationByCloudId(any(), any()) } returns
                CloudActivation(CloudIdLike.any(), emptyMap())
            coEvery { autoreviewValidationService.isWorkspaceAllowedWithoutDevAiActivation(any()) } returns true
            coEvery { autoreviewValidationService.isAcceptanceCriteriaEnabled(any(), any(), any(), any()) } returns
                true
            coEvery {
                autoreviewValidationService.getCloudActivationByCloudId(
                    CloudIdLike.fromString(billingCloudId),
                    setOf(DEVAI_ACTIVATION_KEY, DEVOPS_ACTIVATION_KEY),
                )
            } returns
                CloudActivation(
                    CloudIdLike.fromString(billingCloudId),
                    mapOf(
                        "devai" to ActivationIds(billingCloudId, listOf("1", "2", "3")),
                        "devops" to ActivationIds(billingCloudId, listOf("1", "2", "3")),
                    ),
                )

            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            coVerify(exactly = 1) { autoreviewService.submitIncrementalReview(any(), any(), ReviewTriggerType.MANUAL) }
        }

    @ParameterizedTest
    @EnumSource(
        value = CreditStatus::class,
        names = [
            "DAILY_LIMIT_EXCEEDED", "MINUTE_LIMIT_EXCEEDED", "INSUFFICIENT_CREDIT",
            "FEATURE_DISABLED_ORG_LEVEL", "FEATURE_DISABLED_SITE_LEVEL", "FEATURE_DISABLED_PAID_ONLY", "PRODUCT_NOT_INSTALLED",
            "FREE_LIMIT_EXCEEDED", "PAID_LIMIT_EXCEEDED", "FEATURE_DISABLED_REPOSITORY_LEVEL", "FEATURE_DISABLED_WORKSPACE_LEVEL",
            "USER_NOT_AUTHORIZED", "USER_BLOCKED", "USER_NOT_AUTHORIZED_FOR_AI", "CLI_DISABLED", "BETA_AI_FEATURES_DISABLED",
            "OK",
        ],
    )
    fun `submitManualReview handles billing check failures correctly`(creditStatus: CreditStatus) =
        runTest {
            // Arrange
            val expectedStatus =
                when (creditStatus) {
                    CreditStatus.DAILY_LIMIT_EXCEEDED,
                    CreditStatus.MINUTE_LIMIT_EXCEEDED,
                    CreditStatus.INSUFFICIENT_CREDIT,
                    -> AutoreviewProcessStatus.BILLING_OUT_OF_CREDIT

                    CreditStatus.FEATURE_DISABLED_ORG_LEVEL,
                    CreditStatus.FEATURE_DISABLED_SITE_LEVEL,
                    CreditStatus.FEATURE_DISABLED_PAID_ONLY,
                    CreditStatus.PRODUCT_NOT_INSTALLED,
                    -> AutoreviewProcessStatus.UNCONFIGURED_SITE_ROVO_DEV

                    CreditStatus.ROVO_DEV_AGENTS_BETA_DEPRECATED -> AutoreviewProcessStatus.BILLING_ROVO_DEV_AGENTS_BETA_DEPRECATED

                    CreditStatus.FREE_LIMIT_EXCEEDED -> AutoreviewProcessStatus.BILLING_FREE_TIER_EXHAUSTED

                    CreditStatus.PAID_LIMIT_EXCEEDED -> AutoreviewProcessStatus.BILLING_PAID_LIMIT_EXCEEDED

                    CreditStatus.FEATURE_DISABLED_REPOSITORY_LEVEL -> AutoreviewProcessStatus.SETTING_DISABLED_REPOSITORY

                    CreditStatus.FEATURE_DISABLED_WORKSPACE_LEVEL -> AutoreviewProcessStatus.SETTING_DISABLED_WORKSPACE

                    CreditStatus.USER_NOT_AUTHORIZED,
                    CreditStatus.USER_NOT_AUTHORIZED_FOR_AI,
                    -> AutoreviewProcessStatus.USER_NOT_AUTHORIZED

                    CreditStatus.USER_BLOCKED,
                    CreditStatus.CLI_DISABLED,
                    CreditStatus.BETA_AI_FEATURES_DISABLED,
                    -> AutoreviewProcessStatus.ERROR_GENERIC

                    CreditStatus.OK -> AutoreviewProcessStatus.WORKFLOW_PENDING

                    else -> throw IllegalArgumentException("Unexpected credit status: $creditStatus")
                }

            coEvery {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            } returns
                UserCreditResult(creditResult = CreditResult(status = creditStatus))

            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN)

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe expectedStatus

            // Verify that entitlement check was called (once for AUTOREVIEW_MAIN and once for CUSTOM)
            coVerify(atLeast = 1) {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            }

            val expectedRuns = if (creditStatus == CreditStatus.OK) 1 else 0

            // Verify that no workflow was submitted when billing check fails
            coVerify(exactly = expectedRuns) { autoreviewService.submitIncrementalReview(any(), any(), any()) }
        }

    @Test
    fun `submitManualReview performs per-type billing checks when feature flag enabled`() =
        runTest {
            // Arrange
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN) // CUSTOM is auto-added alongside MAIN

            coEvery {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            } returns UserCreditResult(creditResult = CreditResult(status = CreditStatus.OK))

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            // Expect two per-type checks: AUTOREVIEW_MAIN and CUSTOM
            coVerify(exactly = 2) {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            }
        }

    @ParameterizedTest
    @EnumSource(
        value = CreditStatus::class,
        names = [
            "DAILY_LIMIT_EXCEEDED", "MINUTE_LIMIT_EXCEEDED", "INSUFFICIENT_CREDIT",
            "FEATURE_DISABLED_ORG_LEVEL", "FEATURE_DISABLED_SITE_LEVEL", "FEATURE_DISABLED_PAID_ONLY", "PRODUCT_NOT_INSTALLED",
            "FREE_LIMIT_EXCEEDED", "PAID_LIMIT_EXCEEDED", "FEATURE_DISABLED_REPOSITORY_LEVEL", "FEATURE_DISABLED_WORKSPACE_LEVEL",
            "USER_NOT_AUTHORIZED", "USER_BLOCKED", "USER_NOT_AUTHORIZED_FOR_AI", "CLI_DISABLED", "BETA_AI_FEATURES_DISABLED",
            "OK",
        ],
    )
    fun `submitManualReview per-type billing checks handle credit statuses`(creditStatus: CreditStatus) =
        runTest {
            // Arrange
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN)

            coEvery {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            } returns UserCreditResult(creditResult = CreditResult(status = creditStatus))

            val expectedStatus =
                when (creditStatus) {
                    CreditStatus.DAILY_LIMIT_EXCEEDED,
                    CreditStatus.MINUTE_LIMIT_EXCEEDED,
                    CreditStatus.INSUFFICIENT_CREDIT,
                    -> AutoreviewProcessStatus.BILLING_OUT_OF_CREDIT

                    CreditStatus.FEATURE_DISABLED_ORG_LEVEL,
                    CreditStatus.FEATURE_DISABLED_SITE_LEVEL,
                    CreditStatus.FEATURE_DISABLED_PAID_ONLY,
                    CreditStatus.PRODUCT_NOT_INSTALLED,
                    -> AutoreviewProcessStatus.UNCONFIGURED_SITE_ROVO_DEV

                    CreditStatus.FREE_LIMIT_EXCEEDED -> AutoreviewProcessStatus.BILLING_FREE_TIER_EXHAUSTED

                    CreditStatus.PAID_LIMIT_EXCEEDED -> AutoreviewProcessStatus.BILLING_PAID_LIMIT_EXCEEDED

                    CreditStatus.FEATURE_DISABLED_REPOSITORY_LEVEL -> AutoreviewProcessStatus.SETTING_DISABLED_REPOSITORY

                    CreditStatus.FEATURE_DISABLED_WORKSPACE_LEVEL -> AutoreviewProcessStatus.SETTING_DISABLED_WORKSPACE

                    CreditStatus.USER_NOT_AUTHORIZED,
                    CreditStatus.USER_NOT_AUTHORIZED_FOR_AI,
                    -> AutoreviewProcessStatus.USER_NOT_AUTHORIZED

                    CreditStatus.USER_BLOCKED,
                    CreditStatus.CLI_DISABLED,
                    CreditStatus.BETA_AI_FEATURES_DISABLED,
                    -> AutoreviewProcessStatus.ERROR_GENERIC

                    CreditStatus.OK -> AutoreviewProcessStatus.WORKFLOW_PENDING

                    else -> throw IllegalArgumentException("Unexpected credit status: $creditStatus")
                }

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe expectedStatus

            // Two checks (AUTOREVIEW_MAIN + CUSTOM)
            coVerify(exactly = 2) {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            }

            val expectedRuns = if (creditStatus == CreditStatus.OK) 1 else 0
            coVerify(exactly = expectedRuns) {
                autoreviewService.submitIncrementalReview(
                    any(),
                    any(),
                    ReviewTriggerType.MANUAL,
                )
            }
        }

    @Test
    fun `submitManualReview filters out ACCEPTANCE_CRITERIA from submitAutoreviewWorkflow payload when AC credit fails`() =
        runTest {
            // Arrange
            coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns true
            // Ensure AC is part of supported types initially
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN, AutoreviewEventType.ACCEPTANCE_CRITERIA)
            // Ensure jira issues exist so AC path is chosen
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    GraphWorkspaceARI.from(billingGraphWorkspaceId),
                    any(),
                    any(),
                )
            } returns listOf(JiraIssueARI.valueOf(TEST_JIRA_ISSUE_ARI))

            // Return credit per experience: MAIN OK, AC non-OK, CUSTOM OK
            coEvery {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            } answers {
                val exp = thirdArg<CodeReviewExperience>()
                val status =
                    when (exp) {
                        CodeReviewExperience.ROVODEV_REVIEW_BITBUCKET -> CreditStatus.OK
                        CodeReviewExperience.ROVODEV_REVIEW_CUSTOM_BITBUCKET -> CreditStatus.OK
                        CodeReviewExperience.ROVODEV_REVIEW_ACCEPTANCE_CRITERIA_BITBUCKET -> CreditStatus.FEATURE_DISABLED_PAID_ONLY
                        else -> CreditStatus.OK
                    }
                UserCreditResult(creditResult = CreditResult(status = status))
            }

            val spy = spyk(autoreviewManualTriggerService)
            val salPayloadSlot = slot<AutoreviewSalPayload>()
            coEvery {
                autoreviewService.submitIncrementalReview(
                    capture(salPayloadSlot),
                    any(),
                    any(),
                )
            } coAnswers { callOriginal() }

            // Act
            val result =
                spy.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            salPayloadSlot.isCaptured shouldBe true
            salPayloadSlot.captured.autoreviewEventTypes shouldContain AutoreviewEventType.AUTOREVIEW_MAIN
            salPayloadSlot.captured.autoreviewEventTypes shouldContain AutoreviewEventType.CUSTOM
            salPayloadSlot.captured.autoreviewEventTypes shouldNotContain AutoreviewEventType.ACCEPTANCE_CRITERIA
        }

    @Test
    fun `submitManualReview filters out CUSTOM from submitAutoreviewWorkflow payload when CUSTOM credit fails`() =
        runTest {
            // Arrange
            coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns true
            // Supported initially only MAIN (CUSTOM auto-added)
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns listOf(AutoreviewEventType.AUTOREVIEW_MAIN)

            // Credit per experience: MAIN OK, CUSTOM non-OK
            coEvery {
                autoreviewValidationService.checkCredit(
                    accountId = any(),
                    cloudId = any(),
                    experience = any(),
                    isExistingBetaUser = any(),
                    friendlyPullRequestUrl = any(),
                    scmWorkspaceId = any(),
                    scmRepositoryId = any(),
                )
            } answers {
                val exp = thirdArg<CodeReviewExperience>()
                val status =
                    when (exp) {
                        CodeReviewExperience.ROVODEV_REVIEW_BITBUCKET -> CreditStatus.OK
                        CodeReviewExperience.ROVODEV_REVIEW_CUSTOM_BITBUCKET -> CreditStatus.FEATURE_DISABLED_PAID_ONLY
                        else -> CreditStatus.OK
                    }
                UserCreditResult(creditResult = CreditResult(status = status))
            }

            val spy = spyk(autoreviewManualTriggerService)
            val salPayloadSlot = slot<AutoreviewSalPayload>()
            coEvery {
                autoreviewService.submitIncrementalReview(
                    capture(salPayloadSlot),
                    any(),
                    any(),
                )
            } coAnswers { callOriginal() }

            // Act
            val result =
                spy.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            salPayloadSlot.isCaptured shouldBe true
            salPayloadSlot.captured.autoreviewEventTypes shouldContain AutoreviewEventType.AUTOREVIEW_MAIN
            salPayloadSlot.captured.autoreviewEventTypes shouldNotContain AutoreviewEventType.CUSTOM
        }

    @Nested
    inner class NonBillingSiteACs {
        val exampleJiraIssueAri = JiraIssueARI.from(nonBillingCloudId, "6543")

        @BeforeEach
        fun setUp() {
            coEvery { autoreviewValidationService.isWorkspaceAllowedWithoutDevAiActivation(any()) } returns false
            coEvery { autoreviewValidationService.isAcceptanceCriteriaEnabled(any(), any(), any(), any()) } returns
                true
            coEvery { autoreviewValidationService.isSiteHasRovodevProduct(any(), any(), any()) } returns true
            coEvery {
                atlassianProxyClient.getIssueDetails(nonBillingCloudId, "6543", any())
            } returns
                Optional.of(
                    IssueDetails(
                        id = "6543",
                        self = "https://jiratest.atlassian.net/rest/api/3/issue/6543",
                        key = "NB-123",
                        fields = Fields(summary = "Test Issue Summary"),
                        renderedFields = RenderedFields(description = "<p>Test Issue description</p>"),
                    ),
                )
            // For all tests the PR is linked to a Jira Issue on the non billing site
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    GraphWorkspaceARI.from(nonBillingGraphWorkspaceId),
                    any(),
                    any(),
                )
            } returns
                listOf(
                    exampleJiraIssueAri,
                )
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should use devAiWorkspaceSettings from same site as graphWorkspaceAri for AC getSupportedTypes check`(
            nonBillingDevAiWorkspaceEnabled: Boolean,
        ) = runTest {
            // Mock DevAi Workspace Settings
            mockDevAiWorkspaceEnabled(billingDevAIWorkspaceId, true)
            mockDevAiWorkspaceEnabled(nonBillingDevAIWorkspaceId, nonBillingDevAiWorkspaceEnabled)

            autoreviewManualTriggerService.submitManualReview(
                pullRequestUrl,
                repositoryUuid,
                bitbucketWorkspaceUuid,
                transactionContext,
            )

            coVerify(exactly = 1) {
                autoreviewValidationService.getSupportedEventTypes(
                    eventType = any(),
                    eventSettings =
                        match {
                            it.devAIWorkspaceSettingValue == nonBillingDevAiWorkspaceEnabled
                        },
                    friendlyPullRequestUrl = any(),
                    lastUpdatedTimestamp = any(),
                    graphWorkspaceAri = ARI.of(GraphWorkspaceARI.from(nonBillingGraphWorkspaceId)),
                    associations = any(),
                    logPayload = any(),
                    transactionContext = any(),
                )
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `should only run AC for non-billing sites that have AC supported type`(nonBillingSiteACEnabled: Boolean) =
            runTest {
                // Both DevAI Workspace Enabled
                mockDevAiWorkspaceEnabled(billingDevAIWorkspaceId, true)
                mockDevAiWorkspaceEnabled(nonBillingDevAIWorkspaceId, true)

                mockSupportedEventTypes(
                    GraphWorkspaceARI.from(nonBillingGraphWorkspaceId),
                    buildList {
                        add(AutoreviewEventType.AUTOREVIEW_MAIN)
                        if (nonBillingSiteACEnabled) {
                            add(AutoreviewEventType.ACCEPTANCE_CRITERIA)
                        }
                    },
                )

                coEvery { autoreviewJiraIssueService.getJiraIssuesBySalPayload(any()) } returns
                    listOf(
                        JiraIssueDetails(
                            issueAri = exampleJiraIssueAri.toString(),
                            key = "NB-123",
                            summary = "Test Issue Summary",
                            description = "Test Issue description",
                        ),
                    )

                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

                coVerify(exactly = if (nonBillingSiteACEnabled) 1 else 0) {
                    autoreviewService.submitAcceptanceCriteria(
                        any(),
                        match {
                            it.map { it.issueAri }.contains(exampleJiraIssueAri.toString())
                        },
                    )
                }
            }

        private fun mockSupportedEventTypes(
            graphWorkspaceARI: GraphWorkspaceARI,
            supportedTypes: List<AutoreviewEventType>,
        ) {
            coEvery {
                autoreviewValidationService.getSupportedEventTypes(
                    any(),
                    any(),
                    any(),
                    any(),
                    ARI.of(graphWorkspaceARI),
                    any(),
                    any(),
                    any(),
                )
            } returns supportedTypes
        }

        private fun mockDevAiWorkspaceEnabled(
            devAiWorkspaceId: String,
            enabled: Boolean,
        ) {
            coEvery {
                autoreviewSettingsService.getDevAiWorkspaceAutoreviewSettingsValue(
                    DevaiWorkspaceARI.from(
                        devAiWorkspaceId,
                    ),
                )
            } returns
                AutoreviewDevAIWorkSpaceSettingAttributes(
                    autoreview_enabled = enabled,
                )
        }
    }

    @Test
    fun `returns SETTING_DISABLED_ROVO_DEV_ORG when billing devai workspace setting value is false`() =
        runTest {
            // Arrange
            coEvery { autoreviewSettingsService.getDevAiWorkspaceAutoreviewSettingsValue(any()) } returns
                AutoreviewDevAIWorkSpaceSettingAttributes(autoreview_enabled = false)

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.SETTING_DISABLED_ROVO_DEV_ORG
            coVerify(exactly = 0) { autoreviewService.submitIncrementalReview(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAutoreviewWorkflow(any(), any()) }
            coVerify(exactly = 0) { autoreviewService.submitAcceptanceCriteria(any(), any()) }
        }

    @Test
    fun `should skip devai workspace setting value check when billing site is null`() =
        runTest {
            // Arrange
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    any(),
                )
            } returns
                SettingValue(
                    value =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = true,
                            autoreview_cloud_id_association = null,
                        ),
                    lastUpdatedTime = OffsetDateTime.now(),
                )
            coEvery { autoreviewValidationService.getEntitlementType(any()) } returns ResourceType.ROVO_DEV_BETA

            // Act
            val result =
                autoreviewManualTriggerService.submitManualReview(
                    pullRequestUrl,
                    repositoryUuid,
                    bitbucketWorkspaceUuid,
                    transactionContext,
                )

            // Assert
            result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
            coVerify(exactly = 0) { autoreviewSettingsService.getDevAiWorkspaceAutoreviewSettingsValue(any()) }
        }

    @Nested
    inner class SubmitThirdPartyPullRequestManualReview {
        val githubPrUrl = "https://github.com/owner/repo/pull/123"
        val githubRepoId = "12345678"
        val graphWorkspaceId = "fe8476b1-3847-4d78-87ca-abf898208d75"
        val graphPrId = "526c4393-98da-4fb6-a44b-5bf2e8842db3"
        val graphPrAri = "ari:cloud:graph::pull-request/activation/$graphWorkspaceId/$graphPrId"

        private fun createGitHubRepositoryIdentifiers(gitHubRepoId: String = githubRepoId) =
            RepositoryIdentifiers(gitHubRepositoryId = gitHubRepoId)

        private fun createPullRequestDataDepotDto(
            ari: String? = graphPrAri,
            prId: String = "123",
        ) = PullRequestObjectDataDepot(
            ari = ari,
            entityType = "pull-request",
            title = "Test PR",
            id = prId,
            url = URI.create(githubPrUrl).toURL(),
            status = "OPEN",
            sequenceNumber = 1L,
            sourceBranch = Branch(name = "feature-branch", url = null),
            destinationBranch = Branch(name = "main", url = null),
            author = User(accountId = TEST_AUTHOR_ACCOUNT_ID),
            lastUpdate = "2024-01-15T10:30:00.000Z",
        )

        private fun createDataDepotResponse(
            pullRequests: List<PullRequestObjectDataDepot> =
                listOf(
                    createPullRequestDataDepotDto(),
                ),
        ) = InternalEntityResult(
            entitiesByType =
                EntityTypesMapResult(
                    pullRequest =
                        EntityTypeEntry(
                            providerType = "unified",
                            containerType = "repository",
                            entities = pullRequests,
                        ),
                ),
        )

        @BeforeEach
        fun setupThirdPartyTests() {
            coEvery { salSharedUtil.validateAndExtractPullRequestDetails(githubPrUrl) } returns
                UrlDetails(
                    scm = githubScm.name,
                    domain = "github.com",
                    workspaceName = "owner",
                    repoSlug = "repo",
                    prId = "123",
                )
            coEvery { salSharedUtil.createRepoUrl(any(), any(), any(), any()) } returns
                "https://github.com/owner/repo"
            coEvery { autoreviewUtilityService.getGraphWorkspaceAri(any()) } returns
                GraphWorkspaceARI.from(graphWorkspaceId)
        }

        @Test
        fun `should successfully submit manual review for third party PR`() =
            runTest {
                // Arrange
                val repositoryIdentifiers = createGitHubRepositoryIdentifiers()
                val prDataDepotDto = createPullRequestDataDepotDto()
                val dataDepotResponse = createDataDepotResponse(listOf(prDataDepotDto))

                coEvery {
                    dataDepotClient.entitiesByThirdPartyAris(any(), any())
                } returns dataDepotResponse

                coEvery { salDataDepotStreamhubEventsHandler.handleEvent(any()) } returns Unit

                // Act
                autoreviewManualTriggerService.submitThirdPartyPullRequestManualReview(
                    pullRequestUrl = githubPrUrl,
                    repositoryIdentifiers = repositoryIdentifiers,
                    transactionContext = transactionContext,
                )

                // Assert
                coVerify(exactly = 1) {
                    dataDepotClient.entitiesByThirdPartyAris(
                        aris = any(),
                        workspaceAri = GraphWorkspaceARI.from(graphWorkspaceId),
                    )
                }
                coVerify(exactly = 1) {
                    salDataDepotStreamhubEventsHandler.handleEvent(any(), true)
                }
            }

        @Test
        fun `should throw IllegalStateException when Data Depot returns no PRs`() =
            runTest {
                // Arrange
                val repositoryIdentifiers = createGitHubRepositoryIdentifiers()
                val emptyDataDepotResponse = createDataDepotResponse(emptyList())

                coEvery {
                    dataDepotClient.entitiesByThirdPartyAris(any(), any())
                } returns emptyDataDepotResponse

                // Act & Assert
                val exception =
                    shouldThrow<IllegalStateException> {
                        autoreviewManualTriggerService.submitThirdPartyPullRequestManualReview(
                            pullRequestUrl = githubPrUrl,
                            repositoryIdentifiers = repositoryIdentifiers,
                            transactionContext = transactionContext,
                        )
                    }

                exception.message shouldContain "Pull request ARI could not be retrieved from Data Depot response"
                coVerify(exactly = 0) { salDataDepotStreamhubEventsHandler.handleEvent(any()) }
            }

        @Test
        fun `should throw IllegalArgumentException when cloudId is null`() =
            runTest {
                // Arrange
                val repositoryIdentifiers = createGitHubRepositoryIdentifiers()
                val transactionContextWithoutCloudId =
                    transactionContext.copy(
                        workspace = transactionContext.workspace.copy(cloudId = null),
                    )

                // Act & Assert
                val exception =
                    shouldThrow<IllegalArgumentException> {
                        autoreviewManualTriggerService.submitThirdPartyPullRequestManualReview(
                            pullRequestUrl = githubPrUrl,
                            repositoryIdentifiers = repositoryIdentifiers,
                            transactionContext = transactionContextWithoutCloudId,
                        )
                    }

                exception.message shouldContain "Cloud ID is required"
                coVerify(exactly = 0) { dataDepotClient.entitiesByThirdPartyAris(any(), any()) }
            }

        @Test
        fun `should use first PR when Data Depot returns multiple PRs`() =
            runTest {
                // Arrange
                val repositoryIdentifiers = createGitHubRepositoryIdentifiers()
                val firstPr = createPullRequestDataDepotDto(ari = graphPrAri)
                val secondPrId =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                val secondPr =
                    createPullRequestDataDepotDto(
                        ari = GraphPullRequestARI.from(graphWorkspaceId, secondPrId).toString(),
                    )
                val dataDepotResponse = createDataDepotResponse(listOf(firstPr, secondPr))

                coEvery {
                    dataDepotClient.entitiesByThirdPartyAris(any(), any())
                } returns dataDepotResponse

                coEvery { salDataDepotStreamhubEventsHandler.handleEvent(any()) } returns Unit

                // Act
                autoreviewManualTriggerService.submitThirdPartyPullRequestManualReview(
                    pullRequestUrl = githubPrUrl,
                    repositoryIdentifiers = repositoryIdentifiers,
                    transactionContext = transactionContext,
                )

                // Assert - should complete successfully using the first PR
                coVerify(exactly = 1) {
                    salDataDepotStreamhubEventsHandler.handleEvent(any(), true)
                }
            }

        @Test
        fun `should create incremental review event type`() =
            runTest {
                // Arrange
                val repositoryIdentifiers = createGitHubRepositoryIdentifiers()
                val prDataDepotDto = createPullRequestDataDepotDto()
                val dataDepotResponse = createDataDepotResponse(listOf(prDataDepotDto))

                coEvery {
                    dataDepotClient.entitiesByThirdPartyAris(any(), any())
                } returns dataDepotResponse

                coEvery { salDataDepotStreamhubEventsHandler.handleEvent(any()) } returns Unit

                // Act
                autoreviewManualTriggerService.submitThirdPartyPullRequestManualReview(
                    pullRequestUrl = githubPrUrl,
                    repositoryIdentifiers = repositoryIdentifiers,
                    transactionContext = transactionContext,
                )

                // Assert
                coVerify(exactly = 1) {
                    salDataDepotStreamhubEventsHandler.handleEvent(any(), true)
                }
            }

        @Test
        fun `should map Jira issue associations from DataDepot response`() =
            runTest {
                // Arrange
                val repositoryIdentifiers = createGitHubRepositoryIdentifiers()
                val jiraIssueAri1 = "ari:cloud:jira:$TEST_CLOUD_ID:issue/1001"
                val jiraIssueAri2 = "ari:cloud:jira:$TEST_CLOUD_ID:issue/1002"
                val nonJiraAri =
                    "ari:cloud:graph::commit/activation/c5b2065b-2125-43be-8aff-3cff01104ba2/cd586e3b-b71b-422d-b25f-a127da96f989"

                val prDataDepotDto =
                    createPullRequestDataDepotDto().copy(
                        associatedWith =
                            Associations(
                                associations =
                                    listOf(
                                        devai.modules.shared.datadepot
                                            .Association(ari = jiraIssueAri1),
                                        devai.modules.shared.datadepot
                                            .Association(ari = jiraIssueAri2),
                                        devai.modules.shared.datadepot
                                            .Association(ari = nonJiraAri),
                                    ),
                            ),
                    )
                val dataDepotResponse = createDataDepotResponse(listOf(prDataDepotDto))

                coEvery {
                    dataDepotClient.entitiesByThirdPartyAris(any(), any())
                } returns dataDepotResponse

                coEvery { salDataDepotStreamhubEventsHandler.handleEvent(any(), any()) } returns Unit

                // Act
                autoreviewManualTriggerService.submitThirdPartyPullRequestManualReview(
                    pullRequestUrl = githubPrUrl,
                    repositoryIdentifiers = repositoryIdentifiers,
                    transactionContext = transactionContext,
                )

                // Assert
                coVerify(exactly = 1) {
                    salDataDepotStreamhubEventsHandler.handleEvent(
                        withArg { event ->
                            val payloadStr = event.payload.toString()
                            payloadStr shouldContain jiraIssueAri1
                            payloadStr shouldContain jiraIssueAri2
                            payloadStr.contains(nonJiraAri) shouldBe false
                        },
                        true,
                    )
                }
            }

        @Test
        fun `should handle empty associations from DataDepot`() =
            runTest {
                // Arrange
                val repositoryIdentifiers = createGitHubRepositoryIdentifiers()
                val prDataDepotDto = createPullRequestDataDepotDto().copy(associatedWith = null)
                val dataDepotResponse = createDataDepotResponse(listOf(prDataDepotDto))

                coEvery {
                    dataDepotClient.entitiesByThirdPartyAris(any(), any())
                } returns dataDepotResponse

                coEvery { salDataDepotStreamhubEventsHandler.handleEvent(any(), any()) } returns Unit

                // Act
                autoreviewManualTriggerService.submitThirdPartyPullRequestManualReview(
                    pullRequestUrl = githubPrUrl,
                    repositoryIdentifiers = repositoryIdentifiers,
                    transactionContext = transactionContext,
                )

                // Assert
                coVerify(exactly = 1) {
                    salDataDepotStreamhubEventsHandler.handleEvent(any(), true)
                }
            }
    }
}
