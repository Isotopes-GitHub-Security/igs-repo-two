package devai.modules.autoreview.service

import com.atlassian.ari.principled.jira.JiraIssueARI
import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.model.WorkflowRun
import devai.modules.acra.shared.model.WorkflowRunArtifact
import devai.modules.acra.shared.model.WorkflowRunQueueType
import devai.modules.acra.shared.model.WorkflowRunStatus
import devai.modules.acra.shared.model.artifacts.AnnotatedCodeReviewCommentsArtifact
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames
import devai.modules.acra.shared.model.artifacts.ArtifactType
import devai.modules.acra.shared.model.artifacts.AutoreviewCustomPromptArtifact
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentItem
import devai.modules.acra.shared.model.artifacts.AutoreviewScmCommentsArtifact
import devai.modules.acra.shared.model.codereview.CodeReviewCommentAnnotationKey
import devai.modules.acra.shared.model.codereview.CodeReviewCommentChangeType
import devai.modules.acra.shared.model.codereview.CodeReviewCommentGeneratedBy
import devai.modules.acra.shared.model.codereview.CodeReviewCommentWithAnnotation
import devai.modules.acra.shared.model.codereview.CommentCategoryAnnotation
import devai.modules.autoreview.model.AutoreviewPullRequestComment
import devai.modules.autoreview.queue.model.ReviewComment
import devai.modules.autoreview.service.AutoreviewCustomWorkflowCompletedAction.Companion.CUSTOM_GENERATOR_COMMENT_LABEL
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.time.Instant.now
import java.util.UUID

class AutoreviewCustomWorkflowCompletedActionTest {
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val autoreviewCommentService = mockk<AutoreviewCommentService>(relaxed = true)
    private val autoreviewService = mockk<AutoreviewService>(relaxed = true)
    private val mockPayload = mockk<WorkflowUpdatedPayload>(relaxed = true)
    private val workflowRun = mockk<WorkflowRun>(relaxed = true)
    private val workflowRunId = UUID.randomUUID()
    private val testCustomRuleLink = "https://somea4j/rule/123"

    private val subject =
        AutoreviewCustomWorkflowCompletedAction(
            autoreviewService = autoreviewService,
            featureService = featureService,
            autoreviewCommentService = autoreviewCommentService,
        )

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `should be performed on custom workflow`() {
        every { mockPayload.workflow } returns RootWorkflowName.AUTOREVIEW_CUSTOM_REVIEW.value
        subject.shouldBePerformedOn(mockPayload) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(
        RootWorkflowName::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = ["AUTOREVIEW_CUSTOM_REVIEW"],
    )
    fun `should not be performed on non-custom workflows`(rootWorkflowName: RootWorkflowName) {
        every { mockPayload.workflow } returns rootWorkflowName.value
        subject.shouldBePerformedOn(mockPayload) shouldBe false
    }

    @Test
    fun `requires artifacts`() {
        subject.requiredArtifactTypes() shouldBe
            setOf(ArtifactType.AUTOREVIEW_CUSTOM_PROMPT, ArtifactType.ANNOTATED_CODE_REVIEW_COMMENTS, ArtifactType.AUTOREVIEW_SCM_COMMENTS)
    }

    @Nested
    inner class SendInlineComments {
        @Test
        fun `does not send comments if there are none`() {
            runTest {
                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    comments = emptyList(),
                    customPromptLinkUrl = testCustomRuleLink,
                )

                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
            }
        }

        @Test
        fun `sends inline comments`() {
            runTest {
                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    comments = listOf(exampleComment("There is a bug here"), exampleComment("You missed this again")),
                    customPromptLinkUrl = testCustomRuleLink,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        match { comments ->
                            comments.map { it.comment } shouldContainExactlyInAnyOrder
                                listOf(
                                    "There is a bug here \n $CUSTOM_GENERATOR_COMMENT_LABEL [A4J rule](https://somea4j/rule/123)",
                                    "You missed this again \n $CUSTOM_GENERATOR_COMMENT_LABEL [A4J rule](https://somea4j/rule/123)",
                                )
                            true
                        },
                    )
                }
            }
        }
    }

    @Nested
    inner class PerformAction {
        @Test
        fun `posts pull request comments when workflowRun has comments`() =
            runTest {
                val autoreviewCustomPromptArtifact =
                    mockk<AutoreviewCustomPromptArtifact>(relaxed = true) {
                        every { prompt.link } returns URI(testCustomRuleLink).toURL()
                    }
                val autoreviewScmCommentsArtifact =
                    mockk<AutoreviewScmCommentsArtifact>(relaxed = true) {
                        every { pullRequestUrl } returns "https://bitbucket.org/atlassian/devai-services/pull-requests/709"
                        every { comments } returns
                            listOf(
                                exampleComment("There is a bug here"),
                            ).map {
                                AutoreviewScmCommentItem(
                                    id = it.id,
                                    comment = it.comment,
                                    codeSuggestion = it.codeSuggestion,
                                    path = it.path,
                                    line = it.line,
                                    explanation = it.explanation,
                                    solution = it.solution,
                                    rationale = it.rationale,
                                    changeType = it.changeType,
                                    generatedBy = CodeReviewCommentGeneratedBy.CustomPromptCommentGenerator,
                                )
                            }
                    }
                val autoreviewAnnotationArtifact =
                    mockk<AnnotatedCodeReviewCommentsArtifact>(relaxed = true) {
                        every { comments } returns
                            listOf(
                                CodeReviewCommentWithAnnotation(
                                    id = "internal-id",
                                    comment = "Comment 1",
                                    path = "path/to/file1",
                                    line = 1,
                                    changeType = CodeReviewCommentChangeType.ADDED,
                                    generatedBy = CodeReviewCommentGeneratedBy.CustomPromptCommentGenerator,
                                    annotations =
                                        mapOf(
                                            CodeReviewCommentAnnotationKey.COMMENT_CATEGORY to
                                                CommentCategoryAnnotation("Code Bugs"),
                                            CodeReviewCommentAnnotationKey.COMMENT_SUB_CATEGORY to
                                                CommentCategoryAnnotation("N/A"),
                                        ),
                                ),
                            )
                    }
                val commentIds = listOf(AutoreviewPullRequestComment("internal-id", "8434"))
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any()) } returns commentIds

                val exampleWorkflow =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_CUSTOM_PROMPT_JSON,
                                    data = autoreviewCustomPromptArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = autoreviewAnnotationArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                            ),
                    )

                subject.performAction(
                    workflowRun = exampleWorkflow,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/709",
                        reviewComments =
                            match {
                                val comment = it.first().comment
                                it.size == 1 &&
                                    comment.contains("There is a bug here") &&
                                    comment.contains("explanation") &&
                                    comment.contains("solution") &&
                                    comment.contains("rationale")
                            },
                    )
                }
            }

        @Test
        fun `should early return when no new comments are generated`() =
            runTest {
                val autoreviewCustomPromptArtifact = mockk<AutoreviewCustomPromptArtifact>(relaxed = true)
                val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
                val autoreviewAnnotationArtifact = mockk<AnnotatedCodeReviewCommentsArtifact>(relaxed = true)
                val workflowRun =
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_CUSTOM_PROMPT_JSON,
                                    data = autoreviewCustomPromptArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.ANNOTATED_CODE_REVIEW_COMMENTS,
                                    data = autoreviewAnnotationArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_SCM_COMMENTS_JSON,
                                    data = autoreviewScmCommentsArtifact,
                                ),
                            ),
                    )
                every { autoreviewCustomPromptArtifact.prompt.link } returns
                    URI(
                        "https://isotopes.atlassian.net/jira/software/projects/AUT/settings/automation#/rule/0197ed23-5be6-7382-8d4d-e90f7a1444a8/718668923",
                    ).toURL()
                val prUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/709"
                every { autoreviewScmCommentsArtifact.pullRequestUrl } returns prUrl
                every { autoreviewScmCommentsArtifact.comments } returns emptyList()
                every { autoreviewAnnotationArtifact.comments } returns emptyList()

                subject.performAction(
                    workflowRun = workflowRun,
                    eventPayload = mockPayload,
                )

                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
            }
    }

    @Nested
    inner class GetArtifacts {
        @Test
        fun `gets artifacts when present`() {
            val autoreviewCustomPromptArtifact = mockk<AutoreviewCustomPromptArtifact>(relaxed = true)
            val autoreviewScmCommentsArtifact = mockk<AutoreviewScmCommentsArtifact>(relaxed = true)
            every { autoreviewCustomPromptArtifact.prompt.link } returns
                URI(
                    "https://isotopes.atlassian.net/jira/software/projects/AUT/settings/automation#/rule/0197ed23-5be6-7382-8d4d-e90f7a1444a8/718668923",
                ).toURL()
            every { autoreviewScmCommentsArtifact.pullRequestUrl } returns
                "https://bitbucket.org/atlassian/devai-services/pull-requests/709"
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

            val scmCommentsArtifact =
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
            scmCommentsArtifact.pullRequestUrl shouldBe "https://bitbucket.org/atlassian/devai-services/pull-requests/709"
            scmCommentsArtifact.comments shouldContainExactlyInAnyOrder scmComments

            val customPromptArtifact =
                subject.getAutoreviewCustomPromptsArtifact(
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_CUSTOM_PROMPT_JSON,
                                    data = autoreviewCustomPromptArtifact,
                                ),
                            ),
                    ),
                )
            customPromptArtifact.prompt.link.toString() shouldBe
                "https://isotopes.atlassian.net/jira/software/projects/AUT/settings/automation#/rule/0197ed23-5be6-7382-8d4d-e90f7a1444a8/718668923"
        }

        @Test
        fun `throws exception if there is no Autoreview SCM Comments artifact`() {
            every { workflowRun.artifacts } returns emptyList()

            assertThrows<NullPointerException> { subject.getAutoreviewScmCommentsArtifact(workflowRun) }
        }
    }

    private fun exampleComment(
        comment: String,
        path: String? = "a/b/c.txt",
        line: Int? = 90,
    ) = ReviewComment(
        id = UUID.randomUUID().toString(),
        comment = comment,
        explanation = "explanation",
        solution = "solution",
        rationale = "rationale",
        path = path,
        line = line,
        changeType = CodeReviewCommentChangeType.ADDED,
    )

    private fun exampleWorkflowRun(
        artifacts: List<WorkflowRunArtifact>,
        repoUrl: String = "https://bitbucket.org/atlassian/devai-services",
    ) = WorkflowRun(
        id = workflowRunId,
        issueAri = JiraIssueARI.from("siteid", "10000"),
        repoUrl = URI(repoUrl).toURL(),
        rootWorkflow = RootWorkflowName.AUTOREVIEW_CUSTOM_REVIEW,
        currentWorkflow = "currentWorkflow",
        status = WorkflowRunStatus.COMPLETED,
        artifacts = artifacts,
        queueType = WorkflowRunQueueType.DEFAULT,
        createdTimestamp = now(),
        updatedTimestamp = now(),
        workspaceARI = GLOBAL_WORKSPACE_ARI,
        phase = null,
    )
}
