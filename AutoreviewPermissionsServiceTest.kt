package devai.modules.autoreview.service

import com.atlassian.usercontext.api.AccountId
import devai.modules.clients.sal.GetUserPermissionsForRepoResponse
import devai.modules.clients.sal.SalClient
import devai.modules.shared.exception.SalException
import devai.modules.shared.model.UserContext
import devai.modules.shared.utils.IMeterRegistry
import devai.modules.tenant.model.TransactionContext
import devai.modules.tenant.model.WorkspaceContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus

class AutoreviewPermissionsServiceTest {
    private val aaid = "aaid"
    private val uct = "uct"
    private val transactionContext =
        TransactionContext(
            workspace = WorkspaceContext(null, null, null),
            traceId = null,
            userContext =
                UserContext(
                    accountId = AccountId.of(aaid),
                    userContextToken = uct,
                    accountType = null,
                    tokenExpiration = null,
                ),
        )
    private lateinit var salClient: SalClient
    private lateinit var meterRegistry: IMeterRegistry
    private lateinit var service: AutoreviewPermissionsService

    @BeforeEach
    fun setup() {
        salClient = mockk()
        meterRegistry = mockk(relaxed = true)
        service = AutoreviewPermissionsService(salClient, meterRegistry)
    }

    @ValueSource(
        strings = [
            "https://stash.atlassian.com/scm/project/repo.git",
            "https://github.com/workspace/repo.git",
            "https://gitlab.com/workspace/repo.git",
        ],
    )
    @ParameterizedTest
    fun `test invalid repository throws permission exception, and doesn't call Sal`(repoUrl: String): Unit =
        runBlocking {
            // When checking permissions, should throw AutoreviewPermissionException
            assertThrows(AutoreviewPermissionException::class.java) {
                runBlocking {
                    service.assertRepoReadPermission(transactionContext, repoUrl)
                }
            }
            // And doesn't call sal
            coVerify(exactly = 0) {
                salClient.getUserPermissionsForRepo(any(), any())
            }
        }

    @Test
    fun `test repository not found - 404 response`() =
        runBlocking {
            // Given
            val repoUrl = "https://bitbucket.org/workspace/repo.git"
            coEvery {
                salClient.getUserPermissionsForRepo(repoUrl, any())
            } returns GetUserPermissionsForRepoResponse(404)

            // When & Then
            val exception =
                assertThrows(AutoreviewPermissionException::class.java) {
                    runBlocking {
                        service.assertRepoReadPermission(transactionContext, repoUrl)
                    }
                }

            assertEquals(HttpStatus.NOT_FOUND, exception.httpStatus)
            assertEquals(PermissionErrorType.REPO_NOT_FOUND, exception.errorType)
        }

    @Test
    fun `test bitbucket repository forbidden - 403 response`() =
        runBlocking {
            // Given
            val repoUrl = "https://bitbucket.org/workspace/repo.git"
            coEvery {
                salClient.getUserPermissionsForRepo(repoUrl, any())
            } throws SalException(HttpStatus.FORBIDDEN.value(), "Forbidden")

            // When & Then
            val exception =
                assertThrows(AutoreviewPermissionException::class.java) {
                    runBlocking {
                        service.assertRepoReadPermission(transactionContext, repoUrl)
                    }
                }

            assertEquals(HttpStatus.FORBIDDEN, exception.httpStatus)
            assertEquals(PermissionErrorType.REPO_NOT_FOUND, exception.errorType)
        }

    @Test
    fun `test too many requests - 429 response`() =
        runBlocking {
            // Given
            val repoUrl = "https://bitbucket.org/workspace/repo.git"
            val salException = SalException(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests")
            coEvery {
                salClient.getUserPermissionsForRepo(repoUrl, any())
            } throws salException

            // When & Then
            val exception =
                assertThrows(SalException::class.java) {
                    runBlocking {
                        service.assertRepoReadPermission(transactionContext, repoUrl)
                    }
                }

            assertEquals(salException, exception)
        }

    @Test
    fun `test other error status code`() =
        runBlocking {
            // Given
            val repoUrl = "https://bitbucket.org/workspace/repo.git"
            val salException = SalException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error")
            coEvery {
                salClient.getUserPermissionsForRepo(repoUrl, any())
            } throws salException

            // When & Then
            val exception =
                assertThrows(SalException::class.java) {
                    runBlocking {
                        service.assertRepoReadPermission(transactionContext, repoUrl)
                    }
                }

            assertEquals(salException, exception)
        }

    @Test
    fun `test invalid URL throws IllegalArgumentException`() {
        // Given
        val invalidUrl = "not-a-url"

        // When & Then
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.assertRepoReadPermission(transactionContext, invalidUrl)
            }
        }
    }

    @Test
    fun `test successful permission check`() =
        runBlocking {
            // Given
            val repoUrl = "https://bitbucket.org/workspace/repo.git"
            coEvery {
                salClient.getUserPermissionsForRepo(repoUrl, any())
            } returns GetUserPermissionsForRepoResponse(200)

            // When & Then
            // Should not throw any exception
            service.assertRepoReadPermission(transactionContext, repoUrl)
        }

    @Test
    fun `test unidentified account ID`() =
        runBlocking {
            // Given
            val repoUrl = "https://bitbucket.org/workspace/repo.git"
            val transactionContextWithoutUser =
                mockk<TransactionContext> {
                    every { userContext } returns null
                }

            coEvery {
                salClient.getUserPermissionsForRepo(repoUrl, AccountId.UNIDENTIFIED.value())
            } throws SalException(HttpStatus.NOT_FOUND.value(), "Unidentified account")

            // When & Then
            val exception =
                assertThrows(AutoreviewPermissionException::class.java) {
                    runBlocking {
                        service.assertRepoReadPermission(transactionContextWithoutUser, repoUrl)
                    }
                }

            assertEquals(HttpStatus.NOT_FOUND, exception.httpStatus)
            assertEquals(PermissionErrorType.REPO_NOT_FOUND, exception.errorType)
        }
}
