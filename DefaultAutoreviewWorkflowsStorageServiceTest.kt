package devai.modules.autoreview.service

import com.atlassian.ari.principled.ARI
import com.atlassian.ari.principled.devai.DevaiWorkspaceARI
import com.atlassian.usercontext.api.AccountId
import devai.modules.acra.client.AcraClient
import devai.modules.acra.client.DEV_AI_WORKSPACE_ID
import devai.modules.acra.shared.model.CallerId
import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.model.codereview.CodeReviewCommentChangeType
import devai.modules.autoreview.model.AutoreviewJobDetails
import devai.modules.autoreview.model.AutoreviewOneTimeRun
import devai.modules.autoreview.model.AutoreviewOneTimeRunsPantryItem
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriteria
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriterion
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriterionStatus
import devai.modules.autoreview.model.AutoreviewPantryItem
import devai.modules.autoreview.model.AutoreviewPantryStatus
import devai.modules.autoreview.model.AutoreviewPostedCommentsPantryItem
import devai.modules.autoreview.model.AutoreviewProcessStatus
import devai.modules.autoreview.model.AutoreviewWorkflow
import devai.modules.autoreview.model.WorkflowRunStatus
import devai.modules.autoreview.queue.model.ReviewComment
import devai.modules.autoreview.service.CodingStandardsExtractorCompletedAction.Companion.CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW
import devai.modules.autoreview.service.DefaultAutoreviewServiceTest.Companion.TEST_ISSUE_ID
import devai.modules.autoreview.service.DefaultAutoreviewServiceTest.Companion.TEST_JIRA_ISSUE_ARI
import devai.modules.autoreview.service.DefaultAutoreviewWorkflowsStorageService.AutoreviewPantryKey
import devai.modules.autoreview.service.DefaultAutoreviewWorkflowsStorageService.Companion.ACRA_AUTOREVIEW_URL
import devai.modules.autoreview.service.DefaultAutoreviewWorkflowsStorageService.Companion.ONE_TIME_RUN_TTL_SECONDS
import devai.modules.autoreview.service.DefaultAutoreviewWorkflowsStorageService.Companion.SHORT_COMMIT_HASH_CHARS
import devai.modules.pantry.shared.client.PantryManager
import devai.modules.pantry.shared.entity.PantryEntry
import devai.modules.shared.analytics.service.AutoreviewAnalyticsService
import devai.modules.shared.config.JacksonConfig
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.GLOBAL_WORKSPACE_ARI
import devai.modules.shared.model.UserContext
import devai.modules.shared.model.tcs.WorkspaceId
import devai.modules.tenant.model.TransactionContext
import devai.modules.tenant.model.WorkspaceContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.stream.Stream

@ExtendWith(MockKExtension::class)
class DefaultAutoreviewWorkflowsStorageServiceTest {
    private val objectMapper = JacksonConfig().objectMapper()
    private val pantryEntry = mockk<PantryEntry>(relaxed = true)
    private val transactionContext = mockk<TransactionContext>(relaxed = true)
    private val pantryManager = mockk<PantryManager>(relaxed = true)
    private val acraClient = mockk<AcraClient>(relaxed = true)
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val autoreviewPermissionsService = mockk<AutoreviewPermissionsService>(relaxed = true)
    private lateinit var autoreviewWorkflowsStorageService: DefaultAutoreviewWorkflowsStorageService
    private val autoreviewAnalyticsService = mockk<AutoreviewAnalyticsService>(relaxed = true)

    companion object {
        private val testDevAiWorkspaceAri =
            DevaiWorkspaceARI.valueOf("ari:cloud:devai::workspace/c7dce2a9-02ea-407f-b9ce-b05b8b17d3d3")
        private const val TEST_REPOSITORY_URL = "https://bitbucket.org/workspace/repo"
        private const val TEST_WORKFLOW_RUN_ID = "e7d1c16b-0208-4a4c-a19e-547ac140d852"

        @JvmStatic
        private fun acceptanceCriteriaUpdateExamples(): Stream<Arguments> {
            val emptyCriteria = emptyList<AutoreviewPantryAcceptanceCriterion>()

            val exampleCriterion =
                AutoreviewPantryAcceptanceCriterion(
                    id = TEST_ISSUE_ID,
                    criterion = "Test criterion",
                    status = AutoreviewPantryAcceptanceCriterionStatus.UNMET,
                    issueAri = TEST_JIRA_ISSUE_ARI,
                )

            return Stream.of(
                Arguments.argumentSet(
                    "empty to non-empty, multiplexing off",
                    emptyCriteria,
                    listOf(exampleCriterion),
                    false,
                ),
                Arguments.argumentSet(
                    "non-empty to empty, multiplexing off",
                    listOf(exampleCriterion),
                    emptyCriteria,
                    false,
                ),
                Arguments.argumentSet(
                    "non-empty to non-empty, multiplexing off",
                    listOf(exampleCriterion),
                    listOf(
                        exampleCriterion.copy(
                            status = AutoreviewPantryAcceptanceCriterionStatus.MET,
                        ),
                    ),
                    false,
                ),
                Arguments.argumentSet(
                    "empty to non-empty, multiplexing on",
                    emptyCriteria,
                    listOf(exampleCriterion),
                    true,
                ),
                Arguments.argumentSet(
                    "non-empty to empty, multiplexing on",
                    listOf(exampleCriterion),
                    emptyCriteria,
                    true,
                ),
                Arguments.argumentSet(
                    "non-empty to non-empty, multiplexing on",
                    listOf(exampleCriterion),
                    listOf(
                        exampleCriterion.copy(
                            status = AutoreviewPantryAcceptanceCriterionStatus.MET,
                        ),
                    ),
                    true,
                ),
            )
        }
    }

    @BeforeEach
    fun setUp() {
        autoreviewWorkflowsStorageService =
            DefaultAutoreviewWorkflowsStorageService(
                objectMapper = objectMapper,
                pantryManager = pantryManager,
                acraClient = acraClient,
                featureService = featureService,
                autoreviewPermissionsService = autoreviewPermissionsService,
                autoreviewAnalyticsService = autoreviewAnalyticsService,
            )
    }

    @Test
    fun `getAutoreviewPantryItem gets correct pantry item`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"

            autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl)

            coVerify(exactly = 1) {
                pantryManager.getEntry(
                    any(),
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                )
            }
        }
    }

    @Test
    fun `getPostCommentsPantryItem gets correct pantry item`() {
        runTest {
            val pullRequestUrl = "https://test.com/pr/1"

            autoreviewWorkflowsStorageService.getPostedCommentsPantryItem(pullRequestUrl)

            coVerify(exactly = 1) {
                pantryManager.getEntry(
                    any(),
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_POSTED_COMMENTS.value,
                )
            }
        }
    }

    @Test
    fun `getAcceptanceCriteriaPantryItem uses correct pantry key`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/8763"
            val workspaceAri = ARI.valueOf("ari:cloud:devai::workspace/23456789-2345-2345-2345-234567890123")

            autoreviewWorkflowsStorageService.getAcceptanceCriteriaPantryItem(
                pullRequestUrl,
                workspaceAri,
            )
            coVerify(exactly = 1) {
                pantryManager.getEntry(
                    null,
                    pullRequestUrl,
                    "autoreviewAcceptanceCriteria:workspaceAri:b996f708-cfd7-3f16-b153-ce38fe536970",
                )
            }
        }
    }

    @Test
    fun `storeAutoreviewWorkflows should store new autoreview workflows`() {
        runTest {
            val pullRequestUrl = "https://pr.com/pullrequest/1"
            val workflowId = "workflow-1"
            val rootWorkflowName = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA
            val workspaceAri = ARI.valueOf("ari:cloud:devai::workspace/12345678-1234-1234-1234-123456789012")
            val expectedPantryEntry =
                PantryEntry(
                    container = pullRequestUrl,
                    key = AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                    value =
                        AutoreviewPantryItem(
                            autoreviewWorkflows =
                                listOf(
                                    AutoreviewWorkflow(
                                        jobId = workflowId,
                                        rootWorkflow = rootWorkflowName.value,
                                        createdDate = OffsetDateTime.now().toString(),
                                        sourceCommit = null,
                                        issuesHash = null,
                                        workspaceAri = workspaceAri.toString(),
                                    ),
                                ),
                        ),
                )
            coEvery {
                pantryManager.getEntry(
                    null,
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                )
            } returns
                null

            coEvery {
                pantryManager.createEntry(
                    null,
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                    any(),
                )
            } returns expectedPantryEntry

            val result =
                autoreviewWorkflowsStorageService.storeAutoreviewWorkflows(
                    pullRequestUrl,
                    workflowId,
                    rootWorkflowName,
                    workspaceAri = workspaceAri,
                )
            result shouldBe expectedPantryEntry
        }
    }

    @Test
    fun `storeAutoreviewWorkflows should update existing autoreview workflows`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"
            val workflowId = "workflow-2"
            val rootWorkflowName = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW
            val workspaceAri = ARI.valueOf("ari:cloud:devai::workspace/12345678-1234-1234-1234-123456789012")
            val existingPantryItem =
                AutoreviewPantryItem(
                    autoreviewWorkflows =
                        listOf(
                            AutoreviewWorkflow(
                                jobId = "workflow-1",
                                rootWorkflow = "root-workflow",
                                createdDate = OffsetDateTime.now().toString(),
                                sourceCommit = "commit",
                                issuesHash = "hash",
                                workspaceAri = "workspace-ari",
                            ),
                        ),
                )
            val expectedPantryEntry =
                PantryEntry(
                    container = pullRequestUrl,
                    key = AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                    value =
                        existingPantryItem.copy(
                            autoreviewWorkflows =
                                existingPantryItem.autoreviewWorkflows +
                                    AutoreviewWorkflow(
                                        jobId = workflowId,
                                        rootWorkflow = rootWorkflowName.value,
                                        createdDate = OffsetDateTime.now().toString(),
                                        sourceCommit = null,
                                        issuesHash = null,
                                        workspaceAri = workspaceAri.toString(),
                                    ),
                        ),
                )
            coEvery {
                pantryManager.getEntry(
                    any(),
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                )
            } returns
                PantryEntry(
                    container = pullRequestUrl,
                    key = AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                    value = existingPantryItem,
                )
            coEvery {
                pantryManager.updateEntry(
                    null,
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_WORKFLOWS.value,
                    any(),
                )
            } returns expectedPantryEntry

            val result =
                autoreviewWorkflowsStorageService.storeAutoreviewWorkflows(
                    pullRequestUrl,
                    workflowId,
                    rootWorkflowName,
                    workspaceAri = workspaceAri,
                )
            result shouldBe expectedPantryEntry
        }
    }

    @Test
    fun `storeAcceptanceCriteria should create new entry if none exists`() {
        runTest {
            val pullRequestUrl = "https://github.com/pull/1"
            val acceptanceCriteria =
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
                    reviewIteration = 1,
                    extractionIteration = 1,
                )
            coEvery { pantryManager.getEntry(any(), any()) } returns null
            coEvery {
                pantryManager.createEntry(any(), any(), any())
            } returns pantryEntry

            autoreviewWorkflowsStorageService.storeAcceptanceCriteria(
                pullRequestUrl,
                "123",
                acceptanceCriteria,
                GLOBAL_WORKSPACE_ARI,
                transactionContext,
            )

            coVerify(exactly = 1) {
                pantryManager.createEntry(
                    null,
                    pullRequestUrl,
                    "autoreviewAcceptanceCriteria:workspaceAri:880ab251-10cc-30a1-9634-4bfa8ca2097f",
                    acceptanceCriteria,
                )
            }

            coVerify(exactly = 0) {
                pantryManager.updateEntry(
                    any(),
                    any(),
                    any(),
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource("acceptanceCriteriaUpdateExamples")
    fun `storeAcceptanceCriteria should update entry if one exists`(
        previousCriteria: List<AutoreviewPantryAcceptanceCriterion>,
        newCriteria: List<AutoreviewPantryAcceptanceCriterion>,
    ) {
        runTest {
            val pullRequestUrl = "https://bitbucket.com/pull/1"
            val existingAcceptanceCriteria =
                AutoreviewPantryAcceptanceCriteria(
                    acceptanceCriteria = previousCriteria,
                    reviewIteration = 1,
                    extractionIteration = 1,
                )
            val newAcceptanceCriteria =
                existingAcceptanceCriteria.copy(
                    acceptanceCriteria = newCriteria,
                    reviewIteration = 2,
                )
            coEvery { pantryManager.getEntry(any(), any(), any()) } returns pantryEntry
            every { pantryEntry.value } returns existingAcceptanceCriteria
            coEvery {
                pantryManager.updateEntry(any(), any(), any())
            } returns pantryEntry

            autoreviewWorkflowsStorageService.storeAcceptanceCriteria(
                pullRequestUrl,
                "123",
                newAcceptanceCriteria,
                GLOBAL_WORKSPACE_ARI,
                transactionContext,
            )

            coVerify(exactly = 0) {
                pantryManager.createEntry(
                    any(),
                    any(),
                    any(),
                )
            }

            coVerify(exactly = 1) {
                pantryManager.updateEntry(
                    null,
                    pullRequestUrl,
                    "autoreviewAcceptanceCriteria:workspaceAri:880ab251-10cc-30a1-9634-4bfa8ca2097f",
                    newAcceptanceCriteria,
                )
            }
        }
    }

    @Test
    fun `storeAutoreviewPostedComments should create new entry if none exists`() {
        runTest {
            val pullRequestUrl = "https://example.com/pr/1"
            val postedComments =
                AutoreviewPostedCommentsPantryItem(
                    postedComments =
                        listOf(
                            ReviewComment(
                                id = "abc",
                                comment = "this is comment",
                                path = "src/test/file.kt",
                                line = 12,
                                rationale = "this is rationale",
                                changeType = CodeReviewCommentChangeType.ADDED,
                            ),
                            ReviewComment(
                                id = "123",
                                comment = "this is another comment",
                                path = "src/test/AnotherFile.kt",
                                line = 12,
                                rationale = "this is another rationale",
                                changeType = CodeReviewCommentChangeType.UNCHANGED,
                            ),
                        ),
                )
            coEvery { pantryManager.getEntry(any(), any()) } returns null
            coEvery {
                pantryManager.createEntry(any(), any(), any())
            } returns pantryEntry

            autoreviewWorkflowsStorageService.storeAutoreviewPostedComments(
                pullRequestUrl,
                postedComments,
            )

            coVerify(exactly = 1) {
                pantryManager.createEntry(
                    null,
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_POSTED_COMMENTS.value,
                    postedComments,
                )
            }
            coVerify(exactly = 0) {
                pantryManager.updateEntry(any(), any(), any())
            }
        }
    }

    @Test
    fun `storeAutoreviewPostedComments should update existing entry if one exists`() {
        runTest {
            val pullRequestUrl = "https://example.com/pr/1"
            val existingComments =
                AutoreviewPostedCommentsPantryItem(
                    postedComments =
                        listOf(
                            ReviewComment(
                                id = "abc",
                                comment = "this is a new comment",
                                path = "src/test/file.kt",
                                line = 12,
                                rationale = "this is the new rationale",
                                changeType = CodeReviewCommentChangeType.ADDED,
                            ),
                        ),
                )
            val newComments =
                AutoreviewPostedCommentsPantryItem(
                    postedComments =
                        listOf(
                            ReviewComment(
                                id = "abc123",
                                comment = "this is an existing comment",
                                path = "src/test/existingFile.kt",
                                line = 12,
                                rationale = "this is an exsting rationale",
                                changeType = CodeReviewCommentChangeType.ADDED,
                            ),
                            (
                                ReviewComment(
                                    id = "abc",
                                    comment = "this is a new comment",
                                    path = "src/test/file.kt",
                                    line = 12,
                                    rationale = "this is the new rationale",
                                    changeType = CodeReviewCommentChangeType.ADDED,
                                )
                            ),
                        ),
                )
            coEvery { pantryManager.getEntry(any(), any(), any()) } returns pantryEntry
            every { pantryEntry.value } returns existingComments
            coEvery {
                pantryManager.updateEntry(any(), any(), any())
            } returns pantryEntry

            autoreviewWorkflowsStorageService.storeAutoreviewPostedComments(
                pullRequestUrl,
                newComments,
            )

            coVerify(exactly = 0) {
                pantryManager.createEntry(any(), any(), any())
            }
            coVerify(exactly = 1) {
                pantryManager.updateEntry(
                    null,
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_POSTED_COMMENTS.value,
                    newComments,
                )
            }
        }
    }

    @Test
    fun `getPullRequestStatus gets correct pantry item`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"

            autoreviewWorkflowsStorageService.getPullRequestStatus(testDevAiWorkspaceAri, pullRequestUrl)

            coVerify(exactly = 1) {
                pantryManager.getEntry(
                    any(),
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_STATUS.value,
                )
            }
        }
    }

    @Test
    fun `getPullRequestStatus returns null when pantry entry is null`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"
            coEvery { pantryManager.getEntry(any(), any()) } returns null

            val result = autoreviewWorkflowsStorageService.getPullRequestStatus(testDevAiWorkspaceAri, pullRequestUrl)

            result shouldBe null
        }
    }

    @Test
    fun `getPullRequestStatus returns status when pantry entry exists`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"
            val updatedDate = OffsetDateTime.now()
            val createdDate = updatedDate.minusHours(1)
            val expectedStatus =
                AutoreviewPantryStatus(
                    pullRequestUrl = pullRequestUrl,
                    status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    acceptanceCriteriaStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    createdDate = createdDate,
                    lastUpdatedDate = updatedDate,
                    workspaceAri = "ari:cloud:bitbucket::workspace/123",
                    sourceCommit = "abc123",
                )

            coEvery { pantryManager.getEntry(any(), any(), any()) } returns pantryEntry
            every { pantryEntry.value } returns objectMapper.convertValue(expectedStatus, Map::class.java)

            val result = autoreviewWorkflowsStorageService.getPullRequestStatus(testDevAiWorkspaceAri, pullRequestUrl)

            // Serializing and deserializing with Jackson leads to conversion to UTC, so we compare in UTC
            result!!.copy(
                createdDate = result.createdDate.withOffsetSameInstant(ZoneOffset.UTC),
                lastUpdatedDate = result.lastUpdatedDate.withOffsetSameInstant(ZoneOffset.UTC),
            ) shouldBe
                expectedStatus.copy(
                    createdDate = expectedStatus.createdDate.withOffsetSameInstant(ZoneOffset.UTC),
                    lastUpdatedDate = expectedStatus.lastUpdatedDate.withOffsetSameInstant(ZoneOffset.UTC),
                )
        }
    }

    @Test
    fun `getPullRequestStatus returns null when pantry entry value is invalid`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"
            coEvery { pantryManager.getEntry(any(), any()) } returns pantryEntry
            every { pantryEntry.value } returns mapOf("invalid" to "data")

            val result = autoreviewWorkflowsStorageService.getPullRequestStatus(testDevAiWorkspaceAri, pullRequestUrl)

            result shouldBe null
        }
    }

    @Test
    fun `storePullRequestStatus creates new entry when none exists`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"
            val status = AutoreviewProcessStatus.WORKFLOW_PENDING
            val devAIWorkspaceARi =
                DevaiWorkspaceARI.valueOf("ari:cloud:devai::workspace/e1efaa0f-9172-4962-a37d-b00152456cf4")
            val sourceCommit = "123456789abcde"

            coEvery { pantryManager.getEntry(any(), any(), any()) } returns null
            coEvery { pantryManager.createEntry(any(), any(), any(), any()) } returns pantryEntry

            val result =
                autoreviewWorkflowsStorageService.storePullRequestStatus(
                    pullRequestUrl = pullRequestUrl,
                    status = status,
                    devaiWorkspaceAri = devAIWorkspaceARi,
                    scmSourceCommit = sourceCommit,
                    acceptanceCriteriaStatus = status,
                )

            result shouldBe pantryEntry

            coVerify(exactly = 1) {
                pantryManager.createEntry(
                    ARI.of(devAIWorkspaceARi),
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_STATUS.value,
                    match<AutoreviewPantryStatus> { statusEntry ->
                        statusEntry.pullRequestUrl == pullRequestUrl &&
                            statusEntry.status == status &&
                            statusEntry.workspaceAri == devAIWorkspaceARi.toString() &&
                            statusEntry.sourceCommit == sourceCommit.take(SHORT_COMMIT_HASH_CHARS)
                    },
                )
            }

            // Verify analytics event is fired
            coVerify(exactly = 1) {
                autoreviewAnalyticsService.sendAutoreviewProcessStatusEvent(
                    processStatus = status.name,
                    sourceCommit = sourceCommit.take(SHORT_COMMIT_HASH_CHARS),
                    pullRequestUrl = pullRequestUrl,
                )
            }
        }
    }

    @Test
    fun `storePullRequestStatus updates existing entry when one exists`() {
        runTest {
            val pullRequestUrl = "https://example.com/pull/1"
            val newStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED
            val newSourceCommit = "def456"

            val oldDate = OffsetDateTime.now().minusHours(1)
            val existingStatus =
                AutoreviewPantryStatus(
                    pullRequestUrl = pullRequestUrl,
                    status = AutoreviewProcessStatus.WORKFLOW_PENDING,
                    acceptanceCriteriaStatus = AutoreviewProcessStatus.WORKFLOW_PENDING,
                    createdDate = oldDate,
                    lastUpdatedDate = oldDate,
                    workspaceAri = testDevAiWorkspaceAri.toString(),
                    sourceCommit = "abc123",
                )

            coEvery { pantryManager.getEntry(any(), any(), any()) } returns pantryEntry
            every { pantryEntry.value } returns objectMapper.convertValue(existingStatus, Map::class.java)
            coEvery { pantryManager.updateEntry(any(), any(), any(), any()) } returns pantryEntry

            val result =
                autoreviewWorkflowsStorageService.storePullRequestStatus(
                    pullRequestUrl = pullRequestUrl,
                    status = newStatus,
                    devaiWorkspaceAri = testDevAiWorkspaceAri,
                    scmSourceCommit = newSourceCommit,
                    acceptanceCriteriaStatus = newStatus,
                )

            result shouldBe pantryEntry

            coVerify(exactly = 0) { pantryManager.createEntry(any(), any(), any()) }

            coVerify(exactly = 1) {
                pantryManager.updateEntry(
                    ARI.of(testDevAiWorkspaceAri),
                    pullRequestUrl,
                    AutoreviewPantryKey.AUTOREVIEW_STATUS.value,
                    match<AutoreviewPantryStatus> { statusEntry ->
                        statusEntry.pullRequestUrl == pullRequestUrl &&
                            statusEntry.status == newStatus &&
                            statusEntry.workspaceAri == testDevAiWorkspaceAri.toString() &&
                            statusEntry.sourceCommit == newSourceCommit &&
                            statusEntry.createdDate.isEqual(existingStatus.createdDate) &&
                            statusEntry.lastUpdatedDate.isAfter(existingStatus.lastUpdatedDate)
                    },
                )
            }
        }
    }

    @Nested
    inner class OneTimeRuns {
        @Test
        fun `getOneTimeRuns returns null when pantry entry is null`() {
            runTest {
                // Arrange
                coEvery { pantryManager.getEntry(any(), any()) } returns null

                // Act
                val result =
                    autoreviewWorkflowsStorageService.getOneTimeRuns(
                        testDevAiWorkspaceAri,
                        TEST_REPOSITORY_URL,
                    )

                // Assert
                result shouldBe null
            }
        }

        @Test
        fun `getOneTimeRuns returns null when pantry entry value is invalid`() {
            runTest {
                // Arrange
                coEvery { pantryManager.getEntry(any(), any()) } returns pantryEntry
                every { pantryEntry.value } returns mapOf("invalid" to "data")

                // Act
                val result =
                    autoreviewWorkflowsStorageService.getOneTimeRuns(
                        testDevAiWorkspaceAri,
                        TEST_REPOSITORY_URL,
                    )

                // Assert
                result shouldBe null
            }
        }

        @Test
        fun `getOneTimeRuns returns item when pantry entry exists`() {
            runTest {
                // Arrange
                val autoreviewOneTimeRunsPantryItem =
                    AutoreviewOneTimeRunsPantryItem(
                        oneTimeRuns =
                            listOf(
                                AutoreviewOneTimeRun(
                                    jobId = UUID.randomUUID().toString(),
                                    createdDate = OffsetDateTime.now(),
                                    workspaceAri = testDevAiWorkspaceAri.toString(),
                                    workflowName = CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW,
                                ),
                            ),
                    )

                coEvery { pantryManager.getEntry(any(), any(), any()) } returns pantryEntry
                every { pantryEntry.value } returns
                    objectMapper.convertValue(
                        autoreviewOneTimeRunsPantryItem,
                        AutoreviewOneTimeRunsPantryItem::class.java,
                    )

                // Act
                val result =
                    autoreviewWorkflowsStorageService.getOneTimeRuns(
                        testDevAiWorkspaceAri,
                        TEST_REPOSITORY_URL,
                    )

                // Assert
                result!!.oneTimeRuns.first().jobId shouldBe autoreviewOneTimeRunsPantryItem.oneTimeRuns.first().jobId
                result.oneTimeRuns.first().workflowName shouldBe autoreviewOneTimeRunsPantryItem.oneTimeRuns.first().workflowName
                result.oneTimeRuns.first().workspaceAri shouldBe autoreviewOneTimeRunsPantryItem.oneTimeRuns.first().workspaceAri
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `hasNoRuns returns false when pantry entry exists with same workflow name`(hasSameWorkflowName: Boolean) {
            // Arrange
            val workflowName =
                if (hasSameWorkflowName) {
                    CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW
                } else {
                    "some-other-workflow"
                }
            val result =
                AutoreviewOneTimeRunsPantryItem(
                    oneTimeRuns =
                        listOf(
                            AutoreviewOneTimeRun(
                                jobId = UUID.randomUUID().toString(),
                                createdDate = OffsetDateTime.now(),
                                workspaceAri = testDevAiWorkspaceAri.toString(),
                                workflowName = CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW,
                            ),
                        ),
                )

            // Assert
            if (hasSameWorkflowName) {
                result.hasNoRuns(workflowName) shouldBe false
            } else {
                result.hasNoRuns(workflowName) shouldBe true
            }
        }

        @Test
        fun `createOneTimeRunPantryEntry creates new pantry entry with 24hr expire time when none exists`() {
            runTest {
                // Arrange
                val expireTimeSlot = slot<Instant>()
                val valueSlot = slot<AutoreviewOneTimeRunsPantryItem>()
                coEvery { pantryManager.getEntry(any(), any(), any()) } returns null
                coEvery {
                    pantryManager.createEntry(any(), any(), any(), capture(valueSlot), capture(expireTimeSlot))
                } returns pantryEntry

                // Act
                autoreviewWorkflowsStorageService.createOneTimeRunPantryEntry(
                    devaiWorkspaceAri = testDevAiWorkspaceAri,
                    repositoryUrl = TEST_REPOSITORY_URL,
                    jobId = TEST_WORKFLOW_RUN_ID,
                )
                val createdDate =
                    valueSlot.captured.oneTimeRuns
                        .first()
                        .createdDate
                val expectedExpireTime = Instant.from(createdDate).plusSeconds(ONE_TIME_RUN_TTL_SECONDS)

                // Assert
                expireTimeSlot.captured shouldBe expectedExpireTime
                coVerify(exactly = 1) {
                    pantryManager.createEntry(
                        workspaceAri = ARI.of(testDevAiWorkspaceAri),
                        container = TEST_REPOSITORY_URL,
                        key = AutoreviewPantryKey.AUTOREVIEW_STANDARDS_EXTRACTOR.value,
                        value =
                            match<AutoreviewOneTimeRunsPantryItem> { item ->
                                item.oneTimeRuns.size == 1 &&
                                    item.oneTimeRuns.first().jobId == TEST_WORKFLOW_RUN_ID &&
                                    item.oneTimeRuns.first().workspaceAri == testDevAiWorkspaceAri.toString() &&
                                    item.oneTimeRuns.first().workflowName == CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW
                            },
                        expireTime =
                            match {
                                it.shouldBeInstanceOf<Instant>()
                                true
                            },
                    )
                }
            }
        }

        @Test
        fun `updateOneTimeRunPantryEntry updates existing pantry entry and removes expire time property`() {
            runTest {
                // Arrange
                coEvery { pantryManager.updateEntry(any(), any(), any(), any()) } returns pantryEntry

                // Act
                autoreviewWorkflowsStorageService.updateOneTimeRunPantryEntry(
                    devaiWorkspaceAri = testDevAiWorkspaceAri,
                    repositoryUrl = TEST_REPOSITORY_URL,
                    jobId = TEST_WORKFLOW_RUN_ID,
                )

                // Assert
                coVerify(exactly = 0) { pantryManager.createEntry(any(), any(), any(), any(), any()) }
                coVerify(exactly = 1) {
                    pantryManager.updateEntry(
                        workspaceAri = ARI.of(testDevAiWorkspaceAri),
                        container = TEST_REPOSITORY_URL,
                        key = AutoreviewPantryKey.AUTOREVIEW_STANDARDS_EXTRACTOR.value,
                        value =
                            match<AutoreviewOneTimeRunsPantryItem> { item ->
                                item.oneTimeRuns.size == 1 &&
                                    item.oneTimeRuns.first().jobId == TEST_WORKFLOW_RUN_ID &&
                                    item.oneTimeRuns.first().workspaceAri == testDevAiWorkspaceAri.toString() &&
                                    item.oneTimeRuns.first().workflowName == CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW
                            },
                    )
                }
            }
        }

        @Test
        fun `updateOneTimeRunPantryEntry creates pantry entry when none exists`() {
            runTest {
                // Arrange
                val existingItem =
                    AutoreviewOneTimeRunsPantryItem(
                        oneTimeRuns =
                            listOf(
                                AutoreviewOneTimeRun(
                                    jobId = TEST_WORKFLOW_RUN_ID,
                                    createdDate = OffsetDateTime.now(),
                                    workspaceAri = testDevAiWorkspaceAri.toString(),
                                    workflowName = CODE_REVIEW_CODING_STANDARDS_EXTRACTOR_WORKFLOW,
                                ),
                            ),
                    )

                coEvery {
                    pantryManager.getEntry(any(), any(), any())
                } returns null

                every { pantryEntry.value } returns
                    objectMapper.convertValue(
                        existingItem,
                        AutoreviewOneTimeRunsPantryItem::class.java,
                    )
                coEvery { pantryManager.updateEntry(any(), any(), any(), any()) } returns pantryEntry

                // Act & Assert
                shouldNotThrowAny {
                    autoreviewWorkflowsStorageService.updateOneTimeRunPantryEntry(
                        devaiWorkspaceAri = testDevAiWorkspaceAri,
                        repositoryUrl = TEST_REPOSITORY_URL,
                        jobId = TEST_WORKFLOW_RUN_ID,
                    )
                }
            }
        }
    }

    @Nested
    inner class WorkflowStatusOperations {
        @ParameterizedTest
        @ValueSource(strings = ["IN_PROGRESS", "in-progress"])
        fun `getWorkflowStatusByJobId handles different status name formats for in progress status`(statusName: String) =
            runTest {
                // Given
                val jobId = "test-job-id"
                val expectedStatus = WorkflowRunStatus.IN_PROGRESS
                val acraResponse =
                    AutoreviewJobDetails(
                        id = UUID.randomUUID(),
                        currentWorkflow = "test-workflow",
                        status = statusName,
                        repoUrl = "https://test.repository.com/repo",
                    )

                coEvery {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/$jobId"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                } returns Mono.just(acraResponse)

                // When
                val result = autoreviewWorkflowsStorageService.getWorkflowStatusByJobId(jobId, transactionContext)

                // Then
                result shouldBe expectedStatus
                coVerify {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/$jobId"),
                        AutoreviewJobDetails::class.java,
                        match {
                            it.callerId == CallerId.DEV_AI_AUTOREVIEW &&
                                it.workspaceId == DEV_AI_WORKSPACE_ID &&
                                it.workspaceAri == workspaceAri
                        },
                    )
                }
            }

        private val aaid = "aaid"
        private val uct = "uct"
        private val workspaceAri = "ari:cloud:bitbucket::workspace/test-workspace"
        private val transactionContext =
            TransactionContext(
                workspace =
                    WorkspaceContext(
                        null,
                        WorkspaceId(DEV_AI_WORKSPACE_ID),
                        ARI.valueOf(workspaceAri),
                    ),
                traceId = null,
                userContext =
                    UserContext(
                        accountId = AccountId.of(aaid),
                        userContextToken = uct,
                        accountType = null,
                        tokenExpiration = null,
                    ),
            )

        @Test
        fun `getWorkflowStatusByJobId returns correct status when ACRA client returns valid response`() =
            runTest {
                // Given
                val jobId = "test-job-id"
                val expectedStatus = WorkflowRunStatus.COMPLETED
                val acraResponse =
                    AutoreviewJobDetails(
                        id = UUID.randomUUID(),
                        currentWorkflow = "Dummy workflow",
                        status = "completed",
                        repoUrl = "https://bitbucket.org/atlassian/test-repo",
                    )

                coEvery {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/$jobId"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                } returns Mono.just(acraResponse)

                // When
                val result = autoreviewWorkflowsStorageService.getWorkflowStatusByJobId(jobId, transactionContext)

                // Then
                result shouldBe expectedStatus
                coVerify {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/$jobId"),
                        AutoreviewJobDetails::class.java,
                        match {
                            it.callerId == CallerId.DEV_AI_AUTOREVIEW &&
                                it.workspaceId == DEV_AI_WORKSPACE_ID &&
                                it.workspaceAri == workspaceAri
                        },
                    )
                }
            }

        @Test
        fun `getWorkflowStatusByJobId propagates exception when ACRA client fails`() =
            runTest {
                // Given
                val jobId = "test-job-id"
                val expectedException = RuntimeException("ACRA client error")

                coEvery {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/$jobId"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                } returns Mono.error(expectedException)

                // When/Then
                val exception =
                    assertThrows<RuntimeException> {
                        autoreviewWorkflowsStorageService.getWorkflowStatusByJobId(jobId, transactionContext)
                    }
                exception shouldBe expectedException
            }

        @Test
        fun `getLatestWorkflowStatus returns UNKNOWN when no pantry item exists`() =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"

                coEvery {
                    autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl)
                } returns null

                // When
                val result = autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)

                // Then
                result shouldBe WorkflowRunStatus.UNKNOWN
            }

        @Test
        fun `getLatestWorkflowStatus returns UNKNOWN when no workflows exist in pantry item`() =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"
                val pantryItem =
                    AutoreviewPantryItem(
                        autoreviewWorkflows = emptyList(),
                    )

                coEvery {
                    autoreviewWorkflowsStorageService.getAutoreviewPantryItem(pullRequestUrl)
                } returns pantryItem

                // When
                val result = autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)

                // Then
                result shouldBe WorkflowRunStatus.UNKNOWN
            }

        @Test
        fun `getLatestWorkflowStatus returns status of latest workflow when multiple workflows exist`() =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"
                val oldWorkflow =
                    AutoreviewWorkflow(
                        jobId = "old-job-id",
                        createdDate = OffsetDateTime.now().minusHours(1).toString(),
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value,
                        sourceCommit = "old-commit",
                        workspaceAri = workspaceAri,
                    )
                val latestWorkflow =
                    AutoreviewWorkflow(
                        jobId = "latest-job-id",
                        createdDate = OffsetDateTime.now().toString(),
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value,
                        sourceCommit = "latest-commit",
                        workspaceAri = workspaceAri,
                    )
                val pantryItem =
                    PantryEntry(
                        value = mapOf("autoreviewWorkflows" to listOf(oldWorkflow, latestWorkflow)),
                    )
                val expectedStatus = WorkflowRunStatus.IN_PROGRESS
                val acraResponse =
                    AutoreviewJobDetails(
                        id = UUID.randomUUID(),
                        currentWorkflow = "test-workflow",
                        status = expectedStatus.name,
                        repoUrl = "https://test.repository.com/repo",
                    )

                coEvery {
                    pantryManager.getEntry(any(), any(), any())
                } returns pantryItem

                coEvery {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/${latestWorkflow.jobId}"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                } returns Mono.just(acraResponse)

                // When
                val result = autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)

                // Then
                result shouldBe expectedStatus
                coVerify {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/${latestWorkflow.jobId}"),
                        AutoreviewJobDetails::class.java,
                        match {
                            it.callerId == CallerId.DEV_AI_AUTOREVIEW &&
                                it.workspaceId == DEV_AI_WORKSPACE_ID &&
                                it.workspaceAri == workspaceAri
                        },
                    )
                }
            }

        @Test
        fun `getLatestWorkflowStatus throws error when ACRA client fails`() =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"
                val workflow =
                    AutoreviewWorkflow(
                        jobId = "test-job-id",
                        createdDate = OffsetDateTime.now().toString(),
                        sourceCommit = "some-commit",
                        rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value,
                        workspaceAri = workspaceAri,
                    )
                val pantryItem =
                    PantryEntry(
                        value = mapOf("autoreviewWorkflows" to listOf(workflow)),
                    )

                coEvery {
                    pantryManager.getEntry(any(), any(), any())
                } returns pantryItem

                coEvery {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/${workflow.jobId}"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                } returns Mono.error(RuntimeException("ACRA client error"))

                // When
                assertThrows<RuntimeException> {
                    autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)
                }
            }

        @Test
        fun `getLatestWorkflowStatus excludes workflows without source commit`() =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"
                val pantryItem =
                    PantryEntry(
                        value =
                            mapOf(
                                "autoreviewWorkflows" to
                                    listOf(
                                        // Should be included - has source commit
                                        AutoreviewWorkflow(
                                            jobId = "job-with-commit",
                                            createdDate = OffsetDateTime.now().minusHours(1).toString(),
                                            sourceCommit = "abc123",
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                        // Should be excluded - no source commit (latest by date)
                                        AutoreviewWorkflow(
                                            jobId = "job-without-commit",
                                            createdDate = OffsetDateTime.now().toString(),
                                            sourceCommit = null,
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                    ),
                            ),
                    )

                coEvery {
                    pantryManager.getEntry(any(), any(), any())
                } returns pantryItem

                coEvery {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/job-with-commit"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                } returns
                    Mono.just(
                        AutoreviewJobDetails(
                            id = UUID.randomUUID(),
                            currentWorkflow = "test-workflow",
                            status = "COMPLETED",
                            repoUrl = "https://test.repository.com/repo",
                        ),
                    )

                // When
                autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)

                // Then - should call ACRA for the workflow with source commit, not the latest one without commit
                coVerify(exactly = 1) {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/job-with-commit"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                }
                coVerify(exactly = 0) {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/job-without-commit"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                }
            }

        @ParameterizedTest
        @MethodSource("devai.modules.autoreview.service.DefaultAutoreviewServiceTest#workflowTypeTestData")
        fun `getLatestWorkflowStatus excludes workflows with irrelevant workflow types`(workflowValue: String) =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"
                val pantryItem =
                    PantryEntry(
                        value =
                            mapOf(
                                "autoreviewWorkflows" to
                                    listOf(
                                        // Should be included - relevant workflow type
                                        AutoreviewWorkflow(
                                            jobId = "job-relevant-type",
                                            createdDate = OffsetDateTime.now().minusHours(1).toString(),
                                            sourceCommit = "abc123",
                                            rootWorkflow = workflowValue,
                                            workspaceAri = workspaceAri,
                                        ),
                                        // Should be excluded - irrelevant workflow type (latest by date)
                                        AutoreviewWorkflow(
                                            jobId = "job-irrelevant-type",
                                            createdDate = OffsetDateTime.now().toString(),
                                            sourceCommit = "def456",
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_CODE_SUGGESTION.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                    ),
                            ),
                    )

                coEvery {
                    pantryManager.getEntry(any(), any(), any())
                } returns pantryItem

                coEvery {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/job-relevant-type"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                } returns
                    Mono.just(
                        AutoreviewJobDetails(
                            id = UUID.randomUUID(),
                            currentWorkflow = "test-workflow",
                            status = "COMPLETED",
                            repoUrl = "https://test.repository.com/repo",
                        ),
                    )

                autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)

                // Then - should call ACRA for the relevant workflow type, not the latest irrelevant one
                coVerify(exactly = 1) {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/job-relevant-type"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                }
                coVerify(exactly = 0) {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/job-irrelevant-type"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                }
            }

        @Test
        fun `getLatestWorkflowStatus returns UNKNOWN when no workflows match filter criteria`() =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"
                val pantryItem =
                    PantryEntry(
                        value =
                            mapOf(
                                "autoreviewWorkflows" to
                                    listOf(
                                        // Should be excluded - no source commit
                                        AutoreviewWorkflow(
                                            jobId = "job-1",
                                            createdDate = OffsetDateTime.now().minusHours(1).toString(),
                                            sourceCommit = null,
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                        // Should be excluded - not a relevant workflow type
                                        AutoreviewWorkflow(
                                            jobId = "job-2",
                                            createdDate = OffsetDateTime.now().toString(),
                                            sourceCommit = "abc123",
                                            rootWorkflow = "SOME_OTHER_WORKFLOW",
                                            workspaceAri = workspaceAri,
                                        ),
                                    ),
                            ),
                    )

                coEvery {
                    pantryManager.getEntry(any(), any(), any())
                } returns pantryItem

                // When
                val result = autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)

                // Then - should return UNKNOWN because no workflows match the filter criteria
                result shouldBe WorkflowRunStatus.UNKNOWN

                // Verify that no ACRA calls were made since no valid workflows were found
                coVerify(exactly = 0) {
                    acraClient.submitACRAGetRequest(any(), AutoreviewJobDetails::class.java, any())
                }
            }

        @Test
        fun `getLatestWorkflowStatus includes all relevant workflow types when they have source commit`() =
            runTest {
                // Given
                val pullRequestUrl = "https://test.repository.com/pr/123"
                val pantryItem =
                    PantryEntry(
                        value =
                            mapOf(
                                "autoreviewWorkflows" to
                                    listOf(
                                        AutoreviewWorkflow(
                                            jobId = "job-first-review",
                                            createdDate = OffsetDateTime.now().minusHours(4).toString(),
                                            sourceCommit = "commit1",
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                        AutoreviewWorkflow(
                                            jobId = "job-incremental",
                                            createdDate = OffsetDateTime.now().minusHours(3).toString(),
                                            sourceCommit = "commit2",
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                        AutoreviewWorkflow(
                                            jobId = "job-acceptance-criteria",
                                            createdDate = OffsetDateTime.now().minusHours(2).toString(),
                                            sourceCommit = "commit3",
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                        AutoreviewWorkflow(
                                            jobId = "job-custom-review",
                                            createdDate = OffsetDateTime.now().minusHours(1).toString(),
                                            sourceCommit = "commit4",
                                            rootWorkflow = RootWorkflowName.AUTOREVIEW_CUSTOM_REVIEW.value,
                                            workspaceAri = workspaceAri,
                                        ),
                                    ),
                            ),
                    )

                coEvery {
                    pantryManager.getEntry(any(), any(), any())
                } returns pantryItem

                coEvery {
                    acraClient.submitACRAGetRequest(any(), AutoreviewJobDetails::class.java, any())
                } returns
                    Mono.just(
                        AutoreviewJobDetails(
                            id = UUID.randomUUID(),
                            currentWorkflow = "test-workflow",
                            status = "COMPLETED",
                            repoUrl = "https://test.repository.com/repo",
                        ),
                    )

                // When
                val result = autoreviewWorkflowsStorageService.getLatestWorkflowStatus(pullRequestUrl, transactionContext)

                // Then - should call ACRA for the latest workflow (job-custom-review)
                result shouldBe WorkflowRunStatus.COMPLETED
                coVerify {
                    acraClient.submitACRAGetRequest(
                        URI.create("$ACRA_AUTOREVIEW_URL/job-custom-review"),
                        AutoreviewJobDetails::class.java,
                        any(),
                    )
                }
            }
    }
}
