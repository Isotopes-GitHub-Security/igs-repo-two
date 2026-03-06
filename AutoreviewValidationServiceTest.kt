package devai.modules.autoreview.service

import com.atlassian.ari.principled.ARI
import com.atlassian.ari.principled.CloudIdLike
import com.atlassian.ari.principled.devai.DevaiWorkspaceARI
import com.atlassian.ari.principled.graph.GraphWorkspaceARI
import com.atlassian.usercontext.api.AccountId
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import devai.modules.autoreview.config.redis.AutoreviewConfigWhitelistUrls
import devai.modules.autoreview.queue.model.PRState
import devai.modules.checks.service.EntitlementChecksService
import devai.modules.checks.service.UserPermissionsService
import devai.modules.checks.service.entitlements.SiteEntitlementService
import devai.modules.settings.model.AutoreviewAutomaticTriggerType
import devai.modules.settings.model.AutoreviewDevAIWorkSpaceSettingAttributes
import devai.modules.settings.model.AutoreviewRepositorySettingAttributes
import devai.modules.settings.model.AutoreviewWorkspaceSettingAttributes
import devai.modules.settings.model.SettingValue
import devai.modules.settings.service.AutoreviewSettingsService
import devai.modules.shared.client.dss.DssGatewayClient
import devai.modules.shared.client.idgatekeeper.ResourceType
import devai.modules.shared.client.streamhub.Association
import devai.modules.shared.client.streamhub.AutoreviewEventType
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.AVI_DEVOPS_CREATED_PULL_REQUEST
import devai.modules.shared.model.AVI_DEVOPS_UPDATED_PULL_REQUEST
import devai.modules.shared.model.CodeReviewExperience
import devai.modules.shared.model.CreditResult
import devai.modules.shared.model.CreditStatus
import devai.modules.shared.model.DssCloudIdInstallation
import devai.modules.shared.model.User
import devai.modules.shared.model.UserContext
import devai.modules.shared.model.UserCreditResult
import devai.modules.shared.model.settings.SettingContainerType
import devai.modules.shared.model.tcs.WorkspaceId
import devai.modules.shared.redis.DataDepotPRDedupService
import devai.modules.shared.service.tcs.TcsService
import devai.modules.shared.usage.metering.RovoDevCTALinks
import devai.modules.tenant.model.TransactionContext
import devai.modules.tenant.model.WorkspaceContext
import devai.modules.users.model.graphql.DevAiUser
import io.atlassian.tcs.model.cloud.ActivationIds
import io.atlassian.tcs.model.cloud.CloudURL
import io.atlassian.tcs.model.organization.LinkedOrg
import io.atlassian.tcs.model.organization.OrgLink
import io.atlassian.tcs.model.organization.OrgLinks
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoreviewValidationServiceTest {
    private var configService = mockk<AutoreviewConfigService>(relaxed = true)
    private var autoreviewSettingsService = mockk<AutoreviewSettingsService>(relaxed = true)
    private var tcsService = mockk<TcsService>(relaxed = true)
    private val objectMapper = jacksonObjectMapper()
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val dataDepotPRDedupService = mockk<DataDepotPRDedupService>(relaxed = true)
    private val dssGatewayClient = mockk<DssGatewayClient>(relaxed = true)
    private val siteEntitlementService = mockk<SiteEntitlementService>(relaxed = true)
    private val entitlementChecksService = mockk<EntitlementChecksService>(relaxed = true)
    private val userPermissionsService = mockk<UserPermissionsService>(relaxed = true)
    private val rovoDevCTALinks = mockk<RovoDevCTALinks>(relaxed = true)
    val subject =
        DefaultAutoreviewValidationService(
            autoreviewConfigService = configService,
            autoreviewSettingsService = autoreviewSettingsService,
            tcsService = tcsService,
            objectMapper = objectMapper,
            featureService = featureService,
            dataDepotPRDedupService = dataDepotPRDedupService,
            dssGatewayClient = dssGatewayClient,
            siteEntitlementService = siteEntitlementService,
            entitlementChecksService = entitlementChecksService,
            userPermissionsService = userPermissionsService,
            rovoDevCTALinks = rovoDevCTALinks,
        )

    companion object {
        const val TEST_REPO_URL_IN_WHITELIST = "https://bitbucket.org/atlassian/includedrepo"
        const val TEST_BITBUCKET_EXTERNAL_REPO_URL = "https://bitbucket.org/workspace/testRepo"
        const val TEST_BITBUCKET_INTERNAL_REPO_URL = "https://bitbucket.org/atlassian/internalrepo"
        const val TEST_GITHUB_REPO_URL = "https://github.com/githubOrg/testRepo"
        const val TEST_GITHUB_EXTERNAL_REPO_URL = "https://github.com/external-org/repo-1"
        const val TEST_BITBUCKET_REPOSITORY_UUID = "{6da1df36-3e65-11ea-acc2-128b42819424}"
        const val TEST_BITBUCKET_WORKSPACE_UUID = "{7da1df36-3e65-11ea-acc2-128b42819428}"
        const val TEST_BITBUCKET_INTERNAL_PULL_REQUEST_URL =
            "https://bitbucket.org/atlassian/internalrepo/pull-requests/1234"
        const val TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL =
            "https://bitbucket.org/workspace/testRepo/pull-requests/1234"
        val CLOUD_ID = CloudIdLike.fromString("922168f0-256f-49e0-ac04-4db48b68d2ea")
        val ACCOUNT_ID = AccountId.of("557058:5f3f4e1e-7f8e-4a2d-8c6b-1c3e5f6a7b8c")
        const val DEVAI_WORKSPACE_ID_STRING = "3f3f4e1e-7f8e-4a2d-8c6b-1c3e5f6a7b8c"
    }

    @BeforeEach
    fun setUp() {
        coEvery { configService.getOnboardedRepoUrls() } returns
            AutoreviewConfigWhitelistUrls(
                urls =
                    setOf(
                        "https://bitbucket.org/atlassian/devai-services",
                        "https://bitbucket.org/customerworkspace/devai-services",
                        "https://github.com/orgtest/onboardrepo",
                        TEST_BITBUCKET_EXTERNAL_REPO_URL,
                        TEST_REPO_URL_IN_WHITELIST,
                    ),
                lastUpdatedTime = OffsetDateTime.now(),
            )
    }

    @Test
    fun `getOnboardedRepoUrls is called when checking accepted repos`() {
        val repoUrl = URI.create("https://bitbucket.org/customerworkspace/DEVai-services").toURL()

        runTest {
            val isAccepted =
                subject.isRepoAccepted(repoUrl, TEST_BITBUCKET_WORKSPACE_UUID, TEST_BITBUCKET_REPOSITORY_UUID)
            assertEquals(true, isAccepted)
        }

        coVerify(exactly = 1) { configService.getOnboardedRepoUrls() }
    }

    @Test
    fun `repoUrl is accepted with uppercase predefined onboarded Url`() {
        coEvery { configService.getOnboardedRepoUrls() } returns
            AutoreviewConfigWhitelistUrls(
                urls = setOf("https://bitbucket.org/workspace/DEVAI-SERVICES"),
                lastUpdatedTime = OffsetDateTime.now(),
            )
        val repoUrl = URI.create("https://bitbucket.org/workspace/devai-services").toURL()

        runTest {
            val isAccepted =
                subject.isRepoAccepted(repoUrl, TEST_BITBUCKET_WORKSPACE_UUID, TEST_BITBUCKET_REPOSITORY_UUID)
            assertEquals(true, isAccepted)
        }
    }

    @Test
    fun `isPullRequestLoadTest detects pr belongs to load test correctly`() {
        val pullRequestUrl = URI.create("https://bitbucket.org/ttran4repo/rovo-autoreview/pull-requests/10").toURL()
        assertTrue { subject.isPullRequestForLoadTest(pullRequestUrl) }
        val githubPrUrl = URI.create("https://github.com/autobottest/autoreview-load-test/pull/3").toURL()
        assertTrue { subject.isPullRequestForLoadTest(githubPrUrl) }
    }

    @Nested
    inner class IsRepoAccepted {
        @Test
        fun `Return true if repo belongs to (non-atlassian) workspace but onboarded in whitelist`() {
            val repoUrl = URI.create(TEST_BITBUCKET_EXTERNAL_REPO_URL).toURL()

            runTest {
                val isAccepted =
                    subject.isRepoAccepted(repoUrl, TEST_BITBUCKET_WORKSPACE_UUID, TEST_BITBUCKET_REPOSITORY_UUID)
                assertEquals(true, isAccepted)
            }
        }

        @Test
        fun `Return false if repository is non-atlassian workspace, but also not in whitelist repo`() {
            val repoUrl = URI.create("https://bitbucket.org/workspace/repo").toURL()
            runTest {
                val isAccepted =
                    subject.isRepoAccepted(repoUrl, TEST_BITBUCKET_WORKSPACE_UUID, TEST_BITBUCKET_REPOSITORY_UUID)
                assertEquals(false, isAccepted)
            }
        }

        @Test
        fun `Return true if repository is from Github and already onboarded`() {
            val repoUrl = URI.create("https://github.com/orgtest/onboardrepo").toURL()
            runTest {
                val isAccepted = subject.isRepoAccepted(repoUrl, "orgTest", "onboardrepo")
                assertEquals(true, isAccepted)
            }
        }

        @Test
        fun `Return false if repository is from Github but NOT onboarded`() {
            val repoUrl = URI.create("https://github.com/orgtest/nononboardedrepo").toURL()
            runTest {
                val isAccepted = subject.isRepoAccepted(repoUrl, "orgtest", "nononboardedrepo")
                assertEquals(false, isAccepted)
            }
        }
    }

    @Nested
    inner class IsPullRequestReviewable {
        @Test
        fun `PR is not reviewable if PR state is not known`() {
            subject.isPullRequestReviewable(null) shouldBe false
        }

        @Test
        fun `PR is reviewable if PR state is OPEN`() {
            subject.isPullRequestReviewable(PRState.OPEN) shouldBe true
        }

        @ParameterizedTest
        @EnumSource(value = PRState::class, mode = EnumSource.Mode.EXCLUDE, names = ["OPEN"])
        fun `PR is not reviewable when PR state is not OPEN`(state: PRState) {
            subject.isPullRequestReviewable(state) shouldBe false
        }
    }

    @Nested
    inner class DuplicatePREventCheck {
        @Test
        fun `should return true if jira site and bbc workspace are in the predefined list`() {
            val ariGraphWorkspace = "ari:cloud:graph::workspace/287380ae-085e-11eb-a62e-0a77f3f45304"
            val isDuplicatedEvent =
                subject.isJiraSiteAnBBCWorkspaceCreatesDuplicateEvent(ariGraphWorkspace, "atlassian")
            assertEquals(true, isDuplicatedEvent)
        }

        @Test
        fun `should return false if jira site is not in the predefined list`() {
            val ariGraphWorkspace = "ari:cloud:graph::workspace/997380ae-085e-11eb-a62e-0a77f3f45399"
            val isDuplicatedEvent =
                subject.isJiraSiteAnBBCWorkspaceCreatesDuplicateEvent(ariGraphWorkspace, "atlassian")
            assertEquals(false, isDuplicatedEvent)
        }

        @Test
        fun `should return false if bbc workspace slug is not in the predefined list`() {
            val ariGraphWorkspace = "ari:cloud:graph::workspace/287380ae-085e-11eb-a62e-0a77f3f45304"
            val isDuplicatedEvent = subject.isJiraSiteAnBBCWorkspaceCreatesDuplicateEvent(ariGraphWorkspace, "testRepo")
            assertEquals(false, isDuplicatedEvent)
        }
    }

    @Nested
    inner class GithubAutoreviewRunBySettings {
        @Test
        fun `Repo is NOT opted-in when workspace does not have settings`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.WORKSPACE,
                    TEST_GITHUB_EXTERNAL_REPO_URL.substringBeforeLast("/"),
                )
            } returns null

            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_GITHUB_EXTERNAL_REPO_URL,
                        null,
                        null,
                    )
                assertEquals(false, isAccepted)
            }
        }

        @Test
        fun `Repo is NOT opted-in when workspace has not yet activated`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.WORKSPACE,
                    TEST_GITHUB_EXTERNAL_REPO_URL.substringBeforeLast("/"),
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(false),
                    OffsetDateTime.now(),
                )

            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_GITHUB_EXTERNAL_REPO_URL,
                        null,
                        null,
                    )
                assertEquals(false, isAccepted)
            }
        }

        @Test
        fun `Repo is NOT opted-in when workspace is activated, and repo does not have setting`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.WORKSPACE,
                    TEST_GITHUB_EXTERNAL_REPO_URL.substringBeforeLast("/"),
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )

            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.REPOSITORY,
                    TEST_GITHUB_EXTERNAL_REPO_URL,
                )
            } returns null

            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_GITHUB_EXTERNAL_REPO_URL,
                        null,
                        null,
                    )
                assertEquals(false, isAccepted)
            }
        }

        @Test
        fun `Repo is opted-in when workspace is activated and repo is have setting AR enabled`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.WORKSPACE,
                    TEST_GITHUB_EXTERNAL_REPO_URL.substringBeforeLast("/"),
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )

            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.REPOSITORY,
                    TEST_GITHUB_EXTERNAL_REPO_URL,
                )
            } returns
                SettingValue(
                    AutoreviewRepositorySettingAttributes(true),
                    OffsetDateTime.now(),
                )

            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_GITHUB_EXTERNAL_REPO_URL,
                        null,
                        null,
                    )
                assertEquals(true, isAccepted)
            }
        }

        @Test
        fun `Repo is NOT opted-in when workspace is activated and repo is have setting AR disabled`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.WORKSPACE,
                    TEST_GITHUB_EXTERNAL_REPO_URL.substringBeforeLast("/"),
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.REPOSITORY,
                    TEST_GITHUB_EXTERNAL_REPO_URL,
                )
            } returns
                SettingValue(
                    AutoreviewRepositorySettingAttributes(false),
                    OffsetDateTime.now(),
                )
            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_GITHUB_EXTERNAL_REPO_URL,
                        null,
                        null,
                    )
                assertEquals(false, isAccepted)
            }
        }

        @Test
        fun `Repo is opted-in based on onboardedList when there is exception getting autoreview_settings`() {
            val repoUrl = URI.create("https://bitbucket.org/customerworkspace/DEVai-services").toURL()
            coEvery { autoreviewSettingsService.getWorkspaceAutoreviewSettings(any(), any()) } throws Exception()
            runTest {
                val isAccepted =
                    subject.isRepoAccepted(repoUrl, TEST_BITBUCKET_WORKSPACE_UUID, TEST_BITBUCKET_REPOSITORY_UUID)
                assertEquals(true, isAccepted)
            }
            coVerify(exactly = 1) { configService.getOnboardedRepoUrls() }
        }
    }

    @Nested
    inner class BitbucketAutoreviewRunBySettings {
        @Test
        fun `return true when workspace and repo with ARI have setting on`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    "ari:cloud:bitbucket::workspace/${TEST_BITBUCKET_WORKSPACE_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )

            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.BITBUCKET_REPOSITORY,
                    "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns
                SettingValue(
                    AutoreviewRepositorySettingAttributes(true),
                    OffsetDateTime.now(),
                )

            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_BITBUCKET_EXTERNAL_REPO_URL,
                        TEST_BITBUCKET_WORKSPACE_UUID,
                        TEST_BITBUCKET_REPOSITORY_UUID,
                    )
                assertEquals(true, isAccepted)
            }
        }

        @Test
        fun `return true when repoUrl is not bitbucket`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.WORKSPACE,
                    TEST_GITHUB_REPO_URL.substringBeforeLast("/"),
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )

            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.REPOSITORY,
                    TEST_GITHUB_REPO_URL,
                )
            } returns
                SettingValue(
                    AutoreviewRepositorySettingAttributes(true),
                    OffsetDateTime.now(),
                )

            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_GITHUB_REPO_URL,
                        "orgTest",
                        "testRepo",
                    )
                assertEquals(true, isAccepted)
                coVerify(exactly = 0) {
                    autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                        SettingContainerType.BITBUCKET_WORKSPACE,
                        any(),
                    )
                }
                coVerify(exactly = 0) {
                    autoreviewSettingsService.getRepositoryAutoreviewSettings(
                        SettingContainerType.BITBUCKET_REPOSITORY,
                        any(),
                    )
                }
            }
        }

        @Test
        fun `return true when workspace is activated and internal atlassian repo without setting`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    "ari:cloud:bitbucket::workspace/${TEST_BITBUCKET_WORKSPACE_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )

            // no settings for the repo
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.BITBUCKET_REPOSITORY,
                    "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns null

            // run test for atlassian repo
            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_BITBUCKET_INTERNAL_REPO_URL,
                        TEST_BITBUCKET_WORKSPACE_UUID,
                        TEST_BITBUCKET_REPOSITORY_UUID,
                    )
                assertEquals(true, isAccepted)
            }
        }

        @Test
        fun `return false when workspace is activated and non-atlassian repo without setting`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    "ari:cloud:bitbucket::workspace/${TEST_BITBUCKET_WORKSPACE_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )

            // no settings for the non-atlassian repo
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.BITBUCKET_REPOSITORY,
                    "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns null

            // run test for atlassian repo
            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_BITBUCKET_EXTERNAL_REPO_URL,
                        TEST_BITBUCKET_WORKSPACE_UUID,
                        TEST_BITBUCKET_REPOSITORY_UUID,
                    )
                assertEquals(false, isAccepted)
            }
        }

        @Test
        fun `return false when workspace is activated and internal atlassian repo is opt-out setting`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    "ari:cloud:bitbucket::workspace/${TEST_BITBUCKET_WORKSPACE_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns
                SettingValue(
                    AutoreviewWorkspaceSettingAttributes(true),
                    OffsetDateTime.now(),
                )

            // no settings for the repo
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.BITBUCKET_REPOSITORY,
                    "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns
                SettingValue(
                    AutoreviewRepositorySettingAttributes(false),
                    OffsetDateTime.now(),
                )

            // run test for atlassian repo
            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_BITBUCKET_INTERNAL_REPO_URL,
                        TEST_BITBUCKET_WORKSPACE_UUID,
                        TEST_BITBUCKET_REPOSITORY_UUID,
                    )
                assertEquals(false, isAccepted)
            }
        }

        @Test
        fun `return false when workspace and internal atlassian repo without setting`() {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    SettingContainerType.BITBUCKET_WORKSPACE,
                    "ari:cloud:bitbucket::workspace/${TEST_BITBUCKET_WORKSPACE_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns null

            // no settings for the repo
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.BITBUCKET_REPOSITORY,
                    "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns null

            // run test for atlassian repo
            runTest {
                val isAccepted =
                    subject.isRepoOptInAutoreviewBySettings(
                        TEST_BITBUCKET_INTERNAL_REPO_URL,
                        TEST_BITBUCKET_WORKSPACE_UUID,
                        TEST_BITBUCKET_REPOSITORY_UUID,
                    )
                assertEquals(false, isAccepted)
            }
        }
    }

    @Nested
    inner class GetCloudIdFromGraphWorkspaceAri {
        private val workspaceId = UUID.randomUUID().toString()
        private val graphWorkspaceAri = ARI.valueOf("ari:cloud:graph::workspace/$workspaceId")
        private val cloudId = CloudIdLike.fromString("922168f0-256f-49e0-ac04-4db48b68d2ea")

        @Test
        fun `should return cloudId from graph workspace ARI`() =
            runTest {
                val workspaceIdSlot = slot<WorkspaceId>()
                coEvery { tcsService.getCloudIdFromWorkspaceId(any()) } returns cloudId
                subject.getCloudIdByGraphWorkspaceAri(graphWorkspaceAri) shouldBe cloudId
                coVerify(exactly = 1) { tcsService.getCloudIdFromWorkspaceId(capture(workspaceIdSlot)) }
                workspaceIdSlot.captured shouldBe WorkspaceId(workspaceId)
            }
    }

    @Nested
    inner class IsAcceptanceCriteriaEnabled {
        private val dogfoodingRepo = "https://bitbucket.org/atlassian/devai-services"
        private val dogfoodingPr = "https://bitbucket.org/atlassian/devai-services/pull-requests/1"

        @CsvSource(
            "https://github.com/orgtest/org-repo, https://github.com/orgtest/org-repo/pull/5, true, false",
            "https://bitbucket.org/external/test-repo, https://bitbucket.org/external/test-repo/pull-requests/2, false, true",
            "https://bitbucket.org/atlassian/devai-services, https://bitbucket.org/atlassian/devai-services/pull-requests/1, false, false",
        )
        @ParameterizedTest
        fun `should fetch ac settings`(
            repoUrl: String,
            pullRequestUrl: String,
        ) = runTest {
            val url = URI.create(repoUrl).toURL()
            val isBitbucket = repoUrl.startsWith("https://bitbucket.org/")
            val repoId = if (isBitbucket) TEST_BITBUCKET_REPOSITORY_UUID else ""
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    any(),
                    any(),
                )
            } returns SettingValue(AutoreviewRepositorySettingAttributes(), OffsetDateTime.now())

            subject.isAcceptanceCriteriaEnabled(
                url,
                repoId,
                AVI_DEVOPS_CREATED_PULL_REQUEST,
                pullRequestUrl,
            )

            coVerify(exactly = 1) {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    if (isBitbucket) SettingContainerType.BITBUCKET_REPOSITORY else SettingContainerType.REPOSITORY,
                    if (isBitbucket) {
                        "ari:cloud:bitbucket::repository/${
                            repoId.replace(
                                Regex("[{}]"),
                                "",
                            )
                        }"
                    } else {
                        repoUrl
                    },
                )
            }
            coVerify(exactly = 0) {
                autoreviewSettingsService.setAutoreviewSettings(
                    any(),
                    any(),
                    any(),
                )
            }
        }

        @Test
        fun `should return true and update when all settings are null and is internal repo`() =
            runTest {
                val repoUrl = URI.create("https://bitbucket.org/atlassian/devai-services").toURL()
                val pullRequestUrl = "https://bitbucket.org/other/atlassian/pull-requests/1"
                val repoSettingValue = null
                coEvery {
                    autoreviewSettingsService.getRepositoryAutoreviewSettings(
                        any(),
                        any(),
                    )
                } returns SettingValue(repoSettingValue, OffsetDateTime.now())
                coEvery {
                    autoreviewSettingsService.setAutoreviewSettings(
                        any(),
                        any(),
                        any(),
                    )
                } returns SettingValue(repoSettingValue, OffsetDateTime.now())

                val result =
                    subject.isAcceptanceCriteriaEnabled(
                        repoUrl,
                        TEST_BITBUCKET_REPOSITORY_UUID,
                        AVI_DEVOPS_CREATED_PULL_REQUEST,
                        pullRequestUrl,
                    )

                result shouldBe true
                coVerify(exactly = 1) {
                    autoreviewSettingsService.setAutoreviewSettings(
                        SettingContainerType.BITBUCKET_REPOSITORY,
                        "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                        AutoreviewRepositorySettingAttributes(
                            autoreview_enabled = true,
                            autoreview_acceptance_criteria_enabled = true,
                            autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        ),
                    )
                }
            }

        @Test
        fun `should return true and update when ac settings are null`() =
            runTest {
                val repoUrl = URI.create("https://bitbucket.org/customerworkspace/devai-services").toURL()
                val pullRequestUrl = "https://bitbucket.org/customerworkspace/devai-services/pull-requests/1"
                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = null,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                    )
                coEvery {
                    autoreviewSettingsService.getRepositoryAutoreviewSettings(
                        any(),
                        any(),
                    )
                } returns SettingValue(repoSettingValue, OffsetDateTime.now())
                coEvery {
                    autoreviewSettingsService.setAutoreviewSettings(
                        any(),
                        any(),
                        any(),
                    )
                } returns SettingValue(repoSettingValue, OffsetDateTime.now())

                val result =
                    subject.isAcceptanceCriteriaEnabled(
                        repoUrl,
                        TEST_BITBUCKET_REPOSITORY_UUID,
                        AVI_DEVOPS_CREATED_PULL_REQUEST,
                        pullRequestUrl,
                    )

                result shouldBe true
                coVerify(exactly = 1) {
                    autoreviewSettingsService.setAutoreviewSettings(
                        SettingContainerType.BITBUCKET_REPOSITORY,
                        "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                        AutoreviewRepositorySettingAttributes(
                            autoreview_enabled = true,
                            autoreview_acceptance_criteria_enabled = true,
                            autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        ),
                    )
                }
            }

        @CsvSource(
            "avi:devops:created:pull-request, true, true, true",
            "avi:devops:created:pull-request, true, false, true",
            "avi:devops:created:pull-request, false, true, false",
            "avi:devops:created:pull-request, false, false, false",
            "avi:devops:updated:pull-request, true, true, true",
            "avi:devops:updated:pull-request, true, false, false",
            "avi:devops:updated:pull-request, false, true, false",
            "avi:devops:updated:pull-request, false, false, false",
        )
        @ParameterizedTest
        fun `should return correct ac setting based on event type`(
            eventType: String,
            isAcEnabled: Boolean,
            isCommitTriggerEnabled: Boolean,
            expectedResult: Boolean,
        ) = runTest {
            val repoUrl = URI.create(dogfoodingRepo).toURL()
            val repoSettingValue =
                AutoreviewRepositorySettingAttributes(
                    autoreview_enabled = true,
                    autoreview_acceptance_criteria_enabled = isAcEnabled,
                    autoreview_acceptance_criteria_commit_trigger_enabled = isCommitTriggerEnabled,
                )
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    SettingContainerType.BITBUCKET_REPOSITORY,
                    "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                )
            } returns SettingValue(repoSettingValue, OffsetDateTime.now())

            val result =
                subject.isAcceptanceCriteriaEnabled(
                    repoUrl,
                    TEST_BITBUCKET_REPOSITORY_UUID,
                    eventType,
                    dogfoodingPr,
                )
            result shouldBe expectedResult
        }
    }

    @Nested
    inner class AutoreviewSettings {
        val bitbucketSettingsContainers =
            AutoreviewSettingsContainers(
                SettingContainerType.BITBUCKET_REPOSITORY,
                "ari:cloud:bitbucket::repository/${TEST_BITBUCKET_REPOSITORY_UUID.replace(Regex("[{}]"), "")}",
                SettingContainerType.BITBUCKET_WORKSPACE,
                "ari:cloud:bitbucket::workspace/${TEST_BITBUCKET_WORKSPACE_UUID.replace(Regex("[{}]"), "")}",
                SettingContainerType.DEVAI_WORKSPACE,
                "ari:cloud:devai::workspace/$DEVAI_WORKSPACE_ID_STRING",
            )

        @BeforeEach
        fun setUp() {
            coEvery { featureService.isAutoreviewBitbucketRepoSettingEnabled(any()) } returns true
            coEvery { dataDepotPRDedupService.isDuplicatePR(any(), any(), any(), any(), any()) } returns false
        }

        private val settingsTransactionContext =
            TransactionContext(
                workspace =
                    WorkspaceContext(
                        cloudId = CLOUD_ID,
                        workspaceId = null,
                        workspaceAri = null,
                    ),
                traceId = null,
                userContext =
                    UserContext(
                        accountId = ACCOUNT_ID,
                        accountType = null,
                        userContextToken = null,
                        tokenExpiration = null,
                        authType = null,
                    ),
            )

        private fun mockRepositorySettings(
            settingsContainers: AutoreviewSettingsContainers,
            repoSettings: AutoreviewRepositorySettingAttributes?,
        ) {
            coEvery {
                autoreviewSettingsService.getRepositoryAutoreviewSettings(
                    settingsContainers.repositoryContainerType,
                    settingsContainers.repositoryContainerId,
                )
            } returns repoSettings?.let { SettingValue(it, OffsetDateTime.now()) }
        }

        private fun mockWorkspaceSettings(
            settingsContainers: AutoreviewSettingsContainers,
            workspaceSettings: AutoreviewWorkspaceSettingAttributes?,
        ) {
            coEvery {
                autoreviewSettingsService.getWorkspaceAutoreviewSettings(
                    settingsContainers.workspaceContainerType,
                    settingsContainers.workspaceContainerId,
                )
            } returns workspaceSettings?.let { SettingValue(it, OffsetDateTime.now()) }
        }

        private fun mockDevAiWorkspaceSettings(
            settingsContainers: AutoreviewSettingsContainers,
            workspaceSettings: AutoreviewDevAIWorkSpaceSettingAttributes?,
        ) {
            coEvery {
                autoreviewSettingsService.getDevAiWorkspaceAutoreviewSettings(
                    settingsContainers.devAIWorkspaceContainerType,
                    settingsContainers.devAIWorkspaceEventSettingValue,
                )
            } returns workspaceSettings?.let { SettingValue(it, OffsetDateTime.now()) }
        }

        private fun mockConnectedCloudIds(countOfConnectedIds: Int = 1) {
            coEvery { dssGatewayClient.getInstallationsByBitbucketWorkspace(any()) } returns
                List(countOfConnectedIds) {
                    DssCloudIdInstallation(
                        cloudId = UUID.randomUUID().toString(),
                        bitbucketEnv = "",
                        bitbucketWorkspaceUuid = TEST_BITBUCKET_WORKSPACE_UUID,
                        connectState = "INSTALLED",
                        createdAt = Instant.now(),
                        jiraState = "ACTIVE",
                        connectAppKey = "",
                        lastUpdatedAt = Instant.now(),
                        connectClientKey = "",
                        jiraActivationId = "",
                    )
                }
        }

        @Nested
        inner class InternalAndExternal {
            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `return false settings for AR and ACs when workspace setting is false`(workspaceCheck: Boolean) =
                runTest {
                    // Arrange
                    val repoSettingValue =
                        AutoreviewRepositorySettingAttributes(
                            autoreview_enabled = true,
                            autoreview_acceptance_criteria_enabled = true,
                            autoreview_acceptance_criteria_commit_trigger_enabled = null,
                            autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_ONLY,
                        )
                    val workspaceSettingValue =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = workspaceCheck,
                            autoreview_ip_allowlist_enabled = true,
                        )
                    val devAIWorkspaceSettingValue =
                        AutoreviewDevAIWorkSpaceSettingAttributes(
                            autoreview_enabled = true,
                        )

                    mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                    mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                    mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                    // Act
                    val autoreviewSettings =
                        subject.getEventSettings(
                            settingsContainers = bitbucketSettingsContainers,
                            repoUrl = TEST_BITBUCKET_EXTERNAL_REPO_URL,
                            eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                            friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                            isBitbucketSCM = true,
                            transactionContext = settingsTransactionContext,
                        )

                    // Assert
                    if (workspaceCheck) {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    } else {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                    }
                }

            @CsvSource(
                "true, false",
                "false, true",
            )
            @ParameterizedTest
            fun `return false settings for ACs when either AR or AC repo setting is false`(
                autoreviewRepoCheck: Boolean,
                acceptanceCriteriaRepoCheck: Boolean,
            ) = runTest {
                // Arrange
                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = autoreviewRepoCheck,
                        autoreview_acceptance_criteria_enabled = acceptanceCriteriaRepoCheck,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_ONLY,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = null,
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_REPO_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                if (autoreviewRepoCheck) autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                if (!autoreviewRepoCheck || !acceptanceCriteriaRepoCheck) {
                    autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                }
            }

            @ParameterizedTest
            @EnumSource(value = AutoreviewAutomaticTriggerType::class)
            fun `return true settings for AR and AC incremental when workspace and repo settings are true, and trigger not null`(
                triggerType: AutoreviewAutomaticTriggerType,
            ) = runTest {
                // Arrange
                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        autoreview_automatic_trigger_type = triggerType,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = true,
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_REPO_URL,
                        eventType = AVI_DEVOPS_UPDATED_PULL_REQUEST, // incremental (PR updated event)
                        friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                when (triggerType) {
                    AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    }

                    AutoreviewAutomaticTriggerType.CREATED_ONLY, AutoreviewAutomaticTriggerType.NEVER -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                    }
                }
            }

            @ParameterizedTest
            @CsvSource(
                "avi:devops:created:pull-request, https://bitbucket.org/atlassian/internalrepo",
                "avi:devops:created:pull-request, https://bitbucket.org/workspace/testRepo",
                "avi:devops:updated:pull-request, https://bitbucket.org/atlassian/internalrepo",
                "avi:devops:updated:pull-request, https://bitbucket.org/workspace/testRepo",
            )
            fun `return true settings when workspace and repo true, and trigger null for PR created events only`(
                eventType: String,
                repoUrl: String,
            ) = runTest {
                // Arrange
                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = null,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = null,
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = repoUrl,
                        eventType = eventType,
                        friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                when (eventType) {
                    AVI_DEVOPS_CREATED_PULL_REQUEST -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    }

                    AVI_DEVOPS_UPDATED_PULL_REQUEST -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                    }
                }
            }
        }

        @Nested
        inner class InternalRepository {
            @ParameterizedTest
            @ValueSource(strings = [AVI_DEVOPS_CREATED_PULL_REQUEST, AVI_DEVOPS_UPDATED_PULL_REQUEST])
            fun `return true settings for AR and ACs when workspace or repo settings are null and internal repo for PR created event`(
                eventType: String,
            ) = runTest {
                // Arrange
                // Mock settings
                mockRepositorySettings(bitbucketSettingsContainers, null)
                mockWorkspaceSettings(bitbucketSettingsContainers, null)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, null)

                // Act
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_INTERNAL_REPO_URL,
                        eventType = eventType,
                        friendlyPullRequestUrl = URI(TEST_BITBUCKET_INTERNAL_PULL_REQUEST_URL).toURL(),
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                when (eventType) {
                    AVI_DEVOPS_CREATED_PULL_REQUEST -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    }

                    AVI_DEVOPS_UPDATED_PULL_REQUEST -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                    }
                }
            }

            @ParameterizedTest
            @ValueSource(strings = [AVI_DEVOPS_CREATED_PULL_REQUEST, AVI_DEVOPS_UPDATED_PULL_REQUEST])
            fun `return true settings for AR and ACs when workspace, repo and trigger settings are null for PR created event`(
                eventType: String,
            ) = runTest {
                // Arrange

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = null,
                        autoreview_acceptance_criteria_enabled = null,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = null,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = null,
                        autoreview_ip_allowlist_enabled = null,
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_INTERNAL_REPO_URL,
                        eventType = eventType,
                        friendlyPullRequestUrl = URI(TEST_BITBUCKET_INTERNAL_PULL_REQUEST_URL).toURL(),
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                when (eventType) {
                    AVI_DEVOPS_CREATED_PULL_REQUEST -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    }

                    AVI_DEVOPS_UPDATED_PULL_REQUEST -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                    }
                }
            }

            @ParameterizedTest
            @EnumSource(value = AutoreviewAutomaticTriggerType::class)
            fun `respect trigger settings when workspace and repo settings are null but trigger type is not null`(
                triggerType: AutoreviewAutomaticTriggerType,
            ) = runTest {
                // Arrange
                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = null,
                        autoreview_acceptance_criteria_enabled = null,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = triggerType,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = null,
                        autoreview_ip_allowlist_enabled = null,
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_INTERNAL_REPO_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = URI(TEST_BITBUCKET_INTERNAL_PULL_REQUEST_URL).toURL(),
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                when (triggerType) {
                    AutoreviewAutomaticTriggerType.CREATED_ONLY, AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    }

                    AutoreviewAutomaticTriggerType.NEVER -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                    }
                }
            }

            @ParameterizedTest
            @ValueSource(strings = [AVI_DEVOPS_CREATED_PULL_REQUEST, AVI_DEVOPS_UPDATED_PULL_REQUEST])
            fun `return true settings when workspace true, repo null and trigger null for PR created event`(eventType: String) =
                runTest {
                    // Arrange
                    val repoSettingValue =
                        AutoreviewRepositorySettingAttributes(
                            autoreview_enabled = null,
                            autoreview_acceptance_criteria_enabled = null,
                            autoreview_acceptance_criteria_commit_trigger_enabled = null,
                            autoreview_automatic_trigger_type = null,
                        )
                    val workspaceSettingValue =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = true,
                            autoreview_ip_allowlist_enabled = null,
                        )
                    val devAIWorkspaceSettingValue =
                        AutoreviewDevAIWorkSpaceSettingAttributes(
                            autoreview_enabled = true,
                        )

                    mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                    mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                    mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                    // Act
                    val autoreviewSettings =
                        subject.getEventSettings(
                            settingsContainers = bitbucketSettingsContainers,
                            repoUrl = TEST_BITBUCKET_INTERNAL_REPO_URL,
                            eventType = eventType,
                            friendlyPullRequestUrl = URI(TEST_BITBUCKET_INTERNAL_PULL_REQUEST_URL).toURL(),
                            isBitbucketSCM = true,
                            transactionContext = settingsTransactionContext,
                        )

                    // Assert
                    when (eventType) {
                        AVI_DEVOPS_CREATED_PULL_REQUEST -> {
                            autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                            autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                        }

                        AVI_DEVOPS_UPDATED_PULL_REQUEST -> {
                            autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                            autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                        }
                    }
                }
        }

        @Nested
        inner class ExternalRepository {
            @ParameterizedTest
            @ValueSource(strings = [AVI_DEVOPS_CREATED_PULL_REQUEST, AVI_DEVOPS_UPDATED_PULL_REQUEST])
            fun `return false settings for AR and ACs when workspace or repo settings are null but external repository`(eventType: String) =
                runTest {
                    // Arrange
                    // Mock settings
                    mockRepositorySettings(bitbucketSettingsContainers, null)
                    mockWorkspaceSettings(bitbucketSettingsContainers, null)
                    mockDevAiWorkspaceSettings(bitbucketSettingsContainers, null)

                    // Act
                    val autoreviewSettings =
                        subject.getEventSettings(
                            settingsContainers = bitbucketSettingsContainers,
                            repoUrl = TEST_BITBUCKET_EXTERNAL_REPO_URL,
                            eventType = eventType,
                            friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                            isBitbucketSCM = false,
                            transactionContext = settingsTransactionContext,
                        )

                    // Assert
                    autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                    autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                }

            @ParameterizedTest
            @EnumSource(value = AutoreviewAutomaticTriggerType::class)
            fun `respect trigger settings when workspace and repo true, and trigger not null`(
                triggerType: AutoreviewAutomaticTriggerType,
            ) = runTest {
                // Arrange
                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = triggerType,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = null,
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_REPO_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                when (triggerType) {
                    AutoreviewAutomaticTriggerType.CREATED_ONLY, AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    }

                    AutoreviewAutomaticTriggerType.NEVER -> {
                        autoreviewSettings.isAutoreviewAllowedBySettings shouldBe false
                        autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                    }
                }
            }
        }

        @Nested
        inner class SiteAssociationCheck {
            @ParameterizedTest
            @CsvSource(value = ["true, false", "true, true", "false, false", "false, true"])
            fun `ACs and AR enabled when cloudIds match for standard customer`(
                isMatchingCloudId: Boolean,
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // Arrange
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        null,
                    )
                } returns ResourceType.ROVO_DEV_STANDARD

                val billingCloudId = CLOUD_ID
                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED,
                    )

                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = billingCloudId.toString(),
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext =
                            settingsTransactionContext.copy(
                                workspace =
                                    WorkspaceContext(
                                        cloudId =
                                            if (isMatchingCloudId) {
                                                billingCloudId
                                            } else {
                                                CloudIdLike.fromString(
                                                    "5fd9b3db-3a95-4c43-b119-79d12ab4182e",
                                                )
                                            },
                                        workspaceId = null,
                                        workspaceAri = null,
                                    ),
                            ),
                    )

                // Assert
                autoreviewSettings.billingCloudId shouldBe billingCloudId
                autoreviewSettings.isEventSiteBeta shouldBe false
                autoreviewSettings.isEventSiteRovoDevEnabled shouldBe true
                autoreviewSettings.isSettingsSiteBeta shouldBe false
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe true
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe true

                if (isMatchingCloudId) {
                    autoreviewSettings.isCloudIdAssociatedToSite shouldBe true
                    autoreviewSettings.isAutoreviewEnabled(friendlyPullRequestUrl) shouldBe true
                } else {
                    autoreviewSettings.isCloudIdAssociatedToSite shouldBe false
                    autoreviewSettings.isAutoreviewEnabled(friendlyPullRequestUrl) shouldBe false
                }
            }

            @ParameterizedTest
            @CsvSource(value = ["true, false", "true, true", "false, false", "false, true"])
            fun `ACs and AR enabled, and update settings, for single-site with workspace and repo enabled and null billingCloudId`(
                isBetaCustomer: Boolean,
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // Arrange
                coEvery { siteEntitlementService.getEntitlementType(any(), null) } returns
                    if (isBetaCustomer) {
                        ResourceType.ROVO_DEV_BETA
                    } else {
                        ResourceType.ROVO_DEV_STANDARD
                    }

                coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true
                mockConnectedCloudIds(1) // single connected cloudId

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = null,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true, // workspace enabled
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = null, // billingCloudId null
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                coVerify(exactly = if (isBetaCustomer) 0 else 1) {
                    autoreviewSettingsService.setAutoreviewSettings(
                        containerType = bitbucketSettingsContainers.workspaceContainerType,
                        containerId = bitbucketSettingsContainers.workspaceContainerId,
                        value = workspaceSettingValue.copy(autoreview_cloud_id_association = CLOUD_ID.toString()),
                    )
                }
                autoreviewSettings.billingCloudId shouldBe null
                autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                autoreviewSettings.isAutoreviewEnabled(friendlyPullRequestUrl) shouldBe isBetaCustomer
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe
                    isBetaCustomer
                autoreviewSettings.isEventSiteBeta shouldBe isBetaCustomer
                autoreviewSettings.isEventSiteRovoDevEnabled shouldBe !isBetaCustomer
                autoreviewSettings.isSettingsSiteBeta shouldBe false
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe false
            }

            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `ACs and AR disabled for multi-site non-beta with workspace and repo enabled and null billingCloudId`(
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // Arrange
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        null,
                    )
                } returns ResourceType.ROVO_DEV_STANDARD

                coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true
                mockConnectedCloudIds(2) // multiple sites connected

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = null,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true, // workspace enabled
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = null, // billingCloudId null
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                autoreviewSettings.isAutoreviewEnabled(friendlyPullRequestUrl) shouldBe false
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe false
                autoreviewSettings.billingCloudId shouldBe null
                autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                autoreviewSettings.isEventSiteBeta shouldBe false
                autoreviewSettings.isEventSiteRovoDevEnabled shouldBe true
                autoreviewSettings.isSettingsSiteBeta shouldBe false
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe false // settings cloudId null, so entitlement unknown
            }
        }

        @Nested
        inner class BetaCustomerChecks {
            @BeforeEach
            fun setup() {
                coEvery { siteEntitlementService.getEntitlementType(any(), null) } returns ResourceType.ROVO_DEV_BETA
            }

            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `ACs and AR enabled for multi-site beta with workspace and repo enabled and null billingCloudId`(
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // Arrange
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        null,
                    )
                } returns ResourceType.ROVO_DEV_BETA

                coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true
                mockConnectedCloudIds(2) // multiple sites connected

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = null,
                        autoreview_automatic_trigger_type = null,
                    )
                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true, // workspace enabled
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = null, // billingCloudId null
                    )
                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = true,
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext = settingsTransactionContext,
                    )

                // Assert
                autoreviewSettings.isAutoreviewEnabled(friendlyPullRequestUrl) shouldBe true
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe true
                autoreviewSettings.billingCloudId shouldBe null
                autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                autoreviewSettings.isEventSiteBeta shouldBe true
                autoreviewSettings.isEventSiteRovoDevEnabled shouldBe false
                autoreviewSettings.isSettingsSiteBeta shouldBe false // settings cloudId null, so entitlement unknown
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe false // settings cloudId null, so entitlement unknown
            }

            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `ACs enabled for beta site with mismatched cloudIds and null devAIWorkspaceSettingValue`(
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // Beta sites with different event/settings cloudIds should allow ACs even when devAIWorkspaceSettingValue is null
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        null,
                    )
                } returnsMany listOf(ResourceType.ROVO_DEV_BETA, ResourceType.ROVO_DEV_STANDARD)

                coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true

                val billingCloudId = CLOUD_ID
                val eventCloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED,
                    )

                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = billingCloudId.toString(),
                    )

                // devAIWorkspaceSettingValue is null - typical for Atlassian internal beta sites
                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, null)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext =
                            settingsTransactionContext.copy(
                                workspace =
                                    WorkspaceContext(
                                        cloudId = eventCloudId,
                                        workspaceId = null,
                                        workspaceAri = null,
                                    ),
                            ),
                    )

                // Assert
                autoreviewSettings.billingCloudId shouldBe billingCloudId
                autoreviewSettings.isEventSiteBeta shouldBe true
                autoreviewSettings.isSettingsSiteBeta shouldBe false
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe true
                autoreviewSettings.isCloudIdAssociatedToSite shouldBe false
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                // This should be true now with the fix - beta sites with null devAIWorkspaceSettingValue are allowed
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe
                    isAcBetaSiteNullWorkspaceEnabled
            }

            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `ACs disabled for beta site with mismatched cloudIds and false devAIWorkspaceSettingValue`(
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // Arrange - Validates that explicitly setting devAIWorkspaceSettingValue to false blocks ACs
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        null,
                    )
                } returnsMany listOf(ResourceType.ROVO_DEV_BETA, ResourceType.ROVO_DEV_STANDARD)

                coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true

                val billingCloudId = CLOUD_ID
                val eventCloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED,
                    )

                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = billingCloudId.toString(),
                    )

                val devAIWorkspaceSettingValue =
                    AutoreviewDevAIWorkSpaceSettingAttributes(
                        autoreview_enabled = false, // explicitly disabled
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext =
                            settingsTransactionContext.copy(
                                workspace =
                                    WorkspaceContext(
                                        cloudId = eventCloudId,
                                        workspaceId = null,
                                        workspaceAri = null,
                                    ),
                            ),
                    )

                // Assert
                autoreviewSettings.billingCloudId shouldBe billingCloudId
                autoreviewSettings.isEventSiteBeta shouldBe true
                autoreviewSettings.isSettingsSiteBeta shouldBe false
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe true
                autoreviewSettings.isCloudIdAssociatedToSite shouldBe false
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                // Should be false when explicitly set to false
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe false
            }

            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `ACs enabled for both beta sites with mismatched cloudIds and null devAIWorkspaceSettingValue when feature flag enabled`(
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // This scenario should work when isAcBetaSiteNullWorkspaceEnabled is true
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        null,
                    )
                } returnsMany listOf(ResourceType.ROVO_DEV_BETA, ResourceType.ROVO_DEV_BETA)

                coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true

                val billingCloudId = CLOUD_ID
                val eventCloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED,
                    )

                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = billingCloudId.toString(),
                    )

                // devAIWorkspaceSettingValue is null - typical for Atlassian internal beta sites
                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, null)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext =
                            settingsTransactionContext.copy(
                                workspace =
                                    WorkspaceContext(
                                        cloudId = eventCloudId,
                                        workspaceId = null,
                                        workspaceAri = null,
                                    ),
                            ),
                    )

                // Assert
                autoreviewSettings.billingCloudId shouldBe billingCloudId
                autoreviewSettings.isEventSiteBeta shouldBe true
                autoreviewSettings.isSettingsSiteBeta shouldBe true
                autoreviewSettings.isEventSiteRovoDevEnabled shouldBe false
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe false
                autoreviewSettings.isCloudIdAssociatedToSite shouldBe false
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                autoreviewSettings.devAIWorkspaceSettingValue shouldBe null
                // Should be enabled when both sites are beta and feature flag is true, even with null devAIWorkspaceSettingValue
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe
                    isAcBetaSiteNullWorkspaceEnabled
            }

            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `ACs disabled for non-beta event site with beta settings site and null devAIWorkspaceSettingValue`(
                isAcBetaSiteNullWorkspaceEnabled: Boolean,
            ) = runTest {
                // Arrange - Event site is RovoDev enabled (non-beta), settings site is beta, devAIWorkspaceSettingValue is null
                // This case doesn't match the new condition because isSettingsSiteRovoDevEnabled is false (beta sites)
                // The condition requires: isSettingsSiteRovoDevEnabled || (isEventSiteBeta && isSettingsSiteBeta)
                // Here: false || (false && true) = false
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        null,
                    )
                } returnsMany listOf(ResourceType.ROVO_DEV_STANDARD, ResourceType.ROVO_DEV_BETA)

                coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true

                val billingCloudId = CLOUD_ID
                val eventCloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")

                val repoSettingValue =
                    AutoreviewRepositorySettingAttributes(
                        autoreview_enabled = true,
                        autoreview_acceptance_criteria_enabled = true,
                        autoreview_acceptance_criteria_commit_trigger_enabled = true,
                        autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED,
                    )

                val workspaceSettingValue =
                    AutoreviewWorkspaceSettingAttributes(
                        autoreview_activated = true,
                        autoreview_ip_allowlist_enabled = true,
                        autoreview_cloud_id_association = billingCloudId.toString(),
                    )

                mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                mockDevAiWorkspaceSettings(bitbucketSettingsContainers, null)

                // Act
                val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                val autoreviewSettings =
                    subject.getEventSettings(
                        settingsContainers = bitbucketSettingsContainers,
                        repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                        eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                        friendlyPullRequestUrl = friendlyPullRequestUrl,
                        isBitbucketSCM = true,
                        transactionContext =
                            settingsTransactionContext.copy(
                                workspace =
                                    WorkspaceContext(
                                        cloudId = eventCloudId,
                                        workspaceId = null,
                                        workspaceAri = null,
                                    ),
                            ),
                    )

                // Assert
                autoreviewSettings.billingCloudId shouldBe billingCloudId
                autoreviewSettings.isEventSiteBeta shouldBe false
                autoreviewSettings.isEventSiteRovoDevEnabled shouldBe true
                autoreviewSettings.isSettingsSiteBeta shouldBe true
                autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe false
                autoreviewSettings.isCloudIdAssociatedToSite shouldBe false
                autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                autoreviewSettings.devAIWorkspaceSettingValue shouldBe null
                // ACs are disabled because the condition requires isSettingsSiteRovoDevEnabled (false for beta)
                // or both event and settings sites to be beta (event is not beta here)
                autoreviewSettings.isAcceptanceCriteriaEnabled(
                    friendlyPullRequestUrl,
                    isAcBetaSiteNullWorkspaceEnabled,
                ) shouldBe false
            }

            @ParameterizedTest
            @ValueSource(booleans = [true, false])
            fun `ACs and AR enabled when billingCloudId is set and event is beta`(isAcBetaSiteNullWorkspaceEnabled: Boolean) =
                runTest {
                    // Arrange
                    coEvery {
                        siteEntitlementService.getEntitlementType(
                            any(),
                            null,
                        )
                    } returns ResourceType.ROVO_DEV_BETA

                    coEvery { featureService.isAutoreviewUpdateSiteSettingsEnabled(any()) } returns true
                    mockConnectedCloudIds(1) // single site connected

                    val repoSettingValue =
                        AutoreviewRepositorySettingAttributes(
                            autoreview_enabled = true,
                            autoreview_acceptance_criteria_enabled = true,
                            autoreview_acceptance_criteria_commit_trigger_enabled = null,
                            autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_ONLY,
                        )
                    val workspaceSettingValue =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = true,
                            autoreview_ip_allowlist_enabled = true,
                            autoreview_cloud_id_association = CLOUD_ID.toString(), // billingCloudId set
                        )

                    val devAIWorkspaceSettingValue =
                        AutoreviewDevAIWorkSpaceSettingAttributes(
                            autoreview_enabled = true,
                        )

                    mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                    mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                    mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                    // Act
                    val friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL()
                    val autoreviewSettings =
                        subject.getEventSettings(
                            settingsContainers = bitbucketSettingsContainers,
                            repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                            eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                            friendlyPullRequestUrl = friendlyPullRequestUrl,
                            isBitbucketSCM = true,
                            transactionContext = settingsTransactionContext,
                        )

                    // Assert
                    autoreviewSettings.isAutoreviewEnabled(friendlyPullRequestUrl) shouldBe true
                    autoreviewSettings.isAcceptanceCriteriaEnabled(
                        friendlyPullRequestUrl,
                        isAcBetaSiteNullWorkspaceEnabled,
                    ) shouldBe true
                    autoreviewSettings.billingCloudId shouldBe CLOUD_ID
                    autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                    autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                    autoreviewSettings.isEventSiteBeta shouldBe true
                    autoreviewSettings.isEventSiteRovoDevEnabled shouldBe false
                    autoreviewSettings.isSettingsSiteBeta shouldBe true
                    autoreviewSettings.isSettingsSiteRovoDevEnabled shouldBe false
                }

            @Test
            fun `ACs and AR enabled for single-site beta with enabled workspace, repo and correct trigger settings`() {
                runTest {
                    // Arrange
                    val repoSettingValue =
                        AutoreviewRepositorySettingAttributes(
                            autoreview_enabled = true,
                            autoreview_acceptance_criteria_enabled = true,
                            autoreview_acceptance_criteria_commit_trigger_enabled = true,
                            autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED,
                        )

                    val workspaceSettingValue =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = true,
                            autoreview_ip_allowlist_enabled = true,
                            autoreview_cloud_id_association = CLOUD_ID.toString(),
                        )
                    val devAIWorkspaceSettingValue =
                        AutoreviewDevAIWorkSpaceSettingAttributes(
                            autoreview_enabled = true,
                        )

                    mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                    mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                    mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                    // Act
                    val autoreviewSettings =
                        subject.getEventSettings(
                            settingsContainers = bitbucketSettingsContainers,
                            repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                            eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                            friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                            isBitbucketSCM = true,
                            transactionContext = settingsTransactionContext,
                        )

                    // Assert
                    autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                    autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe true
                }
            }

            @Test
            fun `should not enable ACs for beta customer with disabled repo`() {
                runTest {
                    // Arrange
                    val repoSettingValue =
                        AutoreviewRepositorySettingAttributes(
                            autoreview_enabled = true,
                            autoreview_acceptance_criteria_enabled = false,
                            autoreview_acceptance_criteria_commit_trigger_enabled = false,
                            autoreview_automatic_trigger_type = AutoreviewAutomaticTriggerType.CREATED_AND_UPDATED,
                        )

                    val workspaceSettingValue =
                        AutoreviewWorkspaceSettingAttributes(
                            autoreview_activated = true,
                            autoreview_ip_allowlist_enabled = true,
                            autoreview_cloud_id_association = CLOUD_ID.toString(),
                        )
                    val devAIWorkspaceSettingValue =
                        AutoreviewDevAIWorkSpaceSettingAttributes(
                            autoreview_enabled = true,
                        )

                    mockRepositorySettings(bitbucketSettingsContainers, repoSettingValue)
                    mockWorkspaceSettings(bitbucketSettingsContainers, workspaceSettingValue)
                    mockDevAiWorkspaceSettings(bitbucketSettingsContainers, devAIWorkspaceSettingValue)

                    // Act
                    val autoreviewSettings =
                        subject.getEventSettings(
                            settingsContainers = bitbucketSettingsContainers,
                            repoUrl = TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL,
                            eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                            friendlyPullRequestUrl = URI(TEST_BITBUCKET_EXTERNAL_PULL_REQUEST_URL).toURL(),
                            isBitbucketSCM = true,
                            transactionContext = settingsTransactionContext,
                        )

                    // Assert
                    autoreviewSettings.isAutoreviewAllowedBySettings shouldBe true
                    autoreviewSettings.isAcceptanceCriteriaAllowedBySettings shouldBe false
                }
            }
        }

        @Nested
        inner class SupportedEventTypes {
            @CsvSource(
                value = [
                    // Base checks
                    // AR and AC not allowed (other settings don't matter)
                    "false, false, true, true, true, true, true, true, not_null, true",
                    // devAI workspace not enabled (other settings don't matter)
                    "true, true, true, true, true, true, true, false, not_null, true",

                    // null billingCloudId
                    // AR and AC allowed, beta customer, cloudId associated to site, devAI enabled
                    "true, true, true, false, true, false, true, true, null, true",
                    // AR and AC allowed, beta customer, cloudId not associated to site, devAI enabled
                    "true, true, true, false, true, false, false, true, null, true",
                    // AR and AC allowed, non-beta customer, cloudId associated to site, devAI enabled
                    "true, true, false, true, false, true, true, true, null, true",

                    // with billingCloudId
                    // AR and AC allowed, beta customer, cloudId associated to site, devAI enabled
                    "true, true, true, false, true, false, true, true, not_null, true",
                    // AR and AC allowed, beta customer, cloudId not associated to site, devAI enabled
                    "true, true, true, false, true, false, false, true, not_null, true",
                    // AR and AC allowed, non-beta customer, cloudId associated to site, devAI enabled
                    "true, true, false, true, false, true, true, true, not_null, true",

                    // No site associations // AR and AC allowed, non-beta customer, cloudId associated to site, devAI enabled
                    "true, true, false, true, false, true, true, true, not_null, false",
                ],
            )
            @ParameterizedTest
            fun `should return correct autoreview event types`(
                isAutoreviewAllowedBySettings: Boolean,
                isAcceptanceCriteriaAllowedBySettings: Boolean,
                isEventSiteBeta: Boolean,
                isEventSiteRovoDevEnabled: Boolean,
                isSettingsSiteBeta: Boolean,
                isSettingsSiteRovoDevEnabled: Boolean,
                isCloudIdAssociatedToSite: Boolean,
                devAIWorkspaceSettingValue: Boolean,
                billingCloudId: String?,
                hasAssociations: Boolean,
            ) {
                runTest {
                    // Arrange
                    coEvery { dataDepotPRDedupService.isDuplicatePR(any(), any(), any(), any(), any()) } returns false

                    val associations =
                        if (hasAssociations) {
                            listOf(
                                Association(
                                    type = "jira:issue",
                                    ari = "ari:cloud:jira:feccc06a-0a59-4f25-b375-a44fde726d1a:issue/10350",
                                ),
                            )
                        } else {
                            emptyList()
                        }

                    val eventSettings =
                        AutoreviewEventSettings(
                            isAutoreviewAllowedBySettings = isAutoreviewAllowedBySettings,
                            isAcceptanceCriteriaAllowedBySettings = isAcceptanceCriteriaAllowedBySettings,
                            isEventSiteBeta = isEventSiteBeta,
                            isEventSiteRovoDevEnabled = isEventSiteRovoDevEnabled,
                            isSettingsSiteBeta = isSettingsSiteBeta,
                            isSettingsSiteRovoDevEnabled = isSettingsSiteRovoDevEnabled,
                            isCloudIdAssociatedToSite = isCloudIdAssociatedToSite,
                            devAIWorkspaceSettingValue = devAIWorkspaceSettingValue,
                            billingCloudId =
                                if (billingCloudId ==
                                    "null"
                                ) {
                                    null
                                } else {
                                    CloudIdLike.fromString("f06c6dbf-6af2-41f3-8d6b-ea1fde6a201f")
                                },
                        )

                    // Act
                    val response =
                        subject.getSupportedEventTypes(
                            eventType = AVI_DEVOPS_CREATED_PULL_REQUEST,
                            eventSettings = eventSettings,
                            friendlyPullRequestUrl =
                                URI
                                    .create("https://bitbucket.org/rovo-dev-test-002/test_repo1/pull-requests/12")
                                    .toURL(),
                            lastUpdatedTimestamp = Instant.now().toString(),
                            graphWorkspaceAri = ARI.of(GraphWorkspaceARI.from("8a86df01-b499-4cb4-a1ba-607de0681ed1")),
                            associations = associations,
                            transactionContext = settingsTransactionContext,
                        )

                    // Assert Autoreview
                    if (!isAutoreviewAllowedBySettings) response shouldNotContain AutoreviewEventType.AUTOREVIEW_MAIN
                    if (isAutoreviewAllowedBySettings) {
                        when {
                            !devAIWorkspaceSettingValue && !isEventSiteBeta -> {
                                response shouldNotContain AutoreviewEventType.AUTOREVIEW_MAIN
                            }

                            billingCloudId == "null" -> {
                                if (isEventSiteBeta) {
                                    response shouldContain AutoreviewEventType.AUTOREVIEW_MAIN
                                } else {
                                    response shouldNotContain AutoreviewEventType.AUTOREVIEW_MAIN
                                }
                            }

                            isCloudIdAssociatedToSite && (isSettingsSiteRovoDevEnabled || isEventSiteBeta) -> {
                                response shouldContain AutoreviewEventType.AUTOREVIEW_MAIN
                            }

                            else -> {
                                response shouldNotContain AutoreviewEventType.AUTOREVIEW_MAIN
                            }
                        }
                    }

                    // Assert Acceptance Criteria
                    if (!isAcceptanceCriteriaAllowedBySettings) response shouldNotContain AutoreviewEventType.ACCEPTANCE_CRITERIA
                    if (!hasAssociations) {
                        response shouldNotContain AutoreviewEventType.ACCEPTANCE_CRITERIA
                    } else {
                        if (isAcceptanceCriteriaAllowedBySettings) {
                            when {
                                !devAIWorkspaceSettingValue && !isEventSiteBeta -> {
                                    response shouldNotContain
                                        AutoreviewEventType.AUTOREVIEW_MAIN
                                }

                                billingCloudId == "null" -> {
                                    if (isEventSiteBeta) {
                                        response shouldContain AutoreviewEventType.ACCEPTANCE_CRITERIA
                                    } else {
                                        response shouldNotContain AutoreviewEventType.ACCEPTANCE_CRITERIA
                                    }
                                }

                                isCloudIdAssociatedToSite && (isSettingsSiteRovoDevEnabled || isEventSiteBeta) -> {
                                    response shouldContain
                                        AutoreviewEventType.ACCEPTANCE_CRITERIA
                                }

                                !isCloudIdAssociatedToSite &&
                                    (isEventSiteRovoDevEnabled || isEventSiteBeta) &&
                                    isSettingsSiteRovoDevEnabled &&
                                    devAIWorkspaceSettingValue -> {
                                    response shouldContain AutoreviewEventType.ACCEPTANCE_CRITERIA
                                }

                                else -> {
                                    response shouldNotContain AutoreviewEventType.ACCEPTANCE_CRITERIA
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Nested
    inner class GetUserRovoDevAccess {
        fun testDevaiUser(hasProductAccess: Boolean = true) =
            DevAiUser(
                "user-id",
                hasProductAccess,
                true,
            )

        @Test
        fun `should return install link when user is an org admin without standard and site doesn't have standard access`() =
            runTest {
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevStandardAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(false)
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevEverywhereAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(true)
                coEvery { userPermissionsService.isUserOrgAdmin(any(), any()) } returns true
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        any(),
                    )
                } returns ResourceType.ROVO_DEV_EVERYWHERE

                coEvery { rovoDevCTALinks.getInstallRovoDevLink() } returns "test-install-link"

                val result =
                    subject.getUserRovoDevAccess(
                        CLOUD_ID.toString(),
                        "user-id",
                        "user-context-token",
                    )

                result.ctaLink shouldBe "test-install-link"
            }

        @Test
        fun `should return adminhub link when user is an orgAdmin without standard but site DOES have standard access`() =
            runTest {
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevStandardAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(false)
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevEverywhereAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(true)
                coEvery { userPermissionsService.isUserOrgAdmin(any(), any()) } returns true
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        any(),
                    )
                } returns ResourceType.ROVO_DEV_STANDARD
                coEvery { rovoDevCTALinks.getAddUserToRovoDevLink(any(), any()) } returns "test-adminhub-link"

                val result =
                    subject.getUserRovoDevAccess(
                        CLOUD_ID.toString(),
                        "user-id",
                        "user-context-token",
                    )

                result.ctaLink shouldBe "test-adminhub-link"
            }

        @Test
        fun `should return adminhub link when user is an orgAdmin without standard but site has standard trial`() =
            runTest {
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevStandardAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(false)
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevEverywhereAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(true)
                coEvery { userPermissionsService.isUserOrgAdmin(any(), any()) } returns true
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        any(),
                    )
                } returns ResourceType.ROVO_DEV_STANDARD_TRIAL
                coEvery { rovoDevCTALinks.getAddUserToRovoDevLink(any(), any()) } returns "test-adminhub-link"

                val result =
                    subject.getUserRovoDevAccess(
                        CLOUD_ID.toString(),
                        "user-id",
                        "user-context-token",
                    )

                result.ctaLink shouldBe "test-adminhub-link"
            }

        @Test
        fun `should return request access link when user isn't an org admin, regardless of site setting`() {
            runTest {
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevStandardAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(false)
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevEverywhereAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(true)
                coEvery { userPermissionsService.isUserOrgAdmin(any(), any()) } returns false
                coEvery {
                    siteEntitlementService.getEntitlementType(
                        any(),
                        any(),
                    )
                } returns ResourceType.ROVO_DEV_STANDARD
                coEvery { tcsService.getCloudUrlFromCloudIdAsync(CLOUD_ID) } returns
                    CloudURL(
                        "test-cloud-id",
                        "test-cloud-url",
                        "test-cloud-name",
                    )
                coEvery {
                    rovoDevCTALinks.getRequestAccessLink(
                        CLOUD_ID.toString(),
                        "test-cloud-url",
                    )
                } returns "request-access-link"

                val result =
                    subject.getUserRovoDevAccess(
                        CLOUD_ID.toString(),
                        "user-id",
                        "user-context-token",
                    )

                result.ctaLink shouldBe "request-access-link"
            }
        }

        @Test
        fun `should handle throwing errors from TCS by defaulting to install link`() =
            runTest {
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevStandardAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(false)
                coEvery {
                    userPermissionsService.doesUserHaveRovoDevEverywhereAccess(
                        any(),
                        any(),
                    )
                } returns testDevaiUser(false)
                coEvery { rovoDevCTALinks.getInstallRovoDevLink() } returns "test-install-link"
                coEvery { tcsService.getCloudUrlFromCloudIdAsync(CLOUD_ID) } throws RuntimeException("TCS is down")

                val result =
                    subject.getUserRovoDevAccess(
                        CLOUD_ID.toString(),
                        "user-id",
                        "user-context-token",
                    )

                result.ctaLink shouldBe "test-install-link"
            }
    }

    @Nested
    inner class GetDevAiCloudActivation {
        @Test
        fun `should return CloudActivation on success`() {
            runTest {
                // Arrange
                val activationId = UUID.randomUUID().toString()
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(CLOUD_ID)
                } returns
                    mapOf(
                        DEVAI_ACTIVATION_KEY to ActivationIds(activationId),
                    )

                // Act
                val activation = subject.getDevAiCloudActivation(CLOUD_ID)

                // Assert
                activation shouldNotBe null
                activation?.activationId shouldBe activationId
            }
        }

        @Test
        fun `should return null when there is no devai activationId`() {
            runTest {
                coEvery {
                    tcsService.getCloudIdFromWorkspaceId(any())
                } returns CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")

                coEvery {
                    tcsService.getOrgByCloudId(any())
                } returns
                    LinkedOrg(
                        "org-id",
                        OrgLink(
                            "ari:cloud:graph::workspace/5fd9b3db-3a95-4c43-b119-79d12ab4182e",
                        ),
                    )

                coEvery {
                    tcsService.getOrgLinksByOrgId(any())
                } returns
                    OrgLinks(
                        listOf(
                            OrgLink(
                                "ari:cloud:platform::site/922168f0-256f-49e0-ac04-4db48b68d2ea",
                            ),
                        ),
                    )

                val activationId = UUID.randomUUID().toString()
                coEvery {
                    tcsService.getActivationIdsFromCloudId(any())
                } returns mapOf("rovo" to ActivationIds(activationId))

                val cloudActivation = subject.getDevAiCloudActivation(CLOUD_ID)

                cloudActivation shouldBe null
            }
        }
    }

    @Nested
    inner class GetDevAiCloudActivationByCloudId {
        @Test
        fun `should return CloudActivation on success`() {
            runTest {
                val activationId = UUID.randomUUID().toString()
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(any())
                } returns
                    mapOf(
                        "devai" to ActivationIds(activationId),
                        "rovo" to ActivationIds(UUID.randomUUID().toString()),
                    )

                val activation =
                    subject.getDevAiCloudActivation(
                        CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e"),
                    )

                activation shouldNotBe null
                activation?.activationId shouldBe activationId
            }
        }

        @Test
        fun `should return null when there is no devai activations`() {
            runTest {
                val activationId = UUID.randomUUID().toString()
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(any())
                } returns mapOf("rovo" to ActivationIds(activationId))

                val activation =
                    subject.getDevAiCloudActivation(
                        CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e"),
                    )

                activation shouldBe null
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class GetCloudActivationsByCloudId {
        private val cloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")
        private val devAiId = "8f277667-9b6a-4a77-a056-fc05d23cc94a"
        private val devOpsId = "a4987bd4-adc0-4f22-a33e-fd07085862eb"
        private val jiraId = "cd637743-1dcf-44cd-abee-aa5f17c1af86"

        @ParameterizedTest
        @MethodSource("expectedCloudActivations")
        fun `should return all expected CloudActivation depending on activationKeysFilter`(
            keys: Set<String>?,
            expectedValues: Map<String, String>,
        ) {
            runTest {
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(cloudId)
                } returns
                    mapOf(
                        "devai" to ActivationIds(devAiId),
                        "devops" to ActivationIds(devOpsId),
                        "jira" to ActivationIds(jiraId),
                        "something" to ActivationIds(Optional.empty(), emptyList<String>()),
                    )

                val cloudActivation =
                    subject.getCloudActivationByCloudId(cloudId, keys)

                cloudActivation.activationIds.mapValues { it.value.active.orElse(null) } shouldBe
                    expectedValues
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `should return DevAI activationId when RovoDev is enabled for event cloudId, else null`(isRovoDevEnabled: Boolean) {
            runTest {
                // Arrange
                val activations =
                    if (isRovoDevEnabled) {
                        mapOf(
                            "devai" to ActivationIds(devAiId),
                            "devops" to ActivationIds(devOpsId),
                            "jira" to ActivationIds(jiraId),
                            "something" to ActivationIds(Optional.empty(), emptyList<String>()),
                        )
                    } else {
                        mapOf(
                            "devops" to ActivationIds(devOpsId),
                            "jira" to ActivationIds(jiraId),
                        )
                    }

                coEvery { tcsService.getActivationIdsFromCloudIdAsync(any()) } returns activations

                // Act
                val cloudActivation = subject.getDevAiCloudActivation(CLOUD_ID)

                // Assert
                if (isRovoDevEnabled) {
                    cloudActivation?.activationId shouldBe devAiId
                } else {
                    cloudActivation shouldBe null
                }
            }
        }

        @Test
        fun `should throw exception if tcsService throws exception`() {
            runTest {
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(cloudId)
                } throws RuntimeException("Something Went Wrong")

                shouldThrowAny {
                    subject.getCloudActivationByCloudId(
                        cloudId,
                        setOf("devai"),
                    )
                }
            }
        }

        private fun expectedCloudActivations(): Stream<Arguments> =
            Stream.of(
                Arguments.argumentSet(
                    "No Key filter (All Keys)",
                    null,
                    mapOf(
                        "devai" to devAiId,
                        "devops" to devOpsId,
                        "jira" to jiraId,
                    ),
                ),
                Arguments.argumentSet(
                    "devai only",
                    setOf("devai"),
                    mapOf(
                        "devai" to devAiId,
                    ),
                ),
                Arguments.argumentSet(
                    "multiple keys in filter",
                    setOf("devops", "jira"),
                    mapOf(
                        "devops" to devOpsId,
                        "jira" to jiraId,
                    ),
                ),
                Arguments.argumentSet(
                    "unknown keys",
                    setOf("bbibibue", "anosuet"),
                    emptyMap<String, String>(),
                ),
            )
    }

    @Nested
    inner class GetCloudActivationActiveId {
        private val cloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")
        private val devAiId = "8f277667-9b6a-4a77-a056-fc05d23cc94a"
        private val devOpsId = "a4987bd4-adc0-4f22-a33e-fd07085862eb"

        @ParameterizedTest
        @CsvSource(
            "devops,a4987bd4-adc0-4f22-a33e-fd07085862eb",
            "devai,8f277667-9b6a-4a77-a056-fc05d23cc94a",
        )
        fun `should return requested CloudActivation Active Id on success`(
            activationKey: String,
            expectedId: String,
        ) {
            runTest {
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(cloudId)
                } returns
                    mapOf(
                        "devai" to ActivationIds(devAiId),
                        "devops" to ActivationIds(devOpsId),
                    )

                subject.getCloudActivationActiveId(
                    cloudId,
                    activationKey,
                ) shouldBe expectedId
            }
        }

        @Test
        fun `should return null when there is no activation for given key`() {
            runTest {
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(cloudId)
                } returns mapOf("devops" to ActivationIds(devOpsId))

                val activation =
                    subject.getCloudActivationActiveId(
                        cloudId,
                        "devai",
                    )

                activation shouldBe null
            }
        }
    }

    @Nested
    inner class GetConnectedCloudIdsByBitbucketWorkspaceId {
        private val bitbucketWorkspaceId = "{0446057d-0c7d-4e02-b0a9-a5bf014d4895}"

        @Test
        fun `should return empty list when there are no installations`() =
            runTest {
                coEvery { dssGatewayClient.getInstallationsByBitbucketWorkspace(bitbucketWorkspaceId) } returns emptyList()

                subject.getConnectedCloudIdsByBitbucketWorkspaceId(bitbucketWorkspaceId) shouldBe emptyList()
            }

        @Test
        fun `should return cloudIds of active and installed Installations`() =
            runTest {
                coEvery { dssGatewayClient.getInstallationsByBitbucketWorkspace(bitbucketWorkspaceId) } returns
                    listOf(
                        mockCloudInstallation("cloudId1", mockJiraState = "ACTIVE", mockConnectState = "INSTALLED"),
                        mockCloudInstallation(
                            "cloudId2",
                            mockJiraState = "DESTROYED",
                            mockConnectState = "INSTALLED",
                        ),
                        mockCloudInstallation(
                            "cloudId3",
                            mockJiraState = "ACTIVE",
                            mockConnectState = "UNINSTALLED",
                        ),
                        mockCloudInstallation(
                            "cloudId4",
                            mockJiraState = "SUSPENDED",
                            mockConnectState = "UNINSTALLED",
                        ),
                        mockCloudInstallation("cloudId5", mockJiraState = "ACTIVE", mockConnectState = "INSTALLED"),
                    )

                subject.getConnectedCloudIdsByBitbucketWorkspaceId(bitbucketWorkspaceId) shouldBe
                    listOf(
                        CloudIdLike.fromString("cloudId1"),
                        CloudIdLike.fromString("cloudId5"),
                    )
            }

        @Test
        fun `throws exception when dssGatewayClient throws exception`() =
            runTest {
                coEvery { dssGatewayClient.getInstallationsByBitbucketWorkspace(bitbucketWorkspaceId) } throws RuntimeException()

                shouldThrow<RuntimeException> {
                    subject.getConnectedCloudIdsByBitbucketWorkspaceId(bitbucketWorkspaceId)
                }
            }

        private fun mockCloudInstallation(
            mockCloudId: String,
            mockJiraState: String,
            mockConnectState: String,
        ) = mockk<DssCloudIdInstallation>(relaxed = true) {
            every { cloudId } returns mockCloudId
            every { jiraState } returns mockJiraState
            every { connectState } returns mockConnectState
        }
    }

    @Nested
    inner class CreditCheck {
        @Test
        fun `should check user credit`() =
            runTest {
                // Arrange
                val cloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")
                val accountId = AccountId.of("account-id")
                val billingOrgId = "billing-org-id"
                val experience = CodeReviewExperience.ROVODEV_REVIEW_BITBUCKET
                val isExistingBetaUser = false
                val pullRequestUrl = URI("https://bitbucket.org/ttran4repo/rovo-autoreview/pull-requests/10").toURL()
                val scmWorkspaceId = UUID.randomUUID().toString()
                val scmRepositoryId = UUID.randomUUID().toString()
                val activationId = UUID.randomUUID().toString()
                coEvery { tcsService.getOrgByCloudIdWithRetry(cloudId) } returns
                    LinkedOrg(
                        billingOrgId,
                        OrgLink("ari:org"),
                    )
                coEvery {
                    entitlementChecksService.checkEntitlements(
                        user =
                            User(
                                atlassianOrgId = billingOrgId,
                                atlassianAccountId = accountId.value(),
                                cloudId = cloudId.toString(),
                                experienceId = experience.value,
                                isExistingBetaUser = isExistingBetaUser.toString(),
                                scmWorkspace = scmWorkspaceId,
                                scmRepository = scmRepositoryId,
                                devaiWorkspaceARI = ARI.of(DevaiWorkspaceARI.from(activationId)),
                            ),
                    )
                } returns
                    UserCreditResult(
                        userCreditLimits = null,
                        creditResult = CreditResult(CreditStatus.OK),
                    )
                coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true
                coEvery { featureService.isAutoreviewBillingEntitlementCheckMocked(any()) } returns false
                coEvery {
                    tcsService.getActivationIdsFromCloudIdAsync(any())
                } returns mapOf("devai" to ActivationIds(activationId))

                // Act
                val creditResult =
                    subject.checkCredit(
                        accountId = accountId,
                        cloudId = cloudId,
                        experience = experience,
                        isExistingBetaUser = isExistingBetaUser,
                        friendlyPullRequestUrl = pullRequestUrl,
                        scmWorkspaceId = scmWorkspaceId,
                        scmRepositoryId = scmRepositoryId,
                    )

                // Assert
                creditResult.creditResult.status shouldBe CreditStatus.OK
            }

        @Test
        fun `should throw when check entitlements fails`() =
            runTest {
                // Arrange
                val cloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")
                val accountId = AccountId.of("account-id")
                val experience = CodeReviewExperience.ROVODEV_REVIEW_BITBUCKET
                val isExistingBetaUser = false
                val pullRequestUrl = URI("https://bitbucket.org/ttran4repo/rovo-autoreview/pull-requests/10").toURL()
                val scmWorkspaceId = UUID.randomUUID().toString()
                val scmRepositoryId = UUID.randomUUID().toString()
                val billingOrgId = "billing-org-id"
                coEvery { tcsService.getOrgByCloudIdWithRetry(cloudId) } returns
                    LinkedOrg(
                        billingOrgId,
                        OrgLink("ari:org"),
                    )
                coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true
                coEvery { featureService.isAutoreviewBillingEntitlementCheckMocked(any()) } returns false
                val activationId = UUID.randomUUID().toString()
                coEvery {
                    tcsService.getActivationIdsFromCloudId(any())
                } returns mapOf("devai" to ActivationIds(activationId))
                coEvery {
                    entitlementChecksService.checkEntitlements(
                        user =
                            User(
                                atlassianOrgId = billingOrgId,
                                atlassianAccountId = accountId.value(),
                                cloudId = cloudId.toString(),
                                experienceId = experience.value,
                                isExistingBetaUser = isExistingBetaUser.toString(),
                                scmWorkspace = scmWorkspaceId,
                                scmRepository = scmRepositoryId,
                                devaiWorkspaceARI = ARI.of(DevaiWorkspaceARI.from(activationId)),
                            ),
                    )
                } throws Exception("Something went wrong")

                // Act
                shouldThrow<Exception> {
                    subject.checkCredit(
                        accountId = accountId,
                        cloudId = cloudId,
                        experience = experience,
                        isExistingBetaUser = isExistingBetaUser,
                        friendlyPullRequestUrl = pullRequestUrl,
                        scmWorkspaceId = scmWorkspaceId,
                        scmRepositoryId = scmRepositoryId,
                    )
                }
            }

        @Test
        fun `should throw when billing org is unavailable`() =
            runTest {
                // Arrange
                val cloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")
                val accountId = AccountId.of("account-id")
                val experience = CodeReviewExperience.ROVODEV_REVIEW_BITBUCKET
                val isExistingBetaUser = false
                val pullRequestUrl = URI("https://bitbucket.org/ttran4repo/rovo-autoreview/pull-requests/10").toURL()
                val scmWorkspaceId = UUID.randomUUID().toString()
                val scmRepositoryId = UUID.randomUUID().toString()
                val activationId = UUID.randomUUID().toString()
                coEvery {
                    tcsService.getActivationIdsFromCloudId(any())
                } returns mapOf("devai" to ActivationIds(activationId))
                coEvery { tcsService.getOrgByCloudIdWithRetry(cloudId) } returns null
                coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true
                coEvery { featureService.isAutoreviewBillingEntitlementCheckMocked(any()) } returns false

                // Act
                shouldThrow<IllegalStateException> {
                    subject.checkCredit(
                        accountId = accountId,
                        cloudId = cloudId,
                        experience = experience,
                        isExistingBetaUser = isExistingBetaUser,
                        friendlyPullRequestUrl = pullRequestUrl,
                        scmWorkspaceId = scmWorkspaceId,
                        scmRepositoryId = scmRepositoryId,
                    )
                }
            }

        @Test
        fun `should throw when devai workspace ari is unavailable`() =
            runTest {
                // Arrange
                val cloudId = CloudIdLike.fromString("5fd9b3db-3a95-4c43-b119-79d12ab4182e")
                val accountId = AccountId.of("account-id")
                val experience = CodeReviewExperience.ROVODEV_REVIEW_BITBUCKET
                val isExistingBetaUser = false
                val pullRequestUrl = URI("https://bitbucket.org/ttran4repo/rovo-autoreview/pull-requests/10").toURL()
                val scmWorkspaceId = UUID.randomUUID().toString()
                val scmRepositoryId = UUID.randomUUID().toString()
                coEvery {
                    tcsService.getActivationIdsFromCloudId(any())
                } returns emptyMap()
                coEvery { featureService.isAutoreviewBillingEntitlementCheckEnabled(any()) } returns true
                coEvery { featureService.isAutoreviewBillingEntitlementCheckMocked(any()) } returns false

                // Act
                shouldThrow<IllegalStateException> {
                    subject.checkCredit(
                        accountId = accountId,
                        cloudId = cloudId,
                        experience = experience,
                        isExistingBetaUser = isExistingBetaUser,
                        friendlyPullRequestUrl = pullRequestUrl,
                        scmWorkspaceId = scmWorkspaceId,
                        scmRepositoryId = scmRepositoryId,
                    )
                }
            }
    }

    @Nested
    inner class IsSiteHasRovodevProduct {
        private val testCloudId = CloudIdLike.fromString("test-cloud-id-123")
        private val testEventType = "avi:devops:created:pull-request"
        private val testPullRequestUrl = "https://bitbucket.org/workspace/repo/pull-requests/123"

        @BeforeEach
        fun setUp() {
            // Default setup - feature flag disabled to avoid excessive logging
            coEvery { featureService.isAutoreviewLogDDEventArrivalEnabled() } returns false
        }

        @Nested
        inner class WithRDEFeatureFlagEnabled {
            @ParameterizedTest
            @EnumSource(names = ["ROVO_DEV_BETA", "ROVO_DEV_STANDARD", "ROVO_DEV_STANDARD_TRIAL", "ROVO_DEV_EVERYWHERE"])
            fun `should return true when site has supported entitlements`(resourceType: ResourceType) =
                runTest {
                    // Given
                    coEvery {
                        siteEntitlementService.getEntitlementType(
                            testCloudId.toString(),
                            null,
                        )
                    } returns resourceType

                    // When
                    val result = subject.isSiteHasRovodevProduct(testCloudId, testEventType, testPullRequestUrl)

                    // Then
                    result shouldBe true
                    coVerify { siteEntitlementService.getEntitlementType(testCloudId.toString(), null) }
                }

            @Test
            fun `should return false when site has no rovo dev entitlement`() =
                runTest {
                    // Given
                    coEvery { siteEntitlementService.getEntitlementType(testCloudId.toString(), null) } returns
                        ResourceType.NO_ACTIVE_PRODUCT

                    // When
                    val result = subject.isSiteHasRovodevProduct(testCloudId, testEventType, testPullRequestUrl)

                    // Then
                    result shouldBe false
                }

            @Test
            fun `should return false when site entitlement is null`() =
                runTest {
                    // Given
                    coEvery {
                        siteEntitlementService.getEntitlementType(
                            testCloudId.toString(),
                            null,
                        )
                    } returns ResourceType.NO_ACTIVE_PRODUCT

                    // When
                    val result = subject.isSiteHasRovodevProduct(testCloudId, testEventType, testPullRequestUrl)

                    // Then
                    result shouldBe false
                }
        }
    }
}
