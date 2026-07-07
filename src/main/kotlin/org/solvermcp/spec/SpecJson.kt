package org.solvermcp.spec

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object SpecJson {
    val mapper: ObjectMapper = jacksonObjectMapper()

    fun parseJson(json: String): ProblemSpec = parseNode(mapper.readTree(json))

    fun parseAny(input: Any?): ProblemSpec = when (input) {
        is ProblemSpec -> input
        is String -> parseJson(input)
        is JsonNode -> parseNode(input)
        is Map<*, *> -> parseNode(mapper.valueToTree(input))
        else -> throw IllegalArgumentException("Expected spec as JSON object or string")
    }

    fun toJson(value: Any?): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)

    private fun parseNode(root: JsonNode): ProblemSpec {
        if (!root.isObject) throw IllegalArgumentException("Spec must be a JSON object")
        val name = root.path("name").asText("unnamed")
        val archetype = root.path("archetype").asText("ASSIGNMENT").let {
            runCatching { Archetype.valueOf(it.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown archetype '$it'") }
        }
        return ProblemSpec(
            name = name,
            archetype = archetype,
            entities = parseElements(root.path("entities")),
            values = parseElements(root.path("values")),
            constraints = parseConstraints(root.path("constraints"))
        )
    }

    private fun parseElements(node: JsonNode): List<ElementSpec> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) throw IllegalArgumentException("elements/resources/values must be arrays")
        return node.mapIndexed { index, item ->
            val id = item.path("id").asText(null) ?: throw IllegalArgumentException("Element at index $index is missing id")
            val attrs = parseAttrs(item.get("attrs"), "Element '$id' attrs")
            ElementSpec(id, attrs)
        }
    }

    private fun parseAttrs(node: JsonNode?, context: String): Map<String, Any?> {
        if (node == null || node.isMissingNode || node.isNull) return emptyMap()
        if (!node.isObject) throw IllegalArgumentException("$context must be a JSON object")
        return node.fields().asSequence().associate { it.key to toPlainValue(it.value) }
    }

    private fun parseConstraints(node: JsonNode): List<ConstraintSpec> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) throw IllegalArgumentException("constraints must be an array")
        return node.mapIndexed { index, item ->
            val id = item.path("id").asText(null) ?: throw IllegalArgumentException("Constraint at index $index is missing id")
            val type = item.path("type").asText(null) ?: throw IllegalArgumentException("Constraint '$id' is missing type")
            ConstraintSpec(
                id = id,
                type = type,
                level = parseLevel(item.get("level"), item.get("weight"), defaultFor(type)),
                weight = parseWeight(item.get("weight")),
                whenExpr = item.get("when")?.asText(),
                expr = item.get("expr")?.asText(),
                direction = item.get("direction")?.asText() ?: item.get("goal")?.asText(),
                pairFilter = item.get("pairFilter")?.asText(),
                forbid = item.get("forbid")?.asText(),
                penalizeIf = item.get("penalizeIf")?.asText(),
                groupBy = item.get("groupBy")?.asText(),
                filter = item.get("filter")?.asText(),
                sumExpr = item.get("sumExpr")?.asText(),
                min = item.get("min")?.asIntOrNull(),
                max = item.get("max")?.asIntOrNull(),
                valueFilter = item.get("valueFilter")?.asText(),
                filterA = item.get("filterA")?.asText(),
                filterB = item.get("filterB")?.asText()
            )
        }
    }

    private fun defaultFor(type: String): ScoreLevel =
        if (type == "objective" || type == "balance") ScoreLevel.SOFT else ScoreLevel.HARD

    private fun parseLevel(node: JsonNode?, legacyWeight: JsonNode?, default: ScoreLevel): ScoreLevel {
        if (node != null && !node.isNull && !node.isMissingNode) {
            return ScoreLevel.valueOf(node.asText().uppercase())
        }
        if (legacyWeight?.isTextual == true) {
            return ScoreLevel.valueOf(legacyWeight.asText().uppercase())
        }
        if (legacyWeight?.isObject == true) {
            return if ((legacyWeight.get("soft")?.asLong() ?: 0L) > 0) ScoreLevel.SOFT else ScoreLevel.HARD
        }
        return default
    }

    private fun parseWeight(node: JsonNode?): Long {
        if (node == null || node.isNull || node.isMissingNode || node.isTextual) return 1
        if (node.isNumber) return node.asLong().coerceAtLeast(1L)
        if (node.isObject) {
            return (node.get("weight")?.asLong()
                ?: node.get("soft")?.asLong()
                ?: node.get("hard")?.asLong()
                ?: 1L).coerceAtLeast(1L)
        }
        throw IllegalArgumentException("Weight must be a positive integer")
    }

    private fun toPlainValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isBoolean -> node.booleanValue()
        node.isInt || node.isLong -> node.longValue()
        node.isFloat || node.isDouble || node.isBigDecimal -> node.doubleValue()
        node.isTextual -> node.textValue()
        node.isArray -> node.map { toPlainValue(it) }
        node.isObject -> node.fields().asSequence().associate { it.key to toPlainValue(it.value) }
        else -> node.asText()
    }

    private fun JsonNode.asIntOrNull(): Int? = if (isMissingNode || isNull) null else asInt()
}
