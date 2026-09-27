package com.loosewire.kelp.server

import com.loosewire.kelp.protocol.KelpErrorCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Compatibility surface for the current TIDAL search contract.
 *
 * TIDAL Android SDK 0.3.53 was generated from API schema 1.10.86, where search
 * used `/searchResults/{id}` and returned a single resource. The current API
 * uses `/searchResults?filter[query]=...` and returns a resource array.
 * Continuations belong to the selected relationship, not that collection.
 * Keep this adapter narrow so the generated client owns unchanged endpoints.
 */
internal interface CurrentSearchApi {
    @GET("searchResults")
    suspend fun searchResultsGet(
        @Query("filter[query]") query: String,
        @Query("include") include: String,
    ): Response<String>

    @GET("searchResults/{id}/relationships/{relationship}")
    suspend fun searchRelationshipGet(
        @Path("id") id: String,
        @Path("relationship") relationship: String,
        @Query("include") include: String,
        @Query("page[cursor]") pageCursor: String,
    ): Response<String>
}

/** Both values are opaque; the app simply returns this continuation unchanged. */
internal data class SearchContinuation(val searchId: String, val pageCursor: String) {
    fun encode(): String = JsonArray(listOf(JsonPrimitive(searchId), JsonPrimitive(pageCursor))).toString()

    companion object {
        fun decode(value: String): SearchContinuation = try {
            val parts = Json.parseToJsonElement(value).jsonArray
            require(parts.size == 2)
            require(parts.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() })
            SearchContinuation(parts[0].jsonPrimitive.content, parts[1].jsonPrimitive.content)
        } catch (_: IllegalArgumentException) {
            throw TidalCatalogException(KelpErrorCategory.Protocol, "Invalid search continuation. Search again.")
        }
    }
}

/**
 * Raw response surface for track resources.
 *
 * API schema 1.10.86 marks the musical-analysis fields `key` and `keyScale`
 * as required, while catalog responses legitimately omit them. Returning the
 * JSON body as a string keeps those unused fields from making every song list
 * fail before Kelp can map the fields it actually displays.
 */
internal interface CurrentTracksApi {
    @GET("tracks")
    suspend fun tracksGet(
        @Query("include") include: List<String>,
        @Query("filter[id]") filterId: List<String>,
    ): Response<String>
}
