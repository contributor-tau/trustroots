package org.trustroots.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.trustroots.android.api.CONVERSATION_PAGE_SIZE
import org.trustroots.android.api.MemberSession
import org.trustroots.android.api.MobileApiClient
import org.trustroots.android.api.MobileMember

@RunWith(AndroidJUnit4::class)
class ConversationEarlierMessagesJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var fixture: LocalTransportFixture? = null

    @After fun closeFixture() {
        fixture?.close()
    }

    @Test fun loadsEarlierMessagesAfterAFailedAttempt() {
        val earlierRequests = AtomicInteger()
        val releaseEarlierPage = CountDownLatch(1)
        val local = LocalTransportFixture { request ->
            when {
                request.method == "GET" && request.path == "/api/users/mini/member-id" ->
                    LocalTransportFixture.Response(
                        """{"_id":"member-id","username":"fictional-member","displayName":"Fictional Member"}""",
                    )
                request.method == "GET" && request.path == "/api/messages/member-id?limit=100" ->
                    LocalTransportFixture.Response(messages(CONVERSATION_PAGE_SIZE) { "Fictional recent note $it" })
                request.method == "GET" && request.path == "/api/messages/member-id?limit=100&page=2" ->
                    if (earlierRequests.incrementAndGet() == 1) {
                        LocalTransportFixture.Response(
                            """{"message":"Fictional history is temporarily unavailable."}""",
                            status = 503,
                        )
                    } else {
                        releaseEarlierPage.await(10, TimeUnit.SECONDS)
                        LocalTransportFixture.Response(messages(1) { "Fictional earliest note" })
                    }
                else -> LocalTransportFixture.Response("{}", status = 404)
            }
        }.also { fixture = it }

        compose.setContent {
            ConversationRouteTestHarness(
                api = MobileApiClient(local.baseURL),
                session = MemberSession(
                    "connect.sid=fictional-session",
                    MobileMember("steady-heron", "Steady Heron"),
                ),
                memberID = "member-id",
                onSessionInvalidated = {},
            )
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Fictional recent note 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("conversationMessages").performScrollToNode(hasTestTag("conversationLoadEarlier"))
        compose.onNodeWithText("Load earlier messages").assertIsDisplayed().performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("conversationEarlierFailure").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Fictional history is temporarily unavailable.").assertIsDisplayed()
        compose.onNodeWithTag("conversationFailure").assertDoesNotExist()
        compose.onNodeWithTag("conversationLoadEarlier").assertIsDisplayed().performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("conversationEarlierLoading").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("conversationEarlierFailure").assertDoesNotExist()
        releaseEarlierPage.countDown()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("conversationEarlierLoading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("conversationMessages").performScrollToNode(hasText("Fictional earliest note"))
        compose.onNodeWithText("Fictional earliest note").assertIsDisplayed()
        compose.onNodeWithTag("conversationLoadEarlier").assertDoesNotExist()
        assertEquals(2, local.requests.count { it.path == "/api/messages/member-id?limit=100&page=2" })
        assertEquals(1, local.requests.count { it.path == "/api/messages/member-id?limit=100" })
    }

    private fun messages(count: Int, content: (Int) -> String): String = JSONArray().apply {
        repeat(count) { index ->
            put(JSONObject()
                .put("_id", "message-${content(index).hashCode()}-$index")
                .put("content", content(index))
                .put("userFrom", JSONObject()
                    .put("_id", "member-id")
                    .put("username", "fictional-member")
                    .put("displayName", "Fictional Member")))
        }
    }.toString()
}
