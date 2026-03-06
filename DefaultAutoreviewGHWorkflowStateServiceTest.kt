package devai.modules.autoreview.service

import devai.modules.autoreview.model.AutoreviewProcessStatus
import devai.modules.autoreview.service.DefaultAutoreviewGHWorkflowStateService.Companion.STORE_BLOCK_END
import devai.modules.autoreview.service.DefaultAutoreviewGHWorkflowStateService.Companion.STORE_BLOCK_START
import devai.modules.sal.model.SalModel
import devai.modules.sal.service.SalService
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.User
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class DefaultAutoreviewGHWorkflowStateServiceTest {
    companion object {
        private const val TEST_WORKSPACE = "test-workspace"
        private const val TEST_REPOSITORY = "test-repository"
        private const val TEST_PR_ID = "123"
        private const val TEST_DESCRIPTION = "This is a test description"
    }

    private val salService = mockk<SalService>()
    private val featureService = mockk<DevAiCoreFeatureService>()
    private val user = User(atlassianAccountId = "user-id", cloudId = "test-cloud-id")

    private lateinit var service: DefaultAutoreviewGHWorkflowStateService

    @BeforeEach
    fun setUp() {
        service =
            DefaultAutoreviewGHWorkflowStateService(
                salService = salService,
                featureService = featureService,
            )
    }

    @Nested
    inner class StoreBlockReplacement {
        @Test
        fun `removes existing store block and replaces with new one`() =
            runTest {
                val descriptionWithStoreBlock =
                    """
                    Original description
                    <!-- Rovo Dev code review status -->
                    ---
                    Rovo Dev code review: <strong>Old status</strong>
                    Old message
                    <!-- /Rovo Dev code review status -->
                    """.trimIndent()

                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = descriptionWithStoreBlock,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                // Should contain the original description
                updatedDescription shouldContain "Original description"

                // Should not contain the old store block content
                updatedDescription shouldNotContain "Old status"
                updatedDescription shouldNotContain "Old message"

                // Should contain the new store block with the new status
                updatedDescription shouldContain "Rovo Dev has reviewed this pull request"
                updatedDescription shouldContain "Any suggestions or improvements have been posted as pull request comments."
                updatedDescription shouldContain STORE_BLOCK_START
                updatedDescription shouldContain STORE_BLOCK_END
            }
    }

    @Nested
    inner class StoreBlockAppending {
        @Test
        fun `appends store block when description has no existing store block`() =
            runTest {
                val descriptionWithoutStoreBlock = "This is a simple description without any store block"

                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.WORKFLOW_IN_PROGRESS,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = descriptionWithoutStoreBlock,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                // Should contain the original description
                updatedDescription shouldContain descriptionWithoutStoreBlock

                // Should contain the new store block at the end
                updatedDescription shouldContain STORE_BLOCK_START
                updatedDescription shouldContain STORE_BLOCK_END
                updatedDescription shouldContain "Rovo Dev is reviewing this pull request…"
                updatedDescription shouldContain "Refresh the page in a few minutes to see the results."
            }

        @Test
        fun `appends store block when description is empty`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.PROCESSING,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = "",
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                // Should contain the store block
                updatedDescription shouldContain STORE_BLOCK_START
                updatedDescription shouldContain STORE_BLOCK_END
                updatedDescription shouldContain "Rovo Dev is reviewing this pull request…"
            }
    }

    @Nested
    inner class FeatureGateHandling {
        @Test
        fun `returns null when feature gate is disabled`() =
            runTest {
                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns false

                val result =
                    service.updateWithProcessStatus(
                        status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                        workspace = TEST_WORKSPACE,
                        repositoryName = TEST_REPOSITORY,
                        pullRequestId = TEST_PR_ID,
                        description = TEST_DESCRIPTION,
                        user = user,
                    )

                result shouldBe null

                coVerify(exactly = 0) {
                    salService.updatePullRequestDetails(any())
                }
            }

        @Test
        fun `calls SAL service when feature gate is enabled`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                coVerify(exactly = 1) {
                    salService.updatePullRequestDetails(any())
                }
            }
    }

    @Nested
    inner class StatusMessages {
        @ParameterizedTest
        @EnumSource(
            value = AutoreviewProcessStatus::class,
            names = ["PROCESSING", "WORKFLOW_PENDING", "WORKFLOW_IN_PROGRESS"],
        )
        fun `in-progress statuses show reviewing message`(status: AutoreviewProcessStatus) =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = status,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description
                updatedDescription shouldContain "Rovo Dev is reviewing this pull request…"
                updatedDescription shouldContain "Refresh the page in a few minutes to see the results."
            }

        @Test
        fun `completion status shows completed message`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description
                updatedDescription shouldContain "Rovo Dev has reviewed this pull request"
                updatedDescription shouldContain "Any suggestions or improvements have been posted as pull request comments."
            }

        @ParameterizedTest
        @EnumSource(
            value = AutoreviewProcessStatus::class,
            names = [
                "ERROR_GENERIC",
                "ERROR_REPOSITORY_RATE_LIMIT",
                "WORKFLOW_FAILURE",
                "UNKNOWN",
            ],
        )
        fun `generic error statuses show error message`(status: AutoreviewProcessStatus) =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = status,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev couldn't review this pull request"
                updatedDescription shouldContain "Something went wrong while reviewing this pull request."
            }

        @ParameterizedTest
        @EnumSource(
            value = AutoreviewProcessStatus::class,
            names = [
                "BILLING_OUT_OF_CREDIT",
                "BILLING_FREE_TIER_EXHAUSTED",
                "BILLING_PAID_LIMIT_EXCEEDED",
            ],
        )
        fun `show GH billing error status`(status: AutoreviewProcessStatus) =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = status,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Out of Rovo Dev credits"
                updatedDescription shouldContain "You've used all your Rovo Dev credits, so Rovo Dev can't review your pull requests."
            }

        @Test
        fun `show GH user unauthorized error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.USER_NOT_AUTHORIZED,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev couldn't review this pull request"
                updatedDescription shouldContain "Upgrade to Rovo Dev Standard to continue using code review."
            }

        @Test
        fun `show GH beta deprecation error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.BILLING_ROVO_DEV_AGENTS_BETA_DEPRECATED,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev Agents beta no longer available"
                updatedDescription shouldContain "To resume code reviews, an organization admin must switch to Rovo Dev Standard."
            }

        @Test
        fun `show GH pr size limit error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.ERROR_PR_SIZE_LIMIT,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev couldn't review this pull request because it's too large"
                updatedDescription shouldContain "Try splitting it into smaller pull requests."
            }

        @Test
        fun `show repository size limit error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.ERROR_REPOSITORY_SIZE_LIMIT,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev can't review this pull request because the repository is too large"
                updatedDescription shouldContain "Rovo Dev can't review pull requests in repositories larger than 20GB."
            }

        @Test
        fun `show token limit error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.ERROR_TOKEN_LIMIT,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev token limit exceeded"
                updatedDescription shouldContain "The code review could not be completed because you have exceeded your token limit."
            }

        @Test
        fun `show unconfigured site association error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.UNCONFIGURED_SITE_ASSOCIATION,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "No Rovo Dev credits site found"
                updatedDescription shouldContain "An app admin needs to finish setting up Rovo Dev."
            }

        @Test
        fun `show unconfigured site rovo dev error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.UNCONFIGURED_SITE_ROVO_DEV,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev not available on your Jira site"
                updatedDescription shouldContain "An admin needs to finish setting up Rovo Dev."
            }

        @Test
        fun `show setting disabled workspace error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.SETTING_DISABLED_WORKSPACE,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev not activated in this GitHub organization"
                updatedDescription shouldContain "An organization admin needs to activate Rovo Dev."
            }

        @Test
        fun `show setting disabled repository error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.SETTING_DISABLED_REPOSITORY,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev not activated in this repository"
                updatedDescription shouldContain "An app admin needs to activate Rovo Dev."
            }

        @Test
        fun `show setting disabled rovo dev org error status`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.SETTING_DISABLED_ROVO_DEV_ORG,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val updatedDescription = requestSlot.captured.description

                updatedDescription shouldContain "Rovo Dev not activated in your linked Atlassian organization"
                updatedDescription shouldContain "An Atlassian organization admin needs to activate Rovo Dev."
            }

        @Test
        fun `request includes correct parameters`() =
            runTest {
                coEvery {
                    salService.updatePullRequestDetails(any())
                } returns mockk()

                coEvery {
                    featureService.isAutoreviewGitHubStatefulContainerEnabled(any())
                } returns true

                service.updateWithProcessStatus(
                    status = AutoreviewProcessStatus.WORKFLOW_COMPLETED,
                    workspace = TEST_WORKSPACE,
                    repositoryName = TEST_REPOSITORY,
                    pullRequestId = TEST_PR_ID,
                    description = TEST_DESCRIPTION,
                    user = user,
                )

                val requestSlot = slot<SalModel.UpdatePullRequestRequest>()
                coVerify {
                    salService.updatePullRequestDetails(capture(requestSlot))
                }

                val request = requestSlot.captured
                request.workspace shouldBe TEST_WORKSPACE
                request.repositoryName shouldBe TEST_REPOSITORY
                request.pullRequestId shouldBe TEST_PR_ID
            }
    }
}
