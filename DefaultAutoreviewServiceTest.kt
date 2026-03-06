package devai.modules.autoreview.service

import com.atlassian.ari.principled.ARI
import com.atlassian.ari.principled.CloudIdLike
import com.atlassian.ari.principled.bitbucket.BitbucketPullrequestARI
import com.atlassian.ari.principled.bitbucket.BitbucketWorkspaceARI
import com.atlassian.ari.principled.devai.DevaiWorkspaceARI
import com.atlassian.ari.principled.graph.GraphWorkspaceARI
import com.atlassian.ari.principled.jira.JiraIssueARI
import com.atlassian.usercontext.api.AccountId
import com.atlassian.usercontext.api.AccountType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import devai.modules.acra.client.AcraClient
import devai.modules.acra.client.DEV_AI_WORKSPACE_ID
import devai.modules.acra.shared.client.AcraClientCustomHeaders
import devai.modules.acra.shared.client.ClientException
import devai.modules.acra.shared.model.AcraClientCommentRankerAnalyticResponse
import devai.modules.acra.shared.model.AcraClientWorkflowCompletedAnalyticResponse
import devai.modules.acra.shared.model.AcraCreateWorkflowRunRequest
import devai.modules.acra.shared.model.AcraCreateWorkflowRunResponse
import devai.modules.acra.shared.model.AcraWorkflowRunLinkResponse
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_ACRA_MINI_GENERATOR_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_ACRA_MINI_NEMO_VERSION
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_ACRA_MINI_USE_FACTUAL_CORRECTNESS
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CODE_SUGGESTIONS_ALLOW_ONE_LINE_REPLACED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CODE_SUGGESTIONS_CLASSIFICATION_MODEL_ID
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CODE_SUGGESTIONS_DISABLED_REPOSITORIES
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CODE_SUGGESTIONS_GENERATION_MODEL_ID
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CODE_SUGGESTIONS_MAX_LINES_ALLOWED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_COMMENT_RANKER_THRESHOLD
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CONTEXT7_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CONTEXT_ENGINEERING_MODEL
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CUSTOM_GENERATOR_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_CUSTOM_INSTRUCTIONS_ANNOTATOR_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_ENABLE_CODE_SUGGESTIONS
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_ENABLE_CODE_SUGGESTIONS_RATIONALE
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_ENABLE_USING_JIRA_CONTEXT
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_EXPERIMENTAL_COMMENT_RANKER_THRESHOLD
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_GENERAL_GENERATOR_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_GENERATION_USE_REASONING
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_NEMO_GENERATOR_FILE_NAME
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_NEMO_RECENT_PRS_CONTEXT_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_NEMO_SIMILAR_ISSUE_DIFFS_CONTEXT_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_NEMO_SIMILAR_ISSUE_PR_COMMENTS_CONTEXT_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_USER_PROMPT_VERSION_IN_CODING_STANDARDS
import devai.modules.acra.shared.model.ExecutionFlagNames.CODE_SUGGESTIONS_MAX_LLM_INTERACTIONS_PER_LLM_SESSION
import devai.modules.acra.shared.model.ExecutionFlagNames.MODEL_IN_COMMENT_ANNOTATION
import devai.modules.acra.shared.model.ExecutionFlagNames.MODEL_IN_COMMENT_GENERATION
import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.model.WorkflowRun
import devai.modules.acra.shared.model.WorkflowRunArtifact
import devai.modules.acra.shared.model.WorkflowRunQueueType
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames.AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames.AUTOREVIEW_CUSTOM_PROMPT_JSON
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames.EXECUTION_FLAGS_JSON
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames.PULL_REQUEST_INFO_JSON
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames.SETUP_REPO_JSON
import devai.modules.acra.shared.model.artifacts.ArtifactType
import devai.modules.acra.shared.model.artifacts.AutoreviewAcceptanceCriteriaArtifact
import devai.modules.acra.shared.model.artifacts.AutoreviewAcceptanceCriterion
import devai.modules.acra.shared.model.artifacts.AutoreviewAcceptanceCriterionStatus
import devai.modules.acra.shared.model.artifacts.AutoreviewCustomPromptArtifact
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentItem
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentsArtifact
import devai.modules.acra.shared.model.artifacts.CommentRankerScore
import devai.modules.acra.shared.model.artifacts.ExecutionFlagsArtifact
import devai.modules.acra.shared.model.artifacts.JiraIssue
import devai.modules.acra.shared.model.artifacts.PullRequestInfoArtifact
import devai.modules.acra.shared.model.artifacts.SetupRepoArtifact
import devai.modules.acra.shared.model.codereview.CodeReviewCommentChangeType
import devai.modules.acra.shared.model.codereview.CodeReviewCommentGeneratedBy
import devai.modules.acra.shared.model.codereview.CustomPrompt
import devai.modules.autoreview.model.ACRAPullRequestResponse
import devai.modules.autoreview.model.AutoreviewCodeSuggestionRequest
import devai.modules.autoreview.model.AutoreviewJobDetails
import devai.modules.autoreview.model.AutoreviewOneTimeRun
import devai.modules.autoreview.model.AutoreviewOneTimeRunsPantryItem
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriteria
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriterion
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriterionStatus
import devai.modules.autoreview.model.AutoreviewPantryItem
import devai.modules.autoreview.model.AutoreviewPantryStatus
import devai.modules.autoreview.model.AutoreviewProcessStatus
import devai.modules.autoreview.model.AutoreviewPullRequestComment
import devai.modules.autoreview.model.AutoreviewWorkflow
import devai.modules.autoreview.model.CodeSuggestionPullRequest
import devai.modules.autoreview.model.JiraIssueDetails
import devai.modules.autoreview.model.WorkflowRunStatus
import devai.modules.autoreview.queue.model.AutoreviewCodeSuggestionSalPayload
import devai.modules.autoreview.queue.model.AutoreviewSalPayload
import devai.modules.autoreview.queue.model.AutoreviewSalPayloadType
import devai.modules.autoreview.queue.model.Branch
import devai.modules.autoreview.queue.model.PRDetail
import devai.modules.autoreview.queue.model.PRState
import devai.modules.autoreview.queue.model.PullRequestAuthor
import devai.modules.autoreview.queue.model.ReviewComment
import devai.modules.autoreview.service.CodingStandardsExtractorCompletedAction.Companion.CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW
import devai.modules.autoreview.service.DefaultAutoreviewService.Companion.getAcraClientCustomHeaders
import devai.modules.autoreview.service.DefaultAutoreviewWorkflowsStorageService.Companion.RELEVANT_WORKFLOWS_FOR_STATUS_CHECKS
import devai.modules.autoreview.utils.isRepoEnabledForIncrementalReview
import devai.modules.autoreview.utils.selectSuitableJiraIssues
import devai.modules.sal.service.BitbucketService
import devai.modules.sal.service.SalService
import devai.modules.settings.model.AutoreviewDevAIWorkSpaceSettingAttributes
import devai.modules.settings.model.AutoreviewRepositorySettingAttributes
import devai.modules.settings.model.AutoreviewWorkspaceSettingAttributes
import devai.modules.settings.model.SettingValue
import devai.modules.settings.service.AutoreviewSettingsService
import devai.modules.shared.analytics.service.AutoreviewAnalyticsService
import devai.modules.shared.client.AtlassianProxyClient
import devai.modules.shared.client.ExpandedIssueDetails
import devai.modules.shared.client.Fields
import devai.modules.shared.client.IssueDetails
import devai.modules.shared.client.RenderedFields
import devai.modules.shared.client.idgatekeeper.IdGatekeeperClient
import devai.modules.shared.client.idgatekeeper.MintUctResponse
import devai.modules.shared.client.idgatekeeper.ResourceType
import devai.modules.shared.client.streamhub.Association
import devai.modules.shared.client.streamhub.AutoreviewEventType
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.AVI_DEVOPS_UPDATED_PULL_REQUEST
import devai.modules.shared.model.AutoreviewAgentExperimentConfig
import devai.modules.shared.model.AutoreviewCodeSuggestionsConfig
import devai.modules.shared.model.BadRequestException
import devai.modules.shared.model.BaseIntegrationsServiceResponse
import devai.modules.shared.model.CodeReviewExperience
import devai.modules.shared.model.CommentContent
import devai.modules.shared.model.CommentCreatedEntity
import devai.modules.shared.model.CommentEntity
import devai.modules.shared.model.CommentInline
import devai.modules.shared.model.CommentUser
import devai.modules.shared.model.CreditResult
import devai.modules.shared.model.CreditStatus
import devai.modules.shared.model.GLOBAL_WORKSPACE_ARI
import devai.modules.shared.model.ParentCommentEntity
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
import io.kotest.assertions.extracting
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import reactor.core.publisher.Mono
import java.net.URI
import java.net.URL
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream
import kotlin.jvm.optionals.getOrNull
import devai.modules.acra.shared.model.WorkflowRunStatus as AcraWorkflowRunStatus

@ExtendWith(MockKExtension::class)
class DefaultAutoreviewServiceTest {
    companion object {
        const val TEST_PR_URL = "https://bitbucket.org/workspace/repo/pull-requests/1"
        const val TEST_REPO_URL = "https://bitbucket.org/workspace/repo"
        const val TEST_DEFAULT_AI_WORKSPACE_ID = DEV_AI_WORKSPACE_ID
        const val TEST_DEVAI_WORKSPACE_ARI = "ari:cloud:devai::workspace/00000000-0000-0000-0000-000000000000"
        const val TEST_RESOURCE_ARI =
            "ari:cloud:graph::pull-request/activation/6f3d1ef0-0840-11eb-8870-0e3e7a21a911/526c4393-98da-4fb6-a44b-5bf2e8842db3"
        const val TEST_AUTHOR_ACCOUNT_ID = "123456789013"
        const val JIRA_ISSUE_ASSOCIATION_TYPE = "jira:issue"
        const val TEST_CLOUD_ID = "922168f0-256f-49e0-ac04-4db48b68d2ea"
        const val TEST_ISSUE_ID = "3456"
        const val TEST_ISSUE_KEY = "TEST-3456"
        const val TEST_JIRA_ISSUE_ARI = "ari:cloud:jira:$TEST_CLOUD_ID:issue/$TEST_ISSUE_ID"
        const val TEST_ISSUE_SELF = "https://jiratest.atlassian.net/rest/api/3/issue/$TEST_ISSUE_ID"

        private val TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE =
            AcraCreateWorkflowRunResponse(
                workflowRunId = UUID.fromString("00000000-0000-0000-0000-000000001234"),
                status = AcraWorkflowRunStatus.PENDING,
                rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                queueType = WorkflowRunQueueType.DEFAULT,
            )
        private val TEST_ACRA_PULL_REQUEST_RESPONSE =
            ACRAPullRequestResponse(
                id = UUID.randomUUID().toString(),
                currentWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value,
                state = "PENDING",
                status = AcraWorkflowRunStatus.PENDING.value,
                repoUrl = TEST_REPO_URL,
            )
        private const val TEST_WORKFLOW_RUN_ID = "e7d1c16b-0208-4a4c-a19e-547ac140d852"
        private const val TEST_BITBUCKET_PULL_REQUEST_URL = "https://bitbucket.org/test/pull-requests/1"
        private const val TEST_AR_ID = "testId"
        private const val ATLASSIAN_BITBUCKET_WORKSPACE_UUID = "02b941e3-cfaa-40f9-9a58-cec53e20bdc3"
        private const val TEST_BITBUCKET_WORKSPACE_UUID = "{02b941e3-cfaa-40f9-9a58-aec53e20bdc4}"
        private const val TEST_GITHUB_WORKSPACE_URL = "https://github.com/testworkspace"

        @JvmStatic
        fun autoreviewEventTypesInput(): Stream<Arguments> =
            Stream.of(
                Arguments.of(emptyList<AutoreviewEventType>(), true),
                Arguments.of(emptyList<AutoreviewEventType>(), false),
                Arguments.of(listOf(AutoreviewEventType.AUTOREVIEW_MAIN), true),
                Arguments.of(listOf(AutoreviewEventType.AUTOREVIEW_MAIN), false),
                Arguments.of(listOf(AutoreviewEventType.ACCEPTANCE_CRITERIA), true),
                Arguments.of(listOf(AutoreviewEventType.ACCEPTANCE_CRITERIA), false),
                Arguments.of(
                    listOf(AutoreviewEventType.AUTOREVIEW_MAIN, AutoreviewEventType.ACCEPTANCE_CRITERIA),
                    true,
                ),
                Arguments.of(
                    listOf(AutoreviewEventType.AUTOREVIEW_MAIN, AutoreviewEventType.ACCEPTANCE_CRITERIA),
                    false,
                ),
            )

        @JvmStatic
        fun workflowTypeTestData(): Stream<Arguments> = RELEVANT_WORKFLOWS_FOR_STATUS_CHECKS.map { Arguments.of(it.value) }.stream()
    }

    private val acraClient = mockk<AcraClient>(relaxed = true)
    private val salService = mockk<SalService>(relaxed = true)
    private val atlassianProxyClient = mockk<AtlassianProxyClient>(relaxed = true)
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val autoreviewAnalyticsService = mockk<AutoreviewAnalyticsService>(relaxed = true)
    private val autoreviewValidationService = mockk<AutoreviewValidationService>(relaxed = true)
    private val autoreviewWorkflowsStorageService = mockk<AutoreviewWorkflowsStorageService>(relaxed = true)
    private val idGatekeeperClient = mockk<IdGatekeeperClient>(relaxed = true)
    private val bitbucketService = mockk<BitbucketService>(relaxed = true)
    private val salSharedUtil = mockk<SalSharedUtil>(relaxed = true)
    private val transactionContext = mockk<TransactionContext>(relaxed = true)
    private lateinit var autoreviewService: DefaultAutoreviewService
    private val allowlistedWorkspaces = listOf(TEST_GITHUB_WORKSPACE_URL, "https://github.com/otherworkspace")
    private val autoreviewSettingsService = mockk<AutoreviewSettingsService>(relaxed = true)
    private val autoreviewJiraIssueService = mockk<AutoreviewJiraIssueService>(relaxed = true)
    private val autoreviewUtilityService = mockk<AutoreviewUtilityService>(relaxed = true)
    private val dataDepotPRDedupService = mockk<DataDepotPRDedupService>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val commentFormatter = mockk<ReviewCommentFormatterService>(relaxed = true)

    @BeforeEach
    fun setUp() {
        autoreviewService =
            DefaultAutoreviewService(
                acraClient,
                atlassianProxyClient,
                featureService,
                salService,
                autoreviewAnalyticsService,
                autoreviewValidationService,
                autoreviewWorkflowsStorageService,
                idGatekeeperClient,
                salSharedUtil,
                autoreviewSettingsService,
                autoreviewJiraIssueService,
                autoreviewUtilityService,
                objectMapper,
            )
        coEvery {
            acraClient.submitAcraWorkflowRun(any(), any())
        } returns Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)

        coEvery {
            atlassianProxyClient.getIssueDetails(TEST_CLOUD_ID, TEST_ISSUE_ID, TEST_AUTHOR_ACCOUNT_ID)
        } returns
            Optional.of(
                IssueDetails(
                    id = TEST_ISSUE_ID,
                    self = TEST_ISSUE_SELF,
                    key = TEST_ISSUE_KEY,
                    fields = Fields(summary = "Test Issue Summary"),
                    renderedFields = RenderedFields(description = "<p>Test Issue description</p>"),
                ),
            )
        coEvery { autoreviewValidationService.getCloudIdByDevAiWorkspaceAri(any()) } returns
            CloudIdLike.fromString(
                TEST_CLOUD_ID,
            )
        coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(any()) } returns allowlistedWorkspaces
        coEvery { featureService.getAutoreviewAcceptanceCriteriaReviewOverrideModel(any()) } returns ""
        coEvery { featureService.getAutoreviewAcceptanceCriteriaExtractionOverrideModel(any()) } returns ""
        coEvery { autoreviewJiraIssueService.getJiraIssuesBySalPayload(any()) } returns
            listOf(
                JiraIssueDetails(
                    issueAri = TEST_JIRA_ISSUE_ARI,
                    description = "Test description",
                    summary = "Test summary",
                ),
            )

        val urlDetails =
            UrlDetails(
                scm = "bitbucket",
                domain = "bitbucket.org",
                workspaceName = "test",
                repoSlug = "test",
            )
        coEvery { salSharedUtil.validateAndExtractRepoDetails(any()) } returns urlDetails
        coEvery {
            autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                SettingContainerType.WORKSPACE,
                any(),
            )
        } returns null
        coEvery {
            autoreviewSettingsService.getDevAiWorkspaceAutoreviewSettings(
                SettingContainerType.DEVAI_WORKSPACE,
                any(),
            )
        } returns
            SettingValue(
                value =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    ),
                lastUpdatedTime = OffsetDateTime.now(),
            )

        // Mock salService.createPrComment for postPreCheckErrorComment tests
        coEvery { salService.createPrComment(any()) } returns
            BaseIntegrationsServiceResponse(
                operationType = "CREATE",
                operationStatus = "SUCCESS",
                entityType = "comment",
                entities =
                    listOf(
                        CommentCreatedEntity(id = 2030L),
                    ),
            )

        // By default just return comments without anything appended
        coEvery { commentFormatter.appendFooters(any(), any(), any()) } answers { firstArg<List<ReviewComment>>() }
        every { commentFormatter.mergeComments(any()) } answers { firstArg() }
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `submitAutoreviewWorkflow successfully without associations with FF off`() {
        runTest {
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = emptyList(),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )

            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            verify(exactly = 1) {
                autoreviewAnalyticsService.sendAutoreviewUnifiedAIInitiatedEvent(any(), any())
            }

            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(any(), any())
            }
        }
    }

    @Test
    fun `submitAutoreviewWorkflow should have empty fields and customFields, even when expanded issue details were retrieved`() {
        // Arrange
        coEvery {
            atlassianProxyClient.getIssueDetailsIncludingCustomFields(
                TEST_CLOUD_ID,
                TEST_ISSUE_ID,
                TEST_AUTHOR_ACCOUNT_ID,
            )
        } returns
            Optional.of(
                ExpandedIssueDetails(
                    id = TEST_ISSUE_ID,
                    self = TEST_ISSUE_SELF,
                    key = TEST_ISSUE_KEY,
                    fields =
                        mapOf(
                            "description" to "<p>Test Issue description</p>",
                            "customfield_1000" to "this is custom field value 1001",
                            "customfield_1001" to "",
                            "customfield_1002" to null,
                            "customField_1000" to "invalid custom field",
                            "summary" to "Test Issue Summary",
                        ),
                    renderedFields = mapOf("description" to "<p>Test Issue description</p>"),
                ),
            )

        val acraPayloadCaptor = slot<AcraCreateWorkflowRunRequest>()

        coEvery {
            acraClient.submitAcraWorkflowRun(capture(acraPayloadCaptor), any())
        } returns Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)

        // Act
        runTest {
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations =
                        listOf(
                            Association(
                                type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                ari = TEST_JIRA_ISSUE_ARI,
                            ),
                        ),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )

            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload, expandedDetails = true)

            autoreviewService.submitAutoreviewWorkflow(salPayload, jiraIssues)

            // Assert
            (acraPayloadCaptor.captured.input.artifacts[PULL_REQUEST_INFO_JSON] as PullRequestInfoArtifact).jiraIssues shouldBe
                listOf(
                    JiraIssue(
                        issueAri = "ari:cloud:jira:${TEST_CLOUD_ID}:issue/${TEST_ISSUE_ID}",
                        description = "<p>Test Issue description</p>",
                        summary = "Test Issue Summary",
                        self = "https://jiratest.atlassian.net/rest/api/3/issue/3456",
                        key = "TEST-3456",
                        fields = emptyMap(),
                    ),
                )
            coVerify(exactly = 0) {
                atlassianProxyClient.getIssueDetails(
                    TEST_CLOUD_ID,
                    TEST_ISSUE_ID,
                    TEST_AUTHOR_ACCOUNT_ID,
                )
            }
        }
    }

    @Test
    fun `submitAutoreviewWorkflow should contain empty list of issues when get issue details returns no issue details `() {
        // Arrange
        coEvery {
            atlassianProxyClient.getIssueDetails(TEST_CLOUD_ID, TEST_ISSUE_ID, TEST_AUTHOR_ACCOUNT_ID)
        } returns
            Optional.empty()

        val acraPayloadCaptor = slot<AcraCreateWorkflowRunRequest>()

        coEvery {
            acraClient.submitAcraWorkflowRun(capture(acraPayloadCaptor), any())
        } returns Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)

        // Act
        runTest {
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations =
                        listOf(
                            Association(
                                type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                ari = TEST_JIRA_ISSUE_ARI,
                            ),
                        ),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )

            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)

            autoreviewService.submitAutoreviewWorkflow(salPayload, jiraIssues)

            // Assert
            (acraPayloadCaptor.captured.input.artifacts[PULL_REQUEST_INFO_JSON] as PullRequestInfoArtifact).jiraIssues shouldBe emptyList()
            coVerify(
                exactly = 0,
            ) {
                atlassianProxyClient.getIssueDetailsIncludingCustomFields(
                    TEST_CLOUD_ID,
                    TEST_ISSUE_ID,
                    TEST_AUTHOR_ACCOUNT_ID,
                )
            }
        }
    }

    @Test
    fun `submitAutoreviewWorkflow successfully with associations`() {
        runTest {
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations =
                        listOf(
                            Association(
                                type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                ari = TEST_JIRA_ISSUE_ARI,
                            ),
                        ),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )

            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
            autoreviewService.submitAutoreviewWorkflow(salPayload, jiraIssues)

            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(any(), any())
            }
            coVerify(exactly = 1) {
                atlassianProxyClient.getIssueDetails(
                    TEST_CLOUD_ID,
                    TEST_ISSUE_ID,
                    TEST_AUTHOR_ACCOUNT_ID,
                )
            }
        }
    }

    @Test
    fun `submitAutoreviewWorkflow successfully without associations`() {
        val salPayload =
            createSalAutoreviewPayload(
                prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                associations = emptyList(),
                workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
            )
        coEvery {
            atlassianProxyClient.getIssueDetails(TEST_CLOUD_ID, TEST_ISSUE_ID, TEST_AUTHOR_ACCOUNT_ID)
        } returns Optional.empty()

        runTest {
            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)

            autoreviewService.submitAutoreviewWorkflow(salPayload, jiraIssues)

            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(any(), any())
            }
            coVerify(exactly = 0) {
                atlassianProxyClient.getIssueDetails(
                    TEST_CLOUD_ID,
                    TEST_ISSUE_ID,
                    TEST_AUTHOR_ACCOUNT_ID,
                )
            }
        }
    }

    @ValueSource(strings = ["", " ", "123", TEST_DEFAULT_AI_WORKSPACE_ID, "{$TEST_DEFAULT_AI_WORKSPACE_ID}", "atlassian-mikebuller"])
    @ParameterizedTest
    fun `submitAutoreviewWorkflow successfully with different workspaceId values in transactionContext`(workspaceId: String) {
        runTest {
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    workspaceId = workspaceId,
                )

            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(any(), any())
            }
        }
    }

    @Test
    fun `submitAutoreviewWorkflow successfully with devai workspace ari in transactionContext`() =
        runTest {
            // Arrange
            val salPayload = createSalAutoreviewPayload(workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID)
            val requestHeadersSlot = slot<AcraClientCustomHeaders>()

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            // Assert
            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(any(), capture(requestHeadersSlot))
            }

            requestHeadersSlot.captured.workspaceAri shouldBe TEST_DEVAI_WORKSPACE_ARI
        }

    @Test
    fun `submitAutoreviewWorkflow successfully with cloudId in transactionContext`() =
        runTest {
            // Arrange
            val salPayload =
                createSalAutoreviewPayload(
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    cloudId = CloudIdLike.fromString(TEST_CLOUD_ID),
                )
            val requestHeadersSlot = slot<AcraClientCustomHeaders>()

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            // Assert
            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(any(), capture(requestHeadersSlot))
            }

            requestHeadersSlot.captured.cloudId shouldBe TEST_CLOUD_ID
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `submitAutoreviewWorkflow successful with single and multi-line CS due to enabled repo`(isEnabled: Boolean) {
        runTest {
            // Arrange
            val repository = "https://bitbucket.org/mikebuller/autoreview-test"
            val salPayload =
                createSalAutoreviewPayload(
                    workspaceId = ATLASSIAN_BITBUCKET_WORKSPACE_UUID,
                    repositoryUrl = URI.create(repository).toURL(),
                )
            coEvery { featureService.isAutoreviewCodeSuggestionsEnabled(any()) } returns isEnabled
            coEvery { featureService.getAutoreviewCodeSuggestionsConfig(any(), any()) } returns
                AutoreviewCodeSuggestionsConfig(isMultilineEnabled = isEnabled)

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(
                exactly = 1,
            ) {
                acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any())
            }
            val executionFlags =
                (requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact).flags

            // Assert

            if (isEnabled) {
                executionFlags[AUTOREVIEW_ENABLE_CODE_SUGGESTIONS] shouldBe true
                executionFlags[AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED] shouldBe true
            } else {
                executionFlags[AUTOREVIEW_ENABLE_CODE_SUGGESTIONS] shouldBe false
                executionFlags[AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED] shouldBe false
            }
        }
    }

    @Test
    fun `submitAutoreviewWorkflow successful with single and multi-line CS enabled due to valid repo despite invalid workspaceId`() {
        runTest {
            // Arrange
            val repository = "https://bitbucket.org/mikebuller/autoreview-test"
            val salPayload =
                createSalAutoreviewPayload(
                    workspaceId = "InvalidWorkspaceId",
                    repositoryUrl = URI.create(repository).toURL(),
                )
            coEvery { featureService.isAutoreviewCodeSuggestionsEnabled(any()) } returns true
            coEvery { featureService.getAutoreviewCodeSuggestionsConfig(any(), any()) } returns
                AutoreviewCodeSuggestionsConfig(isMultilineEnabled = true)

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(
                exactly = 1,
            ) {
                acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any())
            }
            val executionFlags =
                (requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact).flags

            // Assert
            executionFlags[AUTOREVIEW_ENABLE_CODE_SUGGESTIONS] shouldBe true
            executionFlags[AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED] shouldBe true
        }
    }

    @Test
    fun `submitAutoreviewWorkflow successful with single and multi-line CS disabled due to FG off despite valid repo and workspaceId`() {
        runTest {
            // Arrange
            val repository = "https://bitbucket.org/mikebuller/autoreview-test"
            val salPayload =
                createSalAutoreviewPayload(
                    workspaceId = ATLASSIAN_BITBUCKET_WORKSPACE_UUID,
                    repositoryUrl = URI.create(repository).toURL(),
                )
            coEvery { featureService.isAutoreviewCodeSuggestionsEnabled(any()) } returns false
            coEvery { featureService.isAutoreviewCodeSuggestionsEnabled(any()) } returns false
            coEvery { featureService.getAutoreviewCodeSuggestionsConfig(any(), any()) } returns
                AutoreviewCodeSuggestionsConfig(isMultilineEnabled = false)

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(
                exactly = 1,
            ) {
                acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any())
            }
            val executionFlags =
                (requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact).flags

            // Assert
            executionFlags[AUTOREVIEW_ENABLE_CODE_SUGGESTIONS] shouldBe false
            executionFlags[AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED] shouldBe false
        }
    }

    @ValueSource(booleans = [true, false])
    @ParameterizedTest
    fun `submitAutoreviewWorkflow workflow successfully with a CS for Github repo when enabled`(isCodeSuggestionsEnabled: Boolean) {
        runTest {
            // Arrange
            val repository = "https://github.com/organisation/repository"
            val salPayload =
                createSalAutoreviewPayload(
                    workspaceId = ATLASSIAN_BITBUCKET_WORKSPACE_UUID,
                    repositoryUrl = URI.create(repository).toURL(),
                )
            coEvery { featureService.isAutoreviewCodeSuggestionsEnabled(any()) } returns isCodeSuggestionsEnabled

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(
                exactly = 1,
            ) {
                acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any())
            }
            val codeSuggestionExecutionFlag =
                (requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact)
                    .flags[AUTOREVIEW_ENABLE_CODE_SUGGESTIONS]

            // Assert
            if (isCodeSuggestionsEnabled) {
                codeSuggestionExecutionFlag shouldBe true
            } else {
                codeSuggestionExecutionFlag shouldBe false
            }
        }
    }

    @ValueSource(strings = ["123", TEST_AUTHOR_ACCOUNT_ID])
    @ParameterizedTest
    fun `submitAutoreviewWorkflow successfully with different accountId values in transactionContext`(accountId: String) {
        runTest {
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = accountId,
                    associations =
                        listOf(
                            Association(
                                type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                ari = TEST_JIRA_ISSUE_ARI,
                            ),
                        ),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )

            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(any(), any())
            }
        }
    }

    @ValueSource(strings = ["", " "])
    @ParameterizedTest
    fun `creating sal autoreview payload with blank account id should throw`(accountId: String) {
        runTest {
            shouldThrow<IllegalArgumentException> {
                createSalAutoreviewPayload(
                    prAuthorAccountId = accountId,
                    associations =
                        listOf(
                            Association(
                                type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                ari = TEST_JIRA_ISSUE_ARI,
                            ),
                        ),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )
            }
        }
    }

    @Test
    fun `submitAutoreviewWorkflow stores pull request association in Pantry`() {
        val sourceCommit = "sourceCommit"
        val salPayload =
            createSalAutoreviewPayload(
                prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                associations = emptyList(),
                sourceCommit = sourceCommit,
                workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
            )
        coEvery {
            atlassianProxyClient.getIssueDetails(TEST_CLOUD_ID, TEST_ISSUE_ID, TEST_AUTHOR_ACCOUNT_ID)
        } returns Optional.empty()

        runTest {
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())
            coVerify(exactly = 1) {
                autoreviewWorkflowsStorageService.storeAutoreviewWorkflows(
                    pullRequestUrl = salPayload.prDetail.url.toString(),
                    workflowId = TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE.workflowRunId.toString(),
                    rootWorkflowName = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                    sourceCommit = sourceCommit,
                    issuesHash = "e56a5c68-e9bd-3b27-9bc9-1341fc0caa9f",
                    workspaceAri = ARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                )
            }
        }
    }

    @Test
    fun `submitAcceptanceCriteria hashes issues and stores pull request in Pantry`() {
        runTest {
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            val sourceCommit = "sourceCommit"
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )
            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)

            autoreviewService.submitAcceptanceCriteria(salPayload, jiraIssues)
            coVerify(exactly = 1) {
                autoreviewWorkflowsStorageService.storeAutoreviewWorkflows(
                    pullRequestUrl = salPayload.prDetail.url.toString(),
                    workflowId = workflowRunId.toString(),
                    rootWorkflowName = rootWorkflow,
                    sourceCommit = sourceCommit,
                    issuesHash = "62504c9e-f07d-369e-80d1-033d75720947",
                    workspaceAri = ARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                )
            }
        }
    }

    @Test
    fun `submitAcceptanceCriteria creates acceptance criteria artifact from storage on retrigger`() {
        runTest {
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            val sourceCommit = "sourceCommit"
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )
            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = TEST_WORKFLOW_RUN_ID,
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                                rootWorkflow = rootWorkflow.value,
                                issuesHash = "62504c9e-f07d-369e-80d1-033d75720947",
                                workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                            ),
                        ),
                )
            val criterion = "Test criterion"
            val explanation = "Test explanation"
            coEvery { autoreviewWorkflowsStorageService.getAcceptanceCriteriaPantryItem(any(), any()) } returns
                AutoreviewPantryAcceptanceCriteria(
                    acceptanceCriteria =
                        listOf(
                            AutoreviewPantryAcceptanceCriterion(
                                id = TEST_ISSUE_ID,
                                criterion = criterion,
                                status = AutoreviewPantryAcceptanceCriterionStatus.MET,
                                issueAri = TEST_JIRA_ISSUE_ARI,
                                explanation = explanation,
                            ),
                        ),
                    reviewIteration = 8,
                    extractionIteration = 5,
                )

            autoreviewService.submitAcceptanceCriteria(salPayload, jiraIssues)
            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
            val acceptanceCriteriaArtifact =
                requestBodySlot.captured.input.artifacts.get(
                    AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                ) as AutoreviewAcceptanceCriteriaArtifact
            acceptanceCriteriaArtifact.reviewed shouldBe false
            acceptanceCriteriaArtifact.acceptanceCriteria shouldHaveSize 1
            val acceptanceCriterion = acceptanceCriteriaArtifact.acceptanceCriteria.first()
            acceptanceCriterion.id shouldBe TEST_ISSUE_ID
            acceptanceCriterion.criterion shouldBe criterion
            acceptanceCriterion.explanation shouldBe explanation
            acceptanceCriterion.status shouldBe AutoreviewAcceptanceCriterionStatus.MET
            acceptanceCriterion.issueAri shouldBe TEST_JIRA_ISSUE_ARI
            acceptanceCriteriaArtifact.jiraIssues.forEach {
                it.issueAri shouldBe TEST_JIRA_ISSUE_ARI
                it.self shouldBe TEST_ISSUE_SELF
                it.key shouldBe TEST_ISSUE_KEY
            }
            acceptanceCriteriaArtifact.reviewIteration shouldBe 8
            acceptanceCriteriaArtifact.extractionIteration shouldBe 5
        }
    }

    @Test
    fun `submitAcceptanceCriteria uses review and extraction iterations only, when issues have changed`() {
        runTest {
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            val sourceCommit = "sourceCommit"
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )
            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = TEST_WORKFLOW_RUN_ID,
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                                rootWorkflow = rootWorkflow.value,
                                issuesHash = "2baace3f-a39e-3ea7-8361-405d9626306e",
                                workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                            ),
                        ),
                )
            coEvery { autoreviewWorkflowsStorageService.getAcceptanceCriteriaPantryItem(any(), any()) } returns
                AutoreviewPantryAcceptanceCriteria(
                    acceptanceCriteria =
                        listOf(
                            AutoreviewPantryAcceptanceCriterion(
                                id = TEST_ISSUE_ID,
                                criterion = "Test criterion",
                                status = AutoreviewPantryAcceptanceCriterionStatus.MET,
                                issueAri = TEST_JIRA_ISSUE_ARI,
                            ),
                        ),
                    reviewIteration = 4,
                    extractionIteration = 3,
                )

            autoreviewService.submitAcceptanceCriteria(salPayload, jiraIssues)
            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
            val acceptanceCriteriaArtifact =
                requestBodySlot.captured.input.artifacts.get(
                    AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                ) as AutoreviewAcceptanceCriteriaArtifact

            acceptanceCriteriaArtifact.acceptanceCriteria shouldBe emptyList()
            acceptanceCriteriaArtifact.jiraIssues shouldBe emptyList()
            acceptanceCriteriaArtifact.reviewIteration shouldBe 4
            acceptanceCriteriaArtifact.extractionIteration shouldBe 3
        }
    }

    @Test
    fun `submitAcceptanceCriteria does not send acceptance criteria artifact on retrigger when no issues hash exists`() {
        runTest {
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            val sourceCommit = "sourceCommit"
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )
            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = TEST_WORKFLOW_RUN_ID,
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                                rootWorkflow = rootWorkflow.value,
                                issuesHash = null,
                            ),
                        ),
                )

            autoreviewService.submitAcceptanceCriteria(salPayload, jiraIssues)
            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
            requestBodySlot.captured.input.artifacts.get(
                AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
            ) shouldBe null
        }
    }

    @Test
    fun `submitAcceptanceCriteria does not send workflow request when jiraIssues is empty`() {
        runTest {
            val sourceCommit = "sourceCommit"

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = emptyList(),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )

            autoreviewService.submitAcceptanceCriteria(salPayload, emptyList())
            coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
        }
    }

    @Nested
    inner class AcceptanceCriteriaIgnoreSummaryOnlyIssues {
        private val exampleJiraIssueDetails =
            listOf(
                mockk<JiraIssueDetails>(relaxed = true),
            )

        private val examplePayload =
            createSalAutoreviewPayload(
                prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                sourceCommit = "sourceCommit",
                workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
            )

        @BeforeEach
        fun setup() {
            mockkStatic(::selectSuitableJiraIssues)

            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = UUID.fromString(TEST_WORKFLOW_RUN_ID),
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
        }

        @AfterEach
        fun tearDown() {
            unmockkStatic(::selectSuitableJiraIssues)
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `Ignores 'Summary Only' Jira Issues depending on Feature Gate value`(featureGateValue: Boolean) =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaIgnoreSummaryOnlyIssues(any()) } returns featureGateValue

                autoreviewService.submitAcceptanceCriteria(examplePayload, exampleJiraIssueDetails)

                verify(exactly = 1) {
                    selectSuitableJiraIssues(
                        jiraIssues = any(),
                        ignoreSummaryOnly = featureGateValue,
                        limitMultipleJiraIssues = any(),
                        branchName = any(),
                    )
                }
            }

        @Test
        fun `submitAcceptanceCriteria does not send workflow request when selectSuitableJiraIssues returns empty list`() =
            runTest {
                every { selectSuitableJiraIssues(any(), any(), any(), any()) } returns emptyList()

                autoreviewService.submitAcceptanceCriteria(examplePayload, exampleJiraIssueDetails)

                verify(exactly = 1) { selectSuitableJiraIssues(match { it.isNotEmpty() }, any(), any(), any()) }
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }
    }

    @Nested
    inner class AcceptanceCriteriaLimitMultipleIssues {
        private val exampleJiraIssueDetails =
            listOf(
                mockk<JiraIssueDetails>(relaxed = true),
                mockk<JiraIssueDetails>(relaxed = true),
            )

        private val examplePayload =
            createSalAutoreviewPayload(
                prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                sourceCommit = "sourceCommit",
                workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
            )

        @BeforeEach
        fun setup() {
            mockkStatic(::selectSuitableJiraIssues)

            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = UUID.fromString(TEST_WORKFLOW_RUN_ID),
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
        }

        @AfterEach
        fun tearDown() {
            unmockkStatic(::selectSuitableJiraIssues)
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `Limits Multiple Jira Issues depending on Feature Gate value`(featureGateValue: Boolean) =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaLimitMultipleJiraIssues(any()) } returns featureGateValue
                examplePayload.prDetail.sourceBranch.name shouldBe "sourceBranch"

                autoreviewService.submitAcceptanceCriteria(examplePayload, exampleJiraIssueDetails)

                verify(exactly = 1) {
                    selectSuitableJiraIssues(
                        jiraIssues = any(),
                        ignoreSummaryOnly = any(),
                        limitMultipleJiraIssues = featureGateValue,
                        branchName = "sourceBranch",
                    )
                }
            }
    }

    @Test
    fun `submitAcceptanceCriteria creates ac artifact from workspaceAri on retrigger when multiplexing is enabled`() {
        runTest {
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            val sourceCommit = "sourceCommit"
            val workspaceAri = "ari:cloud:devai::workspace/f84969f3-7eb3-4ae3-a252-bbd6db8659bb"
            val jiraIssuesHash = "62504c9e-f07d-369e-80d1-033d75720947"
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    workspaceAri = workspaceAri,
                )
            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = "678899",
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                                rootWorkflow = rootWorkflow.value,
                                issuesHash = "f303a933-d8b5-43bb-b4cf-bb119ac7af33",
                                workspaceAri = "ari:cloud:devai::workspace/12345678-1234-1234-1234-123456789012",
                            ),
                            AutoreviewWorkflow(
                                jobId = TEST_WORKFLOW_RUN_ID,
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                                rootWorkflow = rootWorkflow.value,
                                issuesHash = jiraIssuesHash,
                                workspaceAri = workspaceAri,
                            ),
                            AutoreviewWorkflow(
                                jobId = "98938821",
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                                rootWorkflow = rootWorkflow.value,
                                issuesHash = "f13fc1b6-82a4-4f05-bd20-601dc49428ef",
                                workspaceAri = "ari:cloud:devai::workspace/cf08a2b2-1a51-42c0-bd29-dfedad9825ac",
                            ),
                        ),
                )
            val criterion = "Test criterion"
            coEvery { autoreviewWorkflowsStorageService.getAcceptanceCriteriaPantryItem(any(), any()) } returns
                AutoreviewPantryAcceptanceCriteria(
                    acceptanceCriteria =
                        listOf(
                            AutoreviewPantryAcceptanceCriterion(
                                id = TEST_ISSUE_ID,
                                criterion = "Test criterion",
                                status = AutoreviewPantryAcceptanceCriterionStatus.MET,
                                issueAri = TEST_JIRA_ISSUE_ARI,
                            ),
                        ),
                    reviewIteration = 8,
                    extractionIteration = 5,
                )

            autoreviewService.submitAcceptanceCriteria(salPayload, jiraIssues)
            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
            val acceptanceCriteriaArtifact =
                requestBodySlot.captured.input.artifacts.get(
                    AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                ) as AutoreviewAcceptanceCriteriaArtifact
            acceptanceCriteriaArtifact.reviewed shouldBe false
            acceptanceCriteriaArtifact.acceptanceCriteria shouldHaveSize 1
            val acceptanceCriterion = acceptanceCriteriaArtifact.acceptanceCriteria.first()
            acceptanceCriterion.id shouldBe TEST_ISSUE_ID
            acceptanceCriterion.criterion shouldBe criterion
            acceptanceCriterion.status shouldBe AutoreviewAcceptanceCriterionStatus.MET
            acceptanceCriterion.issueAri shouldBe TEST_JIRA_ISSUE_ARI
            acceptanceCriteriaArtifact.jiraIssues.forEach {
                it.issueAri shouldBe TEST_JIRA_ISSUE_ARI
                it.self shouldBe TEST_ISSUE_SELF
                it.key shouldBe TEST_ISSUE_KEY
            }
            acceptanceCriteriaArtifact.reviewIteration shouldBe 8
            acceptanceCriteriaArtifact.extractionIteration shouldBe 5
        }
    }

    @Test
    fun `submitAcceptanceCriteria does not create ac on retrigger when no workspaceAri matches and multiplexing is enabled`() {
        runTest {
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            val sourceCommit = "sourceCommit"
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    workspaceAri = "ari:cloud:devai::workspace/12345678-1234-1234-1234-123456789012",
                )
            val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = TEST_WORKFLOW_RUN_ID,
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                                rootWorkflow = rootWorkflow.value,
                                issuesHash = "f303a933-d8b5-43bb-b4cf-bb119ac7af33",
                                workspaceAri = "ari:cloud:devai::workspace/f84969f3-7eb3-4ae3-a252-bbd6db8659bb",
                            ),
                        ),
                )

            autoreviewService.submitAcceptanceCriteria(salPayload, jiraIssues)
            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
            requestBodySlot.captured.input.artifacts.get(
                AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
            ) shouldBe null
        }
    }

    @Test
    fun `handlePullRequestUpdated should submit Acceptance Criteria job when no pull request pantry item exists`() =
        runTest {
            coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns true
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    autoreviewEventTypes = listOf(AutoreviewEventType.ACCEPTANCE_CRITERIA),
                )

            autoreviewService.handlePullRequestUpdated(salPayload)
            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
            requestBodySlot.captured.rootWorkflow shouldBe rootWorkflow
        }

    @Test
    fun `handlePullRequestUpdated should submit Acceptance Criteria job when stored sourceCommit is unavailable`() =
        runTest {
            coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns true
            val workflowRunId = UUID.randomUUID()
            val rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(TEST_PR_URL) } returns null
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    autoreviewEventTypes = listOf(AutoreviewEventType.ACCEPTANCE_CRITERIA),
                )
            coEvery {
                autoreviewWorkflowsStorageService.getAutoreviewPantryItem(TEST_PR_URL)
            } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = "1",
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = null,
                            ),
                        ),
                )
            autoreviewService.handlePullRequestUpdated(salPayload)
            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
            coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
            requestBodySlot.captured.rootWorkflow shouldBe rootWorkflow
        }

    @Test
    fun `handlePullRequestUpdated should skip Acceptance Criteria job when a stored workflow already has the sourceCommit`() =
        runTest {
            val sourceCommit = "0e46f73259dc4901b5e45591a7c32257a5953e11"
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = emptyList(),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )
            coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns true
            coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(TEST_PR_URL) } returns
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = "1",
                                createdDate = "2021-01-01T00:00:00Z",
                                sourceCommit = sourceCommit,
                            ),
                        ),
                )
            autoreviewService.handlePullRequestUpdated(salPayload)
            coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
        }

    @ParameterizedTest
    @MethodSource("autoreviewEventTypesInput")
    fun `handlePullRequestCreated submits correct workflows based on autoreview event types`(
        autoreviewEventTypes: List<AutoreviewEventType>,
    ) {
        runTest {
            // Arrange
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = UUID.randomUUID(),
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = "sourceCommit",
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    autoreviewEventTypes = autoreviewEventTypes,
                )

            // Act
            autoreviewService.handlePullRequestCreated(salPayload)

            // Assert
            val submitCount =
                when {
                    AutoreviewEventType.AUTOREVIEW_MAIN in autoreviewEventTypes &&
                        AutoreviewEventType.ACCEPTANCE_CRITERIA in autoreviewEventTypes -> 2

                    AutoreviewEventType.AUTOREVIEW_MAIN in autoreviewEventTypes ||
                        AutoreviewEventType.ACCEPTANCE_CRITERIA in autoreviewEventTypes -> 1

                    else -> 0
                }

            coVerify(exactly = submitCount) {
                acraClient.submitAcraWorkflowRun(any(), any())
            }
        }
    }

    @ParameterizedTest
    @MethodSource("autoreviewEventTypesInput")
    fun `handlePullRequestUpdated submits correct workflow based on autoreview event`(autoreviewEventTypes: List<AutoreviewEventType>) {
        runTest {
            mockkStatic(::isRepoEnabledForIncrementalReview)
            every { isRepoEnabledForIncrementalReview(any(), any()) } returns true
            coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns true
            coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = UUID.randomUUID(),
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)

            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                    sourceCommit = "sourceCommit",
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    autoreviewEventTypes = autoreviewEventTypes,
                    repositoryUrl = URI.create("https://bitbucket.org/atlassian/test").toURL(),
                )

            autoreviewService.handlePullRequestUpdated(salPayload)

            coVerify(exactly = autoreviewEventTypes.size) { acraClient.submitAcraWorkflowRun(any(), any()) }
        }
    }

    @Test
    fun `getAutoreviewCommentReviewWorkflowResult return successful results from ACRA Autoreview with generated comments`() {
        runTest {
            // prepare data
            mockAcraClientFetchWorkflowRunAutoreview(2)

            // call
            val commentReviewResult =
                autoreviewService.getAutoreviewCommentReviewWorkflowResult(
                    TEST_WORKFLOW_RUN_ID,
                    TEST_DEVAI_WORKSPACE_ARI,
                )

            // verify
            commentReviewResult.workflowRunId shouldBe TEST_WORKFLOW_RUN_ID
            commentReviewResult.pullRequestURL shouldBe TEST_BITBUCKET_PULL_REQUEST_URL
            commentReviewResult.comments.size shouldBe 2
            commentReviewResult.comments[0].comment shouldBe "Comment 1"
            commentReviewResult.comments[0].changeType shouldBe CodeReviewCommentChangeType.UNKNOWN
            commentReviewResult.comments[1].comment shouldBe "Comment 2"
            commentReviewResult.comments[1].changeType shouldBe CodeReviewCommentChangeType.UNKNOWN
        }
    }

    @Test
    fun `getAutoreviewCommentReviewWorkflowResult return successful - no generated comments`() {
        runTest {
            // prepare data
            mockAcraClientFetchWorkflowRunAutoreview(0)

            // call
            val commentReviewResult =
                autoreviewService.getAutoreviewCommentReviewWorkflowResult(
                    TEST_WORKFLOW_RUN_ID,
                    TEST_DEVAI_WORKSPACE_ARI,
                )

            // verify
            commentReviewResult.workflowRunId shouldBe TEST_WORKFLOW_RUN_ID
            commentReviewResult.pullRequestURL shouldBe TEST_BITBUCKET_PULL_REQUEST_URL
            commentReviewResult.comments.size shouldBe 0
        }
    }

    @Test
    fun `getAutoreviewCommentReviewWorkflowResult return successful results from ACRA Autoreview with generated comments and changeType`() {
        runTest {
            // prepare data
            mockAcraClientFetchWorkflowRunAutoreview(2, changeType = CodeReviewCommentChangeType.REMOVED)

            // call
            val commentReviewResult =
                autoreviewService.getAutoreviewCommentReviewWorkflowResult(
                    TEST_WORKFLOW_RUN_ID,
                    TEST_DEVAI_WORKSPACE_ARI,
                )

            // verify
            commentReviewResult.workflowRunId shouldBe TEST_WORKFLOW_RUN_ID
            commentReviewResult.pullRequestURL shouldBe TEST_BITBUCKET_PULL_REQUEST_URL
            commentReviewResult.comments.size shouldBe 2
            commentReviewResult.comments[0].comment shouldBe "Comment 1"
            commentReviewResult.comments[0].changeType shouldBe CodeReviewCommentChangeType.REMOVED
            commentReviewResult.comments[1].comment shouldBe "Comment 2"
            commentReviewResult.comments[1].changeType shouldBe CodeReviewCommentChangeType.REMOVED
        }
    }

    @Test
    fun `getAutoreviewCommentReviewWorkflowResult return successful source commit`() {
        runTest {
            // prepare
            val sourceCommit = "ed0bba193dd8be019c6d36fa7d9b4a2698edf506"
            mockAcraClientFetchWorkflowRunAutoreview(0, sourceCommit = sourceCommit)

            // call
            val commentReviewResult =
                autoreviewService.getAutoreviewCommentReviewWorkflowResult(
                    TEST_WORKFLOW_RUN_ID,
                    TEST_DEVAI_WORKSPACE_ARI,
                )

            commentReviewResult.sourceCommit shouldBe sourceCommit
        }
    }

    @Test
    fun `getAutoreviewCommentReviewWorkflowResult throw AcraClientException when acra client return error`() {
        runTest {
            // mock exception
            coEvery {
                acraClient.fetchWorkflowRun(
                    eq(TEST_WORKFLOW_RUN_ID),
                    any(),
                    eq(getAcraClientCustomHeaders(TEST_DEVAI_WORKSPACE_ARI)),
                )
            } throws
                ClientException(
                    operation = "GET",
                    httpStatusCode = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    exception = Exception("General ACRA error"),
                )

            // call
            assertThrows<ClientException> {
                autoreviewService.getAutoreviewCommentReviewWorkflowResult(
                    TEST_WORKFLOW_RUN_ID,
                    TEST_DEVAI_WORKSPACE_ARI,
                )
            }
        }
    }

    @Test
    fun `getAutoreviewCommentReviewWorkflowResult throw NoSuchElementException when acra returns no artifacts`() {
        runTest {
            // mock exception
            coEvery {
                acraClient.fetchWorkflowRun(
                    eq(TEST_WORKFLOW_RUN_ID),
                    any(),
                    eq(getAcraClientCustomHeaders(TEST_DEVAI_WORKSPACE_ARI)),
                )
            } returns Mono.empty()

            // call
            assertThrows<NoSuchElementException> {
                autoreviewService.getAutoreviewCommentReviewWorkflowResult(
                    TEST_WORKFLOW_RUN_ID,
                    TEST_DEVAI_WORKSPACE_ARI,
                )
            }
        }
    }

    @Test
    fun `getAutoreviewCommentReviewWorkflowResult throw NoSuchElementException when no autoreviewScmCommentsArtifact`() {
        runTest {
            // mock exception
            val acraWorkflowRun =
                WorkflowRun(
                    id = UUID.fromString(TEST_WORKFLOW_RUN_ID),
                    rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                    currentWorkflow = "AutoreviewScmCommentsWorkflow",
                    status = AcraWorkflowRunStatus.PENDING,
                    repoUrl = URI.create(TEST_REPO_URL).toURL(),
                    issueAri = JiraIssueARI.valueOf(TEST_JIRA_ISSUE_ARI),
                    createdTimestamp = OffsetDateTime.now().toInstant(),
                    updatedTimestamp = OffsetDateTime.now().toInstant(),
                    artifacts = emptyList(),
                )

            coEvery {
                acraClient.fetchWorkflowRun(
                    eq(TEST_WORKFLOW_RUN_ID),
                    any(),
                    eq(getAcraClientCustomHeaders(TEST_DEVAI_WORKSPACE_ARI)),
                )
            } returns Mono.just(acraWorkflowRun)

            // call
            assertThrows<NoSuchElementException> {
                autoreviewService.getAutoreviewCommentReviewWorkflowResult(
                    TEST_WORKFLOW_RUN_ID,
                    TEST_DEVAI_WORKSPACE_ARI,
                )
            }
        }
    }

    private fun createSalAutoreviewPayload(
        prAuthorAccountId: String = TEST_AUTHOR_ACCOUNT_ID,
        associations: List<Association> = emptyList(),
        sourceCommit: String? = null,
        workspaceId: String,
        repositoryUrl: URL = URI.create(TEST_REPO_URL).toURL(),
        cloudId: CloudIdLike = CloudIdLike.fromString(UUID.randomUUID().toString()),
        workspaceAri: String = TEST_DEVAI_WORKSPACE_ARI,
        autoreviewEventTypes: List<AutoreviewEventType> =
            listOf(
                AutoreviewEventType.AUTOREVIEW_MAIN,
                AutoreviewEventType.CUSTOM,
            ),
    ): AutoreviewSalPayload =
        AutoreviewSalPayload(
            traceId = "traceId",
            type = AutoreviewSalPayloadType.CREATE,
            workspaceId = workspaceId,
            workspace = ARI.valueOf(workspaceAri),
            cloudId = cloudId,
            resource = ARI.valueOf(TEST_RESOURCE_ARI),
            repositoryUrl = repositoryUrl,
            prDetail =
                PRDetail(
                    url = URI.create(TEST_PR_URL).toURL(),
                    title = "PR Title",
                    id = "1",
                    sourceBranch = Branch("sourceBranch", null),
                    destinationBranch = Branch("destinationBranch", null),
                    author = PullRequestAuthor(accountId = AccountId.of(prAuthorAccountId)),
                    sourceCommit = sourceCommit,
                ),
            associations = associations,
            autoreviewEventTypes = autoreviewEventTypes,
        )

    private fun mockAutoreviewScmCommentsArtifact(numberOfComment: Int): AutoreviewScmCommentsArtifact {
        val commentList = mutableListOf<AutoreviewScmCommentItem>()
        for (i in 1..numberOfComment) {
            commentList.add(
                AutoreviewScmCommentItem(
                    id = UUID.randomUUID().toString(),
                    comment = "Comment $i",
                    path = "file$i",
                    line = i,
                    rationale = null,
                ),
            )
        }

        return AutoreviewScmCommentsArtifact(
            comments = commentList,
            pullRequestUrl = TEST_BITBUCKET_PULL_REQUEST_URL,
        )
    }

    private fun mockAutoreviewScmCommentsArtifactWithChangeType(
        numberOfComment: Int,
        changeType: CodeReviewCommentChangeType,
        generatedBy: CodeReviewCommentGeneratedBy? = CodeReviewCommentGeneratedBy.CodeBugReviewCommentGenerator,
    ): AutoreviewScmCommentsArtifact {
        val commentList = mutableListOf<AutoreviewScmCommentItem>()
        for (i in 1..numberOfComment) {
            commentList.add(
                AutoreviewScmCommentItem(
                    id = UUID.randomUUID().toString(),
                    comment = "Comment $i",
                    path = "file$i",
                    line = i,
                    rationale = null,
                    changeType = changeType,
                    generatedBy = generatedBy,
                ),
            )
        }

        return AutoreviewScmCommentsArtifact(
            comments = commentList,
            pullRequestUrl = TEST_BITBUCKET_PULL_REQUEST_URL,
        )
    }

    private fun mockAcceptanceCriteriaArtifact() =
        AutoreviewAcceptanceCriteriaArtifact(
            acceptanceCriteria =
                listOf(
                    AutoreviewAcceptanceCriterion(
                        id = "fa2655f8-c983-4e60-99f7-3124e105660b",
                        criterion = "Add unit test for calculation",
                        status = AutoreviewAcceptanceCriterionStatus.MET,
                        issueAri = TEST_JIRA_ISSUE_ARI,
                    ),
                ),
            jiraIssues =
                listOf(
                    JiraIssue(
                        issueAri = TEST_JIRA_ISSUE_ARI,
                        summary = "Example Jira Issue Summary",
                        description = "Example Jira Issue Description",
                        key = TEST_ISSUE_KEY,
                        self = TEST_ISSUE_SELF,
                    ),
                ),
        )

    private fun mockAcraClientFetchWorkflowRunAutoreview(
        numberOfComment: Int = 1,
        changeType: CodeReviewCommentChangeType? = null,
        sourceCommit: String? = null,
    ) {
        val autoreviewScmCommentsArtifact =
            changeType?.let {
                mockAutoreviewScmCommentsArtifactWithChangeType(numberOfComment, it)
            } ?: mockAutoreviewScmCommentsArtifact(numberOfComment)

        coEvery {
            acraClient.fetchWorkflowRun(
                eq(TEST_WORKFLOW_RUN_ID),
                any(),
                eq(getAcraClientCustomHeaders(TEST_DEVAI_WORKSPACE_ARI)),
            )
        } answers { call ->

            val requestedArtifacts = call.invocation.args[1] as List<*>

            val artifacts =
                buildList {
                    add(
                        WorkflowRunArtifact(
                            id = UUID.fromString("e7d1c16b-0208-4a4c-a19e-547ac140d854"),
                            name = AUTOREVIEW_SCM_COMMENTS_JSON,
                            data = autoreviewScmCommentsArtifact,
                        ),
                    )
                    // Only Include Autoreview Acceptance Criteria artifact if it has been requested
                    if (requestedArtifacts.contains("autoreview-acceptance-criteria")) {
                        add(
                            WorkflowRunArtifact(
                                id = UUID.fromString("52cc5eb5-f4d2-47b6-ad7c-56755612deb7"),
                                name = AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                                data = mockAcceptanceCriteriaArtifact(),
                            ),
                        )
                    }
                    if (requestedArtifacts.contains("pull-request-info")) {
                        add(
                            WorkflowRunArtifact(
                                id = UUID.fromString("562831e7-5309-4cde-8696-e47fba093c1c"),
                                name = PULL_REQUEST_INFO_JSON,
                                data =
                                    PullRequestInfoArtifact(
                                        pullRequestUrl = "https://bitbucket.org/atlassian/ic-tf-monorepo/pull-requests/53",
                                        sourceBranch = "akoshelev/MSCALE-3885-add-dev-env-references",
                                        targetBranch = "main",
                                        sourceCommit = sourceCommit,
                                        targetCommit = "317a3068c79c32d54127097792e1506b96e6fa64",
                                        prTitle = "MSCALE-3885 add instructions for dev env setup",
                                        prDescription = "## What\n\nAdd instructions on how to setup dev environment",
                                    ),
                            ),
                        )
                    }
                }

            Mono.just(
                WorkflowRun(
                    id = UUID.fromString(TEST_WORKFLOW_RUN_ID),
                    rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                    currentWorkflow = "AutoreviewScmCommentsWorkflow",
                    status = AcraWorkflowRunStatus.PENDING,
                    repoUrl = URI.create(TEST_REPO_URL).toURL(),
                    issueAri = JiraIssueARI.valueOf(TEST_JIRA_ISSUE_ARI),
                    createdTimestamp = OffsetDateTime.now().toInstant(),
                    updatedTimestamp = OffsetDateTime.now().toInstant(),
                    artifacts = artifacts,
                ),
            )
        }
    }

    @Nested
    inner class SendPullRequestComments {
        @BeforeEach
        fun setup() {
            coEvery {
                bitbucketService.getWorkspaceIdFromWorkspaceSlug(
                    any(),
                    any(),
                )
            } returns TEST_BITBUCKET_WORKSPACE_UUID
        }
    }

    @Nested
    inner class SendAnalyticEventWorkflowCompleted {
        private fun mockWorkflowCompletedEvent(success: Boolean) {
            coEvery {
                acraClient.workflowCompletedAnalyticEvent(
                    match {
                        it.workflowRunId == TEST_WORKFLOW_RUN_ID
                    },
                    any(),
                )
            } returns
                Mono.just(
                    AcraClientWorkflowCompletedAnalyticResponse(
                        workflowRunId = TEST_WORKFLOW_RUN_ID,
                        success = success,
                    ),
                )
        }

        @Test
        fun `should successfully send an event to ACRA when commentIds are empty`() {
            mockWorkflowCompletedEvent(success = true)

            runTest {
                val eventPublished =
                    autoreviewService.sendAnalyticEventWorkflowCompleted(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        commentIds = emptyList(),
                        prSummaryCommentIds = emptyList(),
                    )

                eventPublished shouldBe true

                coVerify(exactly = 1) {
                    acraClient.workflowCompletedAnalyticEvent(
                        requestBody =
                            match {
                                it.commentExternalIds.isEmpty() &&
                                    it.pullRequestComments!!.isEmpty() &&
                                    it.cloudId.toString() == TEST_CLOUD_ID &&
                                    it.workspaceAri == TEST_DEVAI_WORKSPACE_ARI
                            },
                        customHeaders = any(),
                    )
                }
            }
        }

        @Test
        fun `should successfully send an event to ACRA when commentIds are not empty`() {
            mockWorkflowCompletedEvent(success = true)

            runTest {
                val eventPublished =
                    autoreviewService.sendAnalyticEventWorkflowCompleted(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        commentIds =
                            listOf(
                                AutoreviewPullRequestComment("commentId-1", "posted-comment-1"),
                                AutoreviewPullRequestComment("commentId-2", "posted-comment-2"),
                            ),
                        prSummaryCommentIds = listOf("posted-comment-3"),
                    )

                eventPublished shouldBe true

                coVerify(exactly = 1) {
                    acraClient.workflowCompletedAnalyticEvent(
                        requestBody =
                            match {
                                it.commentExternalIds.size == 2 &&
                                    it.pullRequestComments!!.containsAll(
                                        listOf(
                                            mapOf("id" to "commentId-1", "externalId" to "posted-comment-1"),
                                            mapOf("id" to "commentId-2", "externalId" to "posted-comment-2"),
                                        ),
                                    ) &&
                                    it.cloudId.toString() == TEST_CLOUD_ID &&
                                    it.workspaceAri == TEST_DEVAI_WORKSPACE_ARI
                            },
                        customHeaders = any(),
                    )
                }
            }
        }

        @Test
        fun `should continue without exception if request could not be sent to ACRA`() {
            coEvery {
                acraClient.workflowCompletedAnalyticEvent(
                    any(),
                    any(),
                )
            } returns Mono.error(NullPointerException("Something bad happened"))

            runTest {
                val eventPublished =
                    autoreviewService.sendAnalyticEventWorkflowCompleted(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        commentIds = listOf(AutoreviewPullRequestComment("commentId-1", "posted-comment-1")),
                    )

                eventPublished shouldBe false

                coVerify(exactly = 1) {
                    acraClient.workflowCompletedAnalyticEvent(
                        requestBody = any(),
                        customHeaders = any(),
                    )
                }
            }
        }

        @Test
        fun `should continue without exception if ACRA reports unsuccessful publishing`() {
            mockWorkflowCompletedEvent(success = false)

            runTest {
                val eventPublished =
                    autoreviewService.sendAnalyticEventWorkflowCompleted(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        commentIds = listOf(AutoreviewPullRequestComment("commentId-1", "posted-comment-1")),
                    )

                eventPublished shouldBe false

                coVerify(exactly = 1) {
                    acraClient.workflowCompletedAnalyticEvent(
                        requestBody = any(),
                        customHeaders = any(),
                    )
                }
            }
        }
    }

    @Nested
    inner class SendCommentRankerAnalyticEvent {
        private fun mockCommentRankerEvent(success: Boolean) {
            coEvery {
                acraClient.commentRankerAnalyticEvent(
                    match {
                        it.workflowRunId == TEST_WORKFLOW_RUN_ID &&
                            it.workspaceAri == TEST_DEVAI_WORKSPACE_ARI &&
                            it.cloudId == TEST_CLOUD_ID
                    },
                    any(),
                )
            } returns
                Mono.just(
                    AcraClientCommentRankerAnalyticResponse(
                        workflowRunId = TEST_WORKFLOW_RUN_ID,
                        success = success,
                    ),
                )
        }

        @Test
        fun `should successfully send comment ranker analytics event`() {
            mockCommentRankerEvent(success = true)

            runTest {
                val commentRankerScores =
                    listOf(
                        CommentRankerScore(
                            id = "comment-1",
                            comment = "Test comment 1",
                            commentRankerScore = 0.85,
                            threshold = 0.7,
                            isScoreGreaterThanThreshold = true,
                            modelVersion = "v1.0",
                        ),
                        CommentRankerScore(
                            id = "comment-2",
                            comment = "Test comment 2",
                            commentRankerScore = 0.65,
                            threshold = 0.7,
                            isScoreGreaterThanThreshold = false,
                            modelVersion = "v1.0",
                        ),
                    )

                val eventPublished =
                    autoreviewService.sendCommentRankerAnalyticEvent(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        commentRankerScores = commentRankerScores,
                    )

                eventPublished shouldBe true

                coVerify(exactly = 1) {
                    acraClient.commentRankerAnalyticEvent(
                        requestBody =
                            match {
                                it.commentRankerScores.size == 2 &&
                                    it.commentRankerScores.any { score ->
                                        score.id == "comment-1" && score.commentRankerScore == 0.85
                                    } &&
                                    it.commentRankerScores.any { score ->
                                        score.id == "comment-2" && score.commentRankerScore == 0.65
                                    } &&
                                    it.cloudId.toString() == TEST_CLOUD_ID &&
                                    it.workspaceAri == TEST_DEVAI_WORKSPACE_ARI
                            },
                        customHeaders = any(),
                    )
                }
            }
        }

        @Test
        fun `should continue without exception if request could not be sent to ACRA`() {
            coEvery {
                acraClient.commentRankerAnalyticEvent(
                    any(),
                    any(),
                )
            } returns Mono.error(NullPointerException("Something bad happened"))

            runTest {
                val commentRankerScores =
                    listOf(
                        CommentRankerScore(
                            id = "comment-1",
                            comment = "Test comment 1",
                            commentRankerScore = 0.85,
                            threshold = 0.7,
                            isScoreGreaterThanThreshold = true,
                            modelVersion = "v1.0",
                        ),
                    )

                val eventPublished =
                    autoreviewService.sendCommentRankerAnalyticEvent(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        commentRankerScores = commentRankerScores,
                    )

                eventPublished shouldBe false

                coVerify(exactly = 1) {
                    acraClient.commentRankerAnalyticEvent(
                        requestBody = any(),
                        customHeaders = any(),
                    )
                }
            }
        }

        @Test
        fun `should continue without exception if ACRA reports unsuccessful publishing`() {
            mockCommentRankerEvent(success = false)

            runTest {
                val commentRankerScores =
                    listOf(
                        CommentRankerScore(
                            id = "comment-1",
                            comment = "Test comment 1",
                            commentRankerScore = 0.85,
                            threshold = 0.7,
                            isScoreGreaterThanThreshold = true,
                            modelVersion = "v1.0",
                        ),
                    )

                val eventPublished =
                    autoreviewService.sendCommentRankerAnalyticEvent(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        commentRankerScores = commentRankerScores,
                    )

                eventPublished shouldBe false

                coVerify(exactly = 1) {
                    acraClient.commentRankerAnalyticEvent(
                        requestBody = any(),
                        customHeaders = any(),
                    )
                }
            }
        }
    }

    @Nested
    inner class GetAutoreviewWorkflowRun {
        @Test
        fun `returns the workflowRun with the required artifact types`() =
            runTest {
                mockAcraClientFetchWorkflowRunWithArtifacts()

                val workflowRun =
                    autoreviewService.getAutoreviewWorkflowRun(
                        workflowRunId = TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        requiredArtifactTypes =
                            listOf(
                                ArtifactType.AUTOREVIEW_ACCEPTANCE_CRITERIA,
                                ArtifactType.PULL_REQUEST_INFO,
                            ),
                    )

                workflowRun.artifactsByName.keys shouldBe
                    setOf(
                        "pull-request-info.json",
                        "autoreview-acceptance-criteria.json",
                    )
            }

        @Test
        fun `throws exception if acraClient throws exception`() =
            runTest {
                coEvery {
                    acraClient.fetchWorkflowRun(
                        eq(TEST_WORKFLOW_RUN_ID),
                        any(),
                        eq(getAcraClientCustomHeaders(TEST_DEVAI_WORKSPACE_ARI)),
                    )
                } throws
                    ClientException(
                        operation = "GET",
                        httpStatusCode = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        exception = Exception("General ACRA error"),
                    )

                assertThrows<ClientException> {
                    autoreviewService.getAutoreviewWorkflowRun(
                        TEST_WORKFLOW_RUN_ID,
                        TEST_DEVAI_WORKSPACE_ARI,
                        listOf(ArtifactType.AUTOREVIEW_SCM_COMMENTS),
                    )
                }
            }

        /**
         * Mocks fetchWorkflowRun to return a workflowRun with artifacts based on includeContentForArtifactTypes argument
         * **Note: ** Only 3 ArtifactTypes are mocked:
         * - ArtifactType.AUTOREVIEW_ACCEPTANCE_CRITERIA
         * - ArtifactType.PULL_REQUEST_INFO
         * - ArtifactType.AUTOREVIEW_SCM_COMMENTS
         */
        private fun mockAcraClientFetchWorkflowRunWithArtifacts() {
            coEvery {
                acraClient.fetchWorkflowRun(
                    eq(TEST_WORKFLOW_RUN_ID),
                    any(),
                    eq(getAcraClientCustomHeaders(TEST_DEVAI_WORKSPACE_ARI)),
                )
            } answers { call ->

                val requestedArtifacts = call.invocation.args[1] as List<*>

                val artifacts =
                    buildList {
                        if (requestedArtifacts.contains("autoreview-scm-comments")) {
                            add(
                                WorkflowRunArtifact(
                                    id = UUID.fromString("e7d1c16b-0208-4a4c-a19e-547ac140d854"),
                                    name = AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = mockAutoreviewScmCommentsArtifact(2),
                                ),
                            )
                        }
                        // Only Include Autoreview Acceptance Criteria artifact if it has been requested
                        if (requestedArtifacts.contains("autoreview-acceptance-criteria")) {
                            add(
                                WorkflowRunArtifact(
                                    id = UUID.fromString("52cc5eb5-f4d2-47b6-ad7c-56755612deb7"),
                                    name = AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                                    data = mockAcceptanceCriteriaArtifact(),
                                ),
                            )
                        }
                        if (requestedArtifacts.contains("pull-request-info")) {
                            add(
                                WorkflowRunArtifact(
                                    id = UUID.fromString("562831e7-5309-4cde-8696-e47fba093c1c"),
                                    name = PULL_REQUEST_INFO_JSON,
                                    data =
                                        PullRequestInfoArtifact(
                                            pullRequestUrl = "https://bitbucket.org/atlassian/ic-tf-monorepo/pull-requests/53",
                                            sourceBranch = "akoshelev/MSCALE-3885-add-dev-env-references",
                                            targetBranch = "main",
                                            sourceCommit = "817a3068c79c32d54127097792e1506b96e6fa68",
                                            targetCommit = "317a3068c79c32d54127097792e1506b96e6fa64",
                                            prTitle = "MSCALE-3885 add instructions for dev env setup",
                                            prDescription = "## What\n\nAdd instructions on how to setup dev environment",
                                        ),
                                ),
                            )
                        }
                    }

                Mono.just(
                    WorkflowRun(
                        id = UUID.fromString(TEST_WORKFLOW_RUN_ID),
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                        currentWorkflow = "AutoreviewScmCommentsWorkflow",
                        status = AcraWorkflowRunStatus.PENDING,
                        repoUrl = URI.create(TEST_REPO_URL).toURL(),
                        issueAri = JiraIssueARI.valueOf(TEST_JIRA_ISSUE_ARI),
                        createdTimestamp = OffsetDateTime.now().toInstant(),
                        updatedTimestamp = OffsetDateTime.now().toInstant(),
                        artifacts = artifacts,
                    ),
                )
            }
        }
    }

    @Nested
    inner class SubmitCodeSuggestion {
        @BeforeEach
        fun setUp() {
            coEvery {
                featureService.isAutoreviewCodeSuggestionsOnHumanCommentEnabled(any(), any(), any(), any())
            } returns true

            coEvery {
                featureService.isAutoreviewCustomGeneratorEnabled(any())
            } returns true
        }

        @Test
        fun `should send CS request to ACRA with appropriate payload when repository is accepted and PR is reviewable`() {
            runTest {
                // Arrange
                every {
                    autoreviewValidationService.isPullRequestReviewable(any())
                } returns true

                coEvery {
                    autoreviewValidationService.isRepoAccepted(any(), any(), any())
                } returns true

                val acraPayloadCaptor = slot<AutoreviewCodeSuggestionRequest>()
                val urlCaptor = slot<URI>()

                coEvery {
                    acraClient.submitACRAPostRequest(
                        path = capture(urlCaptor),
                        body = capture(acraPayloadCaptor),
                        responseObjectType = any<Class<ACRAPullRequestResponse>>(),
                        acraClientCustomHeaders = any(),
                    )
                } returns Mono.just(TEST_ACRA_PULL_REQUEST_RESPONSE)

                val salPayload =
                    AutoreviewCodeSuggestionSalPayload(
                        traceId = "traceId",
                        comment =
                            CommentEntity(
                                id = 123L,
                                content =
                                    CommentContent(
                                        type = "type",
                                        raw = "raw comment",
                                        markup = "markup comment",
                                        html = "html comment",
                                    ),
                                parent = null,
                                user =
                                    CommentUser(
                                        type = "user",
                                        displayName = "I am a user",
                                        uuid = "abc",
                                        accountId = "abc",
                                    ),
                                inline =
                                    CommentInline(
                                        from = null,
                                        to = null,
                                        path = "test.kt",
                                    ),
                            ),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            CodeSuggestionPullRequest(
                                url = URI.create(TEST_PR_URL).toURL(),
                                state = "OPEN",
                                id = 1L,
                                sourceBranch = "sourceBranch",
                                targetBranch = "targetBranch",
                            ),
                        optionalComment = "optional comment",
                        repositoryUuid = "123",
                        accountId = AccountId.of("abc"),
                    )

                autoreviewService.submitCodeSuggestion(
                    salPayload = salPayload,
                    workspaceUuid = "",
                    repositoryUuid = "",
                    transactionContext = null,
                )

                // Assert
                urlCaptor.captured shouldBe URI.create("/api/v1/autoreview/codeSuggestion")
                acraPayloadCaptor.captured.traceId shouldBe "traceId"
                acraPayloadCaptor.captured.repositoryUrl shouldBe URI.create(TEST_REPO_URL).toURL()
                acraPayloadCaptor.captured.optionalComment shouldBe "optional comment"
                acraPayloadCaptor.captured.comment shouldBe
                    CommentEntity(
                        id = 123L,
                        content =
                            CommentContent(
                                type = "type",
                                raw = "raw comment",
                                markup = "markup comment",
                                html = "html comment",
                            ),
                        parent = null,
                        user =
                            CommentUser(
                                type = "user",
                                displayName = "I am a user",
                                uuid = "abc",
                                accountId = "abc",
                            ),
                        inline =
                            CommentInline(
                                from = null,
                                to = null,
                                path = "test.kt",
                            ),
                    )
            }
        }

        @Test
        fun `should not send CS request to ACRA when repository is not accepted but PR is reviewable`() {
            runTest {
                // Arrange
                every {
                    autoreviewValidationService.isPullRequestReviewable(any())
                } returns true

                coEvery {
                    autoreviewValidationService.isRepoAccepted(any(), any(), any())
                } returns false

                val salPayload =
                    AutoreviewCodeSuggestionSalPayload(
                        traceId = "traceId",
                        comment =
                            CommentEntity(
                                id = 123L,
                                content =
                                    CommentContent(
                                        type = "type",
                                        raw = "raw comment",
                                        markup = "markup comment",
                                        html = "html comment",
                                    ),
                            ),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            CodeSuggestionPullRequest(
                                url = URI.create(TEST_PR_URL).toURL(),
                                state = "OPEN",
                                id = 1L,
                                sourceBranch = "sourceBranch",
                                targetBranch = "targetBranch",
                            ),
                        optionalComment = "optional comment",
                        repositoryUuid = "123",
                        accountId = AccountId.of("abc"),
                    )

                autoreviewService.submitCodeSuggestion(
                    salPayload = salPayload,
                    workspaceUuid = "",
                    repositoryUuid = "",
                    transactionContext = null,
                )

                // Assert
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }

        @Test
        fun `should not send CS request to ACRA when repository is accepted but PR is not reviewable`() {
            runTest {
                // Arrange
                every {
                    autoreviewValidationService.isPullRequestReviewable(any())
                } returns false

                coEvery {
                    autoreviewValidationService.isRepoAccepted(any(), any(), any())
                } returns true

                val salPayload =
                    AutoreviewCodeSuggestionSalPayload(
                        traceId = "traceId",
                        comment =
                            CommentEntity(
                                id = 123L,
                                content =
                                    CommentContent(
                                        type = "type",
                                        raw = "raw comment",
                                        markup = "markup comment",
                                        html = "html comment",
                                    ),
                            ),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            CodeSuggestionPullRequest(
                                url = URI.create(TEST_PR_URL).toURL(),
                                state = "OPEN",
                                id = 1L,
                                sourceBranch = "sourceBranch",
                                targetBranch = "targetBranch",
                            ),
                        optionalComment = "optional comment",
                        repositoryUuid = "123",
                        accountId = AccountId.of("abc"),
                    )

                autoreviewService.submitCodeSuggestion(
                    salPayload = salPayload,
                    workspaceUuid = "",
                    repositoryUuid = "",
                    transactionContext = null,
                )

                // Assert
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }

        @Test
        fun `should not send CS request to ACRA when feature gate is not enabled`() {
            runTest {
                // Arrange
                coEvery {
                    featureService.isAutoreviewCodeSuggestionsOnHumanCommentEnabled(any(), any(), any(), any())
                } returns false

                every {
                    autoreviewValidationService.isPullRequestReviewable(any())
                } returns true

                coEvery {
                    autoreviewValidationService.isRepoAccepted(any(), any(), any())
                } returns true

                val salPayload =
                    AutoreviewCodeSuggestionSalPayload(
                        traceId = "traceId",
                        comment =
                            CommentEntity(
                                id = 123L,
                                content =
                                    CommentContent(
                                        type = "type",
                                        raw = "raw comment",
                                        markup = "markup comment",
                                        html = "html comment",
                                    ),
                            ),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            CodeSuggestionPullRequest(
                                url = URI.create(TEST_PR_URL).toURL(),
                                state = "OPEN",
                                id = 1L,
                                sourceBranch = "sourceBranch",
                                targetBranch = "targetBranch",
                            ),
                        optionalComment = "optional comment",
                        repositoryUuid = "123",
                        accountId = AccountId.of("abc"),
                    )

                autoreviewService.submitCodeSuggestion(
                    salPayload = salPayload,
                    workspaceUuid = "",
                    repositoryUuid = "",
                    transactionContext = null,
                )

                // Assert
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }

        @Test
        fun `should not send CS request to ACRA when comment is not human comment`() {
            runTest {
                // Arrange
                coEvery {
                    featureService.isAutoreviewCodeSuggestionsOnHumanCommentEnabled(any(), any(), any(), any())
                } returns true

                every {
                    autoreviewValidationService.isPullRequestReviewable(any())
                } returns true

                coEvery {
                    autoreviewValidationService.isRepoAccepted(any(), any(), any())
                } returns true

                val salPayload =
                    AutoreviewCodeSuggestionSalPayload(
                        traceId = "traceId",
                        comment =
                            CommentEntity(
                                id = 123L,
                                content =
                                    CommentContent(
                                        type = "type",
                                        raw = "raw comment",
                                        markup = "markup comment",
                                        html = "html comment",
                                    ),
                                parent = null,
                                user =
                                    CommentUser(
                                        type = "bot",
                                        displayName = "I am bot user",
                                        uuid = "abc",
                                        accountId = "abc",
                                    ),
                                inline =
                                    CommentInline(
                                        from = null,
                                        to = null,
                                        path = "typo.kt",
                                    ),
                            ),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            CodeSuggestionPullRequest(
                                url = URI.create(TEST_PR_URL).toURL(),
                                state = "OPEN",
                                id = 1L,
                                sourceBranch = "sourceBranch",
                                targetBranch = "targetBranch",
                            ),
                        optionalComment = "optional comment",
                        repositoryUuid = "123",
                        accountId = AccountId.of("abc"),
                    )

                autoreviewService.submitCodeSuggestion(
                    salPayload = salPayload,
                    workspaceUuid = "",
                    repositoryUuid = "",
                    transactionContext = null,
                )

                // Assert
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }

        @Test
        fun `should not send CS request to ACRA when comment is not parent level inline comment`() {
            runTest {
                // Arrange
                coEvery {
                    featureService.isAutoreviewCodeSuggestionsOnHumanCommentEnabled(any(), any(), any(), any())
                } returns true

                every {
                    autoreviewValidationService.isPullRequestReviewable(any())
                } returns true

                coEvery {
                    autoreviewValidationService.isRepoAccepted(any(), any(), any())
                } returns true

                val salPayload =
                    AutoreviewCodeSuggestionSalPayload(
                        traceId = "traceId",
                        comment =
                            CommentEntity(
                                id = 123L,
                                content =
                                    CommentContent(
                                        type = "type",
                                        raw = "raw comment",
                                        markup = "markup comment",
                                        html = "html comment",
                                    ),
                                parent =
                                    ParentCommentEntity(
                                        id = 123,
                                    ),
                                user =
                                    CommentUser(
                                        type = "user",
                                        displayName = "I am a user",
                                        uuid = "abc",
                                        accountId = "abc",
                                    ),
                                inline =
                                    CommentInline(
                                        from = null,
                                        to = null,
                                        path = "typo.kt",
                                    ),
                            ),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            CodeSuggestionPullRequest(
                                url = URI.create(TEST_PR_URL).toURL(),
                                state = "OPEN",
                                id = 1L,
                                sourceBranch = "sourceBranch",
                                targetBranch = "targetBranch",
                            ),
                        optionalComment = "optional comment",
                        repositoryUuid = "123",
                        accountId = AccountId.of("abc"),
                    )

                autoreviewService.submitCodeSuggestion(
                    salPayload = salPayload,
                    workspaceUuid = "",
                    repositoryUuid = "",
                    transactionContext = null,
                )

                // Assert
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }

        @Test
        fun `should not send CS request to ACRA when comment is PR level comment`() {
            runTest {
                // Arrange
                coEvery {
                    featureService.isAutoreviewCodeSuggestionsOnHumanCommentEnabled(any(), any(), any(), any())
                } returns true

                every {
                    autoreviewValidationService.isPullRequestReviewable(any())
                } returns true

                coEvery {
                    autoreviewValidationService.isRepoAccepted(any(), any(), any())
                } returns true

                val salPayload =
                    AutoreviewCodeSuggestionSalPayload(
                        traceId = "traceId",
                        comment =
                            CommentEntity(
                                id = 123L,
                                content =
                                    CommentContent(
                                        type = "type",
                                        raw = "raw comment",
                                        markup = "markup comment",
                                        html = "html comment",
                                    ),
                                parent = null,
                                user =
                                    CommentUser(
                                        type = "user",
                                        displayName = "I am a user",
                                        uuid = "abc",
                                        accountId = "abc",
                                    ),
                                inline = null,
                            ),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            CodeSuggestionPullRequest(
                                url = URI.create(TEST_PR_URL).toURL(),
                                state = "OPEN",
                                id = 1L,
                                sourceBranch = "sourceBranch",
                                targetBranch = "targetBranch",
                            ),
                        optionalComment = "optional comment",
                        repositoryUuid = "123",
                        accountId = AccountId.of("abc"),
                    )

                autoreviewService.submitCodeSuggestion(
                    salPayload = salPayload,
                    workspaceUuid = "",
                    repositoryUuid = "",
                    transactionContext = null,
                )

                // Assert
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }
    }

    @Nested
    inner class LatestWorkflowDetails {
        private val aaid = "test-account-id"
        private val uct = "test-user-context-token"
        private val testDevaiWorkspaceARI = "ari:cloud:devai::workspace/c7dce2a9-02ea-407f-b9ce-b05b8b17d3d3"
        private val pullRequestUrl = "https://test.repository.com/pr/123"
        private val testJobId = "test-job-id-12345"
        private val testSourceCommit = "abc123def456"

        private val transactionContext =
            TransactionContext(
                workspace =
                    WorkspaceContext(
                        null,
                        WorkspaceId(DEV_AI_WORKSPACE_ID),
                        ARI.valueOf(testDevaiWorkspaceARI),
                    ),
                traceId = "test-trace-id",
                userContext =
                    UserContext(
                        accountId = AccountId.of(aaid),
                        userContextToken = uct,
                        accountType = AccountType.ATLASSIAN,
                        tokenExpiration = null,
                    ),
            )

        @Test
        fun `getLatestWorkflowDetails uses process status when feature flag is enabled and returns status from pantry`() =
            runTest {
                // Given
                val processStatus =
                    AutoreviewPantryStatus(
                        pullRequestUrl = pullRequestUrl,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = null,
                        createdDate = OffsetDateTime.now(),
                        lastUpdatedDate = OffsetDateTime.now(),
                        workspaceAri = testDevaiWorkspaceARI,
                        sourceCommit = "process-status-commit",
                    )

                coEvery {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                } returns processStatus

                // When
                val result = autoreviewService.getLatestWorkflowDetails(pullRequestUrl, transactionContext)

                // Then
                result.status shouldBe AutoreviewProcessStatus.WORKFLOW_COMPLETED
                result.commit shouldBe "process-status-commit"

                coVerify {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                }
                // Should NOT call the workflow-based methods when feature flag is enabled
                coVerify(exactly = 0) { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) }
                coVerify(exactly = 0) {
                    acraClient.submitACRAGetRequest(
                        any(),
                        any<Class<AutoreviewJobDetails>>(),
                        any(),
                    )
                }
            }

        @Test
        fun `getLatestWorkflowDetails returns UNKNOWN when feature flag is enabled but no process status exists`() =
            runTest {
                // Given
                coEvery {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                } returns null

                // When
                val result = autoreviewService.getLatestWorkflowDetails(pullRequestUrl, transactionContext)

                // Then
                result.status shouldBe AutoreviewProcessStatus.UNKNOWN
                result.commit shouldBe ""

                coVerify {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                }
                // Should NOT call the workflow-based methods when feature flag is enabled
                coVerify(exactly = 0) { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(any()) }
                coVerify(exactly = 0) {
                    acraClient.submitACRAGetRequest(
                        any(),
                        any<Class<AutoreviewJobDetails>>(),
                        any(),
                    )
                }
            }

        @Test
        fun `getLatestWorkflowDetails handles different process statuses when feature flag is enabled`() =
            runTest {
                val testCases =
                    listOf(
                        AutoreviewProcessStatus.WORKFLOW_IN_PROGRESS to "in-progress-commit",
                        AutoreviewProcessStatus.WORKFLOW_FAILURE to "failed-commit",
                        AutoreviewProcessStatus.WORKFLOW_COMPLETED to "completed-commit",
                        AutoreviewProcessStatus.UNKNOWN to "unknown-commit",
                    )

                testCases.forEach { (status, commit) ->
                    // Given
                    val processStatus =
                        AutoreviewPantryStatus(
                            pullRequestUrl = pullRequestUrl,
                            status = status,
                            acceptanceCriteriaStatus = null,
                            createdDate = OffsetDateTime.now(),
                            lastUpdatedDate = OffsetDateTime.now(),
                            workspaceAri = testDevaiWorkspaceARI,
                            sourceCommit = commit,
                        )

                    coEvery {
                        autoreviewWorkflowsStorageService.getPullRequestStatus(
                            pullRequestUrl = pullRequestUrl,
                            devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                        )
                    } returns processStatus

                    // When
                    val result = autoreviewService.getLatestWorkflowDetails(pullRequestUrl, transactionContext)

                    // Then
                    result.status shouldBe status
                    result.commit shouldBe commit
                }
            }

        @Test
        fun `getLatestWorkflowDetails uses global workspace ARI when transaction context workspace ARI is null and fg is enabled`() =
            runTest {
                // Given
                val transactionContextWithNullAri =
                    TransactionContext(
                        workspace =
                            WorkspaceContext(
                                null,
                                WorkspaceId(DEV_AI_WORKSPACE_ID),
                                null, // null workspace ARI
                            ),
                        traceId = transactionContext.traceId,
                        userContext = transactionContext.userContext,
                    )
                val processStatus =
                    AutoreviewPantryStatus(
                        pullRequestUrl = pullRequestUrl,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = null,
                        createdDate = OffsetDateTime.now(),
                        lastUpdatedDate = OffsetDateTime.now(),
                        workspaceAri = GLOBAL_WORKSPACE_ARI.toString(),
                        sourceCommit = "global-workspace-commit",
                    )

                coEvery {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(GLOBAL_WORKSPACE_ARI.toString()),
                    )
                } returns processStatus

                // When
                val result = autoreviewService.getLatestWorkflowDetails(pullRequestUrl, transactionContextWithNullAri)

                // Then
                result.status shouldBe AutoreviewProcessStatus.WORKFLOW_COMPLETED
                result.commit shouldBe "global-workspace-commit"

                coVerify {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(GLOBAL_WORKSPACE_ARI.toString()),
                    )
                }
            }

        @Test
        fun `getLatestWorkflowDetails handles exception from getPullRequestStatus when feature flag is enabled`() =
            runTest {
                // Given
                val expectedException = RuntimeException("Database connection failed")

                coEvery {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                } throws expectedException

                // When
                val result = autoreviewService.getLatestWorkflowDetails(pullRequestUrl, transactionContext)

                // Then - The implementation catches exceptions and returns default values
                result.status shouldBe AutoreviewProcessStatus.UNKNOWN
                result.commit shouldBe ""

                coVerify {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                }
            }

        @Test
        fun `getLatestWorkflowDetails returns empty commit when process status has null sourceCommit and feature flag is enabled`() =
            runTest {
                // Given
                val processStatus =
                    AutoreviewPantryStatus(
                        pullRequestUrl = pullRequestUrl,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = null,
                        createdDate = OffsetDateTime.now(),
                        lastUpdatedDate = OffsetDateTime.now(),
                        workspaceAri = testDevaiWorkspaceARI,
                        sourceCommit = "", // Empty source commit to simulate null handling
                    )

                coEvery {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                } returns processStatus

                // When
                val result = autoreviewService.getLatestWorkflowDetails(pullRequestUrl, transactionContext)

                // Then
                result.status shouldBe AutoreviewProcessStatus.WORKFLOW_COMPLETED
                result.commit shouldBe ""

                coVerify {
                    autoreviewWorkflowsStorageService.getPullRequestStatus(
                        pullRequestUrl = pullRequestUrl,
                        devaiWorkspaceAri = DevaiWorkspaceARI.valueOf(testDevaiWorkspaceARI),
                    )
                }
            }
    }

    @Nested
    inner class SubmitManualReviewTests {
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
            coEvery { acraClient.submitAcraWorkflowRun(any(), any()) } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = UUID.randomUUID(),
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
            coEvery { acraClient.submitACRAGetRequest(any(), AutoreviewJobDetails::class.java, any()) } returns
                Mono.just(
                    AutoreviewJobDetails(
                        id = UUID.randomUUID(),
                        currentWorkflow = "test-workflow",
                        status = "COMPLETED",
                        repoUrl = "https://bitbucket.org/test/test",
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
            coEvery {
                autoreviewWorkflowsStorageService.getWorkflowStatusByJobId(any(), any())
            } returns WorkflowRunStatus.COMPLETED
        }

        @Test
        fun `submitManualReview runs first review only when no prior workflows and no jira issues`() =
            runTest {
                // Arrange
                val bodySlot = slot<AcraCreateWorkflowRunRequest>()
                coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl) } returns null
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
                    acraClient.submitAcraWorkflowRun(
                        capture(bodySlot),
                        any(),
                    )
                } returns
                    Mono.just(
                        AcraCreateWorkflowRunResponse(
                            workflowRunId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                            status = AcraWorkflowRunStatus.PENDING,
                            rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                            links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                            queueType = WorkflowRunQueueType.DEFAULT,
                        ),
                    )

                // Act
                val result =
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(any<AcraCreateWorkflowRunRequest>(), any())
                }
                val executionFlags =
                    (bodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact).flags
                executionFlags[AUTOREVIEW_CUSTOM_GENERATOR_ENABLED] shouldBe true
            }

        @Test
        fun `submitManualReview runs first review with ac when no prior workflows and jira issues present`() =
            runTest {
                // Arrange
                val bodySlot = slot<AcraCreateWorkflowRunRequest>()
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
                    acraClient.submitAcraWorkflowRun(capture(bodySlot), any())
                } returns
                    Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = 2) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(
                        match { it.rootWorkflow == RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA },
                        any(),
                    )
                }
            }

        @Test
        fun `submitManualReview runs first review with bracketed ID's`() =
            runTest {
                // Arrange
                val bracketedWorkspaceUuid = "{$bitbucketWorkspaceUuid}"
                val bracketedRepositoryUuid = "{$repositoryUuid}"
                coEvery { autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl) } returns null
                coEvery {
                    acraClient.submitAcraWorkflowRun(any(), any())
                } returns
                    Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)

                // Act
                val result =
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        bracketedRepositoryUuid,
                        bracketedWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(any(), any())
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
                    autoreviewService.submitManualReview(
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
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
                requestBodySlot.captured.rootWorkflow shouldBe RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW
                requestBodySlot.captured.input.artifacts.keys shouldContainExactlyInAnyOrder
                    listOf(
                        SETUP_REPO_JSON,
                        PULL_REQUEST_INFO_JSON,
                        EXECUTION_FLAGS_JSON,
                    )
                requestBodySlot.captured.input.artifacts[SETUP_REPO_JSON] shouldBe
                    SetupRepoArtifact(repoUrl = "https://bitbucket.org/test/test")
                val prInfo = requestBodySlot.captured.input.artifacts[PULL_REQUEST_INFO_JSON] as PullRequestInfoArtifact
                prInfo.pullRequestUrl shouldBe pullRequestUrl
                prInfo.sourceBranch shouldBe "source-branch"
                prInfo.targetBranch shouldBe "destination-branch"
                prInfo.prTitle shouldBe "Test PR"
                prInfo.authorAccountId shouldBe AccountId.of(TEST_AUTHOR_ACCOUNT_ID).toString()
                prInfo.bbcRepoUUID shouldBe repositoryUuid
                prInfo.reviewTriggerType shouldBe ReviewTriggerType.MANUAL
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
                coEvery {
                    acraClient.submitAcraWorkflowRun(any(), any())
                } returns
                    Mono.just(TEST_ACRA_AUTOREVIEW_JOB_SUBMIT_RESPONSE)

                // Act
                val result =
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe if (isSupported) AutoreviewProcessStatus.WORKFLOW_PENDING else AutoreviewProcessStatus.ERROR_GENERIC

                coVerify(exactly = 0) { autoreviewValidationService.isRepoAccepted(any(), any(), any()) }
                coVerify(exactly = if (isSupported) 1 else 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(
                        match { it.rootWorkflow == RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA },
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.WORKFLOW_COMPLETED
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(
                        match { it.rootWorkflow == RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA },
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.WORKFLOW_COMPLETED // returns latest status instead
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.ERROR_GENERIC
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }

        @Test
        fun `throws IllegalArgumentException when pull request details cannot be retrieved`() =
            runTest {
                coEvery { salService.getPullRequestDetails(any()) } returns null

                shouldThrow<IllegalArgumentException> {
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )
                }.message shouldContain "Could not retrieve pull request details"

                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.ERROR_GENERIC
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }

        @Test
        fun `throws BadRequestException when traceId is missing`() =
            runTest {
                val tx = transactionContext.copy(traceId = null)

                shouldThrow<BadRequestException> {
                    autoreviewService.submitManualReview(pullRequestUrl, repositoryUuid, bitbucketWorkspaceUuid, tx)
                }

                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )
                }

                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )
                }

                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }

        @Test
        fun `skips pull request when source commit matches a previous run`() =
            runTest {
                val sourceCommit = "source-commit"
                val prUrl = "https://example.com/pull/123"

                coEvery { autoreviewUtilityService.shouldSkipPullRequest(prUrl, sourceCommit, any()) } returns true
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
                                    jobId = "existing-job-id",
                                    createdDate = OffsetDateTime.now().minusHours(1).toString(),
                                    sourceCommit = sourceCommit,
                                    workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                                ),
                            ),
                    )
                coEvery { featureService.isAutoreviewGetLatestStatusPermissionCheckEnabled(any()) } returns false

                val result =
                    autoreviewService.submitManualReview(
                        prUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.SETTING_DISABLED_REPOSITORY
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }

        @Test
        fun `Should correctly filter non Bitbucket SCM's`() =
            runTest {
                coEvery { salSharedUtil.determineMatchingScm(any(), any()) } returns githubScm

                shouldThrow<IllegalArgumentException> {
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )
                }

                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.UNCONFIGURED_SITE_ROVO_DEV
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.UNCONFIGURED_SITE_ROVO_DEV
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `Should run when workspace is not allowed without dev ai activation`(acceptanceCriteriaEnabled: Boolean) =
            runTest {
                val expectedWorkflowRunCount = if (acceptanceCriteriaEnabled) 2 else 1

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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = expectedWorkflowRunCount) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `Should run when workspace is allowed without dev ai activation`(acceptanceCriteriaEnabled: Boolean) =
            runTest {
                val expectedWorkflowRunCount = if (acceptanceCriteriaEnabled) 2 else 1

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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = expectedWorkflowRunCount) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
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
                coVerify(exactly = expectedRuns) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
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
                    autoreviewService.submitManualReview(
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
                coVerify(exactly = expectedRuns) { acraClient.submitAcraWorkflowRun(any(), any()) }
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

                val spy = spyk(autoreviewService)
                val salPayloadSlot = slot<AutoreviewSalPayload>()
                coEvery {
                    spy.submitIncrementalReview(
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

                val spy = spyk(autoreviewService)
                val salPayloadSlot = slot<AutoreviewSalPayload>()
                coEvery {
                    spy.submitIncrementalReview(
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

                autoreviewService.submitManualReview(
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

                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                    coVerify(exactly = if (nonBillingSiteACEnabled) 1 else 0) {
                        acraClient.submitAcraWorkflowRun(
                            match {
                                val isAcceptanceCriteriaWorkflow =
                                    it.rootWorkflow == RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
                                val containsExampleJiraIssue =
                                    (it.input.artifacts[PULL_REQUEST_INFO_JSON] as? PullRequestInfoArtifact).let { prInfoArtifact ->
                                        prInfoArtifact
                                            ?.jiraIssues
                                            ?.map { it.issueAri }
                                            ?.contains(exampleJiraIssueAri.toString()) ?: false
                                    }
                                isAcceptanceCriteriaWorkflow && containsExampleJiraIssue
                            },
                            any(),
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.SETTING_DISABLED_ROVO_DEV_ORG
                coVerify(exactly = 0) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
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
                    autoreviewService.submitManualReview(
                        pullRequestUrl,
                        repositoryUuid,
                        bitbucketWorkspaceUuid,
                        transactionContext,
                    )

                // Assert
                result shouldBe AutoreviewProcessStatus.WORKFLOW_PENDING
                coVerify(exactly = 0) { autoreviewSettingsService.getDevAiWorkspaceAutoreviewSettingsValue(any()) }
            }
    }

    @Nested
    inner class GetRovoDevAccessTests {
        private val testWorkspaceUuid = UUID.randomUUID().toString()
        private val testUserAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9"
        private val testCloudId = "cloud-id-123"
        private val testTransactionContext =
            TransactionContext(
                workspace = WorkspaceContext.create("", GLOBAL_WORKSPACE_ARI),
                null,
                userContext =
                    UserContext(
                        accountId = AccountId.of(testUserAccountId),
                        null,
                        "123456789",
                        null,
                    ),
            )

        @Test
        fun `throws error when account id is null`() =
            runTest {
                shouldThrow<Exception> {
                    autoreviewService.getRovoDevAccess(
                        testWorkspaceUuid,
                        TransactionContext(
                            workspace = WorkspaceContext.create("", GLOBAL_WORKSPACE_ARI),
                            null,
                            null,
                        ),
                    )
                }
            }

        @Test
        fun `throws exception when settings not found`() =
            runTest {
                coEvery {
                    autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                        SettingContainerType.BITBUCKET_WORKSPACE,
                        BitbucketWorkspaceARI.from(testWorkspaceUuid).toString(),
                    )
                } returns null

                shouldThrow<IllegalStateException> {
                    autoreviewService.getRovoDevAccess(
                        testWorkspaceUuid,
                        testTransactionContext,
                    )
                }
            }

        @Test
        fun `throws exception when cloud ID association not found`() =
            runTest {
                coEvery {
                    autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                        SettingContainerType.BITBUCKET_WORKSPACE,
                        TEST_BITBUCKET_WORKSPACE_UUID,
                    )
                } returns
                    SettingValue(
                        value =
                            AutoreviewWorkspaceSettingAttributes(
                                autoreview_activated = true,
                                autoreview_ip_allowlist_enabled = true,
                                autoreview_cloud_id_association = null,
                            ),
                        lastUpdatedTime = OffsetDateTime.now(),
                    )

                shouldThrow<IllegalArgumentException> {
                    autoreviewService.getRovoDevAccess(
                        testWorkspaceUuid,
                        testTransactionContext,
                    )
                }
            }

        @Test
        fun `returns when all other checks pass`() =
            runTest {
                coEvery {
                    autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                        SettingContainerType.BITBUCKET_WORKSPACE,
                        BitbucketWorkspaceARI.from(testWorkspaceUuid).toString(),
                    )
                } returns
                    SettingValue(
                        value =
                            AutoreviewWorkspaceSettingAttributes(
                                autoreview_activated = true,
                                autoreview_ip_allowlist_enabled = true,
                                autoreview_cloud_id_association = testCloudId,
                            ),
                        lastUpdatedTime = OffsetDateTime.now(),
                    )

                coEvery {
                    autoreviewValidationService.getUserRovoDevAccess(testCloudId, testUserAccountId, any())
                } returns UserRovoDevAccess(true, true, "test-url")

                val result = autoreviewService.getRovoDevAccess(testWorkspaceUuid, testTransactionContext)
                result shouldBe UserRovoDevAccess(true, true, "test-url")
            }
    }

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

    @Nested
    inner class PostIssueComment {
        @BeforeEach
        fun setUp() {
            coEvery { idGatekeeperClient.mintUct(any()) } returns
                MintUctResponse(
                    key = "key1",
                    context =
                        MintUctResponse.Context(
                            token = "user-context-token-example",
                        ),
                )
        }
    }

    @Nested
    inner class HandlePullRequestUpdated {
        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `submit incremental review`(featureGateValue: Boolean) =
            runTest {
                val workflowRunId = UUID.randomUUID()
                val rootWorkflow = RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW
                val sourceCommit = "sourceCommit"

                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        associations =
                            listOf(
                                Association(
                                    type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                    ari = TEST_JIRA_ISSUE_ARI,
                                ),
                            ),
                        sourceCommit = sourceCommit,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns true
                mockkStatic(::isRepoEnabledForIncrementalReview)
                every { isRepoEnabledForIncrementalReview(any(), any()) } returns true
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns featureGateValue
                coEvery {
                    acraClient.submitAcraWorkflowRun(any(), any())
                } returns
                    Mono.just(
                        AcraCreateWorkflowRunResponse(
                            workflowRunId = workflowRunId,
                            status = AcraWorkflowRunStatus.PENDING,
                            rootWorkflow = rootWorkflow,
                            links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                            queueType = WorkflowRunQueueType.DEFAULT,
                        ),
                    )

                autoreviewService.handlePullRequestUpdated(salPayload)
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                when (featureGateValue) {
                    true -> {
                        coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
                        requestBodySlot.captured.rootWorkflow shouldBe rootWorkflow
                    }

                    false -> {
                        coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
                    }
                }
            }

        @Test
        fun `does not submit incremental review to non dogfooding repos`() =
            runTest {
                val sourceCommit = "sourceCommit"

                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        associations =
                            listOf(
                                Association(
                                    type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                    ari = TEST_JIRA_ISSUE_ARI,
                                ),
                            ),
                        sourceCommit = sourceCommit,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns true
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true
                mockkStatic(::isRepoEnabledForIncrementalReview)
                every { isRepoEnabledForIncrementalReview(any(), true) } returns true
                every { isRepoEnabledForIncrementalReview(any(), false) } returns false

                autoreviewService.handlePullRequestUpdated(salPayload)
                coVerify(exactly = 0) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }
    }

    @Nested
    inner class IncrementalReview {
        val workflowRunId = UUID.randomUUID()
        val rootWorkflow = RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW
        val sourceCommit = "sourceCommit"
        val emptyIssuesHash = "e56a5c68-e9bd-3b27-9bc9-1341fc0caa9f"

        val salPayload =
            createSalAutoreviewPayload(
                prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                associations = listOf(Association(type = JIRA_ISSUE_ASSOCIATION_TYPE, ari = TEST_JIRA_ISSUE_ARI)),
                sourceCommit = sourceCommit,
                workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
            )

        @BeforeEach
        fun setup() {
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
        }

        @Test
        fun `submits acra workflow`() {
            runTest {
                autoreviewService.submitIncrementalReview(salPayload, emptyList())

                val bodySlot = slot<AcraCreateWorkflowRunRequest>()
                val headersSlot = slot<AcraClientCustomHeaders>()
                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(
                        requestBody = capture(bodySlot),
                        acraClientCustomHeaders = capture(headersSlot),
                    )
                }
                bodySlot.captured.rootWorkflow shouldBe rootWorkflow
                bodySlot.captured.queueType shouldBe WorkflowRunQueueType.DEFAULT
                bodySlot.captured.input.artifacts.keys shouldContainExactlyInAnyOrder
                    listOf(
                        SETUP_REPO_JSON,
                        PULL_REQUEST_INFO_JSON,
                        EXECUTION_FLAGS_JSON,
                    )

                headersSlot.captured.workspaceAri shouldBe TEST_DEVAI_WORKSPACE_ARI
                headersSlot.captured.traceId shouldBe salPayload.traceId
            }
        }

        @Test
        fun `stores workflow association`() {
            runTest {
                autoreviewService.submitIncrementalReview(salPayload, emptyList())

                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storeAutoreviewWorkflows(
                        pullRequestUrl = salPayload.prDetail.url.toString(),
                        workflowId = workflowRunId.toString(),
                        rootWorkflowName = rootWorkflow,
                        sourceCommit = sourceCommit,
                        issuesHash = emptyIssuesHash,
                        workspaceAri = ARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                    )
                }
            }
        }
    }

    @Nested
    inner class CustomReview {
        val workflowRunId = UUID.randomUUID()
        val rootWorkflow = RootWorkflowName.AUTOREVIEW_CUSTOM_REVIEW
        val cloudId = CloudIdLike.fromString(UUID.randomUUID().toString())

        @Test
        fun `should create workflow in ACRA with correct artifacts successfully`() {
            runTest {
                coEvery { acraClient.submitAcraWorkflowRun(any(), any()) } returns
                    Mono.just(
                        AcraCreateWorkflowRunResponse(
                            workflowRunId = workflowRunId,
                            status = AcraWorkflowRunStatus.PENDING,
                            rootWorkflow = rootWorkflow,
                            links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                            queueType = WorkflowRunQueueType.DEFAULT,
                        ),
                    )

                val result =
                    autoreviewService.submitAutoreviewCustomReview(
                        pullRequestUrl = URI("https://bitbucket.org/test/pull-requests/1").toURL(),
                        repositoryUrl = URI("https://bitbucket.org/test").toURL(),
                        sourceBranchName = "test-feature-branch",
                        destinationBranchName = "master",
                        accountId = "test-account-id",
                        customPrompt = "please review this",
                        ruleActionUrl = URI("https://test-url.com/test").toURL(),
                        prTitle = "test title",
                        prDescription = "test description",
                        bbcWorkspaceUUID = "abc",
                        devAiWorkspaceAri = ARI.valueOf("ari:cloud:devai::workspace/00000000-0000-0000-0000-000000000000"),
                        traceId = "sample-trace",
                        sourceCommit = "source-commit-hash",
                        cloudId = cloudId,
                    )

                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                result.body?.workflowRunId shouldBe workflowRunId
                requestBodySlot.captured.rootWorkflow shouldBe RootWorkflowName.AUTOREVIEW_CUSTOM_REVIEW
                requestBodySlot.captured.input.artifacts.size shouldBe 4
                requestBodySlot.captured.input.artifacts.keys shouldContainExactlyInAnyOrder
                    listOf(
                        SETUP_REPO_JSON,
                        PULL_REQUEST_INFO_JSON,
                        EXECUTION_FLAGS_JSON,
                        AUTOREVIEW_CUSTOM_PROMPT_JSON,
                    )
                requestBodySlot.captured.input.artifacts
                    .get(SETUP_REPO_JSON) shouldBe
                    SetupRepoArtifact(
                        repoUrl = "https://bitbucket.org/test",
                    )
                requestBodySlot.captured.input.artifacts
                    .get(PULL_REQUEST_INFO_JSON) shouldBe
                    PullRequestInfoArtifact(
                        pullRequestUrl = "https://bitbucket.org/test/pull-requests/1",
                        sourceBranch = "test-feature-branch",
                        targetBranch = "master",
                        prTitle = "test title",
                        prDescription = "test description",
                        jiraIssues = emptyList(),
                        authorAccountId = "test-account-id",
                        workspaceId = "abc",
                    )
                requestBodySlot.captured.input.artifacts
                    .get(AUTOREVIEW_CUSTOM_PROMPT_JSON) shouldBe
                    AutoreviewCustomPromptArtifact(
                        prompt =
                            CustomPrompt(
                                content = "please review this",
                                link = URI("https://test-url.com/test").toURL(),
                            ),
                    )
            }
        }

        @Test
        fun `should return error on failure`() {
            runTest {
                coEvery { acraClient.submitAcraWorkflowRun(any(), any()) } throws IllegalArgumentException("Wrong URL")

                val exception =
                    shouldThrow<IllegalArgumentException> {
                        autoreviewService.submitAutoreviewCustomReview(
                            pullRequestUrl = URI("https://bitbucket.org/test/pull-requests/1").toURL(),
                            repositoryUrl = URI("https://bitbucket.org/test").toURL(),
                            sourceBranchName = "test-feature-branch",
                            destinationBranchName = "master",
                            accountId = "test-account-id",
                            customPrompt = "please review this",
                            ruleActionUrl = URI("https://test-url.com/test").toURL(),
                            prTitle = "test title",
                            prDescription = "test description",
                            bbcWorkspaceUUID = "abc",
                            devAiWorkspaceAri = ARI.valueOf("ari:cloud:devai::workspace/00000000-0000-0000-0000-000000000000"),
                            traceId = "sample-trace",
                            sourceCommit = "source-commit-hash",
                            cloudId = cloudId,
                        )
                    }

                exception.message shouldBe "Wrong URL"
            }
        }
    }

    @Nested
    inner class Headers {
        @BeforeEach
        fun setup() {
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = UUID.randomUUID(),
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
        }

        @Test
        fun `submitAutoreviewWorkflow should send correct headers to ACRA`() =
            runTest {
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                        workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                        cloudId = CloudIdLike.fromString(TEST_CLOUD_ID),
                    )

                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                val headersSlot = slot<AcraClientCustomHeaders>()
                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(
                        any(),
                        acraClientCustomHeaders = capture(headersSlot),
                    )
                }

                headersSlot.captured.workspaceAri shouldBe TEST_DEVAI_WORKSPACE_ARI
                headersSlot.captured.cloudId shouldBe salPayload.cloudId.toString()
                headersSlot.captured.accountId shouldBe
                    salPayload.prDetail.author.accountId
                        .toString()
                headersSlot.captured.traceId shouldBe salPayload.traceId
            }
    }

    @Nested
    inner class SubmitAutoreviewWorkflow {
        private val workflowRunId = UUID.randomUUID()
        private val rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW

        @BeforeEach
        fun setup() {
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
        }

        @Test
        fun `submitAutoreviewWorkflow successfully without associations`() {
            runTest {
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        associations = emptyList(),
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                verify(exactly = 1) {
                    autoreviewAnalyticsService.sendAutoreviewUnifiedAIInitiatedEvent(any(), any())
                }

                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }

        @Test
        fun `submitAutoreviewWorkflow successfully with associations`() {
            runTest {
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        associations =
                            listOf(
                                Association(
                                    type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                    ari = TEST_JIRA_ISSUE_ARI,
                                ),
                            ),
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
                autoreviewService.submitAutoreviewWorkflow(salPayload, jiraIssues)

                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
                coVerify(exactly = 1) {
                    atlassianProxyClient.getIssueDetails(
                        TEST_CLOUD_ID,
                        TEST_ISSUE_ID,
                        TEST_AUTHOR_ACCOUNT_ID,
                    )
                }
            }
        }

        @Test
        fun `submitAutoreviewWorkflow creates correct artifacts`() {
            runTest {
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        associations =
                            listOf(
                                Association(
                                    type = JIRA_ISSUE_ASSOCIATION_TYPE,
                                    ari = TEST_JIRA_ISSUE_ARI,
                                ),
                            ),
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                val jiraIssues = autoreviewService.retrieveJiraIssueDetails(salPayload)
                autoreviewService.submitAutoreviewWorkflow(salPayload, jiraIssues)

                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                requestBodySlot.captured.rootWorkflow shouldBe RootWorkflowName.AUTOREVIEW_FIRST_REVIEW
                requestBodySlot.captured.queueType shouldBe WorkflowRunQueueType.DEFAULT
                requestBodySlot.captured.input.artifacts.keys shouldContainExactlyInAnyOrder
                    listOf(
                        SETUP_REPO_JSON,
                        PULL_REQUEST_INFO_JSON,
                        EXECUTION_FLAGS_JSON,
                    )

                val setupRepoArtifact = requestBodySlot.captured.input.artifacts[SETUP_REPO_JSON] as SetupRepoArtifact
                setupRepoArtifact.repoUrl shouldBe TEST_REPO_URL

                val pullRequestInfoArtifact =
                    requestBodySlot.captured.input.artifacts[PULL_REQUEST_INFO_JSON] as PullRequestInfoArtifact
                pullRequestInfoArtifact.pullRequestUrl shouldBe TEST_PR_URL
                pullRequestInfoArtifact.sourceBranch shouldBe "sourceBranch"
                pullRequestInfoArtifact.targetBranch shouldBe "destinationBranch"
                pullRequestInfoArtifact.prTitle shouldBe "PR Title"
                pullRequestInfoArtifact.authorAccountId shouldBe TEST_AUTHOR_ACCOUNT_ID
                pullRequestInfoArtifact.jiraIssues shouldHaveSize 1
                pullRequestInfoArtifact.jiraIssues[0].issueAri shouldBe TEST_JIRA_ISSUE_ARI

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact
                executionFlagsArtifact.flags.isNotEmpty() shouldBe true
            }
        }

        @Test
        fun `submitAutoreviewWorkflow uses correct ACRA client headers`() {
            runTest {
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                val headersSlot = slot<AcraClientCustomHeaders>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(any(), capture(headersSlot)) }

                headersSlot.captured.workspaceAri shouldBe TEST_DEVAI_WORKSPACE_ARI
                headersSlot.captured.traceId shouldBe "traceId"
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "123", TEST_DEFAULT_AI_WORKSPACE_ID, "{$TEST_DEFAULT_AI_WORKSPACE_ID}", "atlassian-mikebuller"])
        fun `submitAutoreviewWorkflow successfully with different workspaceId values`(workspaceId: String) {
            runTest {
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = workspaceId,
                    )

                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                coVerify(exactly = 1) {
                    acraClient.submitAcraWorkflowRun(any(), any())
                }
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `submitAutoreviewWorkflow successfully with CS (single and multi-line) due to enabled repository`(isEnabled: Boolean) {
            runTest {
                // Arrange
                val repository = "https://bitbucket.org/mikebuller/autoreview-test"
                val salPayload =
                    createSalAutoreviewPayload(
                        workspaceId = ATLASSIAN_BITBUCKET_WORKSPACE_UUID,
                        repositoryUrl = URI.create(repository).toURL(),
                    )
                coEvery { featureService.isAutoreviewCodeSuggestionsEnabled(any()) } returns isEnabled
                coEvery { featureService.getAutoreviewCodeSuggestionsConfig(any(), any()) } returns
                    AutoreviewCodeSuggestionsConfig(isMultilineEnabled = isEnabled)

                // Act
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact
                val isCodeSuggestionsEnabled = executionFlagsArtifact.flags[AUTOREVIEW_ENABLE_CODE_SUGGESTIONS]
                val isMultiLineCodeSuggestionsEnabled =
                    executionFlagsArtifact.flags[AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED]

                // Assert
                if (isEnabled) {
                    isCodeSuggestionsEnabled shouldBe true
                    isMultiLineCodeSuggestionsEnabled shouldBe true
                } else {
                    isCodeSuggestionsEnabled shouldBe false
                    isMultiLineCodeSuggestionsEnabled shouldBe false
                }
            }
        }

        @Test
        fun `submitAutoreviewWorkflow stores pull request association in Pantry`() {
            val sourceCommit = "sourceCommit"
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = emptyList(),
                    sourceCommit = sourceCommit,
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                )

            runTest {
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())
                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storeAutoreviewWorkflows(
                        pullRequestUrl = salPayload.prDetail.url.toString(),
                        workflowId = workflowRunId.toString(),
                        rootWorkflowName = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                        sourceCommit = sourceCommit,
                        issuesHash = "e56a5c68-e9bd-3b27-9bc9-1341fc0caa9f", // Hash for empty issues list
                        workspaceAri = ARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                    )
                }
            }
        }

        @Test
        fun `submitAutoreviewWorkflow includes execution flags with correct feature gate values`() {
            runTest {
                // Arrange
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                coEvery { featureService.isAutoreviewCodeSuggestionRationaleEnabled(any()) } returns true
                coEvery { featureService.isAutoreviewOneLineReplacedCodeSuggestionEnabled() } returns true
                coEvery { featureService.isAutoreviewCustomGeneratorEnabled(any()) } returns true
                coEvery { featureService.isAutoreviewCustomInstructionsAnnotatorEnabled() } returns true

                // Act
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                // Assert
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact
                executionFlagsArtifact.flags[AUTOREVIEW_ENABLE_CODE_SUGGESTIONS_RATIONALE] shouldBe true
                executionFlagsArtifact.flags[AUTOREVIEW_CODE_SUGGESTIONS_ALLOW_ONE_LINE_REPLACED] shouldBe true
                executionFlagsArtifact.flags[AUTOREVIEW_CUSTOM_GENERATOR_ENABLED] shouldBe true
                executionFlagsArtifact.flags[AUTOREVIEW_CUSTOM_INSTRUCTIONS_ANNOTATOR_ENABLED] shouldBe true
            }
        }

        @Test
        fun `submitAutoreviewWorkflow without customisation`() {
            runTest {
                // Arrange
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                        autoreviewEventTypes = listOf(AutoreviewEventType.AUTOREVIEW_MAIN),
                    )

                coEvery { featureService.isAutoreviewCustomGeneratorEnabled(any()) } returns true
                coEvery { featureService.isAutoreviewCustomInstructionsAnnotatorEnabled() } returns true
                coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true

                // Act
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                // Assert
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact
                executionFlagsArtifact.flags[AUTOREVIEW_CUSTOM_GENERATOR_ENABLED] shouldBe false
                executionFlagsArtifact.flags[AUTOREVIEW_CUSTOM_INSTRUCTIONS_ANNOTATOR_ENABLED] shouldBe false
            }
        }

        @Test
        fun `submitAutoreviewWorkflow handles empty jira issues correctly`() {
            runTest {
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        associations = emptyList(),
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val pullRequestInfoArtifact =
                    requestBodySlot.captured.input.artifacts[PULL_REQUEST_INFO_JSON] as PullRequestInfoArtifact
                pullRequestInfoArtifact.jiraIssues shouldBe emptyList()
            }
        }

        @Test
        fun `submitAutoreviewWorkflow propagates exception when ACRA client fails`() {
            runTest {
                // Arrange
                val expectedException = RuntimeException("ACRA client error")
                coEvery { acraClient.submitAcraWorkflowRun(any(), any()) } returns Mono.error(expectedException)

                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                // Act & Assert
                val exception =
                    assertThrows<RuntimeException> {
                        autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())
                    }
                exception shouldBe expectedException
            }
        }

        @Test
        fun `submitAutoreviewWorkflow includes bbcRepoUUID in pull request info artifact`() {
            runTest {
                val bbcRepoUUID = "test-bbc-repo-uuid"
                val basePrDetail =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    ).prDetail
                val cloudId = CloudIdLike.fromString(UUID.randomUUID().toString())

                val salPayload =
                    AutoreviewSalPayload(
                        traceId = "traceId",
                        type = AutoreviewSalPayloadType.CREATE,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                        workspace = ARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                        cloudId = cloudId,
                        resource = ARI.valueOf(TEST_RESOURCE_ARI),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        prDetail =
                            PRDetail(
                                url = basePrDetail.url,
                                title = basePrDetail.title,
                                id = basePrDetail.id,
                                sourceBranch = basePrDetail.sourceBranch,
                                destinationBranch = basePrDetail.destinationBranch,
                                author = basePrDetail.author,
                                sourceCommit = basePrDetail.sourceCommit,
                                bbcRepoUUID = bbcRepoUUID,
                            ),
                        associations = emptyList(),
                        autoreviewEventTypes = listOf(AutoreviewEventType.AUTOREVIEW_MAIN),
                    )

                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val pullRequestInfoArtifact =
                    requestBodySlot.captured.input.artifacts[PULL_REQUEST_INFO_JSON] as PullRequestInfoArtifact
                pullRequestInfoArtifact.bbcRepoUUID shouldBe bbcRepoUUID
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `submitAutoreviewWorkflow creates one time run pantry entry if acra workflow successful and FG enabled`(isEnabled: Boolean) {
            runTest {
                // Arrange
                val salPayload =
                    createSalAutoreviewPayload(
                        workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )
                coEvery { featureService.isAutoreviewCodingStandardsExtractorEnabled(any(), any()) } returns isEnabled
                coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true
                coEvery {
                    autoreviewWorkflowsStorageService.getOneTimeRuns(
                        DevaiWorkspaceARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                        TEST_REPO_URL,
                    )
                } returns null // no prior runs

                // Act
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()

                // Assert
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }
                coVerify(exactly = if (isEnabled) 1 else 0) {
                    autoreviewWorkflowsStorageService.createOneTimeRunPantryEntry(
                        DevaiWorkspaceARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                        TEST_REPO_URL,
                        any(),
                    )
                }

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact
                executionFlagsArtifact.flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe if (isEnabled) true else false
            }
        }

        @Test
        fun `submitAutoreviewWorkflow continues even if creating one time run pantry entry throws exception`() {
            runTest {
                // Arrange
                val salPayload =
                    createSalAutoreviewPayload(
                        workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )
                coEvery { featureService.isAutoreviewCodingStandardsExtractorEnabled(any(), any()) } returns true
                coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true
                coEvery {
                    autoreviewWorkflowsStorageService.getOneTimeRuns(
                        DevaiWorkspaceARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                        TEST_REPO_URL,
                    )
                } returns null // no prior runs
                coEvery {
                    autoreviewWorkflowsStorageService.createOneTimeRunPantryEntry(
                        DevaiWorkspaceARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                        TEST_REPO_URL,
                        UUID.randomUUID().toString(),
                    )
                } throws Exception("Failed to create one time run pantry entry")

                coEvery {
                    acraClient.submitAcraWorkflowRun(any(), any())
                } returns
                    Mono.just(
                        AcraCreateWorkflowRunResponse(
                            workflowRunId = workflowRunId,
                            status = AcraWorkflowRunStatus.PENDING,
                            rootWorkflow = rootWorkflow,
                            links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                            queueType = WorkflowRunQueueType.DEFAULT,
                        ),
                    )

                // Act & Assert
                shouldNotThrowAny { autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList()) }
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(any(), any()) }
            }
        }
    }

    @Nested
    inner class IsUsingAutoreviewGitHubIpAllowlistPipelinesTests {
        @Test
        fun `should return false when repositoryUrl is null`() =
            runTest {
                // Act
                val result = autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(null, transactionContext)

                // Assert
                result shouldBe false
            }

        @Test
        fun `should return false when repository is not GitHub`() =
            runTest {
                // Arrange
                val bitbucketUrl = URI.create("https://bitbucket.org/workspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "bitbucket",
                        domain = "bitbucket.org",
                        workspaceName = "workspace",
                        repoSlug = "repo",
                    )

                coEvery { salSharedUtil.validateAndExtractRepoDetails(bitbucketUrl.toString()) } returns urlDetails

                // Act
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(bitbucketUrl, transactionContext)

                // Assert
                result shouldBe false
            }

        @Test
        fun `should return true when repo is opt-in ip allowlist`() {
            runTest {
                // Arrange
                val githubRepoUrl = URI.create("https://github.com/testworkspace/repo").toURL()
                val githubWorkspaceUrl = URI.create("https://github.com/testworkspace").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "testworkspace",
                        repoSlug = "repo",
                    )
                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubRepoUrl.toString()) } returns urlDetails

                val settingValue =
                    SettingValue(
                        value =
                            AutoreviewWorkspaceSettingAttributes(
                                autoreview_activated = true,
                                autoreview_ip_allowlist_enabled = true,
                            ),
                        lastUpdatedTime = OffsetDateTime.now(),
                        containerId = githubWorkspaceUrl.toString(),
                    )

                coEvery {
                    autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                        SettingContainerType.WORKSPACE,
                        githubWorkspaceUrl.toString(),
                    )
                } returns settingValue

                // Execute
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(
                        githubRepoUrl,
                        transactionContext,
                    )

                // Assert
                result shouldBe true
                coVerify(exactly = 0) { featureService.getStringListConfig(any(), any()) }
            }
        }

        @Test
        fun `should return true when GitHub workspace is in allowlist`() =
            runTest {
                // Arrange
                val githubUrl = URI.create("https://github.com/testworkspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "testworkspace",
                        repoSlug = "repo",
                    )
                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(transactionContext) } returns
                    allowlistedWorkspaces

                // Act
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(githubUrl, transactionContext)

                // Assert
                result shouldBe true
            }

        @Test
        fun `should return false when GitHub workspace is not in allowlist`() =
            runTest {
                // Arrange
                val githubUrl = URI.create("https://github.com/notallowedworkspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "notallowedworkspace",
                        repoSlug = "repo",
                    )
                val allowlistedWorkspaces =
                    listOf("https://github.com/testworkspace", "https://github.com/otherworkspace")

                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(transactionContext) } returns
                    allowlistedWorkspaces

                // Act
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(githubUrl, transactionContext)

                // Assert
                result shouldBe false
            }

        @Test
        fun `should return true when GitHub workspace is in allowlist with case insensitive matching`() =
            runTest {
                // Arrange
                val githubUrl = URI.create("https://github.com/TestWorkspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "TestWorkspace",
                        repoSlug = "repo",
                    )
                val allowlistedWorkspaces =
                    listOf("https://github.com/testworkspace", "https://github.com/otherworkspace")

                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(transactionContext) } returns
                    allowlistedWorkspaces

                // Act
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(githubUrl, transactionContext)

                // Assert
                result shouldBe true
            }

        @Test
        fun `should return false when allowlist is empty`() =
            runTest {
                // Arrange
                val githubUrl = URI.create("https://github.com/testworkspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "testworkspace",
                        repoSlug = "repo",
                    )
                val allowlistedWorkspaces = emptyList<String>()

                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(transactionContext) } returns
                    allowlistedWorkspaces

                // Act
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(githubUrl, transactionContext)

                // Assert
                result shouldBe false
            }

        @Test
        fun `should handle null transaction context`() =
            runTest {
                // Arrange
                val githubUrl = URI.create("https://github.com/testworkspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "testworkspace",
                        repoSlug = "repo",
                    )
                val allowlistedWorkspaces = listOf("https://github.com/testworkspace")

                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(null) } returns allowlistedWorkspaces

                // Act
                val result = autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(githubUrl, null)

                // Assert
                result shouldBe true
            }

        @Test
        fun `should handle GitHub Enterprise URLs`() =
            runTest {
                // Arrange
                val githubEnterpriseUrl = URI.create("https://github.enterprise.com/testworkspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.enterprise.com",
                        workspaceName = "testworkspace",
                        repoSlug = "repo",
                    )
                val allowlistedWorkspaces = listOf("https://github.enterprise.com/testworkspace")

                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubEnterpriseUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(transactionContext) } returns
                    allowlistedWorkspaces

                // Act
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(
                        githubEnterpriseUrl,
                        transactionContext,
                    )

                // Assert
                result shouldBe true
            }

        @Test
        fun `should handle workspace names with special characters`() =
            runTest {
                // Arrange
                val githubUrl = URI.create("https://github.com/test-workspace_123/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "test-workspace_123",
                        repoSlug = "repo",
                    )
                val allowlistedWorkspaces = listOf("https://github.com/test-workspace_123")

                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(transactionContext) } returns
                    allowlistedWorkspaces

                // Act
                val result =
                    autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(githubUrl, transactionContext)

                // Assert
                result shouldBe true
            }

        @Test
        fun `should propagate exception when feature service throws exception`() =
            runTest {
                // Arrange
                val githubUrl = URI.create("https://github.com/testworkspace/repo").toURL()
                val urlDetails =
                    UrlDetails(
                        scm = "github",
                        domain = "github.com",
                        workspaceName = "testworkspace",
                        repoSlug = "repo",
                    )

                coEvery { salSharedUtil.validateAndExtractRepoDetails(githubUrl.toString()) } returns urlDetails
                coEvery { featureService.getAutoreviewGitHubWorkspacesUseIPAllowlist(transactionContext) } throws
                    RuntimeException("Feature service error")

                // Act & Assert
                assertThrows<RuntimeException> {
                    runTest {
                        autoreviewService.isUsingAutoreviewGitHubIpAllowlistPipelines(githubUrl, transactionContext)
                    }
                }
            }
    }

    @Nested
    inner class PostPreCheckErrorCommentTests {
        @BeforeEach
        fun setup() {
            coEvery { salService.createPrComment(any()) } returns
                BaseIntegrationsServiceResponse(
                    operationType = "CREATE",
                    operationStatus = "SUCCESS",
                    entityType = "comment",
                    entities =
                        listOf(
                            CommentCreatedEntity(id = 2030L),
                        ),
                )
        }
    }

    @Nested
    inner class PostGithubStatusCommentTests {
        @BeforeEach
        fun setup() {
            coEvery { salService.createPrComment(any()) } returns
                BaseIntegrationsServiceResponse(
                    operationType = "CREATE",
                    operationStatus = "SUCCESS",
                    entityType = "comment",
                    entities =
                        listOf(
                            CommentCreatedEntity(id = 2030L),
                        ),
                )
        }
    }

    @Nested
    inner class ExecutionFlags {
        @ParameterizedTest
        @CsvSource(
            "'', $MODEL_IN_COMMENT_ANNOTATION",
            "gpt-5-2025-08-07, $MODEL_IN_COMMENT_ANNOTATION",
            "'', $AUTOREVIEW_CONTEXT_ENGINEERING_MODEL",
            "gpt-5-2025-08-07, $AUTOREVIEW_CONTEXT_ENGINEERING_MODEL",
        )
        fun `sets model execution flags based on feature gates`(
            model: String,
            flagKey: String,
        ) = runTest {
            when (flagKey) {
                MODEL_IN_COMMENT_ANNOTATION -> {
                    coEvery { featureService.getAutoreviewCommentAnnotationModel(any()) } returns model
                }

                AUTOREVIEW_CONTEXT_ENGINEERING_MODEL -> {
                    coEvery { featureService.getAutoreviewContextEngineeringModel(any()) } returns model
                }
            }

            val flags = autoreviewService.getExecutionFlags()
            flags[flagKey] shouldBe model
        }

        @ParameterizedTest
        @CsvSource(
            "'NEMO', '{\"autoreviewAcraMiniGeneratorEnabled\": true,  \"modelInCommentGeneration\": \"claude-sonnet-4-5@20250929\",  \"autoreviewGeneralGeneratorEnabled\": false,  \"autoreviewAcraMiniUseFactualCorrectness\": false,  \"autoreviewNemoGeneratorFileName\": \"nemo\",  \"autoreviewGenerationUseReasoning\": true,  \"autoreviewContext7Enabled\": false,  \"autoreviewExperimentalCommentRankerThreshold\": 0.34,  \"autoreviewNemoSimilarIssueDiffsContextEnabled\": true,  \"autoreviewNemoSimilarIssuePrCommentsContextEnabled\": true,  \"autoreviewNemoRecentPrsContextEnabled\": true,  \"autoreviewEnableUsingJiraContextInPrompt\": true}'",
            "'GENERAL', '{\"autoreviewAcraMiniGeneratorEnabled\": false,  \"modelInCommentGeneration\": \"claude-sonnet-4@20250514\",  \"autoreviewGeneralGeneratorEnabled\": true,  \"autoreviewAcraMiniUseFactualCorrectness\": true,  \"autoreviewAcraMiniNemoVersion\": \"0.5.1.dev1+mgupta14.exp2\",  \"autoreviewCommentRankerThreshold\": 0.32,  \"autoreviewNemoSimilarIssueDiffsContextEnabled\": false,  \"autoreviewNemoSimilarIssuePrCommentsContextEnabled\": false,  \"autoreviewNemoRecentPrsContextEnabled\": false,  \"autoreviewEnableUsingJiraContextInPrompt\": false }'",
        )
        fun `sets agent experiment execution flags based on dynamic config`(
            variant: String,
            config: String,
        ) = runTest {
            coEvery { featureService.getAutoreviewAgentExperimentConfig(any(), any()) } returns
                objectMapper.readValue<AutoreviewAgentExperimentConfig>(config)

            val flags = autoreviewService.getExecutionFlags()
            when (variant) {
                "NEMO" -> {
                    flags[AUTOREVIEW_ACRA_MINI_GENERATOR_ENABLED] shouldBe true
                    flags[MODEL_IN_COMMENT_GENERATION] shouldBe "claude-sonnet-4-5@20250929"
                    flags[AUTOREVIEW_GENERAL_GENERATOR_ENABLED] shouldBe false
                    flags[AUTOREVIEW_ACRA_MINI_USE_FACTUAL_CORRECTNESS] shouldBe false
                    flags[AUTOREVIEW_NEMO_GENERATOR_FILE_NAME] shouldBe "nemo"
                    flags[AUTOREVIEW_GENERATION_USE_REASONING] shouldBe true
                    flags[AUTOREVIEW_CONTEXT7_ENABLED] shouldBe false
                    flags[AUTOREVIEW_EXPERIMENTAL_COMMENT_RANKER_THRESHOLD] shouldBe 0.34
                    flags[AUTOREVIEW_NEMO_SIMILAR_ISSUE_DIFFS_CONTEXT_ENABLED] shouldBe true
                    flags[AUTOREVIEW_NEMO_SIMILAR_ISSUE_PR_COMMENTS_CONTEXT_ENABLED] shouldBe true
                    flags[AUTOREVIEW_NEMO_RECENT_PRS_CONTEXT_ENABLED] shouldBe true
                    flags[AUTOREVIEW_ENABLE_USING_JIRA_CONTEXT] shouldBe true
                }

                "GENERAL" -> {
                    flags[AUTOREVIEW_ACRA_MINI_GENERATOR_ENABLED] shouldBe false
                    flags[MODEL_IN_COMMENT_GENERATION] shouldBe "claude-sonnet-4@20250514"
                    flags[AUTOREVIEW_GENERAL_GENERATOR_ENABLED] shouldBe true
                    flags[AUTOREVIEW_ACRA_MINI_USE_FACTUAL_CORRECTNESS] shouldBe true
                    flags[AUTOREVIEW_ACRA_MINI_NEMO_VERSION] shouldBe "0.5.1.dev1+mgupta14.exp2"
                    flags[AUTOREVIEW_COMMENT_RANKER_THRESHOLD] shouldBe 0.32
                    flags[AUTOREVIEW_NEMO_SIMILAR_ISSUE_DIFFS_CONTEXT_ENABLED] shouldBe false
                    flags[AUTOREVIEW_NEMO_SIMILAR_ISSUE_PR_COMMENTS_CONTEXT_ENABLED] shouldBe false
                    flags[AUTOREVIEW_NEMO_RECENT_PRS_CONTEXT_ENABLED] shouldBe false
                    flags[AUTOREVIEW_ENABLE_USING_JIRA_CONTEXT] shouldBe false
                }
            }
        }

        @Test
        fun `sets context experiment execution flags when experiment enabled`() =
            runTest {
                // Given - agent experiment config with context flags
                val agentConfig =
                    AutoreviewAgentExperimentConfig(
                        autoreviewAcraMiniGeneratorEnabled = true,
                        autoreviewNemoSimilarIssueDiffsContextEnabled = true,
                        autoreviewNemoSimilarIssuePrCommentsContextEnabled = false,
                        autoreviewNemoRecentPrsContextEnabled = true,
                        autoreviewEnableUsingJiraContextInPrompt = true,
                        modelInCommentGeneration = "claude-sonnet-4-5@20250929",
                    )
                coEvery { featureService.getAutoreviewAgentExperimentConfig(any(), any()) } returns agentConfig

                // When
                val flags = autoreviewService.getExecutionFlags()

                // Then - should use agent config
                flags[AUTOREVIEW_ACRA_MINI_GENERATOR_ENABLED] shouldBe true
                flags[AUTOREVIEW_NEMO_SIMILAR_ISSUE_DIFFS_CONTEXT_ENABLED] shouldBe true
                flags[AUTOREVIEW_NEMO_SIMILAR_ISSUE_PR_COMMENTS_CONTEXT_ENABLED] shouldBe false
                flags[AUTOREVIEW_NEMO_RECENT_PRS_CONTEXT_ENABLED] shouldBe true
                flags[AUTOREVIEW_ENABLE_USING_JIRA_CONTEXT] shouldBe true
                flags[MODEL_IN_COMMENT_GENERATION] shouldBe "claude-sonnet-4-5@20250929"
            }

        @Test
        fun `uses agent config when context experiment disabled`() =
            runTest {
                // Given - agent experiment config
                coEvery { featureService.getAutoreviewAgentExperimentConfig(any(), any()) } returns
                    AutoreviewAgentExperimentConfig(autoreviewContext7Enabled = true)

                // When
                val flags = autoreviewService.getExecutionFlags()

                // Then - should use agent config
                flags[AUTOREVIEW_CONTEXT7_ENABLED] shouldBe true
            }

        @Test
        fun `uses agent experiment config when context experiment flag is false`() =
            runTest {
                // Given - agent experiment config
                coEvery { featureService.getAutoreviewAgentExperimentConfig(any(), any()) } returns
                    AutoreviewAgentExperimentConfig(
                        autoreviewContext7Enabled = true,
                        modelInCommentGeneration = "agent-model",
                    )

                // When
                val flags = autoreviewService.getExecutionFlags()

                // Then - should use agent config
                flags[AUTOREVIEW_CONTEXT7_ENABLED] shouldBe true
                flags[MODEL_IN_COMMENT_GENERATION] shouldBe "agent-model"
            }

        @Test
        fun `uses agent experiment config when context experiment flag is null`() =
            runTest {
                // Given - agent experiment config
                coEvery { featureService.getAutoreviewAgentExperimentConfig(any(), any()) } returns
                    AutoreviewAgentExperimentConfig(autoreviewContext7Enabled = true)

                // When
                val flags = autoreviewService.getExecutionFlags()

                // Then - should use agent config
                flags[AUTOREVIEW_CONTEXT7_ENABLED] shouldBe true
            }

        @ParameterizedTest
        @EnumSource(RootWorkflowName::class, mode = EnumSource.Mode.MATCH_ANY, names = ["AUTOREVIEW.*"])
        fun `sets coding standards extractor execution flag to true only for AUTOREVIEW_FIRST_REVIEW, FG enabled and no prior run exists`(
            rootWorkflowName: RootWorkflowName,
        ) {
            runTest {
                // Arrange
                coEvery { featureService.isAutoreviewCodingStandardsExtractorEnabled(any(), any()) } returns true
                coEvery { autoreviewWorkflowsStorageService.getOneTimeRuns(any(), any()) } returns null

                // Act
                val flags =
                    autoreviewService.getExecutionFlags(
                        transactionContext = createTestTransactionContext(),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        rootWorkflowName = rootWorkflowName,
                    )

                // Assert
                if (rootWorkflowName == RootWorkflowName.AUTOREVIEW_FIRST_REVIEW) {
                    flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe true
                } else {
                    flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe false
                }
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `sets coding standards extractor execution flag to false for AUTOREVIEW_FIRST_REVIEW, no prior runs but FG disabled`(
            codingStandardsExtractorEnabled: Boolean,
        ) {
            runTest {
                // Arrange
                coEvery {
                    featureService.isAutoreviewCodingStandardsExtractorEnabled(
                        any(),
                        any(),
                    )
                } returns codingStandardsExtractorEnabled
                coEvery { autoreviewWorkflowsStorageService.getOneTimeRuns(any(), any()) } returns null

                // Act
                val flags =
                    autoreviewService.getExecutionFlags(
                        transactionContext = createTestTransactionContext(),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        rootWorkflowName = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                    )

                // Assert
                if (codingStandardsExtractorEnabled) {
                    flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe true
                } else {
                    flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe false
                }
            }
        }

        @Test
        fun `sets coding standards extractor execution flag to false when FG enabled but a prior run exists`() {
            runTest {
                // Arrange
                coEvery { featureService.isAutoreviewCodingStandardsExtractorEnabled(any(), any()) } returns true
                coEvery {
                    autoreviewWorkflowsStorageService.getOneTimeRuns(any(), any())
                } returns
                    AutoreviewOneTimeRunsPantryItem(
                        oneTimeRuns =
                            listOf(
                                AutoreviewOneTimeRun(
                                    jobId = UUID.randomUUID().toString(),
                                    createdDate = OffsetDateTime.now(),
                                    workspaceAri = TEST_DEVAI_WORKSPACE_ARI,
                                    workflowName = CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW,
                                ),
                            ),
                    )

                // Act
                val flags =
                    autoreviewService.getExecutionFlags(
                        transactionContext = createTestTransactionContext(),
                        repositoryUrl = URI.create(TEST_REPO_URL).toURL(),
                        rootWorkflowName = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                    )

                // Assert
                flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe false
            }
        }
    }

    @Nested
    inner class CodeSuggestionsDynamicConfig {
        private val workflowRunId = UUID.randomUUID()
        private val rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW
        private val salPayload =
            createSalAutoreviewPayload(
                prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
            )

        @BeforeEach
        fun setup() {
            coEvery {
                acraClient.submitAcraWorkflowRun(any(), any())
            } returns
                Mono.just(
                    AcraCreateWorkflowRunResponse(
                        workflowRunId = workflowRunId,
                        status = AcraWorkflowRunStatus.PENDING,
                        rootWorkflow = rootWorkflow,
                        links = mockk<AcraWorkflowRunLinkResponse>(relaxed = true),
                        queueType = WorkflowRunQueueType.DEFAULT,
                    ),
                )
        }

        @Test
        fun `does not include these flags if they are null`() {
            runTest {
                // Arrange
                val salPayload =
                    createSalAutoreviewPayload(
                        prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                        workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    )

                coEvery {
                    featureService.getAutoreviewCodeSuggestionsConfig(
                        any(),
                        any(),
                    )
                } returns AutoreviewCodeSuggestionsConfig()

                // Act
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                // Assert
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact

                executionFlagsArtifact.flags shouldNotContainKey AUTOREVIEW_CODE_SUGGESTIONS_CLASSIFICATION_MODEL_ID
                executionFlagsArtifact.flags shouldNotContainKey AUTOREVIEW_CODE_SUGGESTIONS_GENERATION_MODEL_ID
                executionFlagsArtifact.flags shouldNotContainKey AUTOREVIEW_CODE_SUGGESTIONS_MAX_LINES_ALLOWED
                executionFlagsArtifact.flags shouldNotContainKey CODE_SUGGESTIONS_MAX_LLM_INTERACTIONS_PER_LLM_SESSION
            }
        }

        @Test
        fun `uses default disabled repos if no config value is configured`() {
            runTest {
                coEvery {
                    featureService.getAutoreviewCodeSuggestionsConfig(
                        any(),
                        any(),
                    )
                } returns AutoreviewCodeSuggestionsConfig()

                // Act
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                // Assert
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact

                executionFlagsArtifact.flags[AUTOREVIEW_CODE_SUGGESTIONS_DISABLED_REPOSITORIES] shouldBe
                    listOf(
                        "https://bitbucket.org/atlassian/jira",
                        "https://bitbucket.org/atlassian/confluence",
                    )
            }
        }

        @Test
        fun `uses configured values if they are successfully retrieved`() {
            runTest {
                coEvery { featureService.getAutoreviewCodeSuggestionsConfig(any(), any()) } returns
                    AutoreviewCodeSuggestionsConfig(
                        modelInCodeSuggestionsClassification = "classification model",
                        modelInCodeSuggestionsGeneration = "generation model",
                        maxInteractionsPerLlmSession = 123,
                        codeSuggestionMaxLinesAllowed = 456,
                        disabledRepositoryNames = listOf("https://bitbucket.org/no/codesuggestions"),
                    )

                // Act
                autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

                // Assert
                val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()
                coVerify(exactly = 1) { acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any()) }

                val executionFlagsArtifact =
                    requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact

                executionFlagsArtifact.flags[AUTOREVIEW_CODE_SUGGESTIONS_CLASSIFICATION_MODEL_ID] shouldBe "classification model"
                executionFlagsArtifact.flags[AUTOREVIEW_CODE_SUGGESTIONS_GENERATION_MODEL_ID] shouldBe "generation model"
                executionFlagsArtifact.flags[CODE_SUGGESTIONS_MAX_LLM_INTERACTIONS_PER_LLM_SESSION] shouldBe 123
                executionFlagsArtifact.flags[AUTOREVIEW_CODE_SUGGESTIONS_MAX_LINES_ALLOWED] shouldBe 456
                executionFlagsArtifact.flags[AUTOREVIEW_CODE_SUGGESTIONS_DISABLED_REPOSITORIES] shouldBe
                    listOf(
                        "https://bitbucket.org/no/codesuggestions",
                    )
            }
        }
    }

    @Nested
    inner class GetCoreReviewIssues {
        private val sampleIssueData =
            mapOf(
                JiraIssueARI.from("SOFTWARE_TEAMS_SITE_ID", "1001") to "ST-123",
                JiraIssueARI.from("HELLO_SITE_ID", "1001") to "HELLO-456",
                JiraIssueARI.valueOf(TEST_JIRA_ISSUE_ARI) to TEST_ISSUE_KEY,
            )

        /**
         * Creates an example payload for use in the tests
         *
         * The default payload (using default arguments) represents a scenario which _does_ trigger the special case jira issue retrieval.
         * Creating payloads with specific values different to the defaults will demonstrate the scenarios which prevent the special retrieval.
         */
        private fun examplePayload(
            associations: List<Association> = emptyList(),
            workspaceId: String = DEV_AI_WORKSPACE_ID,
            repositoryUrlString: String = "https://bitbucket.org/atlassian/awesomerepo",
            autoreviewEventTypes: List<AutoreviewEventType> =
                listOf(
                    AutoreviewEventType.AUTOREVIEW_MAIN,
                    AutoreviewEventType.CUSTOM,
                ),
        ): AutoreviewSalPayload =
            AutoreviewSalPayload(
                traceId = "traceId",
                type = AutoreviewSalPayloadType.CREATE,
                workspaceId = workspaceId,
                workspace = ARI.valueOf(TEST_DEVAI_WORKSPACE_ARI),
                cloudId = CloudIdLike.fromString(UUID.randomUUID().toString()),
                resource = ARI.valueOf(TEST_RESOURCE_ARI),
                repositoryUrl = URI.create(repositoryUrlString).toURL(),
                prDetail =
                    PRDetail(
                        url = URI.create(TEST_PR_URL).toURL(),
                        title = "PR Title",
                        id = "123",
                        sourceBranch = Branch("sourceBranch", null),
                        destinationBranch = Branch("destinationBranch", null),
                        author = PullRequestAuthor(accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)),
                        sourceCommit = null,
                        bbcRepoUUID = "aef3b4c0-8d2e-4a5b-9c1e-7f3a2d6e5f8b",
                    ),
                associations = associations,
                autoreviewEventTypes = autoreviewEventTypes,
            )

        @BeforeEach
        fun setup() {
            // Feature Gate Enabled
            coEvery { featureService.isAutoreviewRetrieveInternalJiraIssues(any()) } returns true

            // setup minting uct
            coEvery { idGatekeeperClient.mintUct(any()) } returns
                MintUctResponse(
                    key = "key1",
                    context =
                        MintUctResponse.Context(
                            token = "user-context-token-example",
                        ),
                )

            // Issue Aris Retrieval
            coEvery {
                autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                    any(),
                    BitbucketPullrequestARI.from("aef3b4c0-8d2e-4a5b-9c1e-7f3a2d6e5f8b:123"),
                    "user-context-token-example",
                )
            } answers {
                when (firstArg<GraphWorkspaceARI>().workspaceId) {
                    SOFTWARETEAMS_DEVOPS_WORKSPACE_ID -> listOf(JiraIssueARI.from("SOFTWARE_TEAMS_SITE_ID", "1001"))
                    HELLO_DEVOPS_WORKSPACE_ID -> listOf(JiraIssueARI.from("HELLO_SITE_ID", "1001"))
                    else -> emptyList()
                }
            }

            // Jira Issues Retrieval
            coEvery {
                atlassianProxyClient.getIssueDetails(any(), any(), any())
            } answers {
                sampleIssueData[JiraIssueARI.from(firstArg(), secondArg())]?.let { issueKey ->
                    Optional.of(
                        IssueDetails(
                            id = secondArg(),
                            self = null,
                            key = issueKey,
                            fields = Fields(""),
                            renderedFields = RenderedFields(""),
                        ),
                    )
                } ?: Optional.empty<IssueDetails>()
            }
        }

        @Test
        fun `should retrieve issues from softwareteams and hello when no jira issues in event`() =
            runTest {
                val softwareTeamsJiraAri = JiraIssueARI.valueOf("ari:cloud:jira:$TEST_CLOUD_ID:issue/456789")
                val helloJiraAri = JiraIssueARI.valueOf("ari:cloud:jira:$TEST_CLOUD_ID:issue/123456")

                coEvery { featureService.isAutoreviewRetrieveInternalJiraIssues(any()) } returns true
                coEvery {
                    autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                        GraphWorkspaceARI.from(SOFTWARETEAMS_DEVOPS_WORKSPACE_ID),
                        any(),
                        any(),
                    )
                } returns listOf(softwareTeamsJiraAri)

                coEvery {
                    autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                        GraphWorkspaceARI.from(HELLO_DEVOPS_WORKSPACE_ID),
                        any(),
                        any(),
                    )
                } returns listOf(helloJiraAri)

                coEvery {
                    autoreviewJiraIssueService.enrichJiraIssueDetails(
                        any(),
                        listOf(softwareTeamsJiraAri, helloJiraAri),
                        any(),
                    )
                } returns
                    listOf(
                        JiraIssueDetails(
                            issueAri = softwareTeamsJiraAri.toString(),
                            description = "test_description",
                            summary = "test_summary",
                            key = "ST-123",
                        ),
                        JiraIssueDetails(
                            issueAri = helloJiraAri.toString(),
                            description = "test_description",
                            summary = "test_summary",
                            key = "HELLO-456",
                        ),
                    )

                val issues =
                    autoreviewService.getFirstReviewJiraIssues(
                        eventJiraIssues = emptyList(),
                        salPayload = examplePayload(),
                    )

                extracting(issues) {
                    key
                } shouldContainExactly
                    listOf(
                        "ST-123",
                        "HELLO-456",
                    )
            }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "https://integration.bb-inf.net/atlassian/awesomerepo",
                "https://bitbucket.org/atlassian1/awesomerepo",
                "https://github.com/atlassian/awesomerepo",
                "https://bitbucket.org/batlassian/awesomerepo",
                "https://bitbucket.org/trello/awesomerepo",
                "https://bitbucket.org/supercompany/awesomerepo",
            ],
        )
        fun `should not be attempted if not the prod bbc atlassian workspace`(repoUrl: String) =
            runTest {
                coEvery { featureService.isAutoreviewRetrieveInternalJiraIssues(any()) } returns true

                val salPayload =
                    examplePayload(
                        repositoryUrlString = repoUrl,
                    )

                autoreviewService.getFirstReviewJiraIssues(emptyList(), salPayload) shouldHaveSize 0
            }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should always use eventJiraIssues if already present and not attempt to retrieve internal issues`(featureGateValue: Boolean) =
            runTest {
                coEvery { featureService.isAutoreviewRetrieveInternalJiraIssues(any()) } returns featureGateValue

                val salPayload =
                    examplePayload(
                        associations =
                            listOf(
                                Association(
                                    JIRA_ISSUE_ASSOCIATION_TYPE,
                                    TEST_JIRA_ISSUE_ARI,
                                ),
                            ),
                    )

                extracting(
                    autoreviewService.getFirstReviewJiraIssues(
                        listOf(
                            mockk<JiraIssueDetails>(relaxed = true) {
                                every { key } returns "ISSUE-987"
                            },
                        ),
                        salPayload,
                    ),
                ) {
                    key
                }.single() shouldBe "ISSUE-987"

                // Verify no attempt was made to retrieve associations
                coVerify(exactly = 0) {
                    autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `should not retrieve internal jira issues when fg is false`() =
            runTest {
                coEvery { featureService.isAutoreviewRetrieveInternalJiraIssues(any()) } returns false

                val salPayload = examplePayload()

                autoreviewService.getFirstReviewJiraIssues(emptyList(), salPayload) shouldHaveSize 0
            }

        @Test
        fun `should not be attempted when AUTOREVIEW_MAIN is not in the event types`() =
            runTest {
                coEvery { featureService.isAutoreviewRetrieveInternalJiraIssues(any()) } returns true

                val salPayload =
                    examplePayload(
                        autoreviewEventTypes = listOf(AutoreviewEventType.ACCEPTANCE_CRITERIA),
                    )

                autoreviewService.getFirstReviewJiraIssues(emptyList(), salPayload) shouldHaveSize 0
            }

        @Test
        fun `returns empty list if exception is thrown when retrieving associated issue aris`() =
            runTest {
                coEvery {
                    autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                        any(),
                        any(),
                        any(),
                    )
                } throws RuntimeException("Error Retrieving Issue ARIs")

                autoreviewService.getFirstReviewJiraIssues(
                    emptyList(),
                    examplePayload(),
                ) shouldHaveSize 0

                coVerify(exactly = 1) {
                    autoreviewJiraIssueService.getJiraIssueArisByBitbucketPullRequestARI(
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `returns empty list if exception is thrown when retrieving jira issues from jira`() =
            runTest {
                coEvery {
                    autoreviewJiraIssueService.enrichJiraIssueDetails(any(), any(), false)
                } throws RuntimeException("Error Retrieving Jira Issues")

                autoreviewService.getFirstReviewJiraIssues(
                    emptyList(),
                    examplePayload(),
                ) shouldHaveSize 0

                coVerify(exactly = 1) {
                    autoreviewJiraIssueService.enrichJiraIssueDetails(
                        any(),
                        any(),
                        false,
                    )
                }
            }

        @Test
        fun `returns empty list if exception is thrown when minting uct`() =
            runTest {
                coEvery {
                    idGatekeeperClient.mintUct(any())
                } throws RuntimeException("Error Minting UCT")

                autoreviewService.getFirstReviewJiraIssues(
                    emptyList(),
                    examplePayload(),
                ) shouldHaveSize 0

                coVerify(exactly = 1) { idGatekeeperClient.mintUct(TEST_AUTHOR_ACCOUNT_ID) }
            }
    }

    @Test
    fun `submitAutoreviewWorkflow should not enable coding standards extractor for GitHub repositories`() {
        runTest {
            // Arrange
            val githubRepoUrl = "https://github.com/testorg/testrepo"
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = emptyList(),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    repositoryUrl = URI.create(githubRepoUrl).toURL(),
                )

            coEvery { featureService.isAutoreviewCodingStandardsExtractorEnabled(any(), any()) } returns true
            coEvery {
                autoreviewWorkflowsStorageService.getOneTimeRuns(any(), any())
            } returns null

            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            // Assert
            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any())
            }

            val codingStandardsExtractorEnabled =
                (requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact)
                    .flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] as? Boolean

            // Should be false for GitHub repositories even when feature flag is enabled
            codingStandardsExtractorEnabled shouldBe false
        }
    }

    @Test
    fun `submitAutoreviewWorkflow should enable coding standards extractor for Bitbucket repositories when conditions are met`() {
        runTest {
            // Arrange
            val bitbucketRepoUrl = "https://bitbucket.org/testworkspace/testrepo"
            val salPayload =
                createSalAutoreviewPayload(
                    prAuthorAccountId = TEST_AUTHOR_ACCOUNT_ID,
                    associations = emptyList(),
                    workspaceId = TEST_DEFAULT_AI_WORKSPACE_ID,
                    repositoryUrl = URI.create(bitbucketRepoUrl).toURL(),
                )

            coEvery { featureService.isAutoreviewCodingStandardsExtractorEnabled(any(), any()) } returns true
            coEvery {
                autoreviewWorkflowsStorageService.getOneTimeRuns(any(), any())
            } returns null

            val requestBodySlot = slot<AcraCreateWorkflowRunRequest>()

            // Act
            autoreviewService.submitAutoreviewWorkflow(salPayload, emptyList())

            // Assert
            coVerify(exactly = 1) {
                acraClient.submitAcraWorkflowRun(capture(requestBodySlot), any())
            }

            val codingStandardsExtractorEnabled =
                (requestBodySlot.captured.input.artifacts[EXECUTION_FLAGS_JSON] as ExecutionFlagsArtifact)
                    .flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] as? Boolean

            // Should be true for Bitbucket repositories when feature flag is enabled and no prior runs
            codingStandardsExtractorEnabled shouldBe true
        }
    }

    @Nested
    inner class CodingStandardsExtractorFeatureFlagMetadata {
        @Test
        fun `isAutoreviewCodingStandardsExtractorEnabled is called with proper metadata for Bitbucket repository`() {
            runTest {
                // Arrange
                val bitbucketRepoUrl = "https://bitbucket.org/testworkspace/testrepo"
                coEvery { featureService.isAutoreviewCodingStandardsExtractorEnabled(any(), any()) } returns true
                coEvery { autoreviewWorkflowsStorageService.getOneTimeRuns(any(), any()) } returns null

                // Act
                val flags =
                    autoreviewService.getExecutionFlags(
                        transactionContext = createTestTransactionContext(),
                        repositoryUrl = URI.create(bitbucketRepoUrl).toURL(),
                        rootWorkflowName = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                    )

                // Assert
                coVerify(exactly = 1) {
                    featureService.isAutoreviewCodingStandardsExtractorEnabled(
                        any(),
                        match { metadata ->
                            metadata?.repositoryUrlSegments?.domain == "bitbucket.org" &&
                                metadata.repositoryUrlSegments?.workspace == "testworkspace" &&
                                metadata.repositoryUrlSegments?.repoName == "testrepo"
                        },
                    )
                }
                flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe true
            }
        }

        @Test
        fun `isAutoreviewCodingStandardsExtractorEnabled can be disabled via feature flag for specific repository`() {
            runTest {
                // Arrange
                val bitbucketRepoUrl = "https://bitbucket.org/disabledworkspace/disabledrepo"
                // Mock the feature flag to return false for this specific repository
                coEvery {
                    featureService.isAutoreviewCodingStandardsExtractorEnabled(
                        any(),
                        match { metadata ->
                            metadata?.repositoryUrlSegments?.workspace == "disabledworkspace"
                        },
                    )
                } returns false
                coEvery { autoreviewWorkflowsStorageService.getOneTimeRuns(any(), any()) } returns null

                // Act
                val flags =
                    autoreviewService.getExecutionFlags(
                        transactionContext = createTestTransactionContext(),
                        repositoryUrl = URI.create(bitbucketRepoUrl).toURL(),
                        rootWorkflowName = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW,
                    )

                // Assert
                flags[AUTOREVIEW_CODE_STANDARDS_EXTRACTOR_ENABLED] shouldBe false
            }
        }

        @Test
        fun `uses default prompt version when not configured`() =
            runTest {
                // Arrange
                coEvery { featureService.getAutoreviewCodingStandardsExtractorPromptVersion(any(), any()) } returns "default"

                // Act
                val flags = autoreviewService.getExecutionFlags()

                // Assert
                flags[AUTOREVIEW_USER_PROMPT_VERSION_IN_CODING_STANDARDS] shouldBe "default"
            }

        @ParameterizedTest
        @CsvSource(
            "default",
            "commit-history",
            "test",
            "constrained",
        )
        fun `supports all coding standards extractor prompt versions`(promptVersion: String) =
            runTest {
                // Arrange
                coEvery { featureService.getAutoreviewCodingStandardsExtractorPromptVersion(any(), any()) } returns promptVersion

                // Act
                val flags = autoreviewService.getExecutionFlags()

                // Assert
                flags[AUTOREVIEW_USER_PROMPT_VERSION_IN_CODING_STANDARDS] shouldBe promptVersion
            }
    }
}
