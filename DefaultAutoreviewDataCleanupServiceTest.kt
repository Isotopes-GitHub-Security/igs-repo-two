package devai.modules.autoreview.service

import devai.modules.acra.shared.model.RootWorkflowName
import devai.modules.acra.shared.persistence.WorkflowRunArtifactRepository
import devai.modules.acra.shared.persistence.WorkflowRunRepository
import devai.modules.acra.shared.persistence.model.WorkflowRunArtifactEntity
import devai.modules.autoreview.model.MAX_DELETED_RECORDS_PER_CLEANUP
import devai.modules.shared.features.DevAiCoreFeatureService
import devai.modules.shared.model.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class DefaultAutoreviewDataCleanupServiceTest {
    private lateinit var workflowRunRepository: WorkflowRunRepository
    private lateinit var workflowRunArtifactRepository: WorkflowRunArtifactRepository
    private lateinit var featureService: DevAiCoreFeatureService
    private lateinit var service: DefaultAutoreviewDataCleanupService

    private val testUser = User(atlassianAccountId = "test-account-id")
    private val fromDate = Instant.now().minus(60, ChronoUnit.DAYS)
    private val toDate = Instant.now().minus(30, ChronoUnit.DAYS)

    @BeforeEach
    fun setUp() {
        workflowRunRepository = mockk(relaxed = true)
        workflowRunArtifactRepository = mockk(relaxed = true)
        featureService = mockk(relaxed = true)
        service =
            DefaultAutoreviewDataCleanupService(
                workflowRunRepository = workflowRunRepository,
                workflowRunArtifactRepository = workflowRunArtifactRepository,
                featureService = featureService,
            )

        // By default, enable the feature flag
        coEvery { featureService.isAutoreviewWorkflowCleanUpServiceEnabled(any()) } returns true
    }

    @Test
    fun `should delete workflow runs and artifacts successfully`() =
        runTest {
            // Arrange
            val workflowRunId1 = UUID.randomUUID()
            val workflowRunId2 = UUID.randomUUID()
            val artifactId1 = UUID.randomUUID()
            val artifactId2 = UUID.randomUUID()
            val artifactId3 = UUID.randomUUID()

            val workflowRunIds =
                listOf(
                    workflowRunId1,
                    workflowRunId2,
                )

            val artifacts =
                listOf(
                    createMockArtifact(artifactId1, workflowRunId1),
                    createMockArtifact(artifactId2, workflowRunId1),
                    createMockArtifact(artifactId3, workflowRunId2),
                )

            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = toDate,
                )
            } returns workflowRunIds

            every {
                workflowRunArtifactRepository.findByWorkflowRunIds(
                    listOf(workflowRunId1, workflowRunId2),
                    emptyList(),
                )
            } returns artifacts

            every {
                workflowRunArtifactRepository.deleteAllById(
                    listOf(artifactId1, artifactId2, artifactId3),
                )
            } returns 3

            every {
                workflowRunRepository.deleteByIds(listOf(workflowRunId1, workflowRunId2))
            } returns 2

            // Act
            val result =
                service.deleteWorkflowRunData(
                    fromDate = fromDate,
                    toDate = toDate,
                    maxDeletedCount = 100,
                    user = testUser,
                )

            // Assert
            assertEquals(2, result)

            coVerify(exactly = 1) {
                featureService.isAutoreviewWorkflowCleanUpServiceEnabled(testUser)
            }

            coVerify(exactly = 1) {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = toDate,
                )
            }

            coVerify(exactly = 1) {
                workflowRunArtifactRepository.findByWorkflowRunIds(
                    listOf(workflowRunId1, workflowRunId2),
                    emptyList(),
                )
            }

            coVerify(exactly = 1) {
                workflowRunArtifactRepository.deleteAllById(
                    listOf(artifactId1, artifactId2, artifactId3),
                )
            }

            coVerify(exactly = 1) {
                workflowRunRepository.deleteByIds(listOf(workflowRunId1, workflowRunId2))
            }
        }

    @Test
    fun `should handle no workflow runs found`() =
        runTest {
            // Arrange
            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = toDate,
                )
            } returns emptyList()

            // Act
            val result =
                service.deleteWorkflowRunData(
                    fromDate = fromDate,
                    toDate = toDate,
                    maxDeletedCount = 100,
                    user = testUser,
                )

            // Assert
            assertEquals(0, result)

            coVerify(exactly = 1) {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = toDate,
                )
            }

            coVerify(exactly = 0) {
                workflowRunArtifactRepository.findByWorkflowRunIds(any(), any())
            }

            coVerify(exactly = 0) {
                workflowRunArtifactRepository.deleteAllById(any())
            }

            coVerify(exactly = 0) {
                workflowRunRepository.deleteByIds(any())
            }
        }

    @Test
    fun `should pass null toDate to repository when null is provided`() =
        runTest {
            // Arrange
            val workflowRuns = listOf(UUID.randomUUID())

            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = null,
                )
            } returns workflowRuns

            every {
                workflowRunArtifactRepository.findByWorkflowRunIds(any(), emptyList())
            } returns emptyList()

            every {
                workflowRunRepository.deleteByIds(any())
            } returns 1

            // Act
            val result =
                service.deleteWorkflowRunData(
                    fromDate = fromDate,
                    toDate = null,
                    maxDeletedCount = 100,
                    user = testUser,
                )

            // Assert
            assertEquals(1, result)

            coVerify(exactly = 1) {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = null,
                )
            }
        }

    @Test
    fun `should coerce maxDeletedCount to MAX_DELETED_RECORDS_PER_CLEANUP`() =
        runTest {
            // Arrange
            val excessiveLimit = 20000
            val workflowRuns = listOf(UUID.randomUUID())

            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = MAX_DELETED_RECORDS_PER_CLEANUP,
                    startDate = fromDate,
                    endDate = toDate,
                )
            } returns workflowRuns

            every {
                workflowRunArtifactRepository.findByWorkflowRunIds(any(), emptyList())
            } returns emptyList()

            every {
                workflowRunRepository.deleteByIds(any())
            } returns 1

            // Act
            val result =
                service.deleteWorkflowRunData(
                    fromDate = fromDate,
                    toDate = toDate,
                    maxDeletedCount = excessiveLimit,
                    user = testUser,
                )

            // Assert
            assertEquals(1, result)

            coVerify(exactly = 1) {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = MAX_DELETED_RECORDS_PER_CLEANUP,
                    startDate = fromDate,
                    endDate = toDate,
                )
            }
        }

    @Test
    fun `should handle workflow runs with no artifacts`() =
        runTest {
            // Arrange
            val workflowRunId = UUID.randomUUID()
            val workflowRuns = listOf(workflowRunId)

            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = toDate,
                )
            } returns workflowRuns

            every {
                workflowRunArtifactRepository.findByWorkflowRunIds(
                    listOf(workflowRunId),
                    emptyList(),
                )
            } returns emptyList()

            every {
                workflowRunArtifactRepository.deleteAllById(emptyList())
            } returns 0

            every {
                workflowRunRepository.deleteByIds(listOf(workflowRunId))
            } returns 1

            // Act
            val result =
                service.deleteWorkflowRunData(
                    fromDate = fromDate,
                    toDate = toDate,
                    maxDeletedCount = 100,
                    user = testUser,
                )

            // Assert
            assertEquals(1, result)

            coVerify(exactly = 1) {
                workflowRunArtifactRepository.deleteAllById(emptyList())
            }

            coVerify(exactly = 1) {
                workflowRunRepository.deleteByIds(listOf(workflowRunId))
            }
        }

    @Test
    fun `should handle mismatch between expected and actual deleted count`() =
        runTest {
            // Arrange
            val workflowRunId1 = UUID.randomUUID()
            val workflowRunId2 = UUID.randomUUID()
            val workflowRuns =
                listOf(
                    workflowRunId1,
                    workflowRunId2,
                )

            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = toDate,
                )
            } returns workflowRuns

            every {
                workflowRunArtifactRepository.findByWorkflowRunIds(any(), emptyList())
            } returns emptyList()

            every {
                workflowRunArtifactRepository.deleteAllById(emptyList())
            } returns 0

            // Only 1 workflow run deleted instead of 2
            every {
                workflowRunRepository.deleteByIds(listOf(workflowRunId1, workflowRunId2))
            } returns 1

            // Act
            val result =
                service.deleteWorkflowRunData(
                    fromDate = fromDate,
                    toDate = toDate,
                    maxDeletedCount = 100,
                    user = testUser,
                )

            // Assert
            assertEquals(1, result)

            coVerify(exactly = 1) {
                workflowRunRepository.deleteByIds(listOf(workflowRunId1, workflowRunId2))
            }
        }

    @Test
    fun `should respect maxDeletedCount limit when less than MAX_DELETED_RECORDS_PER_CLEANUP`() =
        runTest {
            // Arrange
            val customLimit = 50
            val workflowRuns = listOf(UUID.randomUUID())

            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = customLimit,
                    startDate = fromDate,
                    endDate = toDate,
                )
            } returns workflowRuns

            every {
                workflowRunArtifactRepository.findByWorkflowRunIds(any(), emptyList())
            } returns emptyList()

            every {
                workflowRunRepository.deleteByIds(any())
            } returns 1

            // Act
            val result =
                service.deleteWorkflowRunData(
                    fromDate = fromDate,
                    toDate = toDate,
                    maxDeletedCount = customLimit,
                    user = testUser,
                )

            // Assert
            assertEquals(1, result)

            coVerify(exactly = 1) {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = customLimit,
                    startDate = fromDate,
                    endDate = toDate,
                )
            }
        }

    @Test
    fun `should delete artifacts before workflow runs`() =
        runTest {
            // Arrange
            val workflowRunId = UUID.randomUUID()
            val artifactId = UUID.randomUUID()
            val workflowRuns = listOf(workflowRunId)
            val artifacts = listOf(createMockArtifact(artifactId, workflowRunId))

            val deletionOrder = mutableListOf<String>()

            every {
                workflowRunRepository.findByDates(
                    rootWorkflows = RootWorkflowName.AUTOREVIEW_WORKFLOWS.toList(),
                    limit = 100,
                    startDate = fromDate,
                    endDate = toDate,
                )
            } returns workflowRuns

            every {
                workflowRunArtifactRepository.findByWorkflowRunIds(
                    listOf(workflowRunId),
                    emptyList(),
                )
            } returns artifacts

            every {
                workflowRunArtifactRepository.deleteAllById(listOf(artifactId))
            } answers {
                deletionOrder.add("artifacts")
                1
            }

            every {
                workflowRunRepository.deleteByIds(listOf(workflowRunId))
            } answers {
                deletionOrder.add("workflowRuns")
                1
            }

            // Act
            service.deleteWorkflowRunData(
                fromDate = fromDate,
                toDate = toDate,
                maxDeletedCount = 100,
                user = testUser,
            )

            // Assert
            assertEquals(listOf("artifacts", "workflowRuns"), deletionOrder)
        }

    @Test
    fun `should throw exception when feature flag is disabled`() =
        runTest {
            // Arrange
            coEvery { featureService.isAutoreviewWorkflowCleanUpServiceEnabled(any()) } returns false

            // Act & Assert
            val exception =
                assertThrows<IllegalAccessException> {
                    service.deleteWorkflowRunData(
                        fromDate = fromDate,
                        toDate = toDate,
                        maxDeletedCount = 100,
                        user = testUser,
                    )
                }

            assertEquals("Autoreview workflow cleanup service is disabled for user", exception.message)

            // Verify that no repository methods were called
            coVerify(exactly = 0) {
                workflowRunRepository.findByDates(any(), any(), any(), any())
            }
        }

    private fun createMockArtifact(
        id: UUID,
        workflowRunId: UUID,
    ): WorkflowRunArtifactEntity =
        WorkflowRunArtifactEntity(
            id = id,
            name = "test-artifact",
            workflowRunId = workflowRunId,
            data = "{}",
            createdTimestamp = Instant.now(),
            updatedTimestamp = Instant.now(),
        )
}
