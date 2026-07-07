package org.solvermcp.spec

import org.solvermcp.dsl.Expr

enum class Archetype {
    ASSIGNMENT
}

enum class ScoreLevel {
    HARD,
    SOFT
}

data class ElementSpec(
    val id: String,
    val attrs: Map<String, Any?> = emptyMap()
)

data class ConstraintSpec(
    val id: String,
    val type: String,
    val level: ScoreLevel = ScoreLevel.HARD,
    val weight: Long = 1,
    val whenExpr: String? = null,
    val expr: String? = null,
    val direction: String? = null,
    val pairFilter: String? = null,
    val forbid: String? = null,
    val penalizeIf: String? = null,
    val groupBy: String? = null,
    val filter: String? = null,
    val sumExpr: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val valueFilter: String? = null,
    val filterA: String? = null,
    val filterB: String? = null
)

data class ProblemSpec(
    val name: String,
    val archetype: Archetype,
    val entities: List<ElementSpec>,
    val values: List<ElementSpec> = emptyList(),
    val constraints: List<ConstraintSpec> = emptyList()
)

class ConstraintSpecItem() {
    var id: String = ""
    var primitive: String = ""
    var level: ScoreLevel = ScoreLevel.HARD
    var weight: Long = 1
    var min: Long? = null
    var max: Long? = null
    var direction: String? = null
    var whenExprText: String? = null
    var exprText: String? = null
    var pairFilterText: String? = null
    var forbidText: String? = null
    var penalizeIfText: String? = null
    var groupByText: String? = null
    var filterText: String? = null
    var sumExprText: String? = null
    var valueFilterText: String? = null

    @Transient var whenExpr: Expr? = null
    @Transient var expr: Expr? = null
    @Transient var pairFilter: Expr? = null
    @Transient var forbid: Expr? = null
    @Transient var penalizeIf: Expr? = null
    @Transient var groupBy: Expr? = null
    @Transient var filter: Expr? = null
    @Transient var sumExpr: Expr? = null
    @Transient var valueFilter: Expr? = null

    override fun toString(): String = id
}

data class ValidationError(
    val path: String,
    val code: String,
    val message: String,
    val hint: String
)

data class ValidationWarning(
    val path: String,
    val code: String,
    val message: String,
    val hint: String
)

data class ValidationResult(
    val valid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning> = emptyList()
)
