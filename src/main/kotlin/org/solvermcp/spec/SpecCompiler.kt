package org.solvermcp.spec

import org.solvermcp.dsl.Expressions

object SpecCompiler {
    fun compileItems(spec: ProblemSpec): MutableList<ConstraintSpecItem> =
        spec.constraints.map { constraint ->
            ConstraintSpecItem().apply {
                id = constraint.id
                primitive = constraint.type
                level = constraint.level
                weight = if (constraint.level == ScoreLevel.SOFT) constraint.weight.coerceAtLeast(1) else 1
                min = constraint.min?.toLong()
                max = constraint.max?.toLong()
                direction = constraint.direction
                whenExprText = constraint.whenExpr
                exprText = constraint.expr
                pairFilterText = constraint.pairFilter
                forbidText = constraint.forbid
                penalizeIfText = constraint.penalizeIf
                groupByText = constraint.groupBy
                filterText = constraint.filter
                sumExprText = constraint.sumExpr
                valueFilterText = constraint.valueFilter
                whenExpr = constraint.whenExpr?.let(Expressions::parse)
                expr = constraint.expr?.let(Expressions::parse)
                pairFilter = constraint.pairFilter?.let(Expressions::parse)
                forbid = constraint.forbid?.let(Expressions::parse)
                penalizeIf = constraint.penalizeIf?.let(Expressions::parse)
                groupBy = constraint.groupBy?.let(Expressions::parse)
                filter = constraint.filter?.let(Expressions::parse)
                sumExpr = constraint.sumExpr?.let(Expressions::parse)
                valueFilter = constraint.valueFilter?.let(Expressions::parse)
            }
        }.toMutableList()
}
