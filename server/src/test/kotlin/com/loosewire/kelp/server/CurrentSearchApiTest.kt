package com.loosewire.kelp.server

import com.loosewire.kelp.protocol.KelpErrorCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CurrentSearchApiTest {
    @Test
    fun continuationPreservesOpaqueSearchIdentityAndCursor() {
        val original = SearchContinuation("opaque / + % ? \" search", "cursor+/= %25 & unicode 音楽")

        assertEquals(original, SearchContinuation.decode(original.encode()))
    }

    @Test
    fun malformedContinuationIsAProtocolError() {
        for (value in listOf("not-json", "{}", "[]", "[\"id\"]", "[\"id\",null]", "[1,2]", "[\"id\",\"\"]")) {
            val error = assertFailsWith<TidalCatalogException> { SearchContinuation.decode(value) }
            assertEquals(KelpErrorCategory.Protocol, error.category)
        }
    }
}
