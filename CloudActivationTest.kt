package devai.modules.autoreview.service

import com.atlassian.ari.principled.CloudIdLike
import io.atlassian.tcs.model.cloud.ActivationIds
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Optional
import java.util.stream.Stream

private const val TEST_ACTIVATION_KEY = "someThing"

class CloudActivationTest {
    private val cloudIdLike = mockk<CloudIdLike>(relaxed = true)

    @ParameterizedTest
    @MethodSource("getActiveIdExpectedValues")
    fun `getActiveId returns expected values`(
        activationIds: Map<String, ActivationIds>,
        expectedValue: String?,
    ) {
        CloudActivation(
            cloudId = cloudIdLike,
            activationIds = activationIds,
        ).getActiveId(TEST_ACTIVATION_KEY) shouldBe expectedValue
    }

    companion object {
        @JvmStatic
        private fun getActiveIdExpectedValues(): Stream<Arguments> =
            Stream.of(
                Arguments.argumentSet(
                    "returns null when activation Ids is empty map",
                    emptyMap<String, ActivationIds>(),
                    null,
                ),
                Arguments.argumentSet(
                    "returns null when key is not in map",
                    mapOf("otherThing" to ActivationIds("activeId1")),
                    null,
                ),
                Arguments.argumentSet(
                    "returns active id of ActivationIds associated to given key (case-sensitive)",
                    mapOf(
                        "SOMETHING" to ActivationIds("activeId1"),
                        "something" to ActivationIds("activeId2"),
                        "someThing" to ActivationIds("activeId3"),
                    ),
                    "activeId3",
                ),
                Arguments.argumentSet(
                    "null when ActivationIds active is not present",
                    mapOf(
                        "someThing" to ActivationIds(Optional.empty(), null),
                    ),
                    null,
                ),
            )
    }
}
