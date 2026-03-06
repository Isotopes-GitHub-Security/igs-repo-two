package devai.modules.autoreview.service

import com.atlassian.ari.principled.devai.DevaiWorkspaceARI
import com.atlassian.ari.principled.jira.JiraIssueARI
import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.model.WorkflowRun
import devai.modules.acra.shared.model.WorkflowRunArtifact
import devai.modules.acra.shared.model.WorkflowRunQueueType
import devai.modules.acra.shared.model.WorkflowRunStatus
import devai.modules.acra.shared.model.artifacts.AnnotatedCodeReviewCommentsArtifact
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames
import devai.modules.acra.shared.model.artifacts.ArtifactType
import devai.modules.acra.shared.model.artifacts.AutoreviewCodeSuggestion
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentItem
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentsArtifact
import devai.modules.acra.shared.model.artifacts.PullRequestInfoArtifact
import devai.modules.acra.shared.model.codereview.AutoreviewCommentCategory
import devai.modules.acra.shared.model.codereview.AutoreviewCommentSubCategory
import devai.modules.acra.shared.model.codereview.CodeReviewCommentAnnotationKey
import devai.modules.acra.shared.model.codereview.CodeReviewCommentChangeType
import devai.modules.acra.shared.model.codereview.CodeReviewCommentGeneratedBy
import devai.modules.acra.shared.model.codereview.CodeReviewCommentWithAnnotation
import devai.modules.acra.shared.model.codereview.CommentCategoryAnnotation
import devai.modules.acra.shared.model.codereview.CommentSubCategoryAnnotation
import devai.modules.autoreview.model.AutoreviewPostedCommentsPantryItem
import devai.modules.autoreview.model.AutoreviewProcessStatus
import devai.modules.autoreview.model.AutoreviewPullRequestComment
import devai.modules.autoreview.queue.model.ReviewComment
import devai.modules.models.service.CommentDeduplicationService
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.GLOBAL_WORKSPACE_ARI
import devai.modules.shared.queue.model.WorkflowUpdatedPayload
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.time.Instant.now
import java.util.UUID

class AutoreviewIncrementalWorkflowCompletedActionTest {
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val autoreviewService = mockk<AutoreviewService>(relaxed = true)
    private val autoreviewCommentService = mockk<AutoreviewCommentService>(relaxed = true)
    private val autoreviewWorkflowsStorageService = mockk<AutoreviewWorkflowsStorageService>(relaxed = true)
    private val commentDeduplicationService = mockk<CommentDeduplicationService>(relaxed = true)
    private val autoreviewUtilityService = mockk<AutoreviewUtilityService>(relaxed = true)
    private val mockPayload =
        mockk<WorkflowUpdatedPayload>(relaxed = true) {
            every { context.workspaceId } returns "6904c8f3-82af-4822-a3e7-d64a20abf0bb"
            every { context.traceId } returns "test-trace-id"
            every { context.accountId } returns "test-account-id"
        }
    private val workflowRun = mockk<WorkflowRun>(relaxed = true)
    private val workflowRunId = "6904c8f3-82af-4822-a3e7-d64a20abf0bb"
    private val testPrUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/709"

    private val mockPullRequestArtifact: PullRequestInfoArtifact =
        mockk<PullRequestInfoArtifact>(relaxed = true) {
            every { pullRequestUrl } returns testPrUrl
            every { sourceCommit } returns "1124abcde345f67890abcdef1234567890abcd"
        }

    private val testDevAiWorkdspaceAri =
        DevaiWorkspaceARI.valueOf("ari:cloud:devai::workspace/6904c8f3-82af-4822-a3e7-d64a20abf0bb")

    private val subject =
        AutoreviewIncrementalWorkflowCompletedAction(
            featureService = featureService,
            autoreviewService = autoreviewService,
            autoreviewCommentService = autoreviewCommentService,
            autoreviewWorkflowsStorageService = autoreviewWorkflowsStorageService,
            commentDeduplicationService = commentDeduplicationService,
            autoreviewUtilityService = autoreviewUtilityService,
        )

    @BeforeEach
    fun setup() {
        coEvery {
            autoreviewUtilityService.getDevAiWorkspaceForCodeReviewProcess(any())
        } returns testDevAiWorkdspaceAri
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `should be performed on incremental workflow`() {
        every { mockPayload.workflow } returns RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW.value
        subject.shouldBePerformedOn(mockPayload) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(
        RootWorkflowName::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = ["AUTOREVIEW_INCREMENTAL_REVIEW"],
    )
    fun `should not be performed on non-incremental workflows`(rootWorkflowName: RootWorkflowName) {
        every { mockPayload.workflow } returns rootWorkflowName.value
        subject.shouldBePerformedOn(mockPayload) shouldBe false
    }

    @Test
    fun `requires the autoreview scm comments and annotated code review comments artifacts`() {
        subject.requiredArtifactTypes() shouldBe
            setOf(
                ArtifactType.AUTOREVIEW_SCM_COMMENTS,
                ArtifactType.ANNOTATED_CODE_REVIEW_COMMENTS,
                ArtifactType.PULL_REQUEST_INFO,
                ArtifactType.EXECUTION_FLAGS,
            )
    }

    @Nested
    inner class SendInlineComments {
        @Test
        fun `does not send comments if there are none`() =
            runTest {
                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    comments = emptyList(),
                    isMultiLineCommentsEnabled = false,
                )

                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any(), any()) }
            }

        @Test
        fun `sends inline comments`() =
            runTest {
                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    comments = listOf(exampleComment("There is a bug here"), exampleComment("You missed this again")),
                    isMultiLineCommentsEnabled = false,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        match { comments ->
                            comments.map { it.comment } shouldContainExactlyInAnyOrder
                                listOf(
                                    "There is a bug here",
                                    "You missed this again",
                                )
                            true
                        },
                        eq(false),
                    )
                }
            }
    }

    @Nested
    inner class PerformAction {
        @Test
        fun `posts pull request comments when workflowRun has comments`() =
            runTest {
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true
                val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
                val annotatedCodeReviewCommentsArtifact = createMockAnnotatedCodeReviewCommentsArtifact()
                val commentIds = listOf(AutoreviewPullRequestComment("internal-id", "8434"))
                every { autoreviewScmCommentsArtifact.pullRequestUrl } returns
                    testPrUrl
                every { autoreviewScmCommentsArtifact.comments } returns
                    listOf(
                        exampleComment("There is a bug here"),
                    ).map {
                        AutoreviewScmCommentItem(
                            id = it.id,
                            comment = it.comment,
                            codeSuggestion = it.codeSuggestion,
                            path = it.path,
                            line = it.line,
                            rationale = it.rationale,
                            changeType = it.changeType,
                        )
                    }
                coEvery { autoreviewCommentService.prepareCommentsForSending(any(), any(), any()) } returns
                    listOf(
                        ReviewComment(
                            id = autoreviewScmCommentsArtifact.comments.first().id,
                            comment = autoreviewScmCommentsArtifact.comments.first().comment,
                            codeSuggestion = autoreviewScmCommentsArtifact.comments.first().codeSuggestion,
                            path = autoreviewScmCommentsArtifact.comments.first().path,
                            line = autoreviewScmCommentsArtifact.comments.first().line,
                            explanation = autoreviewScmCommentsArtifact.comments.first().explanation,
                            solution = autoreviewScmCommentsArtifact.comments.first().solution,
                            rationale = autoreviewScmCommentsArtifact.comments.first().rationale,
                        ),
                    )
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any(), any()) } returns commentIds

                val exampleWorkflow =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = annotatedCodeReviewCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = mockPullRequestArtifact,
                                ),
                            ),
                    )

                subject.performAction(
                    workflowRun = exampleWorkflow,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        pullRequestUrl = testPrUrl,
                        reviewComments =
                            match {
                                it.size == 1 && it.first().comment == "There is a bug here"
                            },
                        isMultiLineCommentsEnabled = any(),
                    )
                }
            }

        @Test
        fun `should skip deduplication and store posted comments in pantry if nothing in pantry previously`() =
            runTest {
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true
                val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
                val annotatedCodeReviewCommentsArtifact = createMockAnnotatedCodeReviewCommentsArtifact()
                val workflowRun =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = annotatedCodeReviewCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = mockPullRequestArtifact,
                                ),
                            ),
                    )
                val prUrl = testPrUrl
                every { autoreviewScmCommentsArtifact.pullRequestUrl } returns prUrl
                every { autoreviewScmCommentsArtifact.comments } returns
                    listOf(
                        exampleComment("There is a bug here"),
                    ).map {
                        AutoreviewScmCommentItem(
                            id = it.id,
                            comment = it.comment,
                            codeSuggestion = it.codeSuggestion,
                            path = it.path,
                            line = it.line,
                            rationale = it.rationale,
                            changeType = it.changeType,
                        )
                    }
                coEvery { autoreviewCommentService.prepareCommentsForSending(any(), any(), any()) } returns
                    listOf(
                        ReviewComment(
                            id = autoreviewScmCommentsArtifact.comments.first().id,
                            comment = autoreviewScmCommentsArtifact.comments.first().comment,
                            codeSuggestion = autoreviewScmCommentsArtifact.comments.first().codeSuggestion,
                            path = autoreviewScmCommentsArtifact.comments.first().path,
                            line = autoreviewScmCommentsArtifact.comments.first().line,
                            explanation = autoreviewScmCommentsArtifact.comments.first().explanation,
                            solution = autoreviewScmCommentsArtifact.comments.first().solution,
                            rationale = autoreviewScmCommentsArtifact.comments.first().rationale,
                        ),
                    )
                val commentIds =
                    listOf(AutoreviewPullRequestComment(autoreviewScmCommentsArtifact.comments.first().id, "8434"))
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any(), any()) } returns commentIds
                coEvery { autoreviewWorkflowsStorageService.getPostedCommentsPantryItem(any()) } returns null

                subject.performAction(
                    workflowRun = workflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storeAutoreviewPostedComments(
                        pullRequestUrl = prUrl,
                        postedComments =
                            match<AutoreviewPostedCommentsPantryItem> {
                                it.postedComments.size == 1 && it.postedComments.first().id == commentIds.first().id
                            },
                    )
                }
                coVerify(exactly = 0) {
                    commentDeduplicationService.getPairwiseSimilarityMatrix(
                        any(),
                        any(),
                        any(),
                    )
                }
            }

        @Test
        fun `should store posted comments in pantry if something already in pantry`() =
            runTest {
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true
                val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
                val annotatedCodeReviewCommentsArtifact = createMockAnnotatedCodeReviewCommentsArtifact()
                val workflowRun =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = annotatedCodeReviewCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = mockPullRequestArtifact,
                                ),
                            ),
                    )
                every { autoreviewScmCommentsArtifact.pullRequestUrl } returns testPrUrl
                every { autoreviewScmCommentsArtifact.comments } returns
                    listOf(
                        exampleComment("There is a bug here"),
                    ).map {
                        AutoreviewScmCommentItem(
                            id = it.id,
                            comment = it.comment,
                            codeSuggestion = it.codeSuggestion,
                            path = it.path,
                            line = it.line,
                            rationale = it.rationale,
                            changeType = it.changeType,
                        )
                    }
                coEvery { autoreviewCommentService.prepareCommentsForSending(any(), any(), any()) } returns
                    listOf(
                        ReviewComment(
                            id = autoreviewScmCommentsArtifact.comments.first().id,
                            comment = autoreviewScmCommentsArtifact.comments.first().comment,
                            codeSuggestion = autoreviewScmCommentsArtifact.comments.first().codeSuggestion,
                            path = autoreviewScmCommentsArtifact.comments.first().path,
                            line = autoreviewScmCommentsArtifact.comments.first().line,
                            explanation = autoreviewScmCommentsArtifact.comments.first().explanation,
                            solution = autoreviewScmCommentsArtifact.comments.first().solution,
                            rationale = autoreviewScmCommentsArtifact.comments.first().rationale,
                        ),
                    )
                val commentIds =
                    listOf(AutoreviewPullRequestComment(autoreviewScmCommentsArtifact.comments.first().id, "8434"))
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any(), any()) } returns commentIds
                coEvery { autoreviewWorkflowsStorageService.getPostedCommentsPantryItem(any()) } returns
                    AutoreviewPostedCommentsPantryItem(
                        postedComments =
                            listOf(
                                ReviewComment(
                                    id = "old-id",
                                    comment = "old comment",
                                    path = "old path",
                                    line = 1,
                                ),
                            ),
                    )
                coEvery {
                    commentDeduplicationService.getPairwiseSimilarityMatrix(
                        any(),
                        any(),
                        any(),
                    )
                } returns listOf(doubleArrayOf(0.8, 0.6), doubleArrayOf(0.6, 0.8))

                subject.performAction(
                    workflowRun = workflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storeAutoreviewPostedComments(
                        pullRequestUrl = testPrUrl,
                        postedComments =
                            match<AutoreviewPostedCommentsPantryItem> {
                                it.postedComments.size == 2 &&
                                    it.postedComments.last().id == commentIds.last().id &&
                                    it.postedComments.first().id == "old-id"
                            },
                    )
                }

                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storePullRequestStatus(
                        pullRequestUrl = testPrUrl,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = AutoreviewProcessStatus.UNKNOWN,
                        devaiWorkspaceAri = testDevAiWorkdspaceAri,
                        scmSourceCommit = "1124abcde345f67890abcdef1234567890abcd",
                    )
                }
            }

        @Test
        fun `should retain only new comments as per threshold`() =
            runTest {
                // Arrange
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true
                val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
                val annotatedCodeReviewCommentsArtifact = createMockAnnotatedCodeReviewCommentsArtifact()
                val workflowRun =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = annotatedCodeReviewCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = mockPullRequestArtifact,
                                ),
                            ),
                    )
                val prUrl = testPrUrl
                every { autoreviewScmCommentsArtifact.pullRequestUrl } returns prUrl
                every { autoreviewScmCommentsArtifact.comments } returns
                    listOf(
                        exampleComment("This should be refactored"),
                        exampleComment("There is a bug here"),
                    ).map {
                        AutoreviewScmCommentItem(
                            id = it.id,
                            comment = it.comment,
                            codeSuggestion = it.codeSuggestion,
                            path = it.path,
                            line = it.line,
                            rationale = it.rationale,
                            changeType = it.changeType,
                        )
                    }
                coEvery { autoreviewCommentService.prepareCommentsForSending(any(), any(), any()) } returns
                    listOf(
                        ReviewComment(
                            id = autoreviewScmCommentsArtifact.comments.first().id,
                            comment = autoreviewScmCommentsArtifact.comments.first().comment,
                            codeSuggestion = autoreviewScmCommentsArtifact.comments.first().codeSuggestion,
                            path = autoreviewScmCommentsArtifact.comments.first().path,
                            line = autoreviewScmCommentsArtifact.comments.first().line,
                            explanation = autoreviewScmCommentsArtifact.comments.first().explanation,
                            solution = autoreviewScmCommentsArtifact.comments.first().solution,
                            rationale = autoreviewScmCommentsArtifact.comments.first().rationale,
                        ),
                    )
                val commentIds =
                    listOf(
                        AutoreviewPullRequestComment(
                            autoreviewScmCommentsArtifact.comments.first().id,
                            "8434",
                        ),
                    )
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any(), any()) } returns commentIds
                coEvery { autoreviewWorkflowsStorageService.getPostedCommentsPantryItem(any()) } returns
                    AutoreviewPostedCommentsPantryItem(
                        postedComments =
                            listOf(
                                ReviewComment(
                                    id = "old-id",
                                    comment = "I think this is a bug",
                                    path = "old path",
                                    line = 1,
                                ),
                            ),
                    )
                coEvery {
                    commentDeduplicationService.getPairwiseSimilarityMatrix(
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    listOf(
                        doubleArrayOf(1.0, 0.4, 0.8),
                        doubleArrayOf(0.4, 1.0, 0.3),
                        doubleArrayOf(0.8, 0.3, 1.0),
                    )

                // Act
                subject.performAction(
                    workflowRun = workflowRun,
                    eventPayload = mockPayload,
                )

                // Assert
                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        prUrl,
                        match { comments ->
                            comments.size == 1 && comments.first().comment == "This should be refactored"
                        },
                        any(),
                    )
                }
            }

        @Test
        fun `should early return when no new comments are generated`() =
            runTest {
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true
                val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
                val annotatedCodeReviewCommentsArtifact = createMockAnnotatedCodeReviewCommentsArtifact()
                val workflowRun =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = annotatedCodeReviewCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = mockPullRequestArtifact,
                                ),
                            ),
                    )
                every { autoreviewScmCommentsArtifact.pullRequestUrl } returns testPrUrl
                every { autoreviewScmCommentsArtifact.comments } returns emptyList()

                subject.performAction(
                    workflowRun = workflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 0) { autoreviewWorkflowsStorageService.getPostedCommentsPantryItem(any()) }
                coVerify(
                    exactly = 0,
                ) {
                    autoreviewWorkflowsStorageService.storeAutoreviewPostedComments(
                        pullRequestUrl = any(),
                        postedComments = any(),
                    )
                }
                coVerify(exactly = 0) {
                    commentDeduplicationService.getPairwiseSimilarityMatrix(
                        any(),
                        any(),
                        any(),
                    )
                }
                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any(), any()) }
            }

        @Test
        fun `should prepend category to comments`() =
            runTest {
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true

                val commentId = "matching-comment-id"
                val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
                val annotatedCodeReviewCommentsArtifact =
                    AnnotatedCodeReviewCommentsArtifact(
                        comments =
                            listOf(
                                CodeReviewCommentWithAnnotation(
                                    id = commentId,
                                    comment = "Test comment",
                                    path = "test/path.kt",
                                    line = 10,
                                    changeType = CodeReviewCommentChangeType.ADDED,
                                    generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                                    annotations =
                                        mapOf(
                                            CodeReviewCommentAnnotationKey.COMMENT_CATEGORY to
                                                CommentCategoryAnnotation(AutoreviewCommentCategory.MAINTAINABILITY.value),
                                            CodeReviewCommentAnnotationKey.COMMENT_SUB_CATEGORY to
                                                CommentSubCategoryAnnotation(
                                                    AutoreviewCommentSubCategory.MAINTAINABILITY_SUBCATEGORIES[0].value,
                                                ),
                                        ),
                                ),
                            ),
                    )

                val commentIds = listOf(AutoreviewPullRequestComment("internal-id", "8434"))
                every { autoreviewScmCommentsArtifact.pullRequestUrl } returns
                    testPrUrl
                val scmComment =
                    AutoreviewScmCommentItem(
                        id = commentId,
                        comment = "Original comment text",
                        codeSuggestion = null,
                        path = "test/path.kt",
                        line = 10,
                        rationale = null,
                        changeType = CodeReviewCommentChangeType.ADDED,
                    )
                every { autoreviewScmCommentsArtifact.comments } returns listOf(scmComment)
                val expectedComment = "##### ⚠\uFE0F Maintainability - Cyclic Dependency \n\n${scmComment.comment}"
                coEvery { autoreviewCommentService.prepareCommentsForSending(any(), any(), any()) } returns
                    listOf(
                        ReviewComment(
                            id = scmComment.id,
                            comment = expectedComment,
                            codeSuggestion = scmComment.codeSuggestion,
                            path = scmComment.path,
                            line = scmComment.line,
                            explanation = scmComment.explanation,
                            solution = scmComment.solution,
                            rationale = scmComment.rationale,
                        ),
                    )
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any(), any()) } returns commentIds

                val exampleWorkflow =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = annotatedCodeReviewCommentsArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = mockPullRequestArtifact,
                                ),
                            ),
                    )

                subject.performAction(
                    workflowRun = exampleWorkflow,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        pullRequestUrl = testPrUrl,
                        reviewComments =
                            match { comments ->
                                comments.size == 1 &&
                                    comments.first().comment.contains("##### ⚠\uFE0F Maintainability - Cyclic Dependency") &&
                                    comments.first().comment.contains("Original comment text")
                            },
                        isMultiLineCommentsEnabled = any(),
                    )
                }
            }
    }

    @Nested
    inner class GetArtifacts {
        @Test
        fun `gets Autoreview SCM Comments Artifact if it is present`() {
            val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
            every { autoreviewScmCommentsArtifact.pullRequestUrl } returns
                testPrUrl
            val scmComments =
                listOf(
                    exampleComment("There is a bug here"),
                ).map {
                    AutoreviewScmCommentItem(
                        id = it.id,
                        comment = it.comment,
                        codeSuggestion = it.codeSuggestion,
                        path = it.path,
                        line = it.line,
                        rationale = it.rationale,
                        changeType = it.changeType,
                    )
                }
            every { autoreviewScmCommentsArtifact.comments } returns scmComments

            val artifact =
                subject.getAutoreviewScmCommentsArtifact(
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                            ),
                    ),
                )
            artifact.pullRequestUrl shouldBe testPrUrl
            artifact.comments shouldContainExactlyInAnyOrder scmComments
        }

        @Test
        fun `throws exception if there is no Autoreview SCM Comments artifact`() {
            every { workflowRun.artifacts } returns emptyList()

            assertThrows<NullPointerException> { subject.getAutoreviewScmCommentsArtifact(workflowRun) }
        }

        @Test
        fun `gets Annotated Code Review Comments Artifact if it is present`() {
            val annotatedCodeReviewCommentsArtifact = createMockAnnotatedCodeReviewCommentsArtifact()

            val artifact =
                subject.getAnnotatedCodeReviewCommentsArtifact(
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = annotatedCodeReviewCommentsArtifact,
                                ),
                            ),
                    ),
                )
            artifact.comments.size shouldBe 1
            artifact.comments.first().id shouldBe "test-comment-id"
        }

        @Test
        fun `throws exception if there is no Annotated Code Review Comments artifact`() {
            every { workflowRun.artifacts } returns emptyList()

            assertThrows<NullPointerException> { subject.getAnnotatedCodeReviewCommentsArtifact(workflowRun) }
        }
    }

    private fun exampleComment(
        comment: String,
        codeSuggestion: AutoreviewCodeSuggestion? = null,
        path: String? = "a/b/c.txt",
        line: Int? = 90,
    ) = ReviewComment(
        id = UUID.randomUUID().toString(),
        comment = comment,
        codeSuggestion = codeSuggestion,
        path = path,
        line = line,
        changeType = CodeReviewCommentChangeType.ADDED,
    )

    private fun exampleWorkflowRun(
        artifacts: List<WorkflowRunArtifact>,
        repoUrl: String = "https://bitbucket.org/atlassian/devai-services",
    ) = WorkflowRun(
        id = UUID.fromString(workflowRunId),
        issueAri = JiraIssueARI.from("siteid", "10000"),
        repoUrl = URI(repoUrl).toURL(),
        rootWorkflow = RootWorkflowName.AUTOREVIEW_INCREMENTAL_REVIEW,
        currentWorkflow = "currentWorkflow",
        status = WorkflowRunStatus.COMPLETED,
        artifacts = artifacts,
        queueType = WorkflowRunQueueType.DEFAULT,
        createdTimestamp = now(),
        updatedTimestamp = now(),
        workspaceARI = GLOBAL_WORKSPACE_ARI,
        phase = null,
    )

    private fun createMockAnnotatedCodeReviewCommentsArtifact(): AnnotatedCodeReviewCommentsArtifact =
        AnnotatedCodeReviewCommentsArtifact(
            comments =
                listOf(
                    CodeReviewCommentWithAnnotation(
                        id = "test-comment-id",
                        comment = "Test comment",
                        path = "test/path.kt",
                        line = 10,
                        changeType = CodeReviewCommentChangeType.ADDED,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                        annotations =
                            mapOf(
                                CodeReviewCommentAnnotationKey.COMMENT_CATEGORY to
                                    CommentCategoryAnnotation(
                                        AutoreviewCommentCategory.MAINTAINABILITY.value,
                                    ),
                                CodeReviewCommentAnnotationKey.COMMENT_SUB_CATEGORY to
                                    CommentSubCategoryAnnotation(
                                        AutoreviewCommentSubCategory.CYCLIC_DEPENDENCY.value,
                                    ),
                            ),
                    ),
                ),
        )
}
