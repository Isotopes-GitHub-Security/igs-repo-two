package devai.modules.autoreview.service

import com.atlassian.ari.principled.bitbucket.BitbucketPullrequestARI
import com.atlassian.ari.principled.graph.GraphWorkspaceARI
import com.atlassian.ari.principled.jira.JiraIssueARI
import devai.modules.clients.agg.AggClient
import devai.modules.clients.agg.ExternalAssociation
import devai.modules.clients.agg.ExternalAssociationConnection
import devai.modules.clients.agg.ExternalAssociationEdge
import devai.modules.clients.agg.ExternalEntities
import devai.modules.clients.agg.ExternalEntitiesV2Query
import devai.modules.clients.agg.ExternalEntitiesV2Response
import devai.modules.clients.agg.ExternalPullRequest
import devai.modules.clients.agg.JiraIssue
import devai.modules.shared.client.AtlassianProxyClient
import devai.modules.shared.features.DevAiCoreFeatureService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSingleElement
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.jvm.optionals.getOrNull

const val TEST_JIRA_ISSUE_ARI_STRING = "ari:cloud:jira:922168f0-256f-49e0-ac04-4db48b68d2ea:issue/3456"
val TEST_JIRA_ISSUE_ARI = JiraIssueARI.tryParse(TEST_JIRA_ISSUE_ARI_STRING).getOrNull()

private const val TEST_UCT = "uct"

class AutoreviewJiraIssueServiceTest {
    private val aggClient = mockk<AggClient>(relaxed = true)
    private val featureService = mockk<DevAiCoreFeatureService>(relaxed = true)
    private val proxyClient = mockk<AtlassianProxyClient>(relaxed = true)

    private val subject =
        AutoreviewJiraIssueService(
            aggClient,
            featureService,
            proxyClient,
        )

    @Nested
    inner class GetJiraIssueArisByBitbucketPullRequestARI {
        @Test
        fun `returns list of JiraIssueARIs when issues are found`() =
            runTest {
                // Arrange
                mockSuccessfulGetIssuesByBitbucketPullRequest(
                    listOf(
                        JiraIssue(
                            id = TEST_JIRA_ISSUE_ARI_STRING,
                        ),
                    ),
                )
                val graphWorkspaceARI = GraphWorkspaceARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2")
                val bbcPullrequestARI =
                    BitbucketPullrequestARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2:123")

                // Act
                val result =
                    subject
                        .getJiraIssueArisByBitbucketPullRequestARI(
                            graphWorkspaceARI,
                            bbcPullrequestARI,
                            TEST_UCT,
                        )

                // Assert
                result shouldHaveSingleElement TEST_JIRA_ISSUE_ARI
            }

        @Test
        fun `returns empty list when no jira issues found`() =
            runTest {
                // Arrange
                mockSuccessfulGetIssuesByBitbucketPullRequest(emptyList())
                val graphWorkspaceARI = GraphWorkspaceARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2")
                val bbcPullrequestARI =
                    BitbucketPullrequestARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2:123")

                // Act
                val result =
                    subject
                        .getJiraIssueArisByBitbucketPullRequestARI(
                            graphWorkspaceARI,
                            bbcPullrequestARI,
                            TEST_UCT,
                        )

                // Assert
                result shouldHaveSize 0
            }

        @Test
        fun `Should throw exception if Jira issue ID is not an ARI`() =
            runTest {
                // Arrange
                mockSuccessfulGetIssuesByBitbucketPullRequest(
                    listOf(
                        JiraIssue(
                            id = "3456",
                        ),
                    ),
                )
                val graphWorkspaceARI = GraphWorkspaceARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2")
                val bbcPullrequestARI =
                    BitbucketPullrequestARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2:123")

                // Act / Assert
                shouldThrow<AutoreviewJiraIssueServiceException> {
                    subject
                        .getJiraIssueArisByBitbucketPullRequestARI(
                            graphWorkspaceARI,
                            bbcPullrequestARI,
                            TEST_UCT,
                        )
                }
            }

        @Test
        fun `should handle when pull request is empty`() =
            runTest {
                // Arrange
                mockEmptyPullRequestsGetIssuesByBitbucketPullRequest()

                val graphWorkspaceARI = GraphWorkspaceARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2")
                val bbcPullrequestARI =
                    BitbucketPullrequestARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2:123")

                // Act
                val result =
                    subject
                        .getJiraIssueArisByBitbucketPullRequestARI(
                            graphWorkspaceARI,
                            bbcPullrequestARI,
                            TEST_UCT,
                        )

                // Act / Assert
                result shouldHaveSize 0
            }

        @Test
        fun `should handle AGG exception`() =
            runTest {
                // Arrange
                coEvery { aggClient.getIssuesByBitbucketPullRequest(any(), any(), any()) } throws Exception("Something went wrong")

                val graphWorkspaceARI = GraphWorkspaceARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2")
                val bbcPullrequestARI =
                    BitbucketPullrequestARI.from("b7dce2a9-02ea-407f-b9ce-b05b8b17d3d2:123")

                // Act
                shouldThrow<AutoreviewJiraIssueServiceException> {
                    subject
                        .getJiraIssueArisByBitbucketPullRequestARI(
                            graphWorkspaceARI,
                            bbcPullrequestARI,
                            TEST_UCT,
                        )
                }
            }

        private fun mockSuccessfulGetIssuesByBitbucketPullRequest(jiraIssues: List<JiraIssue>) {
            coEvery { aggClient.getIssuesByBitbucketPullRequest(any(), any(), any()) } returns
                ExternalEntitiesV2Response(
                    data =
                        ExternalEntitiesV2Query(
                            externalEntitiesV2 =
                                ExternalEntities(
                                    pullRequest =
                                        listOf(
                                            ExternalPullRequest(
                                                id = "1",
                                                associatedWith =
                                                    ExternalAssociationConnection(
                                                        edges =
                                                            jiraIssues.map {
                                                                ExternalAssociationEdge(
                                                                    node =
                                                                        ExternalAssociation(
                                                                            entity = it,
                                                                        ),
                                                                )
                                                            },
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                    errors = emptyList(),
                )
        }

        private fun mockEmptyPullRequestsGetIssuesByBitbucketPullRequest() {
            coEvery { aggClient.getIssuesByBitbucketPullRequest(any(), any(), any()) } returns
                ExternalEntitiesV2Response(
                    data =
                        ExternalEntitiesV2Query(
                            externalEntitiesV2 =
                                ExternalEntities(
                                    pullRequest = emptyList(),
                                ),
                        ),
                    errors = emptyList(),
                )
        }
    }
}
