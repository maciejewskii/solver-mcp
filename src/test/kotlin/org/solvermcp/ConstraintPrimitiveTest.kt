package org.solvermcp

import ai.timefold.solver.core.api.score.HardSoftScore
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier
import org.junit.jupiter.api.Test
import org.solvermcp.domain.AssignmentSolution
import org.solvermcp.domain.GenericElement
import org.solvermcp.domain.GenericEntity
import org.solvermcp.solver.GenericConstraintProvider
import org.solvermcp.spec.Archetype
import org.solvermcp.spec.ConstraintSpec
import org.solvermcp.spec.ElementSpec
import org.solvermcp.spec.ProblemSpec
import org.solvermcp.spec.ScoreLevel
import org.solvermcp.spec.SpecCompiler

class ConstraintPrimitiveTest {
    private val verifier = ConstraintVerifier.build(
        GenericConstraintProvider(),
        AssignmentSolution::class.java,
        GenericEntity::class.java
    )

    @Test
    fun require() {
        score(
            constraint = ConstraintSpec("cap", "require", whenExpr = "entity.attrs.load <= value.attrs.capacity"),
            entities = listOf("e1" to mapOf("load" to 5)),
            values = listOf("v1" to mapOf("capacity" to 3)),
            assignments = mapOf("e1" to "v1")
        ).scores(HardSoftScore.of(-1, 0))
        score(
            constraint = ConstraintSpec("cap", "require", whenExpr = "entity.attrs.load <= value.attrs.capacity"),
            entities = listOf("e1" to mapOf("load" to 2)),
            values = listOf("v1" to mapOf("capacity" to 3)),
            assignments = mapOf("e1" to "v1")
        ).scores(HardSoftScore.ZERO)
    }

    @Test
    fun forbid() {
        score(
            ConstraintSpec("blocked", "forbid", whenExpr = "value.attrs.blocked == true"),
            listOf("e1" to emptyMap()),
            listOf("v1" to mapOf("blocked" to true)),
            mapOf("e1" to "v1")
        ).scores(HardSoftScore.of(-1, 0))
        score(
            ConstraintSpec("blocked", "forbid", whenExpr = "value.attrs.blocked == true"),
            listOf("e1" to emptyMap()),
            listOf("v1" to mapOf("blocked" to false)),
            mapOf("e1" to "v1")
        ).scores(HardSoftScore.ZERO)
    }

    @Test
    fun allDifferent() {
        score(
            ConstraintSpec("diff", "all_different", expr = "value.id"),
            listOf("a" to emptyMap(), "b" to emptyMap()),
            listOf("x" to emptyMap(), "y" to emptyMap()),
            mapOf("a" to "x", "b" to "x")
        ).scores(HardSoftScore.of(-1, 0))
        score(
            ConstraintSpec("diff", "all_different", expr = "value.id"),
            listOf("a" to emptyMap(), "b" to emptyMap()),
            listOf("x" to emptyMap(), "y" to emptyMap()),
            mapOf("a" to "x", "b" to "y")
        ).scores(HardSoftScore.ZERO)
    }

    @Test
    fun pairRule() {
        val c = ConstraintSpec("team", "pair_rule", pairFilter = "a.attrs.team == b.attrs.team", forbid = "a.value.id == b.value.id")
        score(c, listOf("a" to mapOf("team" to "red"), "b" to mapOf("team" to "red")), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x"))
            .scores(HardSoftScore.of(-1, 0))
        score(c, listOf("a" to mapOf("team" to "red"), "b" to mapOf("team" to "blue")), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x"))
            .scores(HardSoftScore.ZERO)
    }

    @Test
    fun groupCount() {
        val c = ConstraintSpec("count", "group_count", groupBy = "value.id", max = 2)
        score(c, threeEntities(), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x", "c" to "x")).scores(HardSoftScore.of(-1, 0))
        score(c, threeEntities(), listOf("x" to emptyMap(), "y" to emptyMap()), mapOf("a" to "x", "b" to "x", "c" to "y")).scores(HardSoftScore.ZERO)
    }

    @Test
    fun groupSum() {
        val c = ConstraintSpec("sum", "group_sum", groupBy = "value.id", sumExpr = "entity.attrs.load", max = 6)
        score(c, listOf("a" to mapOf("load" to 4), "b" to mapOf("load" to 3)), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x"))
            .scores(HardSoftScore.of(-1, 0))
        score(c, listOf("a" to mapOf("load" to 4), "b" to mapOf("load" to 2)), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x"))
            .scores(HardSoftScore.ZERO)
    }

    @Test
    fun cardinality() {
        val c = ConstraintSpec("card", "cardinality", max = 2)
        score(c, threeEntities(), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x", "c" to "x")).scores(HardSoftScore.of(-1, 0))
        val min = ConstraintSpec("card_min", "cardinality", min = 1)
        score(min, listOf("a" to emptyMap()), listOf("x" to emptyMap(), "y" to emptyMap()), mapOf("a" to "x")).scores(HardSoftScore.of(-1, 0))
    }

    @Test
    fun balance() {
        val c = ConstraintSpec("balance", "balance", level = ScoreLevel.SOFT, groupBy = "value.id", expr = "1")
        score(c, threeEntities(), listOf("x" to emptyMap(), "y" to emptyMap()), mapOf("a" to "x", "b" to "x", "c" to "y"))
            .scores(HardSoftScore.of(0, -5))
    }

    @Test
    fun objective() {
        val c = ConstraintSpec("cost", "objective", level = ScoreLevel.SOFT, expr = "entity.attrs.cost", direction = "minimize")
        score(c, listOf("a" to mapOf("cost" to 4), "b" to mapOf("cost" to 3)), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x"))
            .scores(HardSoftScore.of(0, -7))
        val max = c.copy(direction = "maximize")
        score(max, listOf("a" to mapOf("cost" to 4), "b" to mapOf("cost" to 3)), listOf("x" to emptyMap()), mapOf("a" to "x", "b" to "x"))
            .scores(HardSoftScore.of(0, 7))
    }

    private fun score(
        constraint: ConstraintSpec,
        entities: List<Pair<String, Map<String, Any?>>>,
        values: List<Pair<String, Map<String, Any?>>>,
        assignments: Map<String, String>
    ) = verifier.verifyThat().givenSolution(solution(constraint, entities, values, assignments))

    private fun solution(
        constraint: ConstraintSpec,
        entities: List<Pair<String, Map<String, Any?>>>,
        values: List<Pair<String, Map<String, Any?>>>,
        assignments: Map<String, String>
    ): AssignmentSolution {
        val valueFacts = values.map { GenericElement(it.first, it.second) }
        val valueById = valueFacts.associateBy { it.id }
        return AssignmentSolution().apply {
            this.values = valueFacts.toMutableList()
            this.entities = entities.map { (id, attrs) ->
                GenericEntity(GenericElement(id, attrs)).apply { value = valueById[assignments.getValue(id)] }
            }.toMutableList()
            specItems = SpecCompiler.compileItems(
                ProblemSpec(
                    name = "test",
                    archetype = Archetype.ASSIGNMENT,
                    entities = entities.map { ElementSpec(it.first, it.second) },
                    values = values.map { ElementSpec(it.first, it.second) },
                    constraints = listOf(constraint)
                )
            )
        }
    }

    private fun threeEntities(): List<Pair<String, Map<String, Any?>>> =
        listOf("a" to emptyMap(), "b" to emptyMap(), "c" to emptyMap())
}
