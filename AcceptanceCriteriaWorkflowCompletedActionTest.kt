package devai.modules.autoreview.service

import com.atlassian.ari.principled.jira.JiraIssueARI
import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.model.WorkflowRun
import devai.modules.acra.shared.model.WorkflowRunArtifact
import devai.modules.acra.shared.model.WorkflowRunQueueType
import devai.modules.acra.shared.model.WorkflowRunStatus
import devai.modules.acra.shared.model.artifacts.ArtifactFilenames
import devai.modules.acra.shared.model.artifacts.ArtifactType
import devai.modules.acra.shared.model.artifacts.AutoreviewAcceptanceCriteriaArtifact
import devai.modules.acra.shared.model.artifacts.AutoreviewAcceptanceCriterion
import devai.modules.acra.shared.model.artifacts.AutoreviewAcceptanceCriterionStatus
import devai.modules.acra.shared.model.artifacts.JiraIssue
import devai.modules.acra.shared.model.artifacts.PullRequestInfoArtifact
import devai.modules.acra.shared.model.codereview.CodeReviewComment
import devai.modules.acra.shared.model.codereview.CodeReviewCommentChangeType
import devai.modules.acra.shared.model.codereview.CodeReviewCommentGeneratedBy
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriteria
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriterion
import devai.modules.autoreview.model.AutoreviewPantryAcceptanceCriterionStatus
import devai.modules.autoreview.model.AutoreviewPullRequestComment
import devai.modules.autoreview.queue.model.ReviewComment
import devai.modules.autoreview.utils.buildAcceptanceCriteriaComment
import devai.modules.autoreview.utils.getCriteriaThatShouldHaveCommentsPosted
import devai.modules.autoreview.utils.isDogfoodingJiraSummaryCommentRepo
import devai.modules.autoreview.utils.shouldSendJiraSummaryComment
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.GLOBAL_WORKSPACE_ARI
import devai.modules.shared.queue.model.WorkflowUpdatedPayload
import devai.modules.tenant.model.TransactionContext
import io.kotest.assertions.extracting
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Instant.now
import java.util.UUID

class AcceptanceCriteriaWorkflowCompletedActionTest {
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val autoreviewService = mockk<AutoreviewService>(relaxed = true)
    private val autoreviewWorkflowsStorageService = mockk<AutoreviewWorkflowsStorageService>(relaxed = true)
    private val mockPayload = mockk<WorkflowUpdatedPayload>(relaxed = true)
    private val transactionContext = mockk<TransactionContext>(relaxed = true)
    private val workflowRun = mockk<WorkflowRun>(relaxed = true)
    private val autoreviewCommentService = mockk<AutoreviewCommentService>(relaxed = true)
    private val workflowRunId = "6904c8f3-82af-4822-a3e7-d64a20abf0bb"
    private val workspaceAri = "ari:cloud:devai::workspace/00000000-0000-0000-0000-000000000000"

    private val testSiteId = "9a2bbece-8bb0-40e8-9d93-3f383abb44f4"
    private val testIssueId = "42424"

    private val subject =
        AcceptanceCriteriaWorkflowCompletedAction(
            featureService = featureService,
            autoreviewService = autoreviewService,
            autoreviewCommentService = autoreviewCommentService,
            autoreviewWorkflowsStorageService = autoreviewWorkflowsStorageService,
        )

    @BeforeEach
    fun setUp() {
        mockkStatic(::getCriteriaThatShouldHaveCommentsPosted)

        // Simply return all the criteria here, specific filtering logic is tested in AcceptanceCriteriaUtilsTest
        every { getCriteriaThatShouldHaveCommentsPosted(any(), any()) } answers {
            firstArg<AutoreviewAcceptanceCriteriaArtifact>().acceptanceCriteria
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(::getCriteriaThatShouldHaveCommentsPosted)
    }

    @Test
    fun `should be performed on AC workflow`() {
        every { mockPayload.workflow } returns RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA.value
        subject.shouldBePerformedOn(mockPayload) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(RootWorkflowName::class, mode = EnumSource.Mode.EXCLUDE, names = ["AUTOREVIEW_ACCEPTANCE_CRITERIA"])
    fun `should not be performed on non-AC workflow`(rootWorkflowName: RootWorkflowName) {
        every { mockPayload.workflow } returns rootWorkflowName.value
        subject.shouldBePerformedOn(mockPayload) shouldBe false
    }

    @Test
    fun `requires the acceptance criteria artifact`() {
        subject.requiredArtifactTypes() shouldBe
            setOf(
                ArtifactType.AUTOREVIEW_ACCEPTANCE_CRITERIA,
                ArtifactType.PULL_REQUEST_INFO,
            )
    }

    @Nested
    inner class SendInlineComments {
        @Test
        fun `does not send comments if there are none`() =
            runTest {
                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact =
                        AutoreviewAcceptanceCriteriaArtifact(
                            acceptanceCriteria =
                                listOf(
                                    exampleCriterion("No more bugs", comments = emptyList()),
                                ),
                            jiraIssues = listOf(jiraIssue),
                        ),
                    storedCriteria = null,
                    transactionContext = transactionContext,
                )

                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
            }

        private val acArtifactWithInlineComments =
            AutoreviewAcceptanceCriteriaArtifact(
                acceptanceCriteria =
                    listOf(
                        exampleCriterion(
                            criterion = "Remove the bugs",
                            comments =
                                listOf(
                                    exampleComment(
                                        comment = "There is a bug here",
                                        explanation = "A bug was found",
                                    ),
                                ),
                        ),
                        exampleCriterion(
                            criterion = "Write good tests",
                            comments =
                                listOf(
                                    exampleComment(
                                        comment = "You didn't write good tests",
                                        explanation = "Your tests are bad",
                                        changeType = CodeReviewCommentChangeType.UNCHANGED,
                                    ),
                                ),
                        ),
                        exampleCriterion(
                            criterion = "Add new analytic events",
                            comments =
                                listOf(
                                    exampleComment(
                                        comment = "You didn't add analytic events",
                                        explanation = "Analytic events are missing",
                                        changeType = CodeReviewCommentChangeType.UNKNOWN,
                                    ),
                                ),
                        ),
                        exampleCriterion(
                            criterion = "This one has null comments",
                            comments = null,
                        ),
                        exampleCriterion(
                            criterion = "Upgrade the dependencies",
                            comments =
                                listOf(
                                    exampleComment(
                                        comment = "This dependency was not upgraded",
                                        explanation = "The dependency wasn't upgraded",
                                    ),
                                    exampleComment(
                                        comment = "You missed this one too",
                                        explanation = "Missed it",
                                    ),
                                ),
                        ),
                        exampleCriterion(
                            criterion = "Update logs",
                            comments =
                                listOf(
                                    exampleComment(comment = "This AC has no path", path = ""),
                                ),
                        ),
                        exampleCriterion(
                            criterion = "Update docs",
                            comments =
                                listOf(
                                    exampleComment(comment = "This AC has no line", line = null),
                                ),
                        ),
                        exampleCriterion(
                            criterion = "Update APIs",
                            comments =
                                listOf(
                                    exampleComment("This AC has no line or path", line = null, path = null),
                                ),
                        ),
                    ),
                jiraIssues = listOf(jiraIssue),
            )

        @Test
        fun `sends inline comments as expected when feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns true

                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact = acArtifactWithInlineComments,
                    storedCriteria = null,
                    transactionContext = transactionContext,
                )

                val slotComments = slot<List<ReviewComment>>()

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        capture(slotComments),
                    )
                }

                extracting(slotComments.captured) { comment } shouldContainExactlyInAnyOrder
                    listOf(
                        "There is a bug here\n```expand\nDetails\n\n📖 Explanation: A bug was found\n```\n",
                        "This dependency was not upgraded\n```expand\nDetails\n\n📖 Explanation: The dependency wasn't upgraded\n```\n",
                    )
            }

        @Test
        fun `does not send inline comments when feature gate is not enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns false

                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact = acArtifactWithInlineComments,
                    storedCriteria = null,
                    transactionContext = transactionContext,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
            }

        @Test
        fun `Does not send unchanged inline comments when feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns true

                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact = acArtifactWithInlineComments,
                    storedCriteria = null,
                    transactionContext = transactionContext,
                )

                val slotComments = slot<List<ReviewComment>>()

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        capture(slotComments),
                    )
                }

                extracting(slotComments.captured) { comment } shouldContainExactlyInAnyOrder
                    listOf(
                        "There is a bug here\n```expand\nDetails\n\n📖 Explanation: A bug was found\n```\n",
                        "This dependency was not upgraded\n```expand\nDetails\n\n📖 Explanation: The dependency wasn't upgraded\n```\n",
                    )
            }

        @Test
        fun `does not send inline comments when criteria is filtered out by getCriteriaThatShouldHaveCommentsPosted`() =
            runTest {
                every { getCriteriaThatShouldHaveCommentsPosted(any(), any()) } returns emptyList()

                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact = acArtifactWithInlineComments,
                    storedCriteria = null,
                    transactionContext = transactionContext,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
            }

        @Test
        fun `only sends 1 comment per criteria`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns true

                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact =
                        AutoreviewAcceptanceCriteriaArtifact(
                            acceptanceCriteria =
                                listOf(
                                    exampleCriterion(
                                        criterion = "This should have 1 comment posted",
                                        comments =
                                            listOf(
                                                exampleComment("There is a bug here"),
                                            ),
                                    ),
                                    exampleCriterion(
                                        criterion = "This should have 1 comment posted",
                                        comments =
                                            listOf(
                                                exampleComment("This dependency was not upgraded"),
                                                exampleComment("You missed this one too"),
                                                exampleComment("This won't be posted"),
                                                exampleComment("Neither will this"),
                                            ),
                                    ),
                                    exampleCriterion(
                                        criterion = "This one has null comments so 0 posted",
                                        comments = null,
                                    ),
                                ),
                            jiraIssues = listOf(jiraIssue),
                        ),
                    storedCriteria = null,
                    transactionContext = transactionContext,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        match {
                            it.size == 1 + 1
                        },
                    )
                }
            }

        @Test
        fun `prepends category to inline comments when feature gate is enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns true
                coEvery { featureService.isAutoreviewAcceptanceCriteriaCommentCategoryEnabled(any()) } returns true

                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact = acArtifactWithInlineComments,
                    storedCriteria = null,
                    transactionContext = transactionContext,
                )

                val slotComments = slot<List<ReviewComment>>()

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        capture(slotComments),
                    )
                }

                extracting(slotComments.captured) { comment } shouldContainExactlyInAnyOrder
                    listOf(
                        "##### 🔎 Acceptance Criteria  \n\nThere is a bug here\n```expand\nDetails\n\n📖 Explanation: A bug was found\n```\n",
                        "##### 🔎 Acceptance Criteria  \n\nThis dependency was not upgraded\n```expand\nDetails\n\n📖 Explanation: The dependency wasn't upgraded\n```\n",
                    )
            }

        @Test
        fun `appends explanation to inline comments`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns true
                coEvery { featureService.isAutoreviewAcceptanceCriteriaCommentCategoryEnabled(any()) } returns true

                subject.sendInlineComments(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    acceptanceCriteriaArtifact = acArtifactWithInlineComments,
                    storedCriteria = null,
                    transactionContext = transactionContext,
                )

                val slotComments = slot<List<ReviewComment>>()

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        capture(slotComments),
                    )
                }

                extracting(slotComments.captured) { comment } shouldContainExactlyInAnyOrder
                    listOf(
                        "##### 🔎 Acceptance Criteria  \n\n" +
                            "There is a bug here\n" +
                            "```expand\n" +
                            "Details\n\n" +
                            "📖 Explanation: A bug was found\n" +
                            "```\n",
                        "##### 🔎 Acceptance Criteria  \n\n" +
                            "This dependency was not upgraded\n" +
                            "```expand\n" +
                            "Details\n\n" +
                            "📖 Explanation: The dependency wasn't upgraded\n" +
                            "```\n",
                    )
            }
    }

    @Nested
    inner class SendAcceptanceCriteriaSummaryComment {
        private val artifactThatShouldHaveSummaryComment =
            AutoreviewAcceptanceCriteriaArtifact(
                acceptanceCriteria = listOf(exampleCriterion("No more bugs")),
                jiraIssues = listOf(jiraIssue),
            )

        @BeforeEach
        fun setup() {
            mockkStatic(::buildAcceptanceCriteriaComment)
        }

        @ParameterizedTest
        @NullAndEmptySource
        fun `should not send ac comment if generated comment is null or empty`(generatedComment: String?) =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaSummaryEnabled() } returns true
                coEvery { buildAcceptanceCriteriaComment(any(), any()) } returns generatedComment

                val commentId =
                    subject.sendAcceptanceCriteriaSummaryComment(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                        acceptanceCriteriaArtifact =
                            AutoreviewAcceptanceCriteriaArtifact(
                                acceptanceCriteria = emptyList(),
                                jiraIssues = emptyList(),
                            ),
                    )

                commentId shouldBe null
                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
            }

        @Test
        fun `should send acceptance criteria summary comment if one was generated`() =
            runTest {
                val mockComment = AutoreviewPullRequestComment("internal-id", "8434")
                coEvery { featureService.isAutoreviewAcceptanceCriteriaSummaryEnabled() } returns true
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any()) } returns
                    listOf(
                        mockComment,
                    )
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any()) } returns
                    listOf(
                        mockComment,
                    )
                coEvery { buildAcceptanceCriteriaComment(any(), any()) } returns "Example Acceptance Criteria Comment"

                val comment =
                    subject.sendAcceptanceCriteriaSummaryComment(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                        acceptanceCriteriaArtifact = artifactThatShouldHaveSummaryComment,
                    )

                comment shouldBe mockComment

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        match {
                            it.size == 1 && it.first().comment == "Example Acceptance Criteria Comment"
                        },
                    )
                }
            }

        @Test
        fun `should not send acceptance criteria comment if feature gate is not enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaSummaryEnabled() } returns false

                val commentId =
                    subject.sendAcceptanceCriteriaSummaryComment(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                        acceptanceCriteriaArtifact = artifactThatShouldHaveSummaryComment,
                    )

                commentId shouldBe null
                coVerify(exactly = 0) { buildAcceptanceCriteriaComment(any(), any()) }
                coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any()) }
            }
    }

    @Nested
    inner class SendJiraSummaryComments {
        private val pullRequestInfoArtifact = mockk<PullRequestInfoArtifact>(relaxed = true)
        private val acceptanceCriteriaArtifact = mockk<AutoreviewAcceptanceCriteriaArtifact>(relaxed = true)
        private val storedCriteria = mockk<AutoreviewPantryAcceptanceCriteria>(relaxed = true)

        /**
         * Sets up the default scenario for this group of tests.
         * The default scenario is that 2 jira summary comments will be posted.
         */
        @BeforeEach
        fun setup() {
            mockkStatic(::isDogfoodingJiraSummaryCommentRepo)
            mockkStatic(::shouldSendJiraSummaryComment)
            coEvery { featureService.isAutoreviewAcceptanceCriteriaJiraCommentEnabled() } returns true
            every { isDogfoodingJiraSummaryCommentRepo(any()) } returns true
            every { shouldSendJiraSummaryComment(any(), any(), any()) } returns true
            every { pullRequestInfoArtifact.authorAccountId } returns "authorAccountId"
            every { acceptanceCriteriaArtifact.jiraIssues } returns
                listOf(
                    exampleIssue("10025", "ABC-123"),
                    exampleIssue("27896", "XYZ-456"),
                    exampleIssue("36565", "ZZZ-555"), // This Issue has no criteria
                )
            every { acceptanceCriteriaArtifact.acceptanceCriteria } returns
                listOf(
                    exampleCriterion(criterion = "No more bugs", issueId = "10025"),
                    exampleCriterion(criterion = "Calculations are correct", issueId = "27896"),
                )
            every { storedCriteria.acceptanceCriteria } returns emptyList()
            // Simulate the type of response that would be received for a successfully posted comment
            coEvery { autoreviewCommentService.postIssueComment(any(), any(), any()) } answers {
                val ari = JiraIssueARI.tryParse(firstArg<String>()).orElseThrow()
                "https://examplesite.atlassian.com/browse/${ari.issueId}?focusedCommentId=12345"
            }
        }

        @AfterEach
        fun tearDown() {
            unmockkStatic(::isDogfoodingJiraSummaryCommentRepo)
            unmockkStatic(::shouldSendJiraSummaryComment)
        }

        @Test
        fun `sends a summary comment for each jira issue that has acceptance criteria`() =
            runTest {
                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = null,
                ) shouldBe
                    listOf(
                        "https://examplesite.atlassian.com/browse/10025?focusedCommentId=12345",
                        "https://examplesite.atlassian.com/browse/27896?focusedCommentId=12345",
                    )

                coVerify(exactly = 2) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }

        @Test
        fun `if first summary comment fails, still sends the second summary comment`() =
            runTest {
                coEvery {
                    autoreviewCommentService.postIssueComment(
                        any(),
                        any(),
                        any(),
                    )
                } throws RuntimeException() andThenAnswer {
                    val ari = JiraIssueARI.tryParse(firstArg<String>()).orElseThrow()
                    "https://examplesite.atlassian.com/browse/${ari.issueId}?focusedCommentId=12345"
                }

                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = storedCriteria,
                ) shouldBe
                    listOf("https://examplesite.atlassian.com/browse/27896?focusedCommentId=12345")

                coVerify(exactly = 2) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }

        @Test
        fun `does not send comment if shouldSendJiraSummaryComment returns false`() =
            runTest {
                every { shouldSendJiraSummaryComment(any(), any(), any()) } returns false

                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = storedCriteria,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }

        @Test
        fun `does not send comments if feature gate is not enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaJiraCommentEnabled() } returns false

                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = storedCriteria,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }

        @Test
        fun `does not send comments if repoUrl is not dogFooding`() =
            runTest {
                every { isDogfoodingJiraSummaryCommentRepo(any()) } returns false

                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = storedCriteria,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }

        @Test
        fun `does not send comments if pull request artifact has no authorAccountId`() =
            runTest {
                every { pullRequestInfoArtifact.authorAccountId } returns null

                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = storedCriteria,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }

        @Test
        fun `does not send comments if artifact has no acceptance criteria`() =
            runTest {
                every { acceptanceCriteriaArtifact.acceptanceCriteria } returns emptyList()

                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = storedCriteria,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `does not send comments if ac artifact has no jiraIssues`() =
            runTest {
                every { acceptanceCriteriaArtifact.jiraIssues } returns emptyList()

                subject.sendAcceptanceCriteriaSummaryJiraComments(
                    repoUrl = "https://bitbucket.org/atlassian/devai-services",
                    pullRequestInfoArtifact = pullRequestInfoArtifact,
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    storedCriteria = storedCriteria,
                ) shouldHaveSize 0

                coVerify(exactly = 0) { autoreviewCommentService.postIssueComment(any(), any(), any()) }
            }
    }

    @Nested
    inner class PerformAction {
        @Test
        fun `posts pull request comments when workflowRun has ac comments`() =
            runTest {
                val prArtifact = mockk<PullRequestInfoArtifact>(relaxed = true)
                val commentIds = listOf(AutoreviewPullRequestComment("internal-id", "8434"))
                every { prArtifact.pullRequestUrl } returns "https://bitbucket.org/atlassian/devai-services/pull-requests/709"
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns true
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any()) } returns commentIds
                coEvery { autoreviewService.sendAnalyticEventWorkflowCompleted(any(), any(), any()) } returns true
                coEvery { autoreviewWorkflowsStorageService.getAcceptanceCriteriaPantryItem(any(), GLOBAL_WORKSPACE_ARI) } returns
                    null

                subject.performAction(
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = prArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                                    data =
                                        AutoreviewAcceptanceCriteriaArtifact(
                                            acceptanceCriteria =
                                                listOf(
                                                    exampleCriterion(
                                                        criterion = "Everything should compile",
                                                        comments = listOf(exampleComment("this code will not compile")),
                                                    ),
                                                ),
                                            jiraIssues = listOf(jiraIssue),
                                        ),
                                ),
                            ),
                    ),
                    mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/709"),
                        match {
                            it.size == 1 && it.first().comment == "this code will not compile"
                        },
                    )
                }

                coVerify(exactly = 1) {
                    autoreviewService.sendAnalyticEventWorkflowCompleted(
                        eq(workflowRunId),
                        eq(workspaceAri),
                        eq(commentIds),
                        eq(emptyList()),
                    )
                }
            }

        @Test
        fun `posts ac summary comment`() =
            runTest {
                mockkStatic(::buildAcceptanceCriteriaComment)
                coEvery { buildAcceptanceCriteriaComment(any(), any()) } returns "This is a Summary Comment"
                coEvery { featureService.isAutoreviewAcceptanceCriteriaSummaryEnabled() } returns true
                coEvery { autoreviewService.sendAnalyticEventWorkflowCompleted(any(), any(), any()) } returns true
                coEvery { autoreviewCommentService.postPullRequestComments(any(), any()) }.returnsMany(
                    listOf(
                        AutoreviewPullRequestComment("internal-id", "2345"),
                        AutoreviewPullRequestComment("internal-id", "7890"),
                    ),
                )
                coEvery { autoreviewWorkflowsStorageService.getAcceptanceCriteriaPantryItem(any(), GLOBAL_WORKSPACE_ARI) } returns
                    null

                val prArtifact = mockk<PullRequestInfoArtifact>(relaxed = true)
                every { prArtifact.pullRequestUrl } returns "https://bitbucket.org/atlassian/devai-services/pull-requests/709"

                subject.performAction(
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = prArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                                    data =
                                        AutoreviewAcceptanceCriteriaArtifact(
                                            acceptanceCriteria =
                                                listOf(
                                                    exampleCriterion(
                                                        criterion = "Everything should compile",
                                                        comments = listOf(exampleComment("this code will not compile")),
                                                    ),
                                                ),
                                            jiraIssues = listOf(jiraIssue),
                                        ),
                                ),
                            ),
                    ),
                    mockPayload,
                )

                coVerify(exactly = 1) {
                    autoreviewCommentService.postPullRequestComments(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/709"),
                        match {
                            it.size == 1 && it.first().comment == "This is a Summary Comment"
                        },
                    )
                }

                coVerify(exactly = 1) {
                    autoreviewService.sendAnalyticEventWorkflowCompleted(
                        eq(workflowRunId),
                        eq(workspaceAri),
                        eq(emptyList()),
                        eq(listOf("2345")),
                    )
                }
            }

        @ParameterizedTest
        @CsvSource(
            "true, https://bitbucket.org/atlassian/devai-services",
            "true, https://bitbucket.org/random/other",
            "false, https://bitbucket.org/atlassian/devai-services",
            "false, https://bitbucket.org/random/other",
        )
        fun `stores ac in pantry`(
            featureGateEnabled: Boolean,
            repoUrl: String,
        ) = runTest {
            coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns featureGateEnabled

            val prArtifact = mockk<PullRequestInfoArtifact>(relaxed = true)
            every { prArtifact.pullRequestUrl } returns "$repoUrl/pull-requests/709"

            val criterion = exampleCriterion("No more bugs")
            val acceptanceCriteriaArtifact =
                AutoreviewAcceptanceCriteriaArtifact(
                    acceptanceCriteria = listOf(criterion),
                    jiraIssues = listOf(jiraIssue),
                )

            subject.performAction(
                exampleWorkflowRun(
                    artifacts =
                        listOf(
                            WorkflowRunArtifact(
                                id = UUID.randomUUID(),
                                name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                data = prArtifact,
                            ),
                            WorkflowRunArtifact(
                                id = UUID.randomUUID(),
                                name = ArtifactFilenames.AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                                data = acceptanceCriteriaArtifact,
                            ),
                        ),
                    repoUrl = repoUrl,
                ),
                mockPayload,
            )

            if (featureGateEnabled) {
                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storeAcceptanceCriteria(
                        eq("$repoUrl/pull-requests/709"),
                        eq(workflowRunId),
                        any(),
                        eq(GLOBAL_WORKSPACE_ARI),
                        any(),
                    )
                }
            } else {
                coVerify(exactly = 0) {
                    autoreviewWorkflowsStorageService.storeAcceptanceCriteria(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `skip sending comments when new and old criteria are identical`(featureGateEnabled: Boolean) =
            runTest {
                coEvery { featureService.isAutoreviewAcceptanceCriteriaInlineCommentsEnabled() } returns true
                coEvery { featureService.isAutoreviewCommitTriggerEnabled() } returns featureGateEnabled

                val prArtifact = mockk<PullRequestInfoArtifact>(relaxed = true)
                every { prArtifact.pullRequestUrl } returns "https://bitbucket.org/atlassian/devai-services/pull-requests/709"

                val criterion =
                    exampleCriterion(
                        criterion = "No more bugs",
                        comments =
                            listOf(
                                exampleComment("There is a bug here"),
                            ),
                    )
                val acceptanceCriteriaArtifact =
                    AutoreviewAcceptanceCriteriaArtifact(
                        acceptanceCriteria = listOf(criterion),
                        jiraIssues = listOf(jiraIssue),
                    )

                coEvery { autoreviewWorkflowsStorageService.getAcceptanceCriteriaPantryItem(any(), GLOBAL_WORKSPACE_ARI) } returns
                    AutoreviewPantryAcceptanceCriteria(
                        acceptanceCriteria =
                            listOf(
                                AutoreviewPantryAcceptanceCriterion(
                                    id = criterion.id,
                                    criterion = criterion.criterion,
                                    status = AutoreviewPantryAcceptanceCriterionStatus.valueOf(criterion.status!!.name),
                                    issueAri = criterion.issueAri,
                                ),
                            ),
                    )

                subject.performAction(
                    exampleWorkflowRun(
                        artifacts =
                            listOf(
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                    data = prArtifact,
                                ),
                                WorkflowRunArtifact(
                                    id = UUID.randomUUID(),
                                    name = ArtifactFilenames.AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                                    data = acceptanceCriteriaArtifact,
                                ),
                            ),
                    ),
                    mockPayload,
                )

                when (featureGateEnabled) {
                    false ->
                        coVerify(exactly = 1) { autoreviewCommentService.postPullRequestComments(any(), any(), any()) }

                    true ->
                        coVerify(exactly = 0) { autoreviewCommentService.postPullRequestComments(any(), any(), any()) }
                }
            }
    }

    @Nested
    inner class GetArtifacts {
        @Test
        fun `gets pull request url if it is present`() {
            val prArtifact = mockk<PullRequestInfoArtifact>(relaxed = true)
            every { prArtifact.pullRequestUrl } returns "https://bitbucket.org/atlassian/devai-services/pull-requests/709"

            subject.getPullRequestUrl(
                exampleWorkflowRun(
                    artifacts =
                        listOf(
                            WorkflowRunArtifact(
                                id = UUID.randomUUID(),
                                name = ArtifactFilenames.PULL_REQUEST_INFO_JSON,
                                data = prArtifact,
                            ),
                        ),
                ),
            ) shouldBe "https://bitbucket.org/atlassian/devai-services/pull-requests/709"
        }

        @Test
        fun `throws exception if there is no pull request url`() {
            every { workflowRun.artifacts } returns emptyList()

            assertThrows<NullPointerException> { subject.getPullRequestUrl(workflowRun) }
        }

        @Test
        fun `gets the acceptance criteria artifact if it is present`() {
            val acArtifact =
                AutoreviewAcceptanceCriteriaArtifact(
                    acceptanceCriteria = listOf(exampleCriterion("No more bugs")),
                    jiraIssues = listOf(jiraIssue),
                )

            subject.getAcceptanceCriteriaArtifact(
                exampleWorkflowRun(
                    artifacts =
                        listOf(
                            WorkflowRunArtifact(
                                id = UUID.randomUUID(),
                                name = ArtifactFilenames.AUTOREVIEW_ACCEPTANCE_CRITERIA_JSON,
                                data = acArtifact,
                            ),
                        ),
                ),
            ) shouldBe acArtifact
        }

        @Test
        fun `throws exception if there is no acceptance criteria artifact`() {
            every { workflowRun.artifacts } returns emptyList()
            assertThrows<NullPointerException> { subject.getAcceptanceCriteriaArtifact(workflowRun) }
        }
    }

    @Nested
    inner class StoreAcceptanceCriteria {
        @Test
        fun `transforms and stores acceptance criteria in pantry`() =
            runTest {
                val criterion = exampleCriterion("No more bugs")
                val acceptanceCriteriaArtifact =
                    AutoreviewAcceptanceCriteriaArtifact(
                        acceptanceCriteria = listOf(criterion),
                        jiraIssues = listOf(jiraIssue),
                    )

                val pantryAcceptanceCriteria = slot<AutoreviewPantryAcceptanceCriteria>()

                subject.storeAcceptanceCriteria(
                    pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                    workflowId = "workflowId",
                    acceptanceCriteriaArtifact = acceptanceCriteriaArtifact,
                    workspaceAri = GLOBAL_WORKSPACE_ARI,
                    transactionContext = transactionContext,
                )

                coVerify(exactly = 1) {
                    autoreviewWorkflowsStorageService.storeAcceptanceCriteria(
                        eq("https://bitbucket.org/atlassian/devai-services/pull-requests/909"),
                        eq("workflowId"),
                        capture(pantryAcceptanceCriteria),
                        GLOBAL_WORKSPACE_ARI,
                        any(),
                    )
                }

                pantryAcceptanceCriteria.captured.acceptanceCriteria shouldHaveSize 1
                val pantryCriterion = pantryAcceptanceCriteria.captured.acceptanceCriteria.first()
                pantryCriterion.id shouldBe criterion.id
                pantryCriterion.criterion shouldBe criterion.criterion
                pantryCriterion.status shouldBe
                    criterion.status?.let {
                        AutoreviewPantryAcceptanceCriterionStatus.valueOf(
                            it.name,
                        )
                    }
                pantryCriterion.issueAri shouldBe criterion.issueAri
            }
    }

    private val jiraIssue = exampleIssue(testIssueId, "CDE-765")

    private fun exampleIssue(
        issueId: String,
        key: String,
    ) = JiraIssue(
        issueAri = JiraIssueARI.from(testSiteId, issueId).toString(),
        key = key,
        self = "",
        summary = "Remove the bugs",
        description = "Too many are there",
    )

    private fun exampleCriterion(
        criterion: String,
        comments: List<CodeReviewComment>? = emptyList(),
        status: AutoreviewAcceptanceCriterionStatus = AutoreviewAcceptanceCriterionStatus.UNMET,
        issueId: String = testIssueId,
    ) = AutoreviewAcceptanceCriterion(
        id = UUID.randomUUID().toString(),
        criterion = criterion,
        status = status,
        issueAri = JiraIssueARI.from(testSiteId, issueId).toString(),
        comments = comments,
    )

    private fun exampleComment(
        comment: String,
        path: String? = "a/b/c.txt",
        line: Int? = 90,
        explanation: String? = null,
        changeType: CodeReviewCommentChangeType = CodeReviewCommentChangeType.ADDED,
    ) = CodeReviewComment(
        id = UUID.randomUUID().toString(),
        comment = comment,
        path = path,
        line = line,
        explanation = explanation,
        changeType = changeType,
        generatedBy = CodeReviewCommentGeneratedBy.AcceptanceCriteriaReviewCommentGenerator,
    )

    private fun exampleWorkflowRun(
        artifacts: List<WorkflowRunArtifact>,
        repoUrl: String = "https://bitbucket.org/atlassian/devai-services",
    ) = WorkflowRun(
        id = UUID.fromString(workflowRunId),
        issueAri = JiraIssueARI.from("siteid", "10000"),
        repoUrl = URI(repoUrl).toURL(),
        rootWorkflow = RootWorkflowName.AUTOREVIEW_ACCEPTANCE_CRITERIA,
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
