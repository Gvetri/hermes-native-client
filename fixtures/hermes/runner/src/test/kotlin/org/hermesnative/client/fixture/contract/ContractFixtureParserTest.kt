package org.hermesnative.client.fixture.contract

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hermesnative.client.fixture.PinnedFixtureDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ContractFixtureParserTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("fixture.repositoryRoot")) {
                "fixture.repositoryRoot must identify the repository root"
            },
        )
    private val descriptor: PinnedFixtureDescriptor
        get() = PinnedFixtureDescriptor.load(repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties"))
    private val contractsRoot: File
        get() = repositoryRoot.resolve("fixtures/hermes/contracts")

    @Test
    fun catalog_matches_all_checked_in_contract_fixture_files() {
        val actualFiles =
            contractsRoot.walkTopDown()
                .filter { it.isFile && it.extension in setOf("json", "sse") }
                .map { it.relativeTo(contractsRoot).invariantSeparatorsPath }
                .toSet()

        assertEquals(ContractFixtureCatalog.definitions.map { it.path }.toSet(), actualFiles)
    }

    @Test
    fun every_checked_in_fixture_declares_one_matching_provenance_field() {
        val otherField = if (descriptor.provenanceField == "hermes_revision") "image_digest" else "hermes_revision"

        ContractFixtureCatalog.definitions.forEach { definition ->
            val content = contractsRoot.resolve(definition.path).readText()
            val provenanceCount = ContractFixtureParser.countJsonMember(content, descriptor.provenanceField)
            val otherCount = ContractFixtureParser.countJsonMember(content, otherField)
            assertEquals("Unexpected provenance count in ${definition.path}", 1, provenanceCount)
            assertEquals("Unexpected alternate provenance in ${definition.path}", 0, otherCount)
            assertTrue(content.contains(descriptor.provenanceValue))
        }
    }

    @Test
    fun capability_fixtures_cover_required_missing_and_unknown_additive_endpoints() {
        val request = parseJson("capabilities/request.json")
        val successful = parseJson("capabilities/success.json")
        val missing = parseJson("capabilities/missing-required.json")
        val additive = parseJson("capabilities/unknown-additive.json")

        assertRequest(request, "GET", "/v1/capabilities")
        val successEndpoints = successful.requiredObject("response.body.endpoints")
        SupportedGatewayCapabilities.required.forEach { endpoint ->
            assertTrue("Missing required endpoint '$endpoint'", successEndpoints.containsKey(endpoint))
        }
        assertEquals("GET", successEndpoints.getValue("sessions").jsonObject.getValue("method").jsonPrimitive.content)
        assertEquals(
            "PATCH",
            successEndpoints.getValue("session_update").jsonObject.getValue("method").jsonPrimitive.content,
        )
        assertEquals("POST", successEndpoints.getValue("runs").jsonObject.getValue("method").jsonPrimitive.content)
        assertEquals("GET", successEndpoints.getValue("run_events").jsonObject.getValue("method").jsonPrimitive.content)

        val missingEndpoints = missing.requiredObject("response.body.endpoints")
        assertFalse(missingEndpoints.containsKey("run_events"))

        val additiveEndpoints = additive.requiredObject("response.body.endpoints")
        SupportedGatewayCapabilities.required.forEach { endpoint ->
            assertTrue(
                "Additive fixture dropped required endpoint '$endpoint'",
                additiveEndpoints.containsKey(endpoint),
            )
        }
        assertTrue(additiveEndpoints.containsKey("gateway_future_endpoint"))
        assertEquals("additive-value", additive.requiredString("future_additive_field"))
    }

    @Test
    fun connection_fixtures_cover_authenticated_and_rejected_outcomes() {
        val authenticated = parseJson("connection/authenticated.json")
        val rejected = parseJson("connection/authentication-failed.json")

        assertEquals(200, authenticated.requiredInt("response.status"))
        assertTrue(authenticated.requiredBoolean("response.body.authenticated"))
        assertEquals(401, rejected.requiredInt("response.status"))
        assertFalse(rejected.requiredBoolean("response.body.authenticated"))
        assertEquals("authentication_failed", rejected.requiredString("response.body.error.code"))
    }

    @Test
    fun session_fixtures_cover_pagination_and_all_supported_mutations() {
        val listRequest = parseJson("sessions/list-request.json")
        val firstPage = parseJson("sessions/list-response-page-1.json")
        val secondPage = parseJson("sessions/list-response-page-2.json")
        val createRequest = parseJson("sessions/create-request.json")
        val createResponse = parseJson("sessions/create-response.json")
        val openRequest = parseJson("sessions/open-request.json")
        val openResponse = parseJson("sessions/open-response.json")
        val historyRequest = parseJson("sessions/history-request.json")
        val historyResponse = parseJson("sessions/history-response.json")
        val populatedHistoryResponse = parseJson("sessions/history-response-populated.json")
        val renameRequest = parseJson("sessions/rename-request.json")
        val renameResponse = parseJson("sessions/rename-response.json")
        val deleteRequest = parseJson("sessions/delete-request.json")
        val deleteResponse = parseJson("sessions/delete-response.json")
        val pinRequest = parseJson("sessions/pin-request.json")
        val pinResponse = parseJson("sessions/pin-response.json")
        val unpinRequest = parseJson("sessions/unpin-request.json")
        val unpinResponse = parseJson("sessions/unpin-response.json")

        assertSessionListFixtures(listRequest, firstPage, secondPage)
        assertSessionCreateFixtures(createRequest, createResponse)
        assertSessionOpenAndHistoryFixtures(
            openRequest,
            openResponse,
            historyRequest,
            historyResponse,
            populatedHistoryResponse,
        )
        assertSessionRenameFixtures(renameRequest, renameResponse)
        assertSessionDeleteFixtures(deleteRequest, deleteResponse)
        assertSessionPinFixtures(pinRequest, pinResponse, unpinRequest, unpinResponse)
    }

    private fun assertSessionListFixtures(
        listRequest: ContractJsonFixture,
        firstPage: ContractJsonFixture,
        secondPage: ContractJsonFixture,
    ) {
        assertRequest(listRequest, "GET", "/api/sessions")
        assertEquals(20, listRequest.requiredInt("request.query.limit"))
        assertEquals(0, listRequest.requiredInt("request.query.offset"))
        assertFalse(listRequest.root.getValue("request").jsonObject.getValue("query").jsonObject.containsKey("cursor"))
        assertFalse(listRequest.root.getValue("request").jsonObject.getValue("query").jsonObject.containsKey("search"))

        assertEquals(200, firstPage.requiredInt("response.status"))
        assertEquals("list", firstPage.requiredString("response.body.object"))
        assertEquals(1, firstPage.requiredArray("response.body.data").size)
        assertEquals(20, firstPage.requiredInt("response.body.limit"))
        assertEquals(0, firstPage.requiredInt("response.body.offset"))
        assertTrue(firstPage.requiredBoolean("response.body.has_more"))
        assertEquals(200, secondPage.requiredInt("response.status"))
        assertEquals(0, secondPage.requiredArray("response.body.data").size)
        assertEquals(20, secondPage.requiredInt("response.body.offset"))
        assertFalse(secondPage.requiredBoolean("response.body.has_more"))
    }

    private fun assertSessionCreateFixtures(
        createRequest: ContractJsonFixture,
        createResponse: ContractJsonFixture,
    ) {
        assertRequest(createRequest, "POST", "/api/sessions")
        assertEquals(null, createRequest.requiredNullableString("request.body.title"))
        assertEquals(201, createResponse.requiredInt("response.status"))
        assertEquals("hermes.session", createResponse.requiredString("response.body.object"))
        assertEquals(CREATED_SESSION_ID, createResponse.requiredString("response.body.session.id"))
    }

    private fun assertSessionOpenAndHistoryFixtures(
        openRequest: ContractJsonFixture,
        openResponse: ContractJsonFixture,
        historyRequest: ContractJsonFixture,
        historyResponse: ContractJsonFixture,
        populatedHistoryResponse: ContractJsonFixture,
    ) {
        assertRequest(openRequest, "GET", "/api/sessions/$SESSION_ID")
        assertEquals(200, openResponse.requiredInt("response.status"))
        assertEquals(SESSION_ID, openResponse.requiredString("response.body.session.id"))
        assertRequest(historyRequest, "GET", "/api/sessions/$SESSION_ID/messages")
        assertEquals(200, historyResponse.requiredInt("response.status"))
        assertEquals("list", historyResponse.requiredString("response.body.object"))
        assertEquals(SESSION_ID, historyResponse.requiredString("response.body.session_id"))
        assertEquals(0, historyResponse.requiredArray("response.body.data").size)
        assertEquals(0, historyResponse.requiredInt("response.body.pagination.returned"))
        val populatedMessages =
            populatedHistoryResponse.root["response"]!!.jsonObject["body"]!!.jsonObject["data"]!!.jsonArray
        assertEquals(2, populatedMessages.size)
        val firstMessage = populatedMessages[0].jsonObject
        assertEquals("1", firstMessage["id"]!!.jsonPrimitive.content)
        assertFalse(firstMessage["id"]!!.jsonPrimitive.isString)
        assertEquals("user", firstMessage["role"]!!.jsonPrimitive.content)
        assertEquals("Run this", firstMessage["content"]!!.jsonPrimitive.content)
        assertEquals("1788897540.0", firstMessage["timestamp"]!!.jsonPrimitive.content)
        assertFalse(firstMessage["timestamp"]!!.jsonPrimitive.isString)
        assertFalse(firstMessage.containsKey("run_id"))
        assertFalse(firstMessage.containsKey("run_status"))
        assertFalse(firstMessage.containsKey("run_result"))
        assertEquals("assistant", populatedMessages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Authoritative result", populatedMessages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    private fun assertSessionRenameFixtures(
        renameRequest: ContractJsonFixture,
        renameResponse: ContractJsonFixture,
    ) {
        assertRequest(renameRequest, "PATCH", "/api/sessions/$SESSION_ID")
        assertEquals("Renamed session", renameRequest.requiredString("request.body.title"))
        assertEquals(200, renameResponse.requiredInt("response.status"))
        assertEquals(SESSION_ID, renameResponse.requiredString("response.body.session.id"))
    }

    private fun assertSessionDeleteFixtures(
        deleteRequest: ContractJsonFixture,
        deleteResponse: ContractJsonFixture,
    ) {
        assertRequest(deleteRequest, "DELETE", "/api/sessions/$SESSION_ID")
        assertEquals(200, deleteResponse.requiredInt("response.status"))
        assertEquals("hermes.session.deleted", deleteResponse.requiredString("response.body.object"))
        assertEquals(SESSION_ID, deleteResponse.requiredString("response.body.id"))
        assertTrue(deleteResponse.requiredBoolean("response.body.deleted"))
    }

    private fun assertSessionPinFixtures(
        pinRequest: ContractJsonFixture,
        pinResponse: ContractJsonFixture,
        unpinRequest: ContractJsonFixture,
        unpinResponse: ContractJsonFixture,
    ) {
        assertRequest(pinRequest, "PATCH", "/api/sessions/$SESSION_ID")
        assertTrue(pinRequest.requiredBoolean("request.body.pinned"))
        assertEquals(200, pinResponse.requiredInt("response.status"))
        assertTrue(pinResponse.requiredBoolean("response.body.session.pinned"))

        assertRequest(unpinRequest, "PATCH", "/api/sessions/$SESSION_ID")
        assertFalse(unpinRequest.requiredBoolean("request.body.pinned"))
        assertEquals(200, unpinResponse.requiredInt("response.status"))
        assertFalse(unpinResponse.requiredBoolean("response.body.session.pinned"))
    }

    @Test
    fun run_fixtures_cover_creation_and_status_with_immutable_ids() {
        val createRequest = parseJson("runs/create-request.json")
        val createResponse = parseJson("runs/create-response.json")
        val statusRequest = parseJson("runs/status-request.json")
        val statusResponse = parseJson("runs/status-response.json")

        assertRequest(createRequest, "POST", "/v1/runs")
        assertEquals("", createRequest.requiredString("request.body.input"))
        assertEquals(SESSION_ID, createRequest.requiredString("request.body.session_id"))
        assertEquals(202, createResponse.requiredInt("response.status"))
        assertEquals(RUN_ID, createResponse.requiredString("response.body.run_id"))
        assertEquals("started", createResponse.requiredString("response.body.status"))
        assertFalse(
            createResponse.root.getValue("response").jsonObject
                .getValue("body").jsonObject.containsKey("session_id"),
        )
        assertRequest(statusRequest, "GET", "/v1/runs/$RUN_ID")
        assertEquals(200, statusResponse.requiredInt("response.status"))
        assertEquals("hermes.run", statusResponse.requiredString("response.body.object"))
        assertEquals(RUN_ID, statusResponse.requiredString("response.body.run_id"))
        assertEquals(SESSION_ID, statusResponse.requiredString("response.body.session_id"))
        assertEquals("completed", statusResponse.requiredString("response.body.status"))
    }

    @Test
    fun sse_fixture_preserves_supported_events_and_allows_unknown_event_types() {
        val fixture = parseSse("runs/observation.sse")

        assertEquals(
            listOf("fixture.metadata", "tool.started", "message.delta", "run.completed"),
            fixture.events.map { it.eventType },
        )
        assertEquals(RUN_ID, fixture.events[1].requiredString("run_id"))
        assertEquals("terminal", fixture.events[1].requiredString("tool"))
        assertEquals("Hello", fixture.events[2].requiredString("delta"))
        assertEquals(RUN_ID, fixture.events[3].requiredString("run_id"))
        assertEquals(descriptor.provenanceValue, fixture.provenanceValue)
    }

    @Test
    fun malformed_json_is_reported_as_invalid_json() {
        assertFailure(ContractFixtureFailureCategory.INVALID_JSON) {
            parseJson("malformed/invalid-json.json")
        }
    }

    @Test
    fun missing_required_json_field_has_a_safe_failure_category() {
        val fixture = parseJson("malformed/missing-required-field.json")

        assertFailure(ContractFixtureFailureCategory.MISSING_REQUIRED_FIELD) {
            fixture.requiredArray("response.body.data")
        }
    }

    @Test
    fun invalid_required_json_field_type_has_a_safe_failure_category() {
        val fixture = parseJson("malformed/invalid-required-field-type.json")

        assertFailure(ContractFixtureFailureCategory.INVALID_REQUIRED_FIELD_TYPE) {
            fixture.requiredArray("response.body.data")
        }
    }

    @Test
    fun invalid_sse_framing_has_a_safe_failure_category() {
        assertFailure(ContractFixtureFailureCategory.INVALID_SSE_FRAMING) {
            parseSse("malformed/invalid-sse-framing.sse")
        }
    }

    @Test
    fun missing_provenance_is_rejected() {
        val content =
            contractsRoot.resolve("capabilities/success.json").readText()
                .replace("\"hermes_revision\": \"$PROVENANCE\",\n", "")

        assertFailure(ContractFixtureFailureCategory.MISSING_PROVENANCE) {
            ContractFixtureParser.parseJson("missing-provenance.json", content, descriptor)
        }
    }

    @Test
    fun duplicate_provenance_is_rejected() {
        val content =
            """
            {
              "hermes_revision": "$PROVENANCE",
              "hermes_revision": "$PROVENANCE",
              "response": {"status": 200, "body": {"endpoints": {}}}
            }
            """.trimIndent()

        assertFailure(ContractFixtureFailureCategory.DUPLICATE_PROVENANCE) {
            ContractFixtureParser.parseJson("duplicate-provenance.json", content, descriptor)
        }
    }

    @Test
    fun escaped_provenance_name_is_decoded_for_json() {
        val escapedField = "hermes" + "\\" + "u005frevision"
        val content =
            contractsRoot.resolve("capabilities/success.json").readText()
                .replace("hermes_revision", escapedField)

        val fixture = ContractFixtureParser.parseJson("escaped-provenance.json", content, descriptor)

        assertEquals(PROVENANCE, fixture.provenanceValue)
    }

    @Test
    fun escaped_duplicate_provenance_name_is_rejected_for_json() {
        val escapedField = "hermes" + "\\" + "u005frevision"
        val content =
            """
            {
              "hermes_revision": "$PROVENANCE",
              "$escapedField": "$PROVENANCE",
              "response": {"status": 200, "body": {"endpoints": {}}}
            }
            """.trimIndent()

        assertFailure(ContractFixtureFailureCategory.DUPLICATE_PROVENANCE) {
            ContractFixtureParser.parseJson("escaped-duplicate-provenance.json", content, descriptor)
        }
    }

    @Test
    fun escaped_provenance_name_is_decoded_for_sse() {
        val escapedField = "hermes" + "\\" + "u005frevision"
        val content =
            contractsRoot.resolve("runs/observation.sse").readText()
                .replace("hermes_revision", escapedField)

        val fixture = ContractFixtureParser.parseSse("escaped-provenance.sse", content, descriptor)

        assertEquals(PROVENANCE, fixture.provenanceValue)
    }

    @Test
    fun escaped_duplicate_provenance_name_is_rejected_for_sse() {
        val escapedField = "hermes" + "\\" + "u005frevision"
        val original = "\"hermes_revision\":\"$PROVENANCE\""
        val duplicate = "$original,\"$escapedField\":\"$PROVENANCE\""
        val content = contractsRoot.resolve("runs/observation.sse").readText().replace(original, duplicate)

        assertFailure(ContractFixtureFailureCategory.DUPLICATE_PROVENANCE) {
            ContractFixtureParser.parseSse("escaped-duplicate-provenance.sse", content, descriptor)
        }
    }

    @Test
    fun mismatched_provenance_is_rejected() {
        val content =
            contractsRoot.resolve("capabilities/success.json").readText()
                .replace(PROVENANCE, "0".repeat(40))

        assertFailure(ContractFixtureFailureCategory.INVALID_PROVENANCE) {
            ContractFixtureParser.parseJson("mismatched-provenance.json", content, descriptor)
        }
    }

    @Test
    fun alternate_provenance_field_is_rejected() {
        val content =
            contractsRoot.resolve("capabilities/success.json").readText().replace(
                "\"hermes_revision\": \"$PROVENANCE\",\n",
                "\"image_digest\": \"sha256:${"0".repeat(64)}\",\n",
            )

        assertFailure(ContractFixtureFailureCategory.INVALID_PROVENANCE) {
            ContractFixtureParser.parseJson("alternate-provenance.json", content, descriptor)
        }
    }

    @Test
    fun missing_sse_provenance_is_rejected() {
        val content =
            contractsRoot.resolve("runs/observation.sse").readText()
                .replace("\"hermes_revision\":\"$PROVENANCE\",", "")

        assertFailure(ContractFixtureFailureCategory.MISSING_PROVENANCE) {
            ContractFixtureParser.parseSse("missing-sse-provenance.sse", content, descriptor)
        }
    }

    @Test
    fun duplicate_sse_provenance_is_rejected() {
        val content =
            contractsRoot.resolve("runs/observation.sse").readText()
                .replace(
                    "\"hermes_revision\":\"$PROVENANCE\"",
                    "\"hermes_revision\":\"$PROVENANCE\",\"hermes_revision\":\"$PROVENANCE\"",
                )

        assertFailure(ContractFixtureFailureCategory.DUPLICATE_PROVENANCE) {
            ContractFixtureParser.parseSse("duplicate-sse-provenance.sse", content, descriptor)
        }
    }

    @Test
    fun streaming_fixture_covers_incremental_deltas_and_a_terminal_event() {
        val fixture = parseSse("runs/observation-streaming.sse")

        assertEquals(
            listOf(
                "fixture.metadata",
                "message.delta",
                "message.delta",
                "run.completed",
            ),
            fixture.events.map { it.eventType },
        )
        assertEquals(
            listOf("Hello", " world"),
            fixture.events.filter { it.eventType == "message.delta" }.map { it.requiredString("delta") },
        )
        assertEquals(descriptor.provenanceValue, fixture.provenanceValue)
    }

    @Test
    fun interrupted_fixture_has_no_false_terminal_event() {
        val fixture = parseSse("runs/observation-interrupted.sse")

        assertEquals("message.delta", fixture.events.last().eventType)
        assertTrue(
            fixture.events.none {
                it.eventType == "run.completed" || it.eventType == "run.failed" || it.eventType == "run.cancelled"
            },
        )
    }

    private fun parseJson(path: String): ContractJsonFixture =
        ContractFixtureParser.parseJson(path, contractsRoot.resolve(path).readText(), descriptor)

    private fun parseSse(path: String): ContractSseFixture =
        ContractFixtureParser.parseSse(path, contractsRoot.resolve(path).readText(), descriptor)

    private fun assertRequest(
        fixture: ContractJsonFixture,
        method: String,
        path: String,
    ) {
        assertEquals(method, fixture.requiredString("request.method"))
        assertEquals(path, fixture.requiredString("request.path"))
    }

    private fun assertFailure(
        category: ContractFixtureFailureCategory,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected $category")
        } catch (error: ContractFixtureException) {
            assertEquals(category, error.category)
            assertNotNull(error.message)
        }
    }

    private companion object {
        const val PROVENANCE = "d9833c5615b80e199a174cd67d90ab430695a972"
        const val SESSION_ID = "7c4d3b20-7c7a-4e2a-a593-3a11c2e93f70"
        const val CREATED_SESSION_ID = "b8f0b3e4-9d5b-4d31-8eb2-71c7fc5f4b26"
        const val RUN_ID = "9f54cb79-8b45-4c4d-a4e2-6e7b42d9b1c8"
    }
}
