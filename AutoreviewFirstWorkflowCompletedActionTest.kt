package devai.modules.autoreview.service

import com.atlassian.ari.principled.CloudIdLike
import com.atlassian.ari.principled.devai.DevaiWorkspaceARI
import devai.modules.acra.shared.model.ExecutionFlagNames.AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED
import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.model.WorkflowRun
import devai.modules.acra.shared.model.artifacts.AnnotatedCodeReviewCommentsArtifact
import devai.modules.acra.shared.model.artifacts.Artifact
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames
import devai.modules.acra.shared.model.artifacts.ArtifactType
import devai.modules.acra.shared.model.artifacts.AutoreviewCodeSuggestion
import devai.modules.acra.shared.model.artifacts.AutoreviewCustomInstructionsArtifact
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentItem
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentsArtifact
import devai.modules.acra.shared.model.artifacts.CommentRankerArtifact
import devai.modules.acra.shared.model.artifacts.CommentRankerScore
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
import devai.modules.autoreview.utils.ReviewCommentExtensions.EXPLANATION_PREFIX
import devai.modules.autoreview.utils.ReviewCommentExtensions.RATIONALE_PREFIX
import devai.modules.autoreview.utils.ReviewCommentExtensions.RECOMMENDATION_PREFIX
import devai.modules.pantry.shared.entity.PantryEntry
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.ScmDetails
import devai.modules.shared.model.ScmUrlType
import devai.modules.shared.model.bitbucketScm
import devai.modules.shared.queue.model.WorkflowJobContext
import devai.modules.shared.queue.model.WorkflowUpdatedPayload
import devai.modules.shared.sal.SalSharedUtil
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class AutoreviewFirstWorkflowCompletedActionTest {
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val autoreviewService = mockk<AutoreviewService>(relaxed = true)
    private val autoreviewCommentService = mockk<AutoreviewCommentService>(relaxed = true)
    private val autoreviewWorkflowsStorageService = mockk<AutoreviewWorkflowsStorageService>(relaxed = true)
    private val autoreviewValidationService = mockk<AutoreviewValidationService>(relaxed = true)
    private val salSharedUtil = mockk<SalSharedUtil>(relaxed = true)
    private val autoreviewUtilityService = mockk<AutoreviewUtilityService>(relaxed = true)
    private val mockPayload = mockk<WorkflowUpdatedPayload>(relaxed = true)

    private val mockPullRequestArtifact: PullRequestInfoArtifact =
        mockk<PullRequestInfoArtifact>(relaxed = true) {
            every { pullRequestUrl } returns TEST_PULL_REQUEST_URL
            every { sourceCommit } returns TEST_SOURCE_COMMIT
        }

    private val mockWorkflowRun =
        mockk<WorkflowRun>(relaxed = true) {
            every { id } returns UUID.fromString(TEST_WORKFLOW_RUN_ID)
            every { artifactByNameOrNull<Artifact>(ArtifactFilenames.PULL_REQUEST_INFO_JSON) } returns
                mockPullRequestArtifact
        }

    companion object {
        private const val TEST_WORKFLOW_RUN_ID = "e7d1c16b-0208-4a4c-a19e-547ac140d852"
        private const val TEST_PULL_REQUEST_URL = "https://bitbucket.org/test/pull-requests/1"
        private const val TEST_WORKSPACE_ID = "00000000-0000-0000-0000-000000000000"
        private const val TEST_WORKSPACE_ARI = "ari:cloud:devai::workspace/00000000-0000-0000-0000-000000000000"
        private const val TEST_ACCOUNT_ID = "test-account-id"
        private const val TEST_SOURCE_COMMIT = "commit123"
    }

    private val subject =
        AutoreviewFirstWorkflowCompletedAction(
            featureService = featureService,
            autoreviewService = autoreviewService,
            autoreviewWorkflowsStorageService = autoreviewWorkflowsStorageService,
            autoreviewValidationService = autoreviewValidationService,
            salSharedUtil = salSharedUtil,
            autoreviewUtilityService = autoreviewUtilityService,
            autoreviewCommentService = autoreviewCommentService,
        )

    @BeforeEach
    fun setup() {
        every { mockPayload.context } returns
            mockk<WorkflowJobContext>(relaxed = true) {
                every { workspaceId } returns TEST_WORKSPACE_ID
                every { accountId } returns TEST_ACCOUNT_ID
                every { workspaceAri } returns TEST_WORKSPACE_ARI
            }
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Nested
    inner class ShouldBePerformedOn {
        @Test
        fun `should be performed on first review workflow`() {
            every { mockPayload.workflow } returns RootWorkflowName.AUTOREVIEW_FIRST_REVIEW.value
            subject.shouldBePerformedOn(mockPayload) shouldBe true
        }

        @ParameterizedTest
        @EnumSource(
            RootWorkflowName::class,
            mode = EnumSource.Mode.EXCLUDE,
            names = ["AUTOREVIEW_FIRST_REVIEW"],
        )
        fun `should not be performed on non-first review workflows`(rootWorkflowName: RootWorkflowName) {
            every { mockPayload.workflow } returns rootWorkflowName.value
            subject.shouldBePerformedOn(mockPayload) shouldBe false
        }
    }

    @Nested
    inner class RequiredArtifactTypes {
        @Test
        fun `should contain these required artifacts`() {
            subject.requiredArtifactTypes() shouldBe
                setOf(
                    ArtifactType.AUTOREVIEW_SCM_COMMENTS,
                    ArtifactType.PULL_REQUEST_INFO,
                    ArtifactType.ANNOTATED_CODE_REVIEW_COMMENTS,
                    ArtifactType.EXECUTION_FLAGS,
                    ArtifactType.AUTOREVIEW_CUSTOM_INSTRUCTIONS,
                    ArtifactType.COMMENT_RANKER,
                )
        }
    }

    @Nested
    inner class StorePostedComments {
        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should store posted comments in pantry`(isFeatureGateEnabled: Boolean) =
            runTest {
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns isFeatureGateEnabled

                val commentsArtifact = setupMockCommentsArtifact(numberOfHighImpactComments = 1)

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = if (isFeatureGateEnabled) 1 else 0) {
                    autoreviewWorkflowsStorageService.storeAutoreviewPostedComments(
                        pullRequestUrl = TEST_PULL_REQUEST_URL,
                        postedComments =
                            match<AutoreviewPostedCommentsPantryItem> {
                                it.postedComments.size == 1 &&
                                    it.postedComments.first().id == commentsArtifact.comments.first().id
                            },
                    )
                }
            }

        @Test
        fun `should store nothing in pantry if there's no comments in artifact`() =
            runTest {
                coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns true

                setupMockCommentsArtifact(numberOfHighImpactComments = 0, numberOfLowImpactComments = 0)

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 0) {
                    autoreviewWorkflowsStorageService.storeAutoreviewPostedComments(
                        pullRequestUrl = any(),
                        postedComments = any(),
                    )
                }
            }
    }

    @Nested
    inner class PostHighImpactComments {
        @BeforeEach
        fun setUp() {
            coEvery { featureService.isAutoreviewSalMultiLineCommentsEnabled(any()) } returns false
        }

        @ParameterizedTest
        @ValueSource(ints = [1, 3, 10])
        fun `should post high impact comments to pull request comments`(numberOfHighImpactComments: Int) =
            runTest {
                val scmComments = setupMockCommentsArtifact(numberOfHighImpactComments = numberOfHighImpactComments)
                coEvery {
                    autoreviewCommentService.prepareCommentsForSending(any(), any(), any())
                } returns
                    List(size = numberOfHighImpactComments) {
                        generateReviewComments(
                            id = scmComments.comments[0].id,
                            comment = scmComments.comments[0].comment,
                            codeSuggestion = scmComments.comments[0].codeSuggestion,
                            path = scmComments.comments[0].path,
                            line = scmComments.comments[0].line,
                            explanation = scmComments.comments[0].explanation,
                            solution = scmComments.comments[0].solution,
                            rationale = scmComments.comments[0].rationale,
                        )
                    }

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq(TEST_PULL_REQUEST_URL),
                        match {
                            it.size == numberOfHighImpactComments
                        },
                        any(),
                    )
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should post multiline comments depending on multiline FG`(featureGateValue: Boolean) =
            runTest {
                coEvery { featureService.isAutoreviewSalMultiLineCommentsEnabled(any()) } returns featureGateValue
                coEvery {
                    autoreviewCommentService.prepareCommentsForSending(any(), any(), any())
                } returns
                    listOf(
                        generateReviewComments(UUID.randomUUID().toString(), "example comment 1"),
                        generateReviewComments(UUID.randomUUID().toString(), "example comment 2"),
                    )
                setupMockCommentsArtifact(numberOfHighImpactComments = 2)

                every { mockWorkflowRun.executionFlags } returns
                    mapOf(
                        AUTOREVIEW_SAL_MULTI_LINE_COMMENTS_ENABLED to featureGateValue.toString(),
                    )

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq(TEST_PULL_REQUEST_URL),
                        match {
                            it.size == 2
                        },
                        eq(featureGateValue),
                    )
                }
            }

        @Test
        fun `should not post pull request comments when there are no high impact comments`() =
            runTest {
                setupMockCommentsArtifact(numberOfHighImpactComments = 0, numberOfLowImpactComments = 5)

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any(), any()) }
            }

        @Test
        fun `should prepend category info to comments`() =
            runTest {
                val scmComment = setupMockCommentsArtifact(numberOfHighImpactComments = 1)
                val expectedComment =
                    "##### ⚠\uFE0F Maintainability - Cyclic Dependency \n\n${scmComment.comments[0].comment}"
                coEvery {
                    autoreviewCommentService.prepareCommentsForSending(any(), any(), any())
                } returns
                    listOf(
                        generateReviewComments(
                            id = scmComment.comments[0].id,
                            comment = expectedComment,
                            codeSuggestion = scmComment.comments[0].codeSuggestion,
                            path = scmComment.comments[0].path,
                            line = scmComment.comments[0].line,
                            explanation = scmComment.comments[0].explanation,
                            solution = scmComment.comments[0].solution,
                            rationale = scmComment.comments[0].rationale,
                        ),
                    )

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq(TEST_PULL_REQUEST_URL),
                        match { reviewComments ->
                            reviewComments.size == 1 &&
                                reviewComments.first().comment.startsWith("##### ⚠\uFE0F Maintainability - Cyclic Dependency") &&
                                reviewComments.first().comment.contains("Comment 0")
                        },
                        any(),
                    )
                }
            }

        @Test
        fun `should append details to comments generated by custom review generator`() =
            runTest {
                // Arrange
                val scmComment =
                    setupMockCommentsArtifact(
                        numberOfHighImpactComments = 1,
                        hasSolution = true,
                        hasExplanation = true,
                        generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                    )
                val expectedComment =
                    "Comment 0\n```expand\nDetails\n\n📖 Explanation: Explanation for comment 0\n\n💡 " +
                        "Recommendation: Solution for comment 0\n\n🤔 Rationale: Rationale\n```\n"

                coEvery {
                    autoreviewCommentService.prepareCommentsForSending(any(), any(), any())
                } returns
                    listOf(
                        generateReviewComments(
                            id = scmComment.comments[0].id,
                            comment = expectedComment,
                            codeSuggestion = scmComment.comments[0].codeSuggestion,
                            path = scmComment.comments[0].path,
                            line = scmComment.comments[0].line,
                            explanation = scmComment.comments[0].explanation,
                            solution = scmComment.comments[0].solution,
                            rationale = scmComment.comments[0].rationale,
                        ),
                    )

                // Act
                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                // Assert
                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq(TEST_PULL_REQUEST_URL),
                        match { reviewComments ->
                            val comment = reviewComments.first().comment
                            comment.contains("```expand") &&
                                comment.contains(EXPLANATION_PREFIX) &&
                                comment.contains(RECOMMENDATION_PREFIX) &&
                                comment.contains(RATIONALE_PREFIX)
                        },
                        any(),
                    )
                }
            }

        @Test
        fun `should append explanation details to comments`() =
            runTest {
                // Arrange
                val scmComment =
                    setupMockCommentsArtifact(
                        numberOfHighImpactComments = 1,
                        hasSolution = true,
                        hasExplanation = true,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    )

                coEvery {
                    autoreviewCommentService.prepareCommentsForSending(any(), any(), any())
                } returns
                    listOf(
                        generateReviewComments(
                            id = scmComment.comments[0].id,
                            comment = "Comment 0\n```expand\nDetails\n\n📖 Explanation: Explanation for comment 0\n```\n",
                            codeSuggestion = scmComment.comments[0].codeSuggestion,
                            path = scmComment.comments[0].path,
                            line = scmComment.comments[0].line,
                            explanation = scmComment.comments[0].explanation,
                            solution = scmComment.comments[0].solution,
                            rationale = scmComment.comments[0].rationale,
                        ),
                    )

                // Act
                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                // Assert
                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq(TEST_PULL_REQUEST_URL),
                        match { reviewComments ->
                            val comment = reviewComments.first().comment
                            comment.contains("```expand") &&
                                comment.contains(EXPLANATION_PREFIX)
                        },
                        any(),
                    )
                }
            }
    }

    @Nested
    inner class PostSummaryComments {
        @BeforeEach
        fun setUp() {
            coEvery { featureService.isEnableAutoreviewPRSummaryFeature(any(), any(), any()) } returns true
        }

        @ParameterizedTest
        @CsvSource(
            value = [
                "0,1",
                "1,0",
                "1,1",
            ],
        )
        fun `should post summary comments when there are either low or high impact comments`(
            numberOfHighImpactComments: Int,
            numberOfLowImpactComments: Int,
        ) = runTest {
            setupMockCommentsArtifact(
                numberOfHighImpactComments = numberOfHighImpactComments,
                numberOfLowImpactComments = numberOfLowImpactComments,
            )

            subject.performAction(
                workflowRun = mockWorkflowRun,
                eventPayload = mockPayload,
            )

            coVerify(exactly = 1) {
                autoreviewCommentService.sendPullRequestSummaryComment(
                    TEST_PULL_REQUEST_URL,
                    match { it.size == numberOfHighImpactComments },
                    match {
                        it.size ==
                            numberOfLowImpactComments
                    },
                )
            }
        }

        @Test
        fun `should not post summary comments when there are no comments`() =
            runTest {
                setupMockCommentsArtifact(
                    numberOfHighImpactComments = 0,
                    numberOfLowImpactComments = 0,
                )

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 0) { autoreviewCommentService.sendPullRequestSummaryComment(any(), any(), any()) }
            }

        @Test
        fun `should not post summary comment when summary comment FG is not enabled`() =
            runTest {
                coEvery { featureService.isEnableAutoreviewPRSummaryFeature(any(), any(), any()) } returns false

                setupMockCommentsArtifact(
                    numberOfHighImpactComments = 1,
                    numberOfLowImpactComments = 1,
                )

                subject.performAction(
                    workflowRun = mockWorkflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 0) { autoreviewCommentService.sendPullRequestSummaryComment(any(), any(), any()) }
            }
    }

    @Nested
    inner class SendAnalyticEvent {
        @BeforeEach
        fun setUp() {
            setupMockCommentsArtifact(numberOfHighImpactComments = 2, numberOfLowImpactComments = 1)

            coEvery { autoreviewCommentService.postPullRequestComments(any(), any(), any()) } returns
                listOf(
                    AutoreviewPullRequestComment("test-id-0", "commentId1"),
                    AutoreviewPullRequestComment("test-id-1", "commentId2"),
                )
            coEvery { autoreviewCommentService.sendPullRequestSummaryComment(any(), any(), any()) } returns
                listOf(AutoreviewPullRequestComment("test-id-3", "pr-summary-commentId1"))
        }

        @Test
        fun `should send comment analytics event with PR summary ids`() =
            runTest {
                coEvery { featureService.isEnableAutoreviewPRSummaryFeature(any(), any(), any()) } returns true

                subject.performAction(
                    mockWorkflowRun,
                    mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.sendPullRequestSummaryComment(any(), any(), any())
                }

                coVerify(exactly = 1) {
                    autoreviewService.sendAnalyticEventWorkflowCompleted(
                        eq(TEST_WORKFLOW_RUN_ID),
                        eq(TEST_WORKSPACE_ARI),
                        match { it.size == 2 },
                        match { it[0] == "pr-summary-commentId1" },
                    )
                }
            }

        @Test
        fun `should send comment ranker analytics event`() =
            runTest {
                coEvery { featureService.isEnableAutoreviewPRSummaryFeature(any(), any(), any()) } returns true

                subject.performAction(
                    mockWorkflowRun,
                    mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.sendPullRequestSummaryComment(any(), any(), any())
                }

                coVerify(exactly = 1) {
                    autoreviewService.sendCommentRankerAnalyticEvent(
                        eq(TEST_WORKFLOW_RUN_ID),
                        eq(TEST_WORKSPACE_ARI),
                        match { it.size == 2 },
                    )
                }
            }
    }

    @Nested
    inner class GetArtifacts {
        @Test
        fun `gets Autoreview SCM Comments Artifact if it is present`() {
            val mockArtifact =
                setupMockCommentsArtifact(
                    numberOfLowImpactComments = 1,
                    numberOfHighImpactComments = 1,
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/709",
                )

            val artifact =
                subject.getAutoreviewScmCommentsArtifact(mockWorkflowRun)

            artifact.pullRequestUrl shouldBe "https://bitbucket.org/atlassian/devai-services/pull-requests/709"
            artifact.comments shouldContainExactlyInAnyOrder mockArtifact.comments
        }

        @Test
        fun `throws exception if there is no Autoreview SCM Comments artifact`() {
            every { mockWorkflowRun.artifactByNameOrNull<Artifact>(any()) } returns null

            assertThrows<NullPointerException> { subject.getAutoreviewScmCommentsArtifact(mockWorkflowRun) }
        }
    }

    private fun generateComments(
        size: Int,
        hasExplanation: Boolean = false,
        hasSolution: Boolean = false,
        generatedBy: CodeReviewCommentGeneratedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
    ): List<AutoreviewScmCommentItem> =
        List(size) { index ->
            AutoreviewScmCommentItem(
                id = UUID.randomUUID().toString(),
                comment = "Comment $index",
                codeSuggestion = null,
                path = "a/b/c.text",
                line = 10,
                explanation = if (hasExplanation) "Explanation for comment $index" else null,
                solution = if (hasSolution) "Solution for comment $index" else null,
                rationale = "Rationale",
                changeType = CodeReviewCommentChangeType.ADDED,
                generatedBy = generatedBy,
            )
        }

    private fun setupMockCommentsArtifact(
        numberOfHighImpactComments: Int = 0,
        numberOfLowImpactComments: Int = 0,
        pullRequestUrl: String = TEST_PULL_REQUEST_URL,
        hasExplanation: Boolean = false,
        hasSolution: Boolean = false,
        generatedBy: CodeReviewCommentGeneratedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
    ): AutoreviewScmCommentsArtifact {
        val mockCommentsArtifact =
            AutoreviewScmCommentsArtifact(
                comments =
                    generateComments(
                        size = numberOfHighImpactComments,
                        hasExplanation = hasExplanation,
                        hasSolution = hasSolution,
                        generatedBy = generatedBy,
                    ),
                lowImpactComments = generateComments(size = numberOfLowImpactComments),
                pullRequestUrl = pullRequestUrl,
            )
        val mockAnnotationsArtifact = setupMockAnnotatedCommentsArtifact(mockCommentsArtifact)
        val mockCommentRankerArtifact = setupCommentRankerArtifact(mockCommentsArtifact.comments.map { it.id })

        every {
            mockWorkflowRun.artifactByNameOrNull<Artifact>(
                ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
            )
        } returns
            mockCommentsArtifact
        every {
            mockWorkflowRun.artifactByNameOrNull<Artifact>(
                ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
            )
        } returns mockAnnotationsArtifact
        every {
            mockWorkflowRun.artifactByNameOrNull<Artifact>(
                ArtifactFilenames.AUTOREVIEW_CUSTOM_INSTRUCTIONS_JSON,
            )
        } returns
            AutoreviewCustomInstructionsArtifact(
                instructions = emptyList(),
            )
        every {
            mockWorkflowRun.artifactByNameOrNull<Artifact>(
                ArtifactFilenames.COMMENT_RANKER_JSON,
            )
        } returns mockCommentRankerArtifact

        return mockCommentsArtifact
    }

    private fun setupMockAnnotatedCommentsArtifact(
        mockCommentsArtifact: AutoreviewScmCommentsArtifact,
    ): AnnotatedCodeReviewCommentsArtifact =
        AnnotatedCodeReviewCommentsArtifact(
            comments =
                mockCommentsArtifact.comments.map {
                    CodeReviewCommentWithAnnotation(
                        id = it.id,
                        comment = it.comment,
                        path = it.path,
                        line = it.line,
                        changeType = it.changeType,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                        annotations =
                            mapOf(
                                CodeReviewCommentAnnotationKey.COMMENT_CATEGORY to
                                    CommentCategoryAnnotation(
                                        AutoreviewCommentCategory.MAINTAINABILITY.value,
                                    ),
                                CodeReviewCommentAnnotationKey.COMMENT_SUB_CATEGORY to
                                    CommentSubCategoryAnnotation(
                                        AutoreviewCommentSubCategory.MAINTAINABILITY_SUBCATEGORIES[0].value,
                                    ),
                            ),
                    )
                },
        )

    private fun setupCommentRankerArtifact(commentIds: List<String>): CommentRankerArtifact {
        val scores =
            commentIds.map { id ->
                CommentRankerScore(
                    id = id,
                    comment = "This is a comment",
                    commentRankerScore = 0.9,
                    threshold = 0.3,
                    isScoreGreaterThanThreshold = true,
                    modelVersion = "1.0.0",
                )
            }
        return CommentRankerArtifact(scores = scores)
    }

    private fun generateReviewComments(
        id: String,
        comment: String,
        codeSuggestion: AutoreviewCodeSuggestion? = null,
        path: String? = "a/b/c.txt",
        line: Int? = 90,
        explanation: String? = null,
        solution: String? = null,
        rationale: String? = null,
        changeType: CodeReviewCommentChangeType = CodeReviewCommentChangeType.ADDED,
        generatedBy: CodeReviewCommentGeneratedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
    ) = ReviewComment(
        id = id,
        comment = comment,
        codeSuggestion = codeSuggestion,
        path = path,
        line = line,
        explanation = explanation,
        solution = solution,
        rationale = rationale,
        changeType = changeType,
        generatedBy = generatedBy,
    )

    @Nested
    inner class StoreProcessStatus {
        private val testBillingCloudId = CloudIdLike.fromString("12e73ff5-8b07-4b31-be8e-67ee0666fc43")
        private val testDevaiWorkspaceAri = mockk<DevaiWorkspaceARI>()
        private val testBbcWorkspaceUUID = "bbc-workspace-uuid"

        private val mockPullRequestArtifactForProcessStatus: PullRequestInfoArtifact =
            mockk<PullRequestInfoArtifact>(relaxed = true) {
                every { pullRequestUrl } returns TEST_PULL_REQUEST_URL
                every { sourceCommit } returns TEST_SOURCE_COMMIT
                every { authorAccountId } returns TEST_ACCOUNT_ID
                every { workspaceId } returns testBbcWorkspaceUUID
            }

        private val mockWorkflowRunForProcessStatus =
            mockk<WorkflowRun>(relaxed = true) {
                every { id } returns UUID.fromString(TEST_WORKFLOW_RUN_ID)
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.PULL_REQUEST_INFO_JSON) } returns
                    mockPullRequestArtifactForProcessStatus
                // Setup all other required artifacts to avoid issues
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON) } returns
                    mockk<AutoreviewScmCommentsArtifact>(relaxed = true) {
                        every { comments } returns emptyList()
                        every { lowImpactComments } returns emptyList()
                        every { pullRequestUrl } returns TEST_PULL_REQUEST_URL
                    }
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS) } returns
                    mockk<AnnotatedCodeReviewCommentsArtifact>(relaxed = true) {
                        every { comments } returns emptyList()
                    }
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.AUTOREVIEW_CUSTOM_INSTRUCTIONS_JSON) } returns
                    mockk<AutoreviewCustomInstructionsArtifact>(relaxed = true) {
                        every { instructions } returns emptyList()
                    }
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.COMMENT_RANKER_JSON) } returns
                    mockk<CommentRankerArtifact>(relaxed = true) {
                        every { scores } returns emptyList()
                    }
                every { executionFlags } returns emptyMap()
                every { cloudId } returns testBillingCloudId.toString()
            }

        @BeforeEach
        fun setupProcessStatusTests() {
            // Setup common mocks for process status tests
            coEvery {
                autoreviewUtilityService.getDevAiWorkspaceForCodeReviewProcess(
                    billingCloudId = testBillingCloudId,
                )
            } returns testDevaiWorkspaceAri
        }

        @Test
        fun `should store process status when feature is enabled and SCM is Bitbucket`() =
            runTest {
                // Arrange
                every { salSharedUtil.determineMatchingScm(TEST_PULL_REQUEST_URL, ScmUrlType.PR) } returns bitbucketScm
                coEvery {
                    autoreviewWorkflowsStorageService.storePullRequestStatus(
                        pullRequestUrl = TEST_PULL_REQUEST_URL,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = AutoreviewProcessStatus.UNKNOWN,
                        devaiWorkspaceAri = testDevaiWorkspaceAri,
                        scmSourceCommit = TEST_SOURCE_COMMIT,
                    )
                } returns mockk<PantryEntry>()

                // Act
                subject.performAction(mockWorkflowRunForProcessStatus, mockPayload)

                // Assert
                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storePullRequestStatus(
                        pullRequestUrl = TEST_PULL_REQUEST_URL,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = AutoreviewProcessStatus.UNKNOWN,
                        devaiWorkspaceAri = testDevaiWorkspaceAri,
                        scmSourceCommit = TEST_SOURCE_COMMIT,
                    )
                }
            }

        @Test
        fun `should store process status when SCM is Github`() =
            runTest {
                // Arrange
                val nonBitbucketScm =
                    mockk<ScmDetails> {
                        every { name } returns "github"
                    }
                every {
                    salSharedUtil.determineMatchingScm(
                        TEST_PULL_REQUEST_URL,
                        ScmUrlType.PR,
                    )
                } returns nonBitbucketScm

                // Act
                subject.performAction(mockWorkflowRunForProcessStatus, mockPayload)

                // Assert
                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storePullRequestStatus(any(), any(), any(), any(), any())
                }
            }

        @Test
        fun `should handle null source commit when storing process status`() =
            runTest {
                // Arrange
                val mockPullRequestArtifactNullCommit =
                    mockk<PullRequestInfoArtifact>(relaxed = true) {
                        every { pullRequestUrl } returns TEST_PULL_REQUEST_URL
                        every { sourceCommit } returns null
                        every { authorAccountId } returns TEST_ACCOUNT_ID
                        every { workspaceId } returns testBbcWorkspaceUUID
                    }
                val mockWorkflowRunNullCommit =
                    mockk<WorkflowRun>(relaxed = true) {
                        every { id } returns UUID.fromString(TEST_WORKFLOW_RUN_ID)
                        every { artifactByNameOrNull<Artifact>(ArtifactFilenames.PULL_REQUEST_INFO_JSON) } returns
                            mockPullRequestArtifactNullCommit
                        // Setup all other required artifacts to avoid issues
                        every { artifactByNameOrNull<Artifact>(ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON) } returns
                            mockk<AutoreviewScmCommentsArtifact>(relaxed = true) {
                                every { comments } returns emptyList()
                                every { lowImpactComments } returns emptyList()
                                every { pullRequestUrl } returns TEST_PULL_REQUEST_URL
                            }
                        every { artifactByNameOrNull<Artifact>(ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS) } returns
                            mockk<AnnotatedCodeReviewCommentsArtifact>(relaxed = true) {
                                every { comments } returns emptyList()
                            }
                        every { artifactByNameOrNull<Artifact>(ArtifactFilenames.AUTOREVIEW_CUSTOM_INSTRUCTIONS_JSON) } returns
                            mockk<AutoreviewCustomInstructionsArtifact>(relaxed = true) {
                                every { instructions } returns emptyList()
                            }
                        every { artifactByNameOrNull<Artifact>(ArtifactFilenames.COMMENT_RANKER_JSON) } returns
                            mockk<CommentRankerArtifact>(relaxed = true) {
                                every { scores } returns emptyList()
                            }
                        every { executionFlags } returns emptyMap()
                        every { cloudId } returns testBillingCloudId.toString()
                    }

                every { salSharedUtil.determineMatchingScm(TEST_PULL_REQUEST_URL, ScmUrlType.PR) } returns bitbucketScm
                coEvery {
                    autoreviewWorkflowsStorageService.storePullRequestStatus(
                        pullRequestUrl = TEST_PULL_REQUEST_URL,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = AutoreviewProcessStatus.UNKNOWN,
                        devaiWorkspaceAri = testDevaiWorkspaceAri,
                        scmSourceCommit = "",
                    )
                } returns mockk<PantryEntry>()

                // Act
                subject.performAction(mockWorkflowRunNullCommit, mockPayload)

                // Assert
                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storePullRequestStatus(
                        pullRequestUrl = TEST_PULL_REQUEST_URL,
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        acceptanceCriteriaStatus = AutoreviewProcessStatus.UNKNOWN,
                        devaiWorkspaceAri = testDevaiWorkspaceAri,
                        scmSourceCommit = "",
                    )
                }
            }

        @Test
        fun `should verify utility service calls are made when storing process status`() =
            runTest {
                // Arrange
                every { salSharedUtil.determineMatchingScm(TEST_PULL_REQUEST_URL, ScmUrlType.PR) } returns bitbucketScm
                coEvery {
                    autoreviewWorkflowsStorageService.storePullRequestStatus(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    mockk<PantryEntry>()

                // Act
                subject.performAction(mockWorkflowRunForProcessStatus, mockPayload)

                // Assert
                coVerify(exactly = 1) {
                    autoreviewUtilityService.getDevAiWorkspaceForCodeReviewProcess(
                        billingCloudId = testBillingCloudId,
                    )
                }
            }
    }

    @Nested
    inner class UpdateWorkflowStateForGitHub {
        private val testGitHubPrUrl = "https://github.com/test-owner/test-repo/pull/123"

        @BeforeEach
        fun setup() {
            clearAllMocks()

            // Setup mockPayload with required fields
            every { mockPayload.context.accountId } returns TEST_ACCOUNT_ID
            every { mockPayload.context.workspaceId } returns TEST_WORKSPACE_ID
            every { mockPayload.context.workspaceAri } returns TEST_WORKSPACE_ARI
            every { mockPayload.context.traceId } returns "test-trace-id"

            coEvery { autoreviewValidationService.isPullRequestForLoadTest(any()) } returns false
            coEvery { featureService.isAutoreviewGenerationRetriggerEnabled() } returns false
            coEvery { featureService.isEnableAutoreviewPRSummaryFeature(any(), any(), any()) } returns false
            coEvery { autoreviewUtilityService.updateWorkflowStateForGitHub(any(), any()) } returns Unit
        }

        @Test
        fun `should call updateWorkflowStateForGitHub with WORKFLOW_COMPLETED status`() =
            runTest {
                // Given
                val workflowRun = createWorkflowRunWithPrUrl(testGitHubPrUrl)

                // When
                subject.performAction(workflowRun, mockPayload)

                // Then
                coVerify(exactly = 1) {
                    autoreviewUtilityService.updateWorkflowStateForGitHub(
                        workflowRun = workflowRun,
                        processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    )
                }
            }

        @Test
        fun `should call updateWorkflowStateForGitHub for GitHub PRs`() =
            runTest {
                // Given
                val workflowRun = createWorkflowRunWithPrUrl(testGitHubPrUrl)

                // When
                subject.performAction(workflowRun, mockPayload)

                // Then
                coVerify(exactly = 1) {
                    autoreviewUtilityService.updateWorkflowStateForGitHub(
                        workflowRun = any(),
                        processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    )
                }
            }

        @Test
        fun `should call updateWorkflowStateForGitHub even when no high impact comments are posted`() =
            runTest {
                // Given
                val workflowRun = createWorkflowRunWithNoComments(testGitHubPrUrl)

                // When
                subject.performAction(workflowRun, mockPayload)

                // Then
                coVerify(exactly = 1) {
                    autoreviewUtilityService.updateWorkflowStateForGitHub(
                        workflowRun = any(),
                        processStatus = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    )
                }
            }

        @Test
        fun `should call updateWorkflowStateForGitHub after all other actions complete`() =
            runTest {
                // Given
                val workflowRun = createWorkflowRunWithPrUrl(testGitHubPrUrl)

                // When
                subject.performAction(workflowRun, mockPayload)

                // Then
                coVerifyOrder {
                    autoreviewService.sendAnalyticEventWorkflowCompleted(any(), any(), any(), any())
                    autoreviewUtilityService.updateWorkflowStateForGitHub(any(), any())
                }
            }

        @Test
        fun `should propagate the exceptions from updateWorkflowStateForGitHub`() =
            runTest {
                // Given
                val workflowRun = createWorkflowRunWithPrUrl(testGitHubPrUrl)
                coEvery { autoreviewUtilityService.updateWorkflowStateForGitHub(any(), any()) } throws
                    RuntimeException("Test exception")

                // When & Then
                assertThrows<RuntimeException> {
                    subject.performAction(workflowRun, mockPayload)
                }

                // Verify the call was still attempted
                coVerify(exactly = 1) {
                    autoreviewUtilityService.updateWorkflowStateForGitHub(any(), any())
                }
            }

        private fun createWorkflowRunWithPrUrl(prUrl: String): WorkflowRun {
            val pullRequestArtifact =
                mockk<PullRequestInfoArtifact>(relaxed = true) {
                    every { pullRequestUrl } returns prUrl
                    every { sourceCommit } returns TEST_SOURCE_COMMIT
                    every { workspaceId } returns TEST_WORKSPACE_ID
                    every { authorAccountId } returns TEST_ACCOUNT_ID
                }

            val scmCommentsArtifact =
                AutoreviewScmCommentsArtifact(
                    pullRequestUrl = prUrl,
                    comments =
                        listOf(
                            AutoreviewScmCommentItem(
                                id = "1",
                                comment = "Test comment",
                                codeSuggestion = null,
                                path = "test.kt",
                                line = 10,
                                explanation = "Test explanation",
                                solution = "Test solution",
                                rationale = "Test rationale",
                            ),
                        ),
                    lowImpactComments = emptyList(),
                )

            val annotatedCommentsArtifact =
                AnnotatedCodeReviewCommentsArtifact(
                    comments =
                        listOf(
                            CodeReviewCommentWithAnnotation(
                                id = "1",
                                comment = "Test comment",
                                path = "test.kt",
                                line = 10,
                                explanation = "Test explanation",
                                solution = "Test solution",
                                rationale = "Test rationale",
                                changeType = CodeReviewCommentChangeType.UNKNOWN,
                                generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                                annotations = emptyMap(),
                            ),
                        ),
                )

            val commentRankerArtifact = CommentRankerArtifact(emptyList())

            return mockk<WorkflowRun>(relaxed = true) {
                every { id } returns UUID.fromString(TEST_WORKFLOW_RUN_ID)
                every { cloudId } returns "1166191d-3f28-475c-a121-7edd297f6b9e"
                every { accountId } returns TEST_ACCOUNT_ID
                every { traceId } returns "test-trace-id"
                every { executionFlags } returns emptyMap()
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.PULL_REQUEST_INFO_JSON) } returns
                    pullRequestArtifact
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON) } returns
                    scmCommentsArtifact
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS) } returns
                    annotatedCommentsArtifact
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.COMMENT_RANKER_JSON) } returns
                    commentRankerArtifact
            }
        }

        private fun createWorkflowRunWithNoComments(prUrl: String): WorkflowRun {
            val pullRequestArtifact =
                mockk<PullRequestInfoArtifact>(relaxed = true) {
                    every { pullRequestUrl } returns prUrl
                    every { sourceCommit } returns TEST_SOURCE_COMMIT
                    every { workspaceId } returns TEST_WORKSPACE_ID
                    every { authorAccountId } returns TEST_ACCOUNT_ID
                }

            val scmCommentsArtifact =
                AutoreviewScmCommentsArtifact(
                    pullRequestUrl = prUrl,
                    comments = emptyList(),
                    lowImpactComments = emptyList(),
                )

            val annotatedCommentsArtifact = AnnotatedCodeReviewCommentsArtifact(comments = emptyList())
            val commentRankerArtifact = CommentRankerArtifact(emptyList())

            return mockk<WorkflowRun>(relaxed = true) {
                every { id } returns UUID.fromString(TEST_WORKFLOW_RUN_ID)
                every { cloudId } returns "1166191d-3f28-475c-a121-7edd297f6b9e"
                every { accountId } returns TEST_ACCOUNT_ID
                every { traceId } returns "test-trace-id"
                every { executionFlags } returns emptyMap()
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.PULL_REQUEST_INFO_JSON) } returns
                    pullRequestArtifact
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON) } returns
                    scmCommentsArtifact
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS) } returns
                    annotatedCommentsArtifact
                every { artifactByNameOrNull<Artifact>(ArtifactFilenames.COMMENT_RANKER_JSON) } returns
                    commentRankerArtifact
            }
        }
    }
}
