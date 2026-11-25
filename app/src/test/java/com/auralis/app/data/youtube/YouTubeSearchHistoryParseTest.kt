package com.auralis.app.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the recent-search parser against the real WEB_REMIX shape: the suggestion endpoint called with
 * an empty input answers with `historySuggestionRenderer` entries (each carrying a `feedbackToken`
 * used to delete the term) mixed with `searchSuggestionRenderer` completions.
 */
class YouTubeSearchHistoryParseTest {
    private val client = InnerTubeClient(OkHttpClient(), auth = { null })

    private fun parse(payload: String) =
        client.parseSearchHistorySuggestions(Json.parseToJsonElement(payload) as JsonObject)

    @Test
    fun `history entries carry a feedback token and completions do not`() {
        val suggestions = parse(
            """
            {"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
              {"historySuggestionRenderer":{
                 "suggestion":{"runs":[{"text":"adele"}]},
                 "navigationEndpoint":{"searchEndpoint":{"query":"adele"}},
                 "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"TOKEN123"}}}},
              {"searchSuggestionRenderer":{
                 "suggestion":{"runs":[{"text":"adele"},{"text":" hello"}]},
                 "navigationEndpoint":{"searchEndpoint":{"query":"adele hello"}}}}
            ]}}]}
            """.trimIndent(),
        )
        assertEquals(2, suggestions.size)
        assertEquals("adele", suggestions[0].text)
        assertEquals(true, suggestions[0].fromHistory)
        assertEquals("TOKEN123", suggestions[0].feedbackToken)
        assertEquals("adele hello", suggestions[1].text)
        assertEquals(false, suggestions[1].fromHistory)
        assertNull(suggestions[1].feedbackToken)
    }

    @Test
    fun `duplicate or missing sections yield no suggestions instead of throwing`() {
        assertEquals(0, parse("""{"contents":[]}""").size)
        assertEquals(0, parse("""{}""").size)
    }
}
