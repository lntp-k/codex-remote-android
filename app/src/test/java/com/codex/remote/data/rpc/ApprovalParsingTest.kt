package com.codex.remote.data.rpc

import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.RpcRequestId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun commandApprovalPreservesIdentityWorkingDirectoryAndEscalationScope() {
        val params = json.parseToJsonElement(
            """{
                "threadId":"thread-a",
                "turnId":"turn-9",
                "itemId":"command-4",
                "approvalId":"approval-11",
                "startedAtMs":123456,
                "command":"curl https://example.com/release",
                "cwd":"/srv/app",
                "reason":"Needs release network access",
                "commandActions":[{"type":"unknown","command":"curl https://example.com/release"}],
                "additionalPermissions":{"fileSystem":{"write":["/srv/cache"]},"network":{"enabled":true}},
                "availableDecisions":["accept","decline"],
                "proposedExecpolicyAmendment":["curl","https://example.com"],
                "proposedNetworkPolicyAmendments":[{"host":"example.com","action":"allow"}]
            }""".trimIndent(),
        ).jsonObject

        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("request-44"),
            "item/commandExecution/requestApproval",
            params,
        )
        val context = request.context.associate { it.label to it.value }

        assertEquals(ApprovalKind.COMMAND, request.kind)
        assertEquals(RpcRequestId.Text("request-44"), request.requestId)
        assertEquals("thread-a", request.threadId)
        assertEquals("turn-9", request.turnId)
        assertEquals("command-4", request.itemId)
        assertEquals("approval-11", request.approvalId)
        assertEquals("/srv/app", request.cwd)
        assertEquals(listOf("accept", "decline"), request.availableDecisions)
        assertEquals("/srv/app", context["Working directory"])
        assertEquals("Needs release network access", context["Reason"])
        assertEquals(
            "{\"fileSystem\":{\"write\":[\"/srv/cache\"]},\"network\":{\"enabled\":true}}",
            context["Additional permissions"],
        )
        assertEquals(
            "[{\"host\":\"example.com\",\"action\":\"allow\"}]",
            context["Network policy amendments"],
        )
    }

    @Test
    fun legacyFileApprovalIncludesEveryTargetAndDiff() {
        val params = json.parseToJsonElement(
            """{
                "conversationId":"thread-old",
                "callId":"patch-call",
                "grantRoot":"/srv/app",
                "reason":"Apply generated changes",
                "fileChanges":{
                    "app/src/Main.kt":{"type":"update","unified_diff":"-old\n+new","move_path":"app/src/Renamed.kt"},
                    "app/src/New.kt":{"type":"add","content":"val ready = true"}
                }
            }""".trimIndent(),
        ).jsonObject

        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("request-file"),
            "applyPatchApproval",
            params,
        )
        val context = request.context.associate { it.label to it.value }

        assertEquals(ApprovalKind.FILE_CHANGE, request.kind)
        assertEquals("thread-old", request.threadId)
        assertEquals("patch-call", request.itemId)
        assertEquals("/srv/app", context["Session write root"])
        assertEquals(listOf("app/src/Main.kt", "app/src/New.kt"), request.fileChanges.map { it.path })
        assertEquals(listOf("update", "add"), request.fileChanges.map { it.kind })
        assertEquals(listOf("-old\n+new", "val ready = true"), request.fileChanges.map { it.diff })
        assertEquals(listOf("app/src/Renamed.kt", null), request.fileChanges.map { it.movePath })
        assertTrue(request.canApprove(emptyList()))

        val response = CodexRpcClient.approvalResponseParams(request, "accept")
        assertEquals("approved", response.getValue("decision").jsonPrimitive.content)
    }

    @Test
    fun legacyFileApprovalRejectsUnknownFieldsBlankPathsAndMalformedMoveTargets() {
        val malformedChanges = listOf(
            """{"":{"type":"update","unified_diff":"+x"}}""",
            """{"src/A.kt":{"type":"update","unified_diff":"+x","move_path":7}}""",
            """{"src/A.kt":{"type":"update","unified_diff":"+x","move_path":""}}""",
            """{"src/A.kt":{"type":"update","unified_diff":"+x","futureTarget":"/etc/x"}}""",
            """{"src/A.kt":{"type":"add","content":"x","move_path":"src/B.kt"}}""",
        )

        malformedChanges.forEachIndexed { index, changes ->
            val request = CodexRpcClient.parseApprovalRequest(
                RpcRequestId.Text("legacy-malformed-$index"),
                "applyPatchApproval",
                json.parseToJsonElement(
                    """{"conversationId":"thread-old","callId":"patch-call","fileChanges":$changes}""",
                ).jsonObject,
            )

            assertFalse("case $index must fail closed", request.securityContextComplete)
            assertEquals(listOf("decline"), request.availableDecisions)
            assertFalse(request.canApprove(emptyList()))
        }
    }

    @Test
    fun commandApprovalWithoutExactCommandOrWorkingDirectoryIsDenyOnly() {
        listOf(
            """"cwd":"/srv/app"""",
            """"command":"git status"""",
            """"command":"","cwd":"/srv/app"""",
            """"command":"git status","cwd":""""",
        ).forEachIndexed { index, commandContext ->
            val request = CodexRpcClient.parseApprovalRequest(
                RpcRequestId.Text("missing-command-context-$index"),
                "item/commandExecution/requestApproval",
                json.parseToJsonElement(
                    """{
                        "threadId":"thread-a",
                        "turnId":"turn-a",
                        "itemId":"command-a",
                        "startedAtMs":1,
                        $commandContext
                    }""".trimIndent(),
                ).jsonObject,
            )

            assertFalse("case $index must fail closed", request.securityContextComplete)
            assertEquals(listOf("decline"), request.availableDecisions)
        }
    }

    @Test
    fun malformedCommandPolicyAndNetworkContextIsDenyOnly() {
        val malformedFields = listOf(
            """"commandActions":[{"type":"read","command":"cat a","name":"a","path":"/srv/a","future":true}]""",
            """"networkApprovalContext":{"host":"example.com","protocol":"ftp"}""",
            """"proposedExecpolicyAmendment":["curl",7]""",
            """"proposedNetworkPolicyAmendments":[{"host":"example.com","action":"prompt"}]""",
            """"proposedNetworkPolicyAmendments":[{"host":"","action":"allow"}]""",
        )

        malformedFields.forEachIndexed { index, malformedField ->
            val request = CodexRpcClient.parseApprovalRequest(
                RpcRequestId.Text("malformed-command-policy-$index"),
                "item/commandExecution/requestApproval",
                json.parseToJsonElement(
                    """{
                        "threadId":"thread-a",
                        "turnId":"turn-a",
                        "itemId":"command-a",
                        "startedAtMs":1,
                        "command":"curl https://example.com",
                        "cwd":"/srv/app",
                        $malformedField
                    }""".trimIndent(),
                ).jsonObject,
            )

            assertFalse("case $index must fail closed", request.securityContextComplete)
            assertEquals(listOf("decline"), request.availableDecisions)
        }
    }

    @Test
    fun timelineFileAuthorizationIdentityRejectsNumericOrBlankIds() {
        val validBase = """{"type":"fileChange","id":"patch-a","changes":[{"path":"safe.kt","kind":{"type":"update"},"diff":"+safe"}]}"""
        assertTrue(CodexRpcClient.parseTimelineItem(json.parseToJsonElement(validBase)) != null)

        listOf(
            """{"type":"fileChange","id":7,"changes":[]}""",
            """{"type":"fileChange","id":"","changes":[]}""",
            """{"type":7,"id":"patch-a","changes":[]}""",
        ).forEach { malformed ->
            assertEquals(null, CodexRpcClient.parseTimelineItem(json.parseToJsonElement(malformed)))
        }
        val incomplete = CodexRpcClient.parseTimelineItem(
            json.parseToJsonElement(
                """{"type":"fileChange","id":"patch-a","changes":[{"path":"safe.kt","kind":{"type":"update","move_path":""},"diff":"+safe"}]}""",
            ),
        )
        assertFalse(incomplete!!.fileChangesComplete)
    }

    @Test
    fun permissionApprovalShowsTheExactRequestedFileAndNetworkScope() {
        val params = json.parseToJsonElement(
            """{
                "threadId":"thread-p",
                "turnId":"turn-p",
                "itemId":"permission-p",
                "startedAtMs":200,
                "cwd":"/srv/app",
                "reason":"Publish artifacts",
                "permissions":{
                    "fileSystem":{"entries":[{"access":"write","path":{"type":"path","path":"/srv/releases"}}]},
                    "network":{"enabled":true}
                }
            }""".trimIndent(),
        ).jsonObject

        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("request-permission"),
            "item/permissions/requestApproval",
            params,
        )
        val context = request.context.associate { it.label to it.value }

        assertEquals(ApprovalKind.PERMISSION, request.kind)
        assertEquals("/srv/app", context["Working directory"])
        assertEquals(
            "{\"fileSystem\":{\"entries\":[{\"access\":\"write\",\"path\":{\"type\":\"path\",\"path\":\"/srv/releases\"}}]},\"network\":{\"enabled\":true}}",
            context["Requested permissions"],
        )

        val response = CodexRpcClient.approvalResponseParams(request, "acceptForSession")
        assertEquals("session", response.getValue("scope").jsonPrimitive.content)
        assertEquals(params.getValue("permissions"), response.getValue("permissions"))
    }

    @Test
    fun malformedPermissionScopeIsDenyOnly() {
        val params = json.parseToJsonElement(
            """{
                "threadId":"thread-p",
                "turnId":"turn-p",
                "itemId":"permission-p",
                "startedAtMs":200,
                "cwd":"/srv/app",
                "permissions":"network-and-files"
            }""".trimIndent(),
        ).jsonObject

        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("request-malformed"),
            "item/permissions/requestApproval",
            params,
        )

        assertTrue(!request.canApprove(emptyList()))
        assertEquals(emptyList<String>(), request.availableDecisions.filter { it.startsWith("accept") })
    }

    @Test
    fun permissionScopePreservesPathGlobSpecialRootAndNetworkEntries() {
        val params = json.parseToJsonElement(
            """{
                "threadId":"thread-scope",
                "turnId":"turn-scope",
                "itemId":"permission-scope",
                "startedAtMs":300,
                "cwd":"/srv/app",
                "permissions":{
                    "fileSystem":{"entries":[
                        {"access":"read","path":{"type":"path","path":"/srv/input"}},
                        {"access":"write","path":{"type":"glob_pattern","pattern":"/srv/output/**"}},
                        {"access":"deny","path":{"type":"special","value":{"kind":"root"}}}
                    ]},
                    "network":{"enabled":true}
                }
            }""".trimIndent(),
        ).jsonObject

        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Number(300),
            "item/permissions/requestApproval",
            params,
        )
        val displayed = request.context.single { it.label == "Requested permissions" }.value

        assertTrue(request.canApprove(emptyList()))
        assertEquals(params.getValue("permissions"), json.parseToJsonElement(displayed))
    }

    @Test
    fun unknownNestedPermissionFieldIsDenyOnlyEvenThoughItIsShown() {
        val params = json.parseToJsonElement(
            """{
                "threadId":"thread-unknown",
                "turnId":"turn-unknown",
                "itemId":"permission-unknown",
                "startedAtMs":400,
                "cwd":"/srv/app",
                "permissions":{"fileSystem":{"admin":["/"]}}
            }""".trimIndent(),
        ).jsonObject

        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Number(400),
            "item/permissions/requestApproval",
            params,
        )

        assertTrue(request.context.any { it.label == "Requested permissions" && it.value.contains("admin") })
        assertTrue(!request.canApprove(emptyList()))
        assertEquals(listOf("decline"), request.availableDecisions)
    }

    @Test
    fun responseIdsPreserveTheirExactJsonTypeAndValue() {
        val numeric = CodexRpcClient.parseRequestId(JsonPrimitive(1))
        val text = CodexRpcClient.parseRequestId(JsonPrimitive("1"))
        val padded = CodexRpcClient.parseRequestId(JsonPrimitive("001"))

        assertEquals(RpcRequestId.Number(1), numeric)
        assertEquals(RpcRequestId.Text("1"), text)
        assertEquals(RpcRequestId.Text("001"), padded)
        assertNotEquals(numeric, text)

        val numericEnvelope = CodexRpcClient.responseEnvelope(numeric!!, json.parseToJsonElement("{}").jsonObject)
        val textEnvelope = CodexRpcClient.responseEnvelope(text!!, json.parseToJsonElement("{}").jsonObject)
        val paddedEnvelope = CodexRpcClient.responseEnvelope(padded!!, json.parseToJsonElement("{}").jsonObject)

        assertTrue(!numericEnvelope.getValue("id").jsonPrimitive.isString)
        assertTrue(textEnvelope.getValue("id").jsonPrimitive.isString)
        assertEquals("001", paddedEnvelope.getValue("id").jsonPrimitive.content)
        assertTrue(paddedEnvelope.getValue("id").jsonPrimitive.isString)
    }

    @Test
    fun mixedValidAndMalformedTimelineFileChangesStayDenyOnly() {
        val turns = json.parseToJsonElement(
            """[{
                "id":"turn-file",
                "items":[{
                    "type":"fileChange",
                    "id":"patch-file",
                    "changes":[
                        {"path":"safe.kt","kind":{"type":"update"},"diff":"+safe"},
                        {"path":7,"kind":{"type":"delete"},"diff":"-sensitive"}
                    ]
                }]
            }]""".trimIndent(),
        ).jsonArray
        val item = CodexRpcClient.parseTurnsTimeline(turns, descending = false).single()
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("request-file-strict"),
            "item/fileChange/requestApproval",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-file",
                    "turnId":"turn-file",
                    "itemId":"patch-file",
                    "startedAtMs":500
                }""".trimIndent(),
            ).jsonObject,
        )

        assertEquals("turn-file", item.turnId)
        assertFalse(item.fileChangesComplete)
        assertFalse(request.canApprove(listOf(item), selectedThreadId = "thread-file"))
    }

    @Test
    fun legacyCommandRejectsNonStringArgvAndMalformedParsedCommands() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("legacy-command"),
            "execCommandApproval",
            json.parseToJsonElement(
                """{
                    "conversationId":"thread-old",
                    "callId":"command-old",
                    "cwd":"/srv/app",
                    "command":["git",7],
                    "parsedCmd":["not-an-object"]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(request.securityContextComplete)
        assertEquals(listOf("decline"), request.availableDecisions)
    }

    @Test
    fun permissionRejectsStringAndOutOfRangeGlobDepths() {
        fun requestFor(depth: String) = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("permission-$depth"),
            "item/permissions/requestApproval",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-depth",
                    "turnId":"turn-depth",
                    "itemId":"permission-depth",
                    "startedAtMs":600,
                    "cwd":"/srv/app",
                    "permissions":{"fileSystem":{"globScanMaxDepth":$depth}}
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(requestFor("\"5\"").securityContextComplete)
        assertFalse(requestFor("4294967296").securityContextComplete)
        assertTrue(requestFor("4294967295").securityContextComplete)
    }

    @Test
    fun malformedAvailableDecisionElementFailsClosed() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("decision-malformed"),
            "item/commandExecution/requestApproval",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-d",
                    "turnId":"turn-d",
                    "itemId":"command-d",
                    "startedAtMs":700,
                    "command":"git status",
                    "availableDecisions":["accept",7]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(request.securityContextComplete)
        assertTrue(request.availableDecisions.none { it.startsWith("accept") })
    }

    @Test
    fun validButUnsupportedPolicyDecisionIsShownWithoutCreatingAnUnsafeButton() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("decision-policy"),
            "item/commandExecution/requestApproval",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-policy",
                    "turnId":"turn-policy",
                    "itemId":"command-policy",
                    "startedAtMs":800,
                    "command":"curl https://example.com",
                    "cwd":"/srv/app",
                    "availableDecisions":[
                        {"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["curl"]}},
                        "decline"
                    ]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertTrue(request.securityContextComplete)
        assertEquals(listOf("decline"), request.availableDecisions)
        assertTrue(request.context.single { it.label == "Available decisions" }.value.contains("execpolicy_amendment"))
    }

    @Test
    fun malformedUserInputQuestionCannotBeSubmitted() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-malformed"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":true,
                    "questions":[
                        {"id":"q1","header":"Choice","question":"Continue?","isOther":false,"isSecret":false,"options":null},
                        7
                    ]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(request.securityContextComplete)
        assertEquals(emptyList<String>(), request.availableDecisions)
    }

    @Test
    fun missingQuestionFlagsOptionsOrUniqueLabelsCannotBeSubmitted() {
        val malformedQuestions = listOf(
            """{"id":"q1","header":"Choice","question":"Continue?","isSecret":false,"options":null}""",
            """{"id":"q1","header":"Choice","question":"Continue?","isOther":false,"isSecret":false}""",
            """{"id":"q1","header":"Choice","question":"","isOther":false,"isSecret":false,"options":null}""",
            """{"id":"q1","header":"Choice","question":"Continue?","isOther":false,"isSecret":false,"options":[{"label":"Same","description":"First"},{"label":"Same","description":"Second"}]}""",
        )
        malformedQuestions.forEachIndexed { index, question ->
            val request = CodexRpcClient.parseApprovalRequest(
                RpcRequestId.Text("input-question-shape-$index"),
                "item/tool/requestUserInput",
                json.parseToJsonElement(
                    """{
                        "threadId":"thread-input",
                        "turnId":"turn-input",
                        "itemId":"input-item",
                        "isBlocking":true,
                        "questions":[$question]
                    }""".trimIndent(),
                ).jsonObject,
            )

            assertFalse("case $index must fail closed", request.securityContextComplete)
            assertEquals(emptyList<String>(), request.availableDecisions)
        }
    }

    @Test
    fun excessiveUserInputQuestionCountCannotBeSubmitted() {
        val questions = (1..MAX_APPROVAL_QUESTIONS + 1).joinToString(",") { index ->
            """{"id":"q$index","header":"Choice","question":"Continue?","isOther":false,"isSecret":false,"options":null}"""
        }
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-too-many-questions"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":true,
                    "questions":[$questions]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(request.securityContextComplete)
        assertEquals(emptyList<String>(), request.availableDecisions)
    }

    @Test
    fun excessiveUserInputOptionsCannotBeSubmitted() {
        val options = (1..MAX_APPROVAL_OPTIONS_PER_QUESTION + 1).joinToString(",") { index ->
            """{"description":"Option $index","label":"$index"}"""
        }
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-too-many-options"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":true,
                    "questions":[{
                        "id":"q1",
                        "header":"Choice",
                        "question":"Continue?",
                        "isOther":false,
                        "isSecret":false,
                        "options":[$options]
                    }]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(request.securityContextComplete)
        assertEquals(emptyList<String>(), request.availableDecisions)
    }

    @Test
    fun userInputAtQuestionAndOptionLimitsCanBeSubmitted() {
        val maxOptions = (1..MAX_APPROVAL_OPTIONS_PER_QUESTION).joinToString(",") { index ->
            """{"description":"Option $index","label":"$index"}"""
        }
        val maxQuestions = (1..MAX_APPROVAL_QUESTIONS).joinToString(",") { index ->
            val options = if (index == 1) "[$maxOptions]" else "null"
            """{"id":"q$index","header":"Choice $index","question":"Continue?","isOther":false,"isSecret":false,"options":$options}"""
        }
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-at-limits"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":true,
                    "questions":[$maxQuestions]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertTrue(request.securityContextComplete)
        assertEquals(listOf("accept"), request.availableDecisions)
        assertEquals(MAX_APPROVAL_QUESTIONS, request.questions.size)
        assertEquals(MAX_APPROVAL_OPTIONS_PER_QUESTION, request.questions.first().options.size)
    }

    @Test
    fun duplicateQuestionIdsCannotBeSubmitted() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-duplicate-ids"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":true,
                    "questions":[
                        {"id":"same","header":"First","question":"One?","isOther":false,"isSecret":false,"options":null},
                        {"id":"same","header":"Second","question":"Two?","isOther":false,"isSecret":false,"options":null}
                    ]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(request.securityContextComplete)
        assertEquals(emptyList<String>(), request.availableDecisions)
    }

    @Test
    fun missingOrMalformedBlockingFlagCannotBeSubmitted() {
        listOf(null, "\"false\"", "7").forEachIndexed { index, blockingValue ->
            val blockingField = blockingValue?.let { "\"isBlocking\":$it," }.orEmpty()
            val request = CodexRpcClient.parseApprovalRequest(
                RpcRequestId.Text("input-blocking-$index"),
                "item/tool/requestUserInput",
                json.parseToJsonElement(
                    """{
                        "threadId":"thread-input",
                        "turnId":"turn-input",
                        "itemId":"input-item",
                        $blockingField
                        "questions":[{"id":"q1","header":"Choice","question":"Continue?","isOther":false,"isSecret":false,"options":null}]
                    }""".trimIndent(),
                ).jsonObject,
            )

            assertFalse("case $index must fail closed", request.securityContextComplete)
            assertEquals(emptyList<String>(), request.availableDecisions)
        }
    }

    @Test
    fun secretUserInputCannotBeSubmittedWithoutObscuredInputSupport() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-secret"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":true,
                    "questions":[{
                        "id":"secret",
                        "header":"Credential",
                        "question":"Enter the token",
                        "isOther":false,
                        "isSecret":true,
                        "options":null
                    }]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertFalse(request.securityContextComplete)
        assertEquals(emptyList<String>(), request.availableDecisions)
    }

    @Test
    fun optionDescriptionsAndOtherResponseCapabilityArePreserved() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-option-context"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":false,
                    "questions":[{
                        "id":"choice",
                        "header":"Mode",
                        "question":"Choose a mode",
                        "isOther":true,
                        "isSecret":false,
                        "options":[{"label":"Safe","description":"Keep approval prompts enabled"}]
                    }]
                }""".trimIndent(),
            ).jsonObject,
        )

        assertTrue(request.securityContextComplete)
        assertTrue(request.questions.single().isOther)
        assertEquals("Safe", request.questions.single().options.single().label)
        assertEquals("Keep approval prompts enabled", request.questions.single().options.single().description)
    }

    @Test
    fun userInputResponseRequiresOneCompleteRenderedAnswerPerQuestion() {
        val request = CodexRpcClient.parseApprovalRequest(
            RpcRequestId.Text("input-response"),
            "item/tool/requestUserInput",
            json.parseToJsonElement(
                """{
                    "threadId":"thread-input",
                    "turnId":"turn-input",
                    "itemId":"input-item",
                    "isBlocking":true,
                    "questions":[
                        {
                            "id":"choice",
                            "header":"Mode",
                            "question":"Choose a mode",
                            "isOther":false,
                            "isSecret":false,
                            "options":[
                                {"label":"Safe","description":"Keep prompts"},
                                {"label":"Fast","description":"Use defaults"}
                            ]
                        },
                        {
                            "id":"note",
                            "header":"Note",
                            "question":"Add context",
                            "isOther":false,
                            "isSecret":false,
                            "options":null
                        }
                    ]
                }""".trimIndent(),
            ).jsonObject,
        )
        val validAnswers = mapOf("choice" to listOf("Safe"), "note" to listOf("Proceed carefully"))

        val response = CodexRpcClient.approvalResponseParams(request, "accept", validAnswers)
        val encodedAnswers = response.getValue("answers").jsonObject
        assertEquals("Safe", encodedAnswers.getValue("choice").jsonObject
            .getValue("answers").jsonArray.single().jsonPrimitive.content)
        assertEquals("Proceed carefully", encodedAnswers.getValue("note").jsonObject
            .getValue("answers").jsonArray.single().jsonPrimitive.content)

        val invalidAnswers = listOf(
            mapOf("choice" to listOf("Safe")),
            mapOf("choice" to listOf(""), "note" to listOf("Context")),
            mapOf("choice" to listOf("Unknown"), "note" to listOf("Context")),
            mapOf("choice" to listOf("Safe", "Fast"), "note" to listOf("Context")),
            validAnswers + ("unexpected" to listOf("value")),
        )
        invalidAnswers.forEachIndexed { index, answers ->
            val error = runCatching {
                CodexRpcClient.approvalResponseParams(request, "accept", answers)
            }.exceptionOrNull()
            assertTrue("case $index must fail closed", error is RpcException)
        }
    }
}
