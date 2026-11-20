package com.auralis.app.data.youtube

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist parsing and paging. The Library grid is built from two different renderers — the
 * two-row card on landing pages and the responsive list row — and the live app showed an empty
 * library because only the first was read and the id kept its `VL` prefix.
 */
class YouTubeLibraryParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun root(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    private fun client(
        handler: (request: Request) -> InnerTubeResponse,
        cap: Int = 200,
    ) = InnerTubeClient(
        http = OkHttpClient(),
        auth = {
            YouTubeAuth(cookie = "SAPISID=secret", dataSyncId = "account||active")
        },
        transport = { request -> handler(request) },
        playlistCap = cap,
    )

    @Test
    fun `grid card and list row both parse, with VL stripped and New playlist skipped`() {
        val page = root(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "gridRenderer": {
                              "items": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {"runs": [{"text": "Road Trip"}]},
                                    "subtitle": {"runs": [{"text": "Playlist \u2022 42 songs"}]},
                                    "navigationEndpoint": {
                                      "browseEndpoint": {"browseId": "VLPLabc123"}
                                    }
                                  }
                                },
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": {"runs": [{"text": "New playlist"}]},
                                    "navigationEndpoint": {
                                      "browseEndpoint": {"browseId": "VLPLshouldNotAppear"}
                                    }
                                  }
                                },
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {"runs": [{"text": "Auto Mix"}]}
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {"runs": [{"text": "Auto playlist \u2022 30 songs"}]}
                                        }
                                      }
                                    ],
                                    "navigationEndpoint": {
                                      "browseEndpoint": {"browseId": "VLPLrow456"}
                                    }
                                  }
                                },
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {"runs": [{"text": "Liked Music"}]}
                                        }
                                      }
                                    ],
                                    "navigationEndpoint": {
                                      "browseEndpoint": {"browseId": "VLLM"}
                                    }
                                  }
                                }
                              ]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent(),
        )

        val playlists = client({ InnerTubeResponse(200, "{}") }).parsePlaylistPage(page)

        assertEquals(listOf("PLabc123", "PLrow456", "LM"), playlists.map { it.id })
        assertEquals("Road Trip", playlists[0].title)
        assertEquals(42, playlists[0].trackCount)
        assertEquals("Auto Mix", playlists[1].title)
        assertEquals(30, playlists[1].trackCount)
        assertTrue(playlists.none { it.title == "New playlist" })
    }

    @Test
    fun `continuation pages are followed and merged without duplicates`() = runBlocking {
        val pageOne =
            """
            {
              "contents": {"items": [
                {"musicTwoRowItemRenderer": {
                  "title": {"runs": [{"text": "First"}]},
                  "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLone"}}
                }}
              ]},
              "continuations": [
                {"nextContinuationData": {"continuation": "TOKEN-1"}}
              ]
            }
            """.trimIndent()
        val pageTwo =
            """
            {
              "contents": {"items": [
                {"musicTwoRowItemRenderer": {
                  "title": {"runs": [{"text": "Second"}]},
                  "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLtwo"}}
                }},
                {"musicTwoRowItemRenderer": {
                  "title": {"runs": [{"text": "First again"}]},
                  "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLone"}}
                }}
              ]}
            }
            """.trimIndent()

        var browseRequests = 0
        val client = client({ request ->
            if (request.url.encodedPath.endsWith("/browse")) {
                browseRequests++
                val body = Buffer()
                request.body?.writeTo(body)
                val payload = body.readUtf8()
                if (payload.contains("TOKEN-1")) {
                    InnerTubeResponse(200, pageTwo)
                } else {
                    InnerTubeResponse(200, pageOne)
                }
            } else {
                InnerTubeResponse(200, "{}")
            }
        })

        val result = client.likedPlaylists()

        assertTrue(result is YouTubeLoadResult.Success)
        val playlists = (result as YouTubeLoadResult.Success).value
        assertEquals(listOf("PLone", "PLtwo"), playlists.map { it.id })
        assertEquals(2, browseRequests)
    }

    @Test
    fun `the cap stops paging even when a continuation is offered`() = runBlocking {
        val page =
            """
            {
              "contents": {"items": [
                {"musicTwoRowItemRenderer": {
                  "title": {"runs": [{"text": "Only"}]},
                  "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLonly"}}
                }}
              ]},
              "continuations": [
                {"nextContinuationData": {"continuation": "LOOP"}}
              ]
            }
            """.trimIndent()

        var browseRequests = 0
        val client = client(
            handler = { request ->
                if (request.url.encodedPath.endsWith("/browse")) browseRequests++
                InnerTubeResponse(200, page)
            },
            cap = 1,
        )

        val result = client.likedPlaylists()

        assertTrue(result is YouTubeLoadResult.Success)
        assertEquals(1, (result as YouTubeLoadResult.Success).value.size)
        // Primary browse only: the item cap was reached, so the token is not followed.
        assertEquals(1, browseRequests)
    }

    @Test
    fun `a refused browse is a failure, not an empty library`() = runBlocking {
        val client = client({ _ -> InnerTubeResponse(503, "{}") })

        val result = client.likedPlaylists()

        assertTrue(result is YouTubeLoadResult.Failure)
        assertEquals(503, (result as YouTubeLoadResult.Failure).code)
        assertNotNull(result.reason)
    }
}