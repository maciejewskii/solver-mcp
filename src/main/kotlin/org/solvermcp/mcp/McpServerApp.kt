package org.solvermcp.mcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.solvermcp.guide.ModelingGuide
import org.solvermcp.solver.SolverService
import org.solvermcp.spec.SpecJson

class McpServerApp {
    private val service = SolverService()
    private val json = Json { prettyPrint = true }

    fun start(): Nothing = runBlocking {
        System.setProperty("slf4j.internal.verbosity", "WARN")
        KotlinLoggingConfiguration.logStartupMessage = false
        val server = Server(
            serverInfo = Implementation(name = "solver-mcp", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        server.addTool(
            name = "modeling_guide",
            description = "Guide to the solver-mcp JSON DSL, schema, primitives, and repair workflow.",
            inputSchema = toolSchema()
        ) {
            safe { result(mapOf("guide" to ModelingGuide.text), ModelingGuide.text) }
        }
        server.addTool(
            name = "validate_spec",
            description = "Validate a solver-mcp problem spec against the v0.1 ASSIGNMENT DSL.",
            inputSchema = toolSchema("spec" to specSchema())
        ) { request ->
            safe { result(service.validate(arg(request.arguments, "spec"))) }
        }
        server.addTool(
            name = "solve",
            description = "Solve a validated ASSIGNMENT problem spec with Timefold.",
            inputSchema = toolSchema("spec" to specSchema(), "timeLimitSeconds" to integerSchema(1, 60, 10))
        ) { request ->
            safe { result(service.solve(arg(request.arguments, "spec"), longArg(request.arguments, "timeLimitSeconds"))) }
        }
        server.addTool(
            name = "explain_solution",
            description = "Analyze a provided spec and assignment map using Timefold ScoreAnalysis.",
            inputSchema = toolSchema("spec" to specSchema(), "assignments" to objectSchema(), "topN" to integerSchema(1, 100, 10))
        ) { request ->
            safe {
                result(
                    service.explain(
                        arg(request.arguments, "spec"),
                        arg(request.arguments, "assignments"),
                        longArg(request.arguments, "topN")?.toInt() ?: 10
                    )
                )
            }
        }
        server.addTool(
            name = "diagnose_infeasible",
            description = "List broken hard constraints and best-effort single hard-constraint relaxations.",
            inputSchema = toolSchema("spec" to specSchema(), "timeLimitSeconds" to integerSchema(1, 60, 10))
        ) { request ->
            safe { result(service.diagnose(arg(request.arguments, "spec"), longArg(request.arguments, "timeLimitSeconds"))) }
        }

        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered()
        ) {}
        server.createSession(transport)
        awaitCancellation()
    }

    private fun result(value: Any?, text: String = SpecJson.toJson(value), isError: Boolean = false): CallToolResult {
        val structured = Json.parseToJsonElement(SpecJson.toJson(value)).jsonObject
        return CallToolResult(
            content = listOf(TextContent(text = text)),
            isError = isError,
            structuredContent = structured
        )
    }

    private inline fun safe(block: () -> CallToolResult): CallToolResult =
        try {
            block()
        } catch (ex: Exception) {
            result(
                mapOf(
                    "error" to mapOf(
                        "code" to "tool_error",
                        "message" to (ex.message ?: ex::class.java.name),
                        "hint" to "Call validate_spec first and compare the spec against spec-schema.json and modeling_guide."
                    )
                ),
                isError = true
            )
        }

    private fun arg(args: JsonObject?, name: String): Any? {
        val element = args?.get(name) ?: throw IllegalArgumentException("$name is required")
        return SpecJson.mapper.readValue(element.toString(), Any::class.java)
    }

    private fun longArg(args: JsonObject?, name: String): Long? =
        args?.get(name)?.jsonPrimitive?.longOrNull

    private fun toolSchema(vararg properties: Pair<String, JsonObject>): ToolSchema =
        ToolSchema(
            properties = buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
            required = properties.mapNotNull { (name, _) -> name.takeIf { it == "spec" || it == "assignments" } }
        )

    private fun specSchema(): JsonObject = buildJsonObject {
        put("description", "Problem spec JSON object. See spec-schema.json and modeling_guide.")
        put("type", "object")
    }

    private fun objectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
    }

    private fun integerSchema(min: Int, max: Int, default: Int): JsonObject = buildJsonObject {
        put("type", "integer")
        put("minimum", min)
        put("maximum", max)
        put("default", default)
    }
}
