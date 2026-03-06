package devai.modules.autoreview.service

import com.atlassian.ari.principled.CloudIdLike
import com.atlassian.usercontext.api.AccountId
import devai.modules.acra.client.AcraClient
import devai.modules.acra.client.DEV_AI_WORKSPACE_ID
import devai.modules.acra.shared.model.artifacts.AutoreviewCodeSuggestion
import devai.modules.acra.shared.model.artifacts.AutoreviewItem
import devai.modules.acra.shared.model.codereview.CodeReviewCommentChangeType
import devai.modules.acra.shared.model.codereview.CodeReviewCommentGeneratedBy
import devai.modules.acra.shared.model.codesuggestions.CodeSuggestionChangeType
import devai.modules.autoreview.model.AutoreviewPullRequestComment
import devai.modules.autoreview.queue.model.ReviewComment
import devai.modules.sal.model.SalModel
import devai.modules.sal.service.BitbucketService
import devai.modules.sal.service.SalService
import devai.modules.shared.client.idgatekeeper.IdGatekeeperClient
import devai.modules.shared.client.idgatekeeper.MintUctResponse
import devai.modules.shared.client.integrations.IntegrationsServiceClient
import devai.modules.shared.client.streamhub.AutoreviewEventType
import devai.modules.shared.config.OutboundAuthContainerProperties
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.BaseIntegrationsServiceResponse
import devai.modules.shared.model.CommentCreatedEntity
import devai.modules.shared.model.CreditResult
import devai.modules.shared.model.CreditStatus
import devai.modules.shared.model.IntegrationsServiceException
import devai.modules.shared.model.UrlDetails
import devai.modules.shared.model.UserCreditResult
import devai.modules.shared.model.WorkItemComment
import devai.modules.shared.sal.SalSharedUtil
import devai.modules.shared.service.tcs.TcsService
import io.atlassian.tcs.model.cloud.CloudURL
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI

class DefaultAutoreviewCommentServiceTest {
    companion object {
        private const val TEST_AR_ID = "testId"
        private const val TEST_BITBUCKET_WORKSPACE_UUID = "{02b941e3-cfaa-40f9-9a58-aec53e20bdc4}"
        private const val TEST_CLOUD_ID = "922168f0-256f-49e0-ac04-4db48b68d2ea"
        private const val TEST_AUTHOR_ACCOUNT_ID = "123456789013"
        private const val TEST_PR_URL = "https://bitbucket.org/test/pull-requests/1"
        private val TEST_CODE_SUGGESTION =
            AutoreviewCodeSuggestion(
                code = "```suggestion\nThis is a code suggestion\n```",
                startLine = 39,
                endLine = 39,
            )
    }

    private val acraClient = mockk<AcraClient>(relaxed = true)
    private val bitbucketService = mockk<BitbucketService>(relaxed = true)
    private val commentFormatter = mockk<ReviewCommentFormatterService>(relaxed = true)
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val idGatekeeperClient = mockk<IdGatekeeperClient>(relaxed = true)
    private val integrationsServiceClient = mockk<IntegrationsServiceClient>(relaxed = true)
    private val outboundAuthContainerProperties = mockk<OutboundAuthContainerProperties>(relaxed = true)
    private val salService = mockk<SalService>(relaxed = true)
    private val salSharedUtil = mockk<SalSharedUtil>(relaxed = true)
    private val tcsService = mockk<TcsService>(relaxed = true)
    private lateinit var autoreviewCommentService: DefaultAutoreviewCommentService

    @BeforeEach
    fun setUp() {
        autoreviewCommentService =
            DefaultAutoreviewCommentService(
                acraClient = acraClient,
                bitbucketService = bitbucketService,
                commentFormatter = commentFormatter,
                featureService = featureService,
                idGatekeeperClient = idGatekeeperClient,
                integrationsServiceClient = integrationsServiceClient,
                outboundAuthContainerProperties = outboundAuthContainerProperties,
                salService = salService,
                salSharedUtil = salSharedUtil,
                tcsService = tcsService,
            )

        val urlDetails =
            UrlDetails(
                scm = "bitbucket",
                domain = "bitbucket.org",
                workspaceName = "test",
                repoSlug = "test",
            )
        coEvery { salSharedUtil.validateAndExtractRepoDetails(any()) } returns urlDetails
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

    @Nested
    inner class SendPullRequestComments {
        private val feedbackId = "9999"

        @BeforeEach
        fun setup() {
            coEvery {
                bitbucketService.getWorkspaceIdFromWorkspaceSlug(
                    any(),
                    any(),
                )
            } returns TEST_BITBUCKET_WORKSPACE_UUID
        }

        private fun generateComments(numberOfComments: Int): List<ReviewComment> {
            val commentList = mutableListOf<ReviewComment>()
            for (i in 1..numberOfComments) {
                commentList.add(
                    ReviewComment(
                        id = "${TEST_AR_ID}-$i",
                        comment = "Comment $i",
                        path = "file$i",
                        line = i,
                    ),
                )
            }
            return commentList
        }

        @Test
        fun `send a single pr comment successfully`() {
            coEvery { salService.createPrComment(any()) } returns
                BaseIntegrationsServiceResponse(
                    operationType = "CREATE",
                    operationStatus = "SUCCESS",
                    entityType = "comment",
                    entities =
                        listOf(
                            CommentCreatedEntity(id = 3030L),
                        ),
                )

            runTest {
                val listIds =
                    autoreviewCommentService.postPullRequestComments(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                        reviewComments = generateComments(1),
                    )

                listIds.map { it.externalId }.shouldContainExactlyInAnyOrder("3030")
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `send a single multi-line pr comment with CS successfully`(isMultiLineEnabled: Boolean) {
            runTest {
                // Arrange
                coEvery { salService.createPrComment(any()) } returnsMany
                    listOf(
                        BaseIntegrationsServiceResponse(
                            operationType = "CREATE",
                            operationStatus = "SUCCESS",
                            entityType = "comment",
                            entities =
                                listOf(
                                    CommentCreatedEntity(id = 3030L),
                                ),
                        ),
                        BaseIntegrationsServiceResponse(
                            operationType = "CREATE",
                            operationStatus = "SUCCESS",
                            entityType = "comment",
                            entities =
                                listOf(
                                    CommentCreatedEntity(id = feedbackId.toLong()),
                                ),
                        ),
                    )

                val pullRequestURL = "https://bitbucket.org/atlassian/devai-services/pull-requests/123"
                val reviewComments =
                    generateComments(1).map {
                        it.copy(
                            codeSuggestion =
                                AutoreviewCodeSuggestion(
                                    code = "```suggestion\nsuggested code\n```",
                                    rationale = "This is a suggestion",
                                    startLine = 1,
                                    endLine = 2,
                                    changeType = CodeSuggestionChangeType.SINGLE_FILE_SINGLE_LINE_REPLACED,
                                ),
                        )
                    }

                val salCommentPayloadSlot = mutableListOf<SalModel.CreatePullRequestCommentRequest>()
                coEvery { salService.createPrComment(capture(salCommentPayloadSlot)) } returnsMany
                    listOf(
                        BaseIntegrationsServiceResponse(
                            operationType = "CREATE",
                            operationStatus = "SUCCESS",
                            entityType = "comment",
                            entities =
                                listOf(
                                    CommentCreatedEntity(id = 3030L),
                                ),
                        ),
                        BaseIntegrationsServiceResponse(
                            operationType = "CREATE",
                            operationStatus = "SUCCESS",
                            entityType = "comment",
                            entities =
                                listOf(
                                    CommentCreatedEntity(id = feedbackId.toLong()),
                                ),
                        ),
                    )

                // Act
                autoreviewCommentService.postPullRequestComments(
                    pullRequestUrl = pullRequestURL,
                    reviewComments = reviewComments,
                    isMultiLineCommentsEnabled = isMultiLineEnabled,
                )

                // Assert
                salCommentPayloadSlot shouldHaveSize 1
                val commentPayload = salCommentPayloadSlot.first()
                val comment = reviewComments.first()

                if (isMultiLineEnabled) {
                    commentPayload.startFrom shouldBe null
                    commentPayload.from shouldBe null
                    commentPayload.startTo shouldBe comment.codeSuggestion?.startLine
                    commentPayload.to shouldBe comment.codeSuggestion?.endLine
                } else {
                    commentPayload.startFrom shouldBe null
                    commentPayload.startTo shouldBe null
                    commentPayload.from shouldBe null
                    commentPayload.to shouldBe comment.line
                }
            }
        }

        @Test
        fun `send multiple pr comments successfully`() {
            coEvery { salService.createPrComment(any()) } returnsMany
                listOf(
                    BaseIntegrationsServiceResponse(
                        operationType = "CREATE",
                        operationStatus = "SUCCESS",
                        entityType = "comment",
                        entities =
                            listOf(
                                CommentCreatedEntity(id = 2030L),
                            ),
                    ),
                    BaseIntegrationsServiceResponse(
                        operationType = "CREATE",
                        operationStatus = "SUCCESS",
                        entityType = "comment",
                        entities =
                            listOf(
                                CommentCreatedEntity(id = 4003L),
                            ),
                    ),
                    BaseIntegrationsServiceResponse(
                        operationType = "CREATE",
                        operationStatus = "SUCCESS",
                        entityType = "comment",
                        entities =
                            listOf(
                                CommentCreatedEntity(id = 10950L),
                            ),
                    ),
                )

            runTest {
                val listIds =
                    autoreviewCommentService.postPullRequestComments(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/809",
                        reviewComments = generateComments(3),
                    )

                listIds.shouldContainInOrder(
                    AutoreviewPullRequestComment(id = "testId-1", externalId = "2030"),
                    AutoreviewPullRequestComment(id = "testId-2", externalId = "4003"),
                    AutoreviewPullRequestComment(id = "testId-3", externalId = "10950"),
                )
            }
        }

        @Test
        fun `should not throw exception when there is an error sending a comment`() {
            coEvery { salService.createPrComment(any()) } throws NullPointerException("Something bad happened")

            runTest {
                val listIds =
                    autoreviewCommentService.postPullRequestComments(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/889",
                        reviewComments = generateComments(1),
                    )

                listIds.shouldBeEmpty()
            }
        }

        @Test
        fun `should return successful comment ids even when another comment was not posted successfully`() {
            coEvery { salService.createPrComment(any()) } returnsMany
                listOf(
                    BaseIntegrationsServiceResponse(
                        operationType = "CREATE",
                        operationStatus = "SUCCESS",
                        entityType = "comment",
                        entities =
                            listOf(
                                CommentCreatedEntity(id = 2030),
                            ),
                    ),
                    BaseIntegrationsServiceResponse(
                        operationType = "CREATE",
                        operationStatus = "FAILURE",
                        entityType = "comment",
                        entities = emptyList(),
                    ),
                    BaseIntegrationsServiceResponse(
                        operationType = "CREATE",
                        operationStatus = "SUCCESS",
                        entityType = "comment",
                        entities =
                            listOf(
                                CommentCreatedEntity(id = 10950),
                            ),
                    ),
                )

            runTest {
                val listIds =
                    autoreviewCommentService.postPullRequestComments(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/801",
                        reviewComments = generateComments(3),
                    )

                listIds.map { it.externalId }.shouldContainExactly(
                    "2030",
                    "10950",
                )
            }
        }
    }

    @Nested
    inner class PrepareCommentsForSending {
        private val gitHubRepoUrl = "https://github.com/myaccount/somerepo"
        private val internalBBCRepoUrl = "https://bitbucket.org/atlassian/something"
        private val externalBBCRepoUrl = "https://bitbucket.org/someotherworkspace/something"

        @BeforeEach
        fun setup() {
            coEvery {
                bitbucketService.getWorkspaceIdFromWorkspaceSlug(
                    any(),
                    any(),
                )
            } returns TEST_BITBUCKET_WORKSPACE_UUID
        }

        @Test
        fun `should not throw exception for empty comments`() {
            runTest {
                val comments = autoreviewCommentService.prepareCommentsForSending(gitHubRepoUrl, comments = emptyList())
                comments.shouldBeEmpty()
            }
        }

        @Test
        fun `should continue without exception when malformed url is given`() {
            runTest {
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        "this is not a good url",
                        comments =
                            listOf(
                                ReviewComment(
                                    id = TEST_AR_ID,
                                    comment = "This is the real comment",
                                    line = 39,
                                    path = "fileA.txt",
                                ),
                            ),
                    )

                comments.shouldHaveSize(1)
            }
        }

        @Test
        fun `should pass comments through commentFormatter appendFooters`() {
            runTest {
                coEvery {
                    commentFormatter.appendFooters(
                        any(),
                        any(),
                        any(),
                    )
                } answers {
                    firstArg<List<ReviewComment>>().map {
                        it.copy(
                            comment = it.comment + "WITH A FOOTER APPENDED",
                        )
                    }
                }

                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        TEST_PR_URL,
                        comments =
                            listOf(
                                ReviewComment(
                                    id = TEST_AR_ID,
                                    comment = "This is the real comment",
                                    line = 39,
                                    path = "fileA.txt",
                                ),
                            ),
                    )

                coVerify(exactly = 1) {
                    commentFormatter.appendFooters(
                        match { it.single().id == TEST_AR_ID },
                        TEST_PR_URL,
                        any(),
                    )
                }
                comments.single().comment shouldEndWith "WITH A FOOTER APPENDED"
            }
        }

        @Test
        fun `should not include internal feedback comment for external BBC repo`() {
            runTest {
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments =
                            listOf(
                                ReviewComment(
                                    id = TEST_AR_ID,
                                    comment = "This is the real comment",
                                    line = 39,
                                    path = "fileA.txt",
                                ),
                            ),
                    )

                comments.size shouldBe 1
                comments[0].comment shouldBe "This is the real comment"
            }
        }

        @Test
        fun `should add CS to comment when codeSuggestion exists`() {
            runTest {
                // Arrange
                val autoreviewComment =
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "This is the real comment",
                        codeSuggestion = TEST_CODE_SUGGESTION,
                        line = 39,
                        path = "fileA.txt",
                    )
                val expectedFinalComment = "${autoreviewComment.comment}\n${TEST_CODE_SUGGESTION.code}"

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = listOf(autoreviewComment),
                    )

                // Assert
                comments.size shouldBe 1
                comments[0].comment shouldBe expectedFinalComment
                comments[0].codeSuggestion shouldNotBe null
            }
        }

        @Test
        fun `should add CS to comment when codeSuggestion does not exists`() {
            runTest {
                // Arrange
                val autoreviewComment =
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "This is the real comment",
                        line = 39,
                        path = "fileA.txt",
                    )

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = listOf(autoreviewComment),
                    )

                // Assert
                comments.size shouldBe 1
                comments[0].comment shouldBe autoreviewComment.comment
            }
        }

        @Test
        fun `should add CS to comments that has codeSuggestion not empty`() {
            runTest {
                // Arrange
                val autoreviewComments =
                    listOf(
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment with code suggestion",
                            codeSuggestion = TEST_CODE_SUGGESTION,
                            line = 39,
                            path = "fileA.txt",
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment without code suggestion",
                            line = 40,
                            path = "fileA.txt",
                        ),
                    )

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = autoreviewComments,
                    )

                // Assert
                comments.size shouldBe 2
                comments[0].codeSuggestion shouldNotBe null
                comments[1].codeSuggestion shouldBe null
                comments[0].comment shouldBe "Comment with code suggestion\n```suggestion\nThis is a code suggestion\n```"
                comments[1].comment shouldBe "Comment without code suggestion"
            }
        }

        @Test
        fun `should add custom label for comments generated by CustomReviewCommentGenerator`() {
            runTest {
                // Arrange
                // Mock the salSharedUtil to return a test href link
                every {
                    salSharedUtil.buildHrefLinkToFile(externalBBCRepoUrl, null, ".rovodev/.review-agent.md")
                } returns "https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md"

                // Mock salSharedUtil.validateAndExtractPullRequestDetails for createTransactionContext
                every {
                    salSharedUtil.validateAndExtractPullRequestDetails(externalBBCRepoUrl)
                } returns
                    mockk {
                        every { scm } returns "bitbucket"
                        every { domain } returns "bitbucket.org"
                        every { workspaceName } returns "someotherworkspace"
                    }

                // Mock bitbucketService.getWorkspaceIdFromWorkspaceSlug
                coEvery {
                    bitbucketService.getWorkspaceIdFromWorkspaceSlug("bitbucket.org", "someotherworkspace")
                } returns "{$DEV_AI_WORKSPACE_ID}"

                val autoreviewComment =
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "This is a custom review comment.",
                        line = 39,
                        path = "fileA.txt",
                        generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                    )

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = listOf(autoreviewComment),
                    )

                // Assert
                comments.size shouldBe 1
                comments[0].comment shouldBe
                    "This is a custom review comment. \n > _Based on this repository's_ [custom instructions](https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md)"
            }
        }

        @Test
        fun `should add custom label to comments generated by CustomReviewCommentGenerator`() {
            runTest {
                // Arrange
                // Mock the salSharedUtil to return a test href link
                every {
                    salSharedUtil.buildHrefLinkToFile(externalBBCRepoUrl, null, ".rovodev/.review-agent.md")
                } returns "https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md"

                // Mock salSharedUtil.validateAndExtractPullRequestDetails for createTransactionContext
                every {
                    salSharedUtil.validateAndExtractPullRequestDetails(externalBBCRepoUrl)
                } returns
                    mockk {
                        every { scm } returns "bitbucket"
                        every { domain } returns "bitbucket.org"
                        every { workspaceName } returns "someotherworkspace"
                    }

                // Mock bitbucketService.getWorkspaceIdFromWorkspaceSlug
                coEvery {
                    bitbucketService.getWorkspaceIdFromWorkspaceSlug("bitbucket.org", "someotherworkspace")
                } returns "{$DEV_AI_WORKSPACE_ID}"

                val autoreviewComment =
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "This is a custom review comment",
                        line = 39,
                        path = "fileA.txt",
                        generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                    )

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = listOf(autoreviewComment),
                    )

                // Assert
                comments.size shouldBe 1
                comments[0].comment shouldBe
                    "This is a custom review comment \n > _Based on this repository's_ [custom instructions](https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md)"
            }
        }

        @Test
        fun `should add custom label to multiple custom generated comments`() {
            runTest {
                // Arrange
                // Mock the salSharedUtil to return a test href link
                every {
                    salSharedUtil.buildHrefLinkToFile(externalBBCRepoUrl, null, ".rovodev/.review-agent.md")
                } returns "https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md"

                // Mock salSharedUtil.validateAndExtractPullRequestDetails for createTransactionContext
                every {
                    salSharedUtil.validateAndExtractPullRequestDetails(externalBBCRepoUrl)
                } returns
                    mockk {
                        every { scm } returns "bitbucket"
                        every { domain } returns "bitbucket.org"
                        every { workspaceName } returns "someotherworkspace"
                    }

                // Mock bitbucketService.getWorkspaceIdFromWorkspaceSlug
                coEvery {
                    bitbucketService.getWorkspaceIdFromWorkspaceSlug("bitbucket.org", "someotherworkspace")
                } returns "{$DEV_AI_WORKSPACE_ID}"

                val autoreviewComments =
                    listOf(
                        ReviewComment(
                            id = "1",
                            comment = "First custom comment.",
                            line = 39,
                            path = "fileA.txt",
                            generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                        ),
                        ReviewComment(
                            id = "2",
                            comment = "Second custom comment.",
                            line = 40,
                            path = "fileB.txt",
                            generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                        ),
                        ReviewComment(
                            id = "3",
                            comment = "Regular comment",
                            line = 41,
                            path = "fileC.txt",
                            generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                        ),
                    )

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = autoreviewComments,
                    )

                // Assert
                comments.size shouldBe 3
                comments[0].comment shouldBe
                    "First custom comment. \n > _Based on this repository's_ [custom instructions](https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md)"
                comments[1].comment shouldBe
                    "Second custom comment. \n > _Based on this repository's_ [custom instructions](https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md)"
                comments[2].comment shouldBe "Regular comment"
                comments[2].comment shouldNotContain "Based on this repository's"
            }
        }

        @Test
        fun `should preserve custom label after merging comments with same path and line`() {
            runTest {
                // Arrange
                // Mock the salSharedUtil to return a test href link
                every {
                    salSharedUtil.buildHrefLinkToFile(externalBBCRepoUrl, null, ".rovodev/.review-agent.md")
                } returns "https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md"

                // Mock salSharedUtil.validateAndExtractPullRequestDetails for createTransactionContext
                every {
                    salSharedUtil.validateAndExtractPullRequestDetails(externalBBCRepoUrl)
                } returns
                    mockk {
                        every { scm } returns "bitbucket"
                        every { domain } returns "bitbucket.org"
                        every { workspaceName } returns "someotherworkspace"
                    }

                // Mock bitbucketService.getWorkspaceIdFromWorkspaceSlug
                coEvery {
                    bitbucketService.getWorkspaceIdFromWorkspaceSlug("bitbucket.org", "someotherworkspace")
                } returns "{$DEV_AI_WORKSPACE_ID}"

                val autoreviewComments =
                    listOf(
                        ReviewComment(
                            id = "1",
                            comment = "First custom comment.",
                            line = 39,
                            path = "fileA.txt",
                            generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                        ),
                        ReviewComment(
                            id = "2",
                            comment = "Second custom comment.",
                            line = 39,
                            path = "fileA.txt",
                            generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                        ),
                    )

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = autoreviewComments,
                    )

                // Assert
                comments.size shouldBe 1 // Comments should be merged
                val mergedComment = comments[0]
                mergedComment.comment shouldContain
                    "* First custom comment. \n> _Based on this repository's_ [custom instructions](https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md)"
                mergedComment.comment shouldContain
                    "* Second custom comment. \n> _Based on this repository's_ [custom instructions](https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md)"
                mergedComment.path shouldBe "fileA.txt"
                mergedComment.line shouldBe 39
            }
        }

        @Test
        fun `Test mergeSamePathAndLineComments() merges comments with custom label`() {
            runTest {
                // Arrange
                // Mock the salSharedUtil to return a test href link
                every {
                    salSharedUtil.buildHrefLinkToFile(externalBBCRepoUrl, null, ".rovodev/.review-agent.md")
                } returns "https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md"

                // Mock salSharedUtil.validateAndExtractPullRequestDetails for createTransactionContext
                every {
                    salSharedUtil.validateAndExtractPullRequestDetails(externalBBCRepoUrl)
                } returns
                    mockk {
                        every { scm } returns "bitbucket"
                        every { domain } returns "bitbucket.org"
                        every { workspaceName } returns "someotherworkspace"
                    }

                coEvery {
                    bitbucketService.getWorkspaceIdFromWorkspaceSlug("bitbucket.org", "someotherworkspace")
                } returns "{$DEV_AI_WORKSPACE_ID}"

                // Arrange
                val comments =
                    listOf(
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 1",
                            path = "path/to/file1",
                            line = 5,
                            changeType = CodeReviewCommentChangeType.ADDED,
                            generatedBy = CodeReviewCommentGeneratedBy.CodeBugReviewCommentGenerator,
                        ),
                        ReviewComment(
                            id = "testId-2",
                            comment = "Comment 2",
                            path = "path/to/file1",
                            line = 5,
                            changeType = CodeReviewCommentChangeType.ADDED,
                            generatedBy = CodeReviewCommentGeneratedBy.CodePerformanceReviewCommentGenerator,
                        ),
                        ReviewComment(
                            id = "testId-3",
                            comment = "Comment 3 by customisation.",
                            path = "path/to/file1",
                            line = 5,
                            changeType = CodeReviewCommentChangeType.ADDED,
                            generatedBy = CodeReviewCommentGeneratedBy.CustomReviewCommentGenerator,
                        ),
                    )

                // Act
                val finalComments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = comments,
                    )

                // Assert
                finalComments.size shouldBe 1
                finalComments[0].comment shouldBe
                    "* Comment 1\n* Comment 2\n* Comment 3 by customisation. \n> _Based on this repository's_ [custom instructions](https://bitbucket.org/someotherworkspace/src/main/.rovodev/.review-agent.md)"
            }
        }

        @Test
        fun `should not add custom label for comments not generated by CustomReviewCommentGenerator`() {
            runTest {
                // Arrange
                val autoreviewComment =
                    ReviewComment(
                        id = TEST_AR_ID,
                        comment = "This is a regular review comment",
                        line = 39,
                        path = "fileA.txt",
                        generatedBy = CodeReviewCommentGeneratedBy.GeneralCodeReviewCommentGenerator,
                    )

                // Act
                val comments =
                    autoreviewCommentService.prepareCommentsForSending(
                        externalBBCRepoUrl,
                        comments = listOf(autoreviewComment),
                    )

                // Assert
                comments.size shouldBe 1
                comments[0].comment shouldBe "This is a regular review comment"
            }
        }

        @Test
        fun `should use commentFormatter mergeComments when feature gate enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewMergeCommentsSameGeneratorOnly(any()) } returns true

                autoreviewCommentService.prepareCommentsForSending(
                    TEST_PR_URL,
                    comments =
                        listOf(
                            ReviewComment(
                                id = "mergeCommentsExample1",
                                comment = "This is the real comment",
                                line = 39,
                                path = "fileA.txt",
                            ),
                        ),
                )

                coVerify(exactly = 1) { commentFormatter.mergeComments(match { it.single().id == "mergeCommentsExample1" }) }
            }

        @Test
        fun `should not use commentFormatter mergeComments when feature gate is not enabled`() =
            runTest {
                coEvery { featureService.isAutoreviewMergeCommentsSameGeneratorOnly(any()) } returns false

                autoreviewCommentService
                    .prepareCommentsForSending(
                        TEST_PR_URL,
                        comments =
                            listOf(
                                ReviewComment(
                                    id = TEST_AR_ID,
                                    comment = "This is the Original comment",
                                    line = 39,
                                    path = "fileA.txt",
                                ),
                            ),
                    )

                coVerify(exactly = 0) { commentFormatter.mergeComments(any()) }
            }

        @Nested
        inner class MergingComments {
            @Test
            fun `Test mergeSamePathAndLineComments() merges comments with the same path and line number`() {
                // Arrange
                val comments =
                    listOf(
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 1",
                            path = "path/to/file1",
                            line = 1,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 2",
                            path = "path/to/file1",
                            line = 1,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 3",
                            path = "path/to/file2",
                            line = 2,
                        ),
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
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 3",
                            path = "path/to/file2",
                            line = 2,
                        ),
                    )

                // Act
                val finalComments = autoreviewCommentService.mergeSamePathAndLineComments(comments)

                // Assert
                assertEquals(2, finalComments.size)
                assertEquals(expectedComments, finalComments)
            }

            @Test
            fun `Test mergeSamePathAndLineComments() merges comments and uses changeType with lowest ordinal value`() {
                // Arrange
                val comments =
                    listOf(
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 1",
                            path = "path/to/file1",
                            line = 1,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 2",
                            path = "path/to/file1",
                            line = 1,
                            changeType = CodeReviewCommentChangeType.UNCHANGED,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 3",
                            path = "path/to/file2",
                            line = 2,
                            changeType = CodeReviewCommentChangeType.REMOVED,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 4",
                            path = "path/to/file2",
                            line = 2,
                            changeType = CodeReviewCommentChangeType.ADDED,
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
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "* Comment 3\n* Comment 4",
                            path = "path/to/file2",
                            line = 2,
                            changeType = CodeReviewCommentChangeType.ADDED,
                        ),
                    )

                // Act
                val finalComments = autoreviewCommentService.mergeSamePathAndLineComments(comments)

                // Assert
                assertEquals(2, finalComments.size)
                assertEquals(expectedComments, finalComments)
            }

            @Test
            fun `Test mergeSamePathAndLineComments() doesn't merge comments with different paths`() {
                // Arrange
                val comments =
                    listOf(
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 1",
                            path = "path/to/file1",
                            line = 1,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 2",
                            path = "path/to/file2",
                            line = 1,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 3",
                            path = "path/to/file3",
                            line = 2,
                        ),
                    )

                // Act
                val finalComments = autoreviewCommentService.mergeSamePathAndLineComments(comments)

                // Assert
                assertEquals(3, finalComments.size)
                assertEquals(comments, finalComments)
            }

            @Test
            fun `Test mergeSamePathAndLineComments() doesn't merge comments with different line numbers`() {
                // Arrange
                val comments =
                    listOf(
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 1",
                            path = "path/to/file1",
                            line = 1,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 2",
                            path = "path/to/file2",
                            line = 1,
                        ),
                        ReviewComment(
                            id = TEST_AR_ID,
                            comment = "Comment 3",
                            path = "path/to/file3",
                            line = 2,
                        ),
                    )

                // Act
                val finalComments = autoreviewCommentService.mergeSamePathAndLineComments(comments)

                // Assert
                assertEquals(3, finalComments.size)
                assertEquals(comments, finalComments)
            }

            @Test
            fun `Test mergeSamePathAndLineComments() doesn't merge comments with emptyList()`() {
                // Act
                val expectedComments: List<AutoreviewItem> = emptyList()
                val finalComments = autoreviewCommentService.mergeSamePathAndLineComments(emptyList())

                // Assert
                assertEquals(0, finalComments.size)
                assertEquals(expectedComments, finalComments)
            }

            @Test
            fun `Test mergeSamePathAndLineComments() should add first CS only for two comments with a codeSuggestion`() {
                runTest {
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
                    val comments =
                        autoreviewCommentService.prepareCommentsForSending(
                            externalBBCRepoUrl,
                            comments = autoreviewComments,
                        )

                    // Assert
                    comments.size shouldBe 1
                    comments[0].comment shouldBe
                        """
                |* Comment with code suggestion
                |* Another comment with another code suggestion
                |```suggestion
                |This is the first code suggestion
                |```
                        """.trimMargin()
                }
            }
        }
    }

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

        @Test
        fun `throws exception if ari is not a Jira Issue Ari`() =
            runTest {
                assertThrows<IllegalArgumentException> {
                    autoreviewCommentService.postIssueComment(
                        issueAri = "not an ari",
                        authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                        comment = "I am a comment",
                    )
                }.message shouldBe "issueAri could not be parsed into JiraIssueARI"
            }

        @ParameterizedTest
        @ValueSource(strings = ["", "   "])
        fun `throws exception if comment is empty`(comment: String) =
            runTest {
                assertThrows<IllegalArgumentException> {
                    autoreviewCommentService.postIssueComment(
                        issueAri = "ari:cloud:jira:569c3671-ffcf-4e4b-af6c-31947292079d:issue/12056",
                        authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                        comment = comment,
                    )
                }.message shouldBe "comment must not be blank"
            }

        @Test
        fun `throws exception if uct cannot be minted`() =
            runTest {
                coEvery { idGatekeeperClient.mintUct(any()) } throws RuntimeException("Something went wrong")

                assertThrows<RuntimeException> {
                    autoreviewCommentService.postIssueComment(
                        issueAri = "ari:cloud:jira:569c3671-ffcf-4e4b-af6c-31947292079d:issue/12056",
                        authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                        comment = "This looks great",
                    )
                }.message shouldBe "Something went wrong"
            }

        @Test
        fun `throws exception when integration services client throws exception`() =
            runTest {
                coEvery {
                    integrationsServiceClient.createWorkItemComment(
                        any(),
                        any(),
                    )
                } throws
                    IntegrationsServiceException(
                        operation = "Something went wrong",
                        response = "Error response here",
                        status = 500,
                        exception = RuntimeException("Some kind of execption"),
                    )

                assertThrows<IntegrationsServiceException> {
                    autoreviewCommentService.postIssueComment(
                        issueAri = "ari:cloud:jira:569c3671-ffcf-4e4b-af6c-31947292079d:issue/12056",
                        authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                        comment = "This looks great",
                    )
                }
            }

        @Test
        fun `throws exception if response entities is null`() =
            runTest {
                coEvery {
                    integrationsServiceClient.createWorkItemComment(
                        any(),
                        any(),
                    )
                } returns
                    BaseIntegrationsServiceResponse(
                        entities = null,
                    )

                assertThrows<NoSuchElementException> {
                    autoreviewCommentService.postIssueComment(
                        issueAri = "ari:cloud:jira:569c3671-ffcf-4e4b-af6c-31947292079d:issue/12056",
                        authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                        comment = "This looks great",
                    )
                }.message shouldBe "No Comment entity found in response"
            }

        @Test
        fun `throws exception if response entities is empty list`() =
            runTest {
                coEvery {
                    integrationsServiceClient.createWorkItemComment(
                        any(),
                        any(),
                    )
                } returns
                    BaseIntegrationsServiceResponse(
                        entities = emptyList(),
                    )

                assertThrows<NoSuchElementException> {
                    autoreviewCommentService.postIssueComment(
                        issueAri = "ari:cloud:jira:569c3671-ffcf-4e4b-af6c-31947292079d:issue/12056",
                        authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                        comment = "This looks great",
                    )
                }.message shouldBe "No Comment entity found in response"
            }

        @Test
        fun `throws exception if entity does not contain url`() =
            runTest {
                coEvery {
                    integrationsServiceClient.createWorkItemComment(
                        any(),
                        any(),
                    )
                } returns
                    BaseIntegrationsServiceResponse(
                        entities =
                            listOf(
                                WorkItemComment(
                                    url = null,
                                ),
                            ),
                    )

                assertThrows<NullPointerException> {
                    autoreviewCommentService.postIssueComment(
                        issueAri = "ari:cloud:jira:569c3671-ffcf-4e4b-af6c-31947292079d:issue/12056",
                        authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                        comment = "This looks great",
                    )
                }.message shouldBe "No url found on comment entity"
            }

        @Test
        fun `returns url of created comment on success`() =
            runTest {
                coEvery {
                    integrationsServiceClient.createWorkItemComment(
                        any(),
                        any(),
                    )
                } returns
                    BaseIntegrationsServiceResponse(
                        entities =
                            listOf(
                                WorkItemComment(
                                    url = "https://example.atlassian.com/browse/12056?focusedCommentId=10011",
                                ),
                            ),
                    )

                autoreviewCommentService.postIssueComment(
                    issueAri = "ari:cloud:jira:569c3671-ffcf-4e4b-af6c-31947292079d:issue/12056",
                    authorAccountId = "712020:9a8ab90d-1759-820c-b1e6-12d6585223f9",
                    comment = "This looks great",
                ) shouldBe "https://example.atlassian.com/browse/12056?focusedCommentId=10011"
            }
    }

    @Nested
    inner class PostPreCheckErrorComment {
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

        @Test
        fun `postPreCheckErrorComment should post billing error comment when credit status is not OK`() {
            runTest {
                // Arrange
                val pullRequestUrl = URI.create("https://github.com/owner/repo/pull/123").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.INSUFFICIENT_CREDIT,
                                        message = "Not enough credits",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = pullRequestUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 1
                result[0].externalId shouldBe "2030"

                val commentsSlot = slot<SalModel.CreatePullRequestCommentRequest>()
                coVerify(exactly = 1) {
                    salService.createPrComment(capture(commentsSlot))
                }

                val comment = commentsSlot.captured
                comment.url shouldBe pullRequestUrl
                comment.comment shouldBe DefaultAutoreviewCommentService.BILLING_CHECK_ERROR
                comment.path shouldBe null // Must be PR level comment (no file path)
                comment.startTo shouldBe null // Must be PR level comment (no line number)
                comment.startFrom shouldBe null
                comment.to shouldBe null
                comment.from shouldBe null
            }
        }

        @Test
        fun `postPreCheckErrorComment should post billing error comment when multiple event types have credit issues`() {
            runTest {
                // Arrange
                val pullRequestUrl = URI.create("https://github.com/owner/repo/pull/124").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.INSUFFICIENT_CREDIT,
                                        message = "Not enough credits for main review",
                                    ),
                            ),
                        AutoreviewEventType.ACCEPTANCE_CRITERIA to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.FREE_LIMIT_EXCEEDED,
                                        message = "Free limit exceeded",
                                    ),
                            ),
                        AutoreviewEventType.CUSTOM to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.OK,
                                        message = "Credits available",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = pullRequestUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 1
                result[0].externalId shouldBe "2030"

                val commentsSlot = slot<SalModel.CreatePullRequestCommentRequest>()
                coVerify(exactly = 1) {
                    salService.createPrComment(capture(commentsSlot))
                }

                val comment = commentsSlot.captured
                comment.url shouldBe pullRequestUrl
                comment.comment shouldBe DefaultAutoreviewCommentService.BILLING_CHECK_ERROR
                comment.path shouldBe null
                comment.startTo shouldBe null
                comment.startFrom shouldBe null
                comment.to shouldBe null
                comment.from shouldBe null
            }
        }

        @Test
        fun `postPreCheckErrorComment should NOT post billing error comment when all event types have OK status`() {
            runTest {
                // Arrange
                val pullRequestUrl = URI.create(TEST_PR_URL).toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.OK,
                                        message = "Credits available",
                                    ),
                            ),
                        AutoreviewEventType.ACCEPTANCE_CRITERIA to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.OK,
                                        message = "Credits available",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = pullRequestUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 0

                coVerify(exactly = 0) {
                    salService.createPrComment(any())
                }
            }
        }

        @Test
        fun `postPreCheckErrorComment should NOT post billing error comment when event type user credits map is empty`() {
            runTest {
                // Arrange
                val pullRequestUrl = URI.create(TEST_PR_URL).toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits = emptyMap<AutoreviewEventType, UserCreditResult>()

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = pullRequestUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 0

                coVerify(exactly = 0) {
                    salService.createPrComment(any())
                }
            }
        }

        @Test
        fun `postPreCheckErrorComment should NOT post billing error comment when rejected reason is feature disabled`() {
            runTest {
                // Arrange
                val pullRequestUrl = URI.create(TEST_PR_URL).toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.FEATURE_DISABLED_PAID_ONLY,
                                        message = "FEATURE_DISABLED_PAID_ONLY",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = pullRequestUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 0

                coVerify(exactly = 0) {
                    salService.createPrComment(any())
                }
            }
        }

        @ParameterizedTest
        @EnumSource(CreditStatus::class, names = ["INSUFFICIENT_CREDIT", "FREE_LIMIT_EXCEEDED", "PAID_LIMIT_EXCEEDED"])
        fun `postPreCheckErrorComment should handle different non-OK credit statuses`(creditStatus: CreditStatus) {
            runTest {
                // Arrange
                val pullRequestUrl = URI.create("https://github.com/owner/repo/pull/125").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = creditStatus,
                                        message = "Credit issue: $creditStatus",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = pullRequestUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 1
                result[0].externalId shouldBe "2030"

                val commentsSlot = slot<SalModel.CreatePullRequestCommentRequest>()
                coVerify(exactly = 1) {
                    salService.createPrComment(capture(commentsSlot))
                }

                val comment = commentsSlot.captured
                comment.url shouldBe pullRequestUrl
                comment.comment shouldBe DefaultAutoreviewCommentService.BILLING_CHECK_ERROR
                comment.path shouldBe null
                comment.startTo shouldBe null
                comment.startFrom shouldBe null
                comment.to shouldBe null
                comment.from shouldBe null
            }
        }

        @Test
        fun `postPreCheckErrorComment should handle GitHub PR URL`() {
            runTest {
                // Arrange
                val githubPrUrl = URI.create("https://github.com/owner/repo/pull/123").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.INSUFFICIENT_CREDIT,
                                        message = "Not enough credits",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = githubPrUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 1
                result[0].externalId shouldBe "2030"

                val commentsSlot = slot<SalModel.CreatePullRequestCommentRequest>()
                coVerify(exactly = 1) {
                    salService.createPrComment(capture(commentsSlot))
                }

                val comment = commentsSlot.captured
                comment.url shouldBe githubPrUrl
                comment.comment shouldBe DefaultAutoreviewCommentService.BILLING_CHECK_ERROR
                comment.path shouldBe null
                comment.startTo shouldBe null
                comment.startFrom shouldBe null
                comment.to shouldBe null
                comment.from shouldBe null
            }
        }

        @Test
        fun `should NOT post billing error comment for Bitbucket repository when credit limit exceeded`() =
            runTest {
                // Arrange
                val bitbucketPrUrl = URI.create("https://bitbucket.org/workspace/repo/pull-requests/123").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.INSUFFICIENT_CREDIT,
                                        message = "Not enough credits",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = bitbucketPrUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldBe emptyList()

                // Verify that no comment was posted to SAL service
                coVerify(exactly = 0) {
                    salService.createPrComment(any())
                }
            }

        @Test
        fun `should post billing error comment for GitHub repository when daily limit exceeded`() =
            runTest {
                // Arrange
                val githubPrUrl = URI.create("https://github.com/owner/repo/pull/456").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.DAILY_LIMIT_EXCEEDED,
                                        message = "Daily limit exceeded",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = githubPrUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 1
                result[0].externalId shouldBe "2030"

                val commentsSlot = slot<SalModel.CreatePullRequestCommentRequest>()
                coVerify(exactly = 1) {
                    salService.createPrComment(capture(commentsSlot))
                }

                val capturedRequest = commentsSlot.captured
                capturedRequest.url shouldBe githubPrUrl
                capturedRequest.comment shouldBe DefaultAutoreviewCommentService.BILLING_CHECK_ERROR
            }

        @Test
        fun `should NOT post billing error comment for Bitbucket repository when daily limit exceeded`() =
            runTest {
                // Arrange
                val bitbucketPrUrl = URI.create("https://bitbucket.org/workspace/repo/pull-requests/456").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = CreditStatus.DAILY_LIMIT_EXCEEDED,
                                        message = "Daily limit exceeded",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = bitbucketPrUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldBe emptyList()
                coVerify(exactly = 0) {
                    salService.createPrComment(any())
                }
            }

        @ParameterizedTest
        @EnumSource(
            value = CreditStatus::class,
            names = ["DAILY_LIMIT_EXCEEDED", "MINUTE_LIMIT_EXCEEDED", "INSUFFICIENT_CREDIT", "FREE_LIMIT_EXCEEDED", "PAID_LIMIT_EXCEEDED"],
        )
        fun `should handle all credit limit statuses for GitHub repositories`(creditStatus: CreditStatus) =
            runTest {
                // Arrange
                val githubPrUrl = URI.create("https://github.com/owner/repo/pull/999").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = creditStatus,
                                        message = "Credit limit reached",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = githubPrUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldHaveSize 1
                coVerify(exactly = 1) {
                    salService.createPrComment(any())
                }
            }

        @ParameterizedTest
        @EnumSource(
            value = CreditStatus::class,
            names = ["DAILY_LIMIT_EXCEEDED", "MINUTE_LIMIT_EXCEEDED", "INSUFFICIENT_CREDIT", "FREE_LIMIT_EXCEEDED", "PAID_LIMIT_EXCEEDED"],
        )
        fun `should NOT post comments for all credit limit statuses for Bitbucket repositories`(creditStatus: CreditStatus) =
            runTest {
                // Arrange
                val bitbucketPrUrl = URI.create("https://bitbucket.org/workspace/repo/pull-requests/999").toURL()
                val accountId = AccountId.of(TEST_AUTHOR_ACCOUNT_ID)
                val cloudId = CloudIdLike.fromString(TEST_CLOUD_ID)
                val eventTypeUserCredits =
                    mapOf(
                        AutoreviewEventType.AUTOREVIEW_MAIN to
                            UserCreditResult(
                                creditResult =
                                    CreditResult(
                                        status = creditStatus,
                                        message = "Credit limit reached",
                                    ),
                            ),
                    )

                // Act
                val result =
                    autoreviewCommentService.postPreCheckErrorComment(
                        pullRequestUrl = bitbucketPrUrl,
                        accountId = accountId,
                        cloudId = cloudId,
                        eventTypeUserCredits = eventTypeUserCredits,
                    )

                // Assert
                result shouldBe emptyList()
                coVerify(exactly = 0) {
                    salService.createPrComment(any())
                }
            }
    }

    @Nested
    inner class PostGithubStatusComment {
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

        @Test
        fun `postStatusComment should post comment for GitHub PR`() {
            runTest {
                // Arrange
                val githubPrUrl = "https://github.com/owner/repo/pull/123"
                val statusMessage = "Workflow failed due to configuration error"
                // Act
                val result = autoreviewCommentService.postGithubStatusComment(githubPrUrl, statusMessage)

                // Assert
                result!!.externalId shouldBe "2030"

                val commentsSlot = slot<SalModel.CreatePullRequestCommentRequest>()
                coVerify(exactly = 1) {
                    salService.createPrComment(capture(commentsSlot))
                }

                val comment = commentsSlot.captured
                comment.url shouldBe URI.create(githubPrUrl).toURL()
                comment.comment shouldBe statusMessage
                comment.path shouldBe null // Must be PR level comment (no file path)
                comment.startTo shouldBe null // Must be PR level comment (no line number)
                comment.startFrom shouldBe null
                comment.to shouldBe null
                comment.from shouldBe null
            }
        }

        @Test
        fun `postStatusComment should return empty list for Bitbucket PR`() {
            runTest {
                // Arrange
                val bitbucketPrUrl = "https://bitbucket.org/workspace/repo/pull-requests/123"
                val statusMessage = "Workflow failed due to configuration error"

                // Act
                val result = autoreviewCommentService.postGithubStatusComment(bitbucketPrUrl, statusMessage)

                // Assert
                result shouldBe null

                coVerify(exactly = 0) {
                    salService.createPrComment(any())
                }
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should build GitHub 3LO Dance comment with redirectUrl`(isStaging: Boolean) =
            runTest {
                // Given
                val cloudId = CloudIdLike.fromString("test-cloud-id")
                val testTenantUrl = "https://test-tenant.atlassian.net"
                val expectedEncodedUrl = java.net.URLEncoder.encode("$testTenantUrl/rovodev/github-oauth-redirect", "UTF-8")
                every { outboundAuthContainerProperties.githubContainerId } returns "random-container-id-for-testing"
                every { tcsService.getCloudUrlFromCloudId(cloudId) } returns CloudURL("test-cloud-id", testTenantUrl, "test-tenant")

                // When
                val comment =
                    autoreviewCommentService.buildGitHub3LODanceComment(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                        cloudId = cloudId,
                        isStaging = isStaging,
                    )

                // Then
                comment shouldContain "link your GitHub account to your Atlassian account"
                comment shouldContain "random-container-id-for-testing"
                comment shouldContain "serviceKey=github"
                comment shouldContain "redirectUrl=$expectedEncodedUrl"

                if (isStaging) {
                    comment shouldContain "https://id.stg.internal.atlassian.com/outboundAuth/start"
                } else {
                    comment shouldContain "https://id.atlassian.com/outboundAuth/start"
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should build GitHub 3LO Dance comment without redirectUrl when tcsService returns null`(isStaging: Boolean) =
            runTest {
                // Given
                val cloudId = CloudIdLike.fromString("test-cloud-id")
                every { outboundAuthContainerProperties.githubContainerId } returns "random-container-id-for-testing"
                every { tcsService.getCloudUrlFromCloudId(cloudId) } returns null

                // When
                val comment =
                    autoreviewCommentService.buildGitHub3LODanceComment(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                        cloudId = cloudId,
                        isStaging = isStaging,
                    )

                // Then
                comment shouldContain "link your GitHub account to your Atlassian account"
                comment shouldContain "random-container-id-for-testing"
                comment shouldContain "serviceKey=github"
                comment shouldNotContain "redirectUrl"

                if (isStaging) {
                    comment shouldContain "https://id.stg.internal.atlassian.com/outboundAuth/start"
                } else {
                    comment shouldContain "https://id.atlassian.com/outboundAuth/start"
                }
            }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should build GitHub 3LO Dance comment without redirectUrl when tcsService throws exception`(isStaging: Boolean) =
            runTest {
                // Given
                val cloudId = CloudIdLike.fromString("test-cloud-id")
                every { outboundAuthContainerProperties.githubContainerId } returns "random-container-id-for-testing"
                every { tcsService.getCloudUrlFromCloudId(cloudId) } throws RuntimeException("TCS service unavailable")

                // When
                val comment =
                    autoreviewCommentService.buildGitHub3LODanceComment(
                        pullRequestUrl = "https://bitbucket.org/atlassian/devai-services/pull-requests/909",
                        cloudId = cloudId,
                        isStaging = isStaging,
                    )

                // Then
                comment shouldContain "link your GitHub account to your Atlassian account"
                comment shouldContain "random-container-id-for-testing"
                comment shouldContain "serviceKey=github"
                comment shouldNotContain "redirectUrl"

                if (isStaging) {
                    comment shouldContain "https://id.stg.internal.atlassian.com/outboundAuth/start"
                } else {
                    comment shouldContain "https://id.atlassian.com/outboundAuth/start"
                }
            }
    }
}
