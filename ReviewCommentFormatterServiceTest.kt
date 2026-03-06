package devai.modules.autoreview.service

import devai.modules.acra.shared.model.artifacts.AutoreviewCodeSuggestion
import devai.modules.acra.shared.model.codereview.CodeReviewCommentChangeType
import devai.modules.acra.shared.model.codereview.CodeReviewCommentGeneratedBy
import devai.modules.autoreview.queue.model.ReviewComment
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.utils.isGitHubRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotEndWith
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

private const val GITHUB_PR_URL = "https://github.com/myaccount/somerepo"
private const val BITBUCKET_PR_URL = "https://bitbucket.org"
private const val COMMENT_ID = "testId"
private const val TEST_AR_ID = COMMENT_ID

class ReviewCommentFormatterServiceTest {
    private val atlassianTrustLink = "https://www.atlassian.com/trust/atlassian-intelligence"
    private val devAiFeedbackLink =
        "https://customerfeedback.atlassian.net/servicedesk/customer/portal/159/create/2470?customfield_10047=commentId%3A+testId%0Acomment%3A+This+is+the+real+comment%0AprURL%3A+https%3A%2F%2Fgithub.com%2Fmyaccount%2Fsomerepo"
    private val devAiFeedbackLinkWithCodeSuggestion =
        "https://customerfeedback.atlassian.net/servicedesk/customer/portal/159/create/2470?customfield_10047=commentId%3A+testId%0Acomment%3A+This+is+the+real+comment%0A%60%60%60suggestion%0AThis+is+a+code+suggestion%0A%60%60%60%0AprURL%3A+https%3A%2F%2Fgithub.com%2Fmyaccount%2Fsomerepo"

    private val testCodeSuggestion =
        AutoreviewCodeSuggestion(
            code = "```suggestion\nThis is a code suggestion\n```",
            startLine = 39,
            endLine = 39,
        )

    val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)

    val subject = ReviewCommentFormatterService(featureService)

    @BeforeEach
    fun setUp() {
        mockkStatic(::isGitHubRepository)
        every { isGitHubRepository(any()) } answers {
            firstArg<String>() == GITHUB_PR_URL
        }
        coEvery { featureService.isAutoreviewAppendGithubRdsCtaFooter(any()) } returns false
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(::isGitHubRepository)
    }

    @Nested
    inner class GitHubFooters {
        private val gitHubCommentFooter =
            "\n> [Uses AI. Verify results.]($atlassianTrustLink) [Give Feedback]($devAiFeedbackLink)"

        private val gitHubCommentFooterWithCodeSuggestion =
            "\n> [Uses AI. Verify results.]($atlassianTrustLink) [Give Feedback]($devAiFeedbackLinkWithCodeSuggestion)"

        @Test
        fun `should append github footer for github url`() =
            runTest {
                val comments =
                    subject.appendFooters(
                        comments =
                            listOf(
                                ReviewComment(
                                    id = COMMENT_ID,
                                    comment = "This is the real comment",
                                    line = 39,
                                    path = "fileA.txt",
                                ),
                            ),
                        prUrl = GITHUB_PR_URL,
                        transactionContext = mockk(relaxed = true),
                    )

                comments[0].comment shouldEndWith gitHubCommentFooter
            }

        @Test
        fun `should not append github footer for non-github url`() =
            runTest {
                val comments =
                    subject.appendFooters(
                        comments =
                            listOf(
                                ReviewComment(
                                    id = COMMENT_ID,
                                    comment = "This is the real comment",
                                    line = 39,
                                    path = "fileA.txt",
                                ),
                            ),
                        prUrl = BITBUCKET_PR_URL,
                        transactionContext = mockk(relaxed = true),
                    )

                comments[0].comment shouldNotEndWith gitHubCommentFooter
            }

        @Test
        fun `should return comment with code suggestion and it in feedback url if present in comment`() =
            runTest {
                // Arrange
                val autoreviewComment =
                    ReviewComment(
                        id = COMMENT_ID,
                        comment = "This is the real comment\n${testCodeSuggestion.code}",
                        codeSuggestion = testCodeSuggestion,
                        line = 39,
                        path = "fileA.txt",
                    )

                // Act
                val comments =
                    subject.appendFooters(
                        comments = listOf(autoreviewComment),
                        prUrl = GITHUB_PR_URL,
                        transactionContext = mockk(relaxed = true),
                    )

                // Assert
                comments.size shouldBe 1
                comments[0].comment shouldBe autoreviewComment.comment + gitHubCommentFooterWithCodeSuggestion
                comments[0].codeSuggestion shouldBe testCodeSuggestion
            }

        @Nested
        @TestInstance(TestInstance.Lifecycle.PER_CLASS)
        inner class RovoDevStandardPromotion {
            val promotionRegex = Regex("""^>.+available with_ \[_Rovo Dev Standard_]\(.*\)$""", RegexOption.MULTILINE)

            @BeforeEach
            fun setUp() {
                coEvery { featureService.isAutoreviewAppendGithubRdsCtaFooter(any()) } returns true
            }

            @ParameterizedTest
            @EnumSource(
                value = CodeReviewCommentGeneratedBy::class,
                mode = EnumSource.Mode.EXCLUDE,
                names = ["AcceptanceCriteriaReviewCommentGenerator", "CustomReviewCommentGenerator", "CustomPromptCommentGenerator"],
            )
            fun `should not include rovo dev standard promotion if comment not generated by RDS feature`(
                generatedBy: CodeReviewCommentGeneratedBy,
            ) = runTest {
                val comments =
                    subject.appendFooters(
                        comments =
                            listOf(
                                ReviewComment(
                                    id = COMMENT_ID,
                                    comment = "This is the real comment",
                                    line = 39,
                                    path = "fileA.txt",
                                    generatedBy = generatedBy,
                                ),
                            ),
                        prUrl = GITHUB_PR_URL,
                        transactionContext = mockk(relaxed = true),
                    )

                comments[0].comment shouldNotContain promotionRegex
            }

            @ParameterizedTest
            @EnumSource(
                value = CodeReviewCommentGeneratedBy::class,
            )
            fun `should not include rovo dev standard promotion on bitbucket url`(generatedBy: CodeReviewCommentGeneratedBy) =
                runTest {
                    val comments =
                        subject.appendFooters(
                            comments =
                                listOf(
                                    ReviewComment(
                                        id = COMMENT_ID,
                                        comment = "This is the real comment",
                                        line = 39,
                                        path = "fileA.txt",
                                        generatedBy = generatedBy,
                                    ),
                                ),
                            prUrl = BITBUCKET_PR_URL,
                            transactionContext = mockk(relaxed = true),
                        )

                    comments[0].comment shouldNotContain promotionRegex
                }

            @ParameterizedTest
            @MethodSource("rovoDevStandardPromotionCases")
            fun `should include rovo dev standard promotion if comment is generated by RDS feature`(
                generatedBy: CodeReviewCommentGeneratedBy,
                expectedPromotionComment: String,
            ) = runTest {
                val comments =
                    subject.appendFooters(
                        comments =
                            listOf(
                                ReviewComment(
                                    id = COMMENT_ID,
                                    comment = "This is the real comment",
                                    line = 39,
                                    path = "fileA.txt",
                                    generatedBy = generatedBy,
                                ),
                            ),
                        prUrl = GITHUB_PR_URL,
                        transactionContext = mockk(relaxed = true),
                    )

                comments.single().comment shouldContain expectedPromotionComment
                // To verify the effectiveness of negative test using the same regex
                expectedPromotionComment shouldMatch promotionRegex
            }

            fun rovoDevStandardPromotionCases(): Stream<Arguments> =
                Stream.of(
                    Arguments.argumentSet(
                        "Acceptance Criteria",
                        CodeReviewCommentGeneratedBy.AcceptanceCriteriaReviewCommentGenerator,
                        "> :sparkles: _Acceptance criteria checks available with_ [_Rovo Dev Standard_](https://support.atlassian.com/rovo/docs/check-acceptance-criteria-in-a-code-review/)",
                    ),
                    Arguments.argumentSet(
                        "Custom Instructions",
                        CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                        "> :sparkles: _Custom review instructions available with_ [_Rovo Dev Standard_](https://support.atlassian.com/rovo/docs/set-custom-instructions-for-code-reviews/)",
                    ),
                    Arguments.argumentSet(
                        "Custom Prompt / Automation",
                        CodeReviewCommentGeneratedBy.CustomPromptCommentGenerator,
                        "> :sparkles: _Custom, automated PR reviews available with_ [_Rovo Dev Standard_](https://support.atlassian.com/rovo/docs/work-with-rovo-dev-in-automations/)",
                    ),
                )
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class DevAIFeedbackLink {
        @ParameterizedTest
        @MethodSource("correctFeedbackLinkForValidInputs")
        fun `returns correct feedback link for valid inputs`(
            commentId: String,
            commentText: String,
            prUrl: String,
            expectedUrl: String,
        ) {
            val comment = ReviewComment(id = commentId, comment = commentText, path = "", line = 1)
            val result = subject.getDevaiFeedbackLink(comment, prUrl)
            result shouldBe expectedUrl
        }

        @Test
        fun `handles special characters in comment text`() {
            val comment = ReviewComment(id = "123", comment = "Special characters: &%$#@!", path = "", line = 1)
            val prUrl = "https://bitbucket.org/test/pull-requests/1"
            val result = subject.getDevaiFeedbackLink(comment, prUrl)
            result shouldContain "Special+characters%3A+%26%25%24%23%40%21"
        }

        fun correctFeedbackLinkForValidInputs(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "123",
                    "This is a comment",
                    "https://bitbucket.org/test/pull-requests/1",
                    "https://customerfeedback.atlassian.net/servicedesk/customer/portal/159/create/2470?customfield_10047=commentId%3A+123%0Acomment%3A+This+is+a+comment%0AprURL%3A+https%3A%2F%2Fbitbucket.org%2Ftest%2Fpull-requests%2F1",
                ),
                Arguments.of(
                    "456",
                    "Another comment",
                    "https://github.com/test/repo/pull/2",
                    "https://customerfeedback.atlassian.net/servicedesk/customer/portal/159/create/2470?customfield_10047=commentId%3A+456%0Acomment%3A+Another+comment%0AprURL%3A+https%3A%2F%2Fgithub.com%2Ftest%2Frepo%2Fpull%2F2",
                ),
            )
    }

    @DisplayName("Test mergeSamePathAndLineComments")
    @Nested
    inner class MergingComments {
        private val baseComment =
            ReviewComment(
                id = TEST_AR_ID,
                comment = "Base Comment",
                path = "path/to/file1",
                line = 1,
                generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
            )

        @Test
        fun `merges comments with the same path, line number, and generatedBy`() {
            // Arrange
            val comments =
                listOf(
                    baseComment.copy(comment = "Comment 1", line = 1),
                    baseComment.copy(comment = "Comment 2", line = 1),
                    baseComment.copy(comment = "Comment 3", line = 2, path = "path/to/file2"),
                )

            val expectedComments =
                listOf(
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment =
                            "* Comment 1\n" +
                                "* Comment 2",
                        path = "path/to/file1",
                        line = 1,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    ),
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "Comment 3",
                        path = "path/to/file2",
                        line = 2,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    ),
                )

            // Act
            val finalComments = subject.mergeComments(comments)

            // Assert
            assertEquals(2, finalComments.size)
            assertEquals(expectedComments, finalComments)
        }

        @Test
        fun `merges comments and uses changeType with lowest ordinal value`() {
            // Arrange
            val comments =
                listOf(
                    baseComment.copy(comment = "Comment 1", line = 1),
                    baseComment.copy(
                        comment = "Comment 2",
                        line = 1,
                        changeType = CodeReviewCommentChangeType.UNCHANGED,
                    ),
                    baseComment.copy(
                        comment = "Comment 3",
                        line = 2,
                        changeType = CodeReviewCommentChangeType.REMOVED,
                        path = "path/to/file2",
                    ),
                    baseComment.copy(
                        comment = "Comment 4",
                        line = 2,
                        changeType = CodeReviewCommentChangeType.ADDED,
                        path = "path/to/file2",
                    ),
                )

            val expectedComments =
                listOf(
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "* Comment 1\n* Comment 2",
                        path = "path/to/file1",
                        line = 1,
                        changeType = CodeReviewCommentChangeType.UNCHANGED,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    ),
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "* Comment 3\n* Comment 4",
                        path = "path/to/file2",
                        line = 2,
                        changeType = CodeReviewCommentChangeType.ADDED,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    ),
                )

            // Act
            val finalComments = subject.mergeComments(comments)

            // Assert
            assertEquals(2, finalComments.size)
            assertEquals(expectedComments, finalComments)
        }

        @Test
        fun `doesn't merge comments with different paths`() {
            // Arrange
            val comments =
                listOf(
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "Comment 1",
                        path = "path/to/file1",
                        line = 1,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    ),
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "Comment 2",
                        path = "path/to/file2",
                        line = 1,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    ),
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "Comment 3",
                        path = "path/to/file3",
                        line = 2,
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    ),
                )

            // Act
            val finalComments = subject.mergeComments(comments)

            // Assert
            assertEquals(3, finalComments.size)
            assertEquals(comments, finalComments)
        }

        @Test
        fun `doesn't merge comments with different line numbers`() {
            // Arrange
            val comments =
                listOf(
                    baseComment.copy(line = 1),
                    baseComment.copy(line = 2),
                    baseComment.copy(line = 3),
                )

            // Act
            val finalComments = subject.mergeComments(comments)

            // Assert
            assertEquals(3, finalComments.size)
            assertEquals(comments, finalComments)
        }

        @Test
        fun `doesn't merge comments with different generatedBy`() {
            // Arrange
            val comments =
                listOf(
                    baseComment.copy(generatedBy = CodeReviewCommentGeneratedBy.CustomPromptCommentGenerator),
                    baseComment.copy(generatedBy = CodeReviewCommentGeneratedBy.AcceptanceCriteriaReviewCommentGenerator),
                )

            // Act
            val finalComments = subject.mergeComments(comments)

            // Assert
            assertEquals(2, finalComments.size)
            assertEquals(comments, finalComments)
        }

        @Test
        fun `doesn't merge comments with emptyList()`() {
            // Act
            val expectedComments: List<ReviewComment> = emptyList()
            val finalComments = subject.mergeComments(emptyList())

            // Assert
            assertEquals(0, finalComments.size)
            assertEquals(expectedComments, finalComments)
        }

        @Test
        fun `only keeps code suggestion from the first comment`() {
            // Arrange
            val autoreviewComments =
                listOf(
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "Comment with code suggestion",
                        codeSuggestion =
                            AutoreviewCodeSuggestion(
                                code = "```suggestion\nThis is the first code suggestion\n```",
                                startLine = 39,
                                endLine = 39,
                            ),
                        line = 39,
                        path = "fileA.txt",
                    ),
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "Another comment with another code suggestion",
                        codeSuggestion =
                            AutoreviewCodeSuggestion(
                                code = "```suggestion\nThis is the second code suggestion\n```",
                                startLine = 39,
                                endLine = 39,
                            ),
                        line = 39,
                        path = "fileA.txt",
                    ),
                )

            // Act
            val comments = subject.mergeComments(autoreviewComments)

            // Assert
            comments.single().run {
                comment shouldBe
                    """
                    |* Comment with code suggestion
                    |* Another comment with another code suggestion
                    """.trimMargin()
                codeSuggestion shouldBe
                    AutoreviewCodeSuggestion(
                        code = "```suggestion\nThis is the first code suggestion\n```",
                        startLine = 39,
                        endLine = 39,
                    )
            }
        }
    }
}
