package org.solvermcp.solver

import ai.timefold.solver.core.api.function.QuadFunction
import ai.timefold.solver.core.api.function.ToLongTriFunction
import ai.timefold.solver.core.api.function.TriFunction
import ai.timefold.solver.core.api.function.TriPredicate
import ai.timefold.solver.core.api.score.HardSoftScore
import ai.timefold.solver.core.api.score.stream.Constraint
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors
import ai.timefold.solver.core.api.score.stream.ConstraintProvider
import ai.timefold.solver.core.api.score.stream.DefaultConstraintJustification
import ai.timefold.solver.core.api.score.stream.Joiners
import org.solvermcp.domain.GenericElement
import org.solvermcp.domain.GenericEntity
import org.solvermcp.dsl.EvalContext
import org.solvermcp.dsl.Expr
import org.solvermcp.dsl.asBoolean
import org.solvermcp.dsl.asScoreLong
import org.solvermcp.spec.ConstraintSpecItem
import org.solvermcp.spec.ScoreLevel
import java.util.function.BiPredicate
import java.util.function.ToLongBiFunction

class GenericConstraintProvider : ConstraintProvider {
    override fun defineConstraints(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Array<Constraint> = arrayOf(
        requireHard(factory),
        requireSoft(factory),
        forbidHard(factory),
        forbidSoft(factory),
        allDifferentHard(factory),
        allDifferentSoft(factory),
        pairRuleHard(factory),
        pairRuleSoft(factory),
        groupCountHard(factory),
        groupCountSoft(factory),
        groupSumHard(factory),
        groupSumSoft(factory),
        cardinalityHard(factory),
        cardinalitySoft(factory),
        cardinalityMissingHard(factory),
        cardinalityMissingSoft(factory),
        balanceSoft(factory),
        objectiveMinSoft(factory),
        objectiveMaxSoft(factory)
    )

    private fun requireHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemEntity(factory, "require", ScoreLevel.HARD)
            .filter(BiPredicate { item, entity -> !evalBool(item.whenExpr, EvalContext().withEntity(entity)) })
            .penalize(HardSoftScore.ONE_HARD, ToLongBiFunction { item, _ -> item.weight })
            .justifyWith(TriFunction { item, entity, score -> DefaultConstraintJustification.of(score, item, entity, entity.value ?: "unassigned") })
            .asConstraint("require.HARD")

    private fun requireSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemEntity(factory, "require", ScoreLevel.SOFT)
            .filter(BiPredicate { item, entity -> !evalBool(item.whenExpr, EvalContext().withEntity(entity)) })
            .penalize(HardSoftScore.ONE_SOFT, ToLongBiFunction { item, _ -> item.weight })
            .justifyWith(TriFunction { item, entity, score -> DefaultConstraintJustification.of(score, item, entity, entity.value ?: "unassigned") })
            .asConstraint("require.SOFT")

    private fun forbidHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemEntity(factory, "forbid", ScoreLevel.HARD)
            .filter(BiPredicate { item, entity -> evalBool(item.whenExpr, EvalContext().withEntity(entity)) })
            .penalize(HardSoftScore.ONE_HARD, ToLongBiFunction { item, _ -> item.weight })
            .justifyWith(TriFunction { item, entity, score -> DefaultConstraintJustification.of(score, item, entity, entity.value ?: "unassigned") })
            .asConstraint("forbid.HARD")

    private fun forbidSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemEntity(factory, "forbid", ScoreLevel.SOFT)
            .filter(BiPredicate { item, entity -> evalBool(item.whenExpr, EvalContext().withEntity(entity)) })
            .penalize(HardSoftScore.ONE_SOFT, ToLongBiFunction { item, _ -> item.weight })
            .justifyWith(TriFunction { item, entity, score -> DefaultConstraintJustification.of(score, item, entity, entity.value ?: "unassigned") })
            .asConstraint("forbid.SOFT")

    private fun allDifferentHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemPair(factory, "all_different", ScoreLevel.HARD)
            .filter(TriPredicate { item, a, b -> evalAny(item.expr, EvalContext().withEntity(a)) == evalAny(item.expr, EvalContext().withEntity(b)) })
            .penalize(HardSoftScore.ONE_HARD, ToLongTriFunction { item, _, _ -> item.weight })
            .justifyWith(QuadFunction { item, a, b, score -> DefaultConstraintJustification.of(score, item, a, b) })
            .asConstraint("all_different.HARD")

    private fun allDifferentSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemPair(factory, "all_different", ScoreLevel.SOFT)
            .filter(TriPredicate { item, a, b -> evalAny(item.expr, EvalContext().withEntity(a)) == evalAny(item.expr, EvalContext().withEntity(b)) })
            .penalize(HardSoftScore.ONE_SOFT, ToLongTriFunction { item, _, _ -> item.weight })
            .justifyWith(QuadFunction { item, a, b, score -> DefaultConstraintJustification.of(score, item, a, b) })
            .asConstraint("all_different.SOFT")

    private fun pairRuleHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemPair(factory, "pair_rule", ScoreLevel.HARD)
            .filter(TriPredicate { item, a, b -> pairRuleViolation(item, a, b) })
            .penalize(HardSoftScore.ONE_HARD, ToLongTriFunction { item, _, _ -> item.weight })
            .justifyWith(QuadFunction { item, a, b, score -> DefaultConstraintJustification.of(score, item, a, b) })
            .asConstraint("pair_rule.HARD")

    private fun pairRuleSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemPair(factory, "pair_rule", ScoreLevel.SOFT)
            .filter(TriPredicate { item, a, b -> pairRuleViolation(item, a, b) })
            .penalize(HardSoftScore.ONE_SOFT, ToLongTriFunction { item, _, _ -> item.weight })
            .justifyWith(QuadFunction { item, a, b, score -> DefaultConstraintJustification.of(score, item, a, b) })
            .asConstraint("pair_rule.SOFT")

    private fun groupCountHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        groupedCount(factory, ScoreLevel.HARD)
            .filter(TriPredicate { item, _, count -> boundViolation(count, item) > 0 })
            .penalize(HardSoftScore.ONE_HARD, ToLongTriFunction { item, _, count -> boundViolation(count, item) * item.weight })
            .justifyWith(QuadFunction { item, group, count, score -> DefaultConstraintJustification.of(score, item, group ?: "null", count) })
            .asConstraint("group_count.HARD")

    private fun groupCountSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        groupedCount(factory, ScoreLevel.SOFT)
            .filter(TriPredicate { item, _, count -> boundViolation(count, item) > 0 })
            .penalize(HardSoftScore.ONE_SOFT, ToLongTriFunction { item, _, count -> boundViolation(count, item) * item.weight })
            .justifyWith(QuadFunction { item, group, count, score -> DefaultConstraintJustification.of(score, item, group ?: "null", count) })
            .asConstraint("group_count.SOFT")

    private fun groupSumHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        groupedSum(factory, ScoreLevel.HARD)
            .filter(TriPredicate { item, _, sum -> boundViolation(sum, item) > 0 })
            .penalize(HardSoftScore.ONE_HARD, ToLongTriFunction { item, _, sum -> boundViolation(sum, item) * item.weight })
            .justifyWith(QuadFunction { item, group, sum, score -> DefaultConstraintJustification.of(score, item, group ?: "null", sum) })
            .asConstraint("group_sum.HARD")

    private fun groupSumSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        groupedSum(factory, ScoreLevel.SOFT)
            .filter(TriPredicate { item, _, sum -> boundViolation(sum, item) > 0 })
            .penalize(HardSoftScore.ONE_SOFT, ToLongTriFunction { item, _, sum -> boundViolation(sum, item) * item.weight })
            .justifyWith(QuadFunction { item, group, sum, score -> DefaultConstraintJustification.of(score, item, group ?: "null", sum) })
            .asConstraint("group_sum.SOFT")

    private fun cardinalityHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        groupedCardinality(factory, ScoreLevel.HARD)
            .filter(TriPredicate { item, _, count -> boundViolation(count, item) > 0 })
            .penalize(HardSoftScore.ONE_HARD, ToLongTriFunction { item, _, count -> boundViolation(count, item) * item.weight })
            .justifyWith(QuadFunction { item, value, count, score -> DefaultConstraintJustification.of(score, item, value ?: "null", count) })
            .asConstraint("cardinality.HARD")

    private fun cardinalitySoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        groupedCardinality(factory, ScoreLevel.SOFT)
            .filter(TriPredicate { item, _, count -> boundViolation(count, item) > 0 })
            .penalize(HardSoftScore.ONE_SOFT, ToLongTriFunction { item, _, count -> boundViolation(count, item) * item.weight })
            .justifyWith(QuadFunction { item, value, count, score -> DefaultConstraintJustification.of(score, item, value ?: "null", count) })
            .asConstraint("cardinality.SOFT")

    private fun cardinalityMissingHard(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemValue(factory, "cardinality", ScoreLevel.HARD)
            .filter(BiPredicate { item, value -> (item.min ?: 0L) > 0 && valuePasses(item, value) })
            .ifNotExists(GenericEntity::class.java, Joiners.equal({ _, value -> value }, { entity: GenericEntity -> entity.value }))
            .penalize(HardSoftScore.ONE_HARD, ToLongBiFunction { item, _ -> (item.min ?: 0L) * item.weight })
            .justifyWith(TriFunction { item, value, score -> DefaultConstraintJustification.of(score, item, value, 0L) })
            .asConstraint("cardinality_missing.HARD")

    private fun cardinalityMissingSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemValue(factory, "cardinality", ScoreLevel.SOFT)
            .filter(BiPredicate { item, value -> (item.min ?: 0L) > 0 && valuePasses(item, value) })
            .ifNotExists(GenericEntity::class.java, Joiners.equal({ _, value -> value }, { entity: GenericEntity -> entity.value }))
            .penalize(HardSoftScore.ONE_SOFT, ToLongBiFunction { item, _ -> (item.min ?: 0L) * item.weight })
            .justifyWith(TriFunction { item, value, score -> DefaultConstraintJustification.of(score, item, value, 0L) })
            .asConstraint("cardinality_missing.SOFT")

    private fun balanceSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemEntity(factory, "balance", ScoreLevel.SOFT)
            .filter(BiPredicate { item, entity -> item.filter == null || evalBool(item.filter, EvalContext().withEntity(entity)) })
            .groupBy(
                { item, _ -> item },
                { item, entity -> evalAny(item.groupBy, EvalContext().withEntity(entity)) },
                ConstraintCollectors.countBi()
            )
            .penalize(HardSoftScore.ONE_SOFT, ToLongTriFunction { item, _, count -> count.toLong() * count.toLong() * item.weight })
            .justifyWith(QuadFunction { item, group, count, score -> DefaultConstraintJustification.of(score, item, group ?: "null", count) })
            .asConstraint("balance.SOFT")

    private fun objectiveMinSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemEntity(factory, "objective", ScoreLevel.SOFT)
            .filter(BiPredicate { item, entity -> isMinimize(item) && (item.filter == null || evalBool(item.filter, EvalContext().withEntity(entity))) })
            .penalize(HardSoftScore.ONE_SOFT, ToLongBiFunction { item, entity -> evalLong(item.expr, EvalContext().withEntity(entity)) * item.weight })
            .justifyWith(TriFunction { item, entity, score -> DefaultConstraintJustification.of(score, item, entity) })
            .asConstraint("objective_minimize.SOFT")

    private fun objectiveMaxSoft(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory): Constraint =
        itemEntity(factory, "objective", ScoreLevel.SOFT)
            .filter(BiPredicate { item, entity -> !isMinimize(item) && (item.filter == null || evalBool(item.filter, EvalContext().withEntity(entity))) })
            .reward(HardSoftScore.ONE_SOFT, ToLongBiFunction { item, entity -> evalLong(item.expr, EvalContext().withEntity(entity)) * item.weight })
            .justifyWith(TriFunction { item, entity, score -> DefaultConstraintJustification.of(score, item, entity) })
            .asConstraint("objective_maximize.SOFT")

    private fun itemEntity(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory, primitive: String, level: ScoreLevel) =
        factory.forEach(ConstraintSpecItem::class.java)
            .filter { item -> item.primitive == primitive && item.level == level }
            .join(GenericEntity::class.java)
            .filter(BiPredicate { _, entity -> entity.value != null })

    private fun itemValue(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory, primitive: String, level: ScoreLevel) =
        factory.forEach(ConstraintSpecItem::class.java)
            .filter { item -> item.primitive == primitive && item.level == level }
            .join(GenericElement::class.java)

    private fun itemPair(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory, primitive: String, level: ScoreLevel) =
        factory.forEach(ConstraintSpecItem::class.java)
            .filter { item -> item.primitive == primitive && item.level == level }
            .join(GenericEntity::class.java)
            .join(GenericEntity::class.java)
            .filter(TriPredicate { _, a, b -> a.id < b.id && a.value != null && b.value != null })

    private fun groupedCount(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory, level: ScoreLevel) =
        itemEntity(factory, "group_count", level)
            .filter(BiPredicate { item, entity -> item.filter == null || evalBool(item.filter, EvalContext().withEntity(entity)) })
            .groupBy(
                { item, _ -> item },
                { item, entity -> evalAny(item.groupBy, EvalContext().withEntity(entity)) },
                ConstraintCollectors.countBi()
            )

    private fun groupedSum(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory, level: ScoreLevel) =
        itemEntity(factory, "group_sum", level)
            .filter(BiPredicate { item, entity -> item.filter == null || evalBool(item.filter, EvalContext().withEntity(entity)) })
            .groupBy(
                { item, _ -> item },
                { item, entity -> evalAny(item.groupBy, EvalContext().withEntity(entity)) },
                ConstraintCollectors.sum { item: ConstraintSpecItem, entity: GenericEntity ->
                    evalLong(item.sumExpr, EvalContext().withEntity(entity))
                }
            )

    private fun groupedCardinality(factory: ai.timefold.solver.core.api.score.stream.ConstraintFactory, level: ScoreLevel) =
        itemEntity(factory, "cardinality", level)
            .filter(BiPredicate { item, entity -> entity.value != null && valuePasses(item, entity.value!!) })
            .groupBy(
                { item, _ -> item },
                { _, entity -> entity.value },
                ConstraintCollectors.countBi()
            )

    private fun pairRuleViolation(item: ConstraintSpecItem, a: GenericEntity, b: GenericEntity): Boolean {
        val ctx = EvalContext().withPair(a, b)
        if (item.pairFilter != null && !evalBool(item.pairFilter, ctx)) return false
        return when {
            item.forbid != null -> evalBool(item.forbid, ctx)
            item.penalizeIf != null -> evalBool(item.penalizeIf, ctx)
            else -> false
        }
    }

    private fun valuePasses(item: ConstraintSpecItem, value: GenericElement): Boolean =
        item.valueFilter == null || evalBool(item.valueFilter, EvalContext().withValue(value))

    private fun isMinimize(item: ConstraintSpecItem): Boolean =
        !item.direction.equals("maximize", ignoreCase = true)

    private fun boundViolation(value: Number, item: ConstraintSpecItem): Long {
        val longValue = value.toLong()
        val below = item.min?.let { (it - longValue).coerceAtLeast(0L) } ?: 0L
        val above = item.max?.let { (longValue - it).coerceAtLeast(0L) } ?: 0L
        return below + above
    }

    private fun evalBool(expr: Expr?, ctx: EvalContext): Boolean =
        asBoolean(requireNotNull(expr) { "Missing boolean expression" }.eval(ctx), expr.pos)

    private fun evalLong(expr: Expr?, ctx: EvalContext): Long =
        asScoreLong(requireNotNull(expr) { "Missing numeric expression" }.eval(ctx), expr.pos)

    private fun evalAny(expr: Expr?, ctx: EvalContext): Any? =
        requireNotNull(expr) { "Missing expression" }.eval(ctx)
}
