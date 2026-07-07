package org.solvermcp.spec

import org.solvermcp.dsl.Expressions
import org.solvermcp.dsl.ParseException

object SpecValidator {
    private val assignmentPrimitives = setOf(
        "require", "forbid", "all_different", "pair_rule", "group_count", "group_sum", "cardinality", "balance", "objective"
    )

    fun validate(spec: ProblemSpec): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()
        if (spec.name.isBlank()) errors += error("name", "missing_name", "Problem name is blank", "Set a short name to make solve traces readable.")
        if (spec.entities.isEmpty()) errors += error("entities", "empty_entities", "No entities were provided", "Add at least one entity to assign or sequence.")
        if (spec.entities.size > 500) warnings += warning("entities", "large_entity_count", "Spec has ${spec.entities.size} entities", "This generic pair-oriented solver is intended for small and medium agent-modeled problems.")
        val estimatedPairs = spec.entities.size.toLong() * (spec.entities.size - 1L) / 2L
        if (estimatedPairs > 50_000L) warnings += warning("constraints", "large_pair_space", "Estimated pair space is $estimatedPairs", "Pair primitives may be slow; reduce entities or simplify pair rules.")
        duplicateIds("entities", spec.entities, errors)
        duplicateIds("values", spec.values, errors)
        if (spec.values.isEmpty()) errors += error("values", "empty_values", "ASSIGNMENT specs require values", "Add a non-empty values array.")
        duplicateIds("constraints", spec.constraints.map { ElementSpec(it.id) }, errors)
        spec.constraints.forEachIndexed { index, constraint ->
            validateConstraint(spec, constraint, index, errors)
        }
        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    private fun duplicateIds(path: String, elements: List<ElementSpec>, errors: MutableList<ValidationError>) {
        elements.groupBy { it.id }.filter { it.key.isBlank() || it.value.size > 1 }.forEach { (id, duplicates) ->
            val code = if (id.isBlank()) "blank_id" else "duplicate_id"
            errors += error(path, code, "Id '$id' appears ${duplicates.size} times in $path", "Use stable unique ids; they are the references used in solutions and explanations.")
        }
    }

    private fun validateConstraint(spec: ProblemSpec, constraint: ConstraintSpec, index: Int, errors: MutableList<ValidationError>) {
        val path = "constraints[$index]"
        if (constraint.type !in assignmentPrimitives) {
            errors += error("$path.type", "unsupported_primitive", "Primitive '${constraint.type}' is not supported for ${spec.archetype}", "Use one of: ${assignmentPrimitives.sorted().joinToString(", ")}.")
            return
        }
        if (constraint.weight < 1) errors += error("$path.weight", "invalid_weight", "Weight must be positive", "Use weight >= 1; HARD constraints usually omit weight.")
        if (constraint.type in setOf("balance", "objective") && constraint.level != ScoreLevel.SOFT) {
            errors += error("$path.level", "invalid_level", "${constraint.type} must be SOFT in v1.0", "Set level to SOFT.")
        }
        when (constraint.type) {
            "require", "forbid" -> requireField(path, "when", constraint.whenExpr, errors)
            "all_different", "objective" -> requireField(path, "expr", constraint.expr, errors)
            "pair_rule" -> if (constraint.forbid == null && constraint.penalizeIf == null) {
                errors += error(path, "missing_pair_condition", "pair_rule needs forbid or penalizeIf", "Add forbid for hard conflicts or penalizeIf for weighted pair penalties.")
            }
            "group_count" -> {
                requireField(path, "groupBy", constraint.groupBy, errors)
                requireBound(path, constraint, errors)
            }
            "group_sum" -> {
                requireField(path, "groupBy", constraint.groupBy, errors)
                requireField(path, "sumExpr", constraint.sumExpr, errors)
                requireBound(path, constraint, errors)
            }
            "cardinality" -> requireBound(path, constraint, errors)
            "balance" -> requireField(path, "groupBy", constraint.groupBy, errors)
        }
        expressionsFor(constraint).forEach { (field, source) ->
            validateExpression("$path.$field", source, spec, errors)
        }
    }

    private fun expressionsFor(c: ConstraintSpec): List<Pair<String, String>> = listOfNotNull(
        c.whenExpr?.let { "when" to it },
        c.expr?.let { "expr" to it },
        c.pairFilter?.let { "pairFilter" to it },
        c.forbid?.let { "forbid" to it },
        c.penalizeIf?.let { "penalizeIf" to it },
        c.groupBy?.let { "groupBy" to it },
        c.filter?.let { "filter" to it },
        c.sumExpr?.let { "sumExpr" to it },
        c.valueFilter?.let { "valueFilter" to it },
        c.filterA?.let { "filterA" to it },
        c.filterB?.let { "filterB" to it }
    )

    private fun validateExpression(path: String, source: String, spec: ProblemSpec, errors: MutableList<ValidationError>) {
        val expr = try {
            Expressions.parse(source)
        } catch (ex: ParseException) {
            errors += error(path, "expression_parse", ex.message ?: "Expression parse failed", "Check operators, quotes, and parentheses near position ${ex.position}.")
            return
        } catch (ex: RuntimeException) {
            errors += error(path, "expression_parse", ex.message ?: "Expression parse failed", "Use only identifiers, literals, arithmetic, comparisons, and boolean operators.")
            return
        }
        val entityAttrs = spec.entities.flatMap { it.attrs.keys }.toSet()
        val valueAttrs = spec.values.flatMap { it.attrs.keys }.toSet()
        expr.attrRefs().forEach { ref ->
            val known = when (ref.root) {
                "entity", "a", "b" -> ref.attr in entityAttrs
                "value" -> ref.attr in valueAttrs
                "group" -> true
                else -> true
            }
            if (!known) {
                errors += error(path, "unknown_attr", "Attribute '${ref.attr}' is not present for '${ref.root}'", "Either add attrs.${ref.attr} to the relevant elements or fix the expression path.")
            }
        }
    }

    private fun requireField(path: String, name: String, value: String?, errors: MutableList<ValidationError>) {
        if (value.isNullOrBlank()) errors += error("$path.$name", "missing_field", "Missing required field '$name'", "Add '$name' for this primitive.")
    }

    private fun requireBound(path: String, constraint: ConstraintSpec, errors: MutableList<ValidationError>) {
        if (constraint.min == null && constraint.max == null) {
            errors += error(path, "missing_bound", "Missing min or max", "Add min, max, or both.")
        }
    }

    private fun error(path: String, code: String, message: String, hint: String): ValidationError =
        ValidationError(path, code, message, hint)

    private fun warning(path: String, code: String, message: String, hint: String): ValidationWarning =
        ValidationWarning(path, code, message, hint)
}
