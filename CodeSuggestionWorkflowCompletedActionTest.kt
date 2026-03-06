package devai.modules.autoreview.service
import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.model.WorkflowRunArtifact
import devai.modules.acra.shared.model.WorkflowRunStatus
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames
import devai.modules.acra.shared.model.artifacts.ArtifactType
import devai.modules.acra.shared.model.artifacts.AutoreviewCodeSuggestion
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentItem
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentsArtifact
import devai.modules.acra.shared.model.artifacts.PullRequestInfoArtifact
import devai.modules.autoreview.model.AutoreviewPullRequestComment
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.queue.model.WorkflowUpdatedPayload
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mockWorkflowRun
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CodeSuggestionWorkflowCompletedActionTest {
    private val autoreviewService = mockk<AutoreviewService>(relaxed = true)
    private val autoreviewCommentService = mockk<AutoreviewCommentService>(relaxed = true)
    private val mockPayload = mockk<WorkflowUpdatedPayload>(relaxed = true)
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val subject =
        CodeSuggestionWorkflowCompletedAction(
            autoreviewService = autoreviewService,
            featureService = featureService,
            autoreviewCommentService = autoreviewCommentService,
        )

    @Test
    fun `shouldBePerformedOn should return true when workflow type is AUTOREVIEW_CODE_SUGGESTION`() {
        every { mockPayload.workflow } returns RootWorkflowName.AUTOREVIEW_CODE_SUGGESTION.value
        subject.shouldBePerformedOn(mockPayload) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(RootWorkflowName::class, mode = EnumSource.Mode.EXCLUDE, names = ["AUTOREVIEW_CODE_SUGGESTION"])
    fun `shouldBePerformedOn should return false on workflow type other than AUTOREVIEW_CODE_SUGGESTION`(
        rootWorkflowName: RootWorkflowName,
    ) {
        every { mockPayload.workflow } returns rootWorkflowName.value
        subject.shouldBePerformedOn(mockPayload) shouldBe false
    }

    @Test
    fun `should contain these required artifacts`() {
        subject.requiredArtifactTypes() shouldBe
            setOf(
                ArtifactType.AUTOREVIEW_SCM_COMMENTS,
                ArtifactType.PULL_REQUEST_INFO,
                ArtifactType.EXECUTION_FLAGS,
            )
    }

    @Test
    fun `performAction should trigger postReviewComments when there is comment with code suggestion`() {
        runTest {
            val workflowRunId = UUID.randomUUID()

            subject.performAction(
                mockWorkflowRun(
                    rootWorkflow = RootWorkflowName.AUTOREVIEW_CODE_SUGGESTION,
                    workflowRunStatus = WorkflowRunStatus.COMPLETED,
                    artifacts =
                        listOf(
                            WorkflowRunArtifact(
                                id = workflowRunId,
                                name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                data =
                                    PullRequestInfoArtifact(
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                        prTitle = "this is PR title",
                                        prDescription = "this is PR description",
                                    ),
                            ),
                            WorkflowRunArtifact(
                                id = workflowRunId,
                                name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                data =
                                    AutoreviewScmCommentsArtifact(
                                        comments =
                                            listOf(
                                                AutoreviewScmCommentItem(
                                                    id = UUID.randomUUID().toString(),
                                                    comment = "this is a comment",
                                                    codeSuggestion =
                                                        AutoreviewCodeSuggestion(
                                                            code =
                                                                "```suggestion\n" +
                                                                    "this is a code\n" +
                                                                    "```",
                                                            startLine = 2,
                                                            endLine = 2,
                                                        ),
                                                    path = "badcode.kt",
                                                    line = 2,
                                                    rationale = null,
                                                ),
                                            ),
                                        lowImpactComments = emptyList(),
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                    ),
                            ),
                        ),
                ),
                mockPayload,
            )
            coVerify(exactly = 1) {
                autoreviewCommentService.postPullRequestComments(
                    eq("https://bitbucket.org/atlassian/partner-central/pull-requests/12433"),
                    match {
                        it.map { reviewComment -> reviewComment.comment } shouldContainExactlyInAnyOrder
                            listOf(
                                "This code suggestion may be helpful to address your comment:\n```suggestion\nthis is a code\n```",
                            )
                        true
                    },
                )
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `performAction triggers postReviewComments when there's a code suggestion, and includes rationale when FF true and not when false`(
        isRationaleEnabled: Boolean,
    ) {
        runTest {
            // Arrange
            coEvery { featureService.isAutoreviewCodeSuggestionRationaleEnabled(any()) } returns isRationaleEnabled
            val workflowRunId = UUID.randomUUID()

            // Act
            subject.performAction(
                mockWorkflowRun(
                    rootWorkflow = RootWorkflowName.AUTOREVIEW_CODE_SUGGESTION,
                    workflowRunStatus = WorkflowRunStatus.COMPLETED,
                    artifacts =
                        listOf(
                            WorkflowRunArtifact(
                                id = workflowRunId,
                                name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                data =
                                    PullRequestInfoArtifact(
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                        prTitle = "this is PR title",
                                        prDescription = "this is PR description",
                                    ),
                            ),
                            WorkflowRunArtifact(
                                id = workflowRunId,
                                name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                data =
                                    AutoreviewScmCommentsArtifact(
                                        comments =
                                            listOf(
                                                AutoreviewScmCommentItem(
                                                    id = UUID.randomUUID().toString(),
                                                    comment = "this is a comment",
                                                    codeSuggestion =
                                                        AutoreviewCodeSuggestion(
                                                            code =
                                                                "```suggestion\n" +
                                                                    "This is code\n" +
                                                                    "```",
                                                            rationale = "This is a rationale",
                                                            startLine = 2,
                                                            endLine = 2,
                                                        ),
                                                    path = "badcode.kt",
                                                    line = 2,
                                                    rationale = null,
                                                ),
                                            ),
                                        lowImpactComments = emptyList(),
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                    ),
                            ),
                        ),
                ),
                mockPayload,
            )

            coVerify(exactly = 1) {
                autoreviewCommentService.postPullRequestComments(
                    eq("https://bitbucket.org/atlassian/partner-central/pull-requests/12433"),
                    match { comments ->
                        comments.size shouldBe 1
                        comments[0].comment shouldBe
                            if (isRationaleEnabled) {
                                """
                                This code suggestion may be helpful to address your comment:
                                ```suggestion
                                This is code
                                ```
                                This is a rationale
                                """.trimIndent()
                            } else {
                                """
                                This code suggestion may be helpful to address your comment:
                                ```suggestion
                                This is code
                                ```
                                """.trimIndent()
                            }
                        true
                    },
                )
            }
        }
    }

    @Test
    fun `performAction should not trigger postReviewComments when there is no comment with code suggestion`() {
        runTest {
            val workflowRunId = UUID.randomUUID()

            subject.performAction(
                mockWorkflowRun(
                    rootWorkflow = RootWorkflowName.AUTOREVIEW_CODE_SUGGESTION,
                    workflowRunStatus = WorkflowRunStatus.COMPLETED,
                    artifacts =
                        listOf(
                            WorkflowRunArtifact(
                                id = workflowRunId,
                                name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                data =
                                    PullRequestInfoArtifact(
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                        prTitle = "this is PR title",
                                        prDescription = "this is PR description",
                                    ),
                            ),
                            WorkflowRunArtifact(
                                id = workflowRunId,
                                name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                data =
                                    AutoreviewScmCommentsArtifact(
                                        comments =
                                            listOf(
                                                AutoreviewScmCommentItem(
                                                    id = UUID.randomUUID().toString(),
                                                    comment = "this is a comment",
                                                    path = "badcode.kt",
                                                    line = 2,
                                                    rationale = null,
                                                ),
                                            ),
                                        lowImpactComments = emptyList(),
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                    ),
                            ),
                        ),
                ),
                mockPayload,
            )

            coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
        }
    }

    @Test
    fun `performAction should send workflow completed analytics events`() {
        runTest {
            val workflowRun =
                mockWorkflowRun(
                    rootWorkflow = RootWorkflowName.AUTOREVIEW_CODE_SUGGESTION,
                    workflowRunStatus = WorkflowRunStatus.COMPLETED,
                    artifacts =
                        listOf(
                            WorkflowRunArtifact(
                                id = UUID.randomUUID(),
                                name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                data =
                                    PullRequestInfoArtifact(
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                        prTitle = "this is PR title",
                                        prDescription = "this is PR description",
                                    ),
                            ),
                            WorkflowRunArtifact(
                                id = UUID.randomUUID(),
                                name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                data =
                                    AutoreviewScmCommentsArtifact(
                                        comments =
                                            listOf(
                                                AutoreviewScmCommentItem(
                                                    id = UUID.randomUUID().toString(),
                                                    comment = "this is a comment",
                                                    codeSuggestion =
                                                        AutoreviewCodeSuggestion(
                                                            code =
                                                                "```suggestion\n" +
                                                                    "this is a code\n" +
                                                                    "```",
                                                            startLine = 2,
                                                            endLine = 2,
                                                        ),
                                                    path = "badcode.kt",
                                                    line = 2,
                                                    rationale = null,
                                                ),
                                            ),
                                        lowImpactComments = emptyList(),
                                        pullRequestUrl = "https://bitbucket.org/atlassian/partner-central/pull-requests/12433",
                                    ),
                            ),
                        ),
                )

            coEvery {
                autoreviewCommentService.postPullRequestComments(any(), any(), any())
            } returns listOf(AutoreviewPullRequestComment("123", "123"))

            subject.performAction(workflowRun, mockPayload)

            coVerify(exactly = 1) {
                autoreviewService.sendAnalyticEventWorkflowCompleted(
                    workflowRun.id.toString(),
                    workflowRun.workspaceARI.toString(),
                    listOf(AutoreviewPullRequestComment("123", "123")),
                    emptyList(),
                )
            }
        }
    }
}
