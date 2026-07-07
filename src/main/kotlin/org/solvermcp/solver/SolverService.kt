package org.solvermcp.solver

import ai.timefold.solver.core.api.score.HardSoftScore
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy
import ai.timefold.solver.core.api.solver.SolutionManager
import ai.timefold.solver.core.api.solver.SolverFactory
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig
import ai.timefold.solver.core.config.solver.SolverConfig
import ai.timefold.solver.core.config.solver.termination.TerminationConfig
import org.solvermcp.domain.AssignmentSolution
import org.solvermcp.domain.GenericElement
import org.solvermcp.domain.GenericEntity
import org.solvermcp.spec.ConstraintSpecItem
import org.solvermcp.spec.ProblemSpec
import org.solvermcp.spec.ScoreLevel
import org.solvermcp.spec.SpecCompiler
import org.solvermcp.spec.SpecJson
import org.solvermcp.spec.SpecValidator
import org.solvermcp.spec.ValidationError
import org.solvermcp.spec.ValidationWarning
import org.solvermcp.dsl.EvalContext
import org.solvermcp.dsl.Expr
import org.solvermcp.dsl.asBoolean
import org.solvermcp.dsl.asScoreLong
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

data class ScoreDto(val hard: Long, val soft: Long)
data class ConstraintScoreDto(val id: String, val hard: Long, val soft: Long, val matchCount: Int)
data class SolveStatsDto(val timeLimitSeconds: Long, val elapsedMillis: Long, val solver: String = "Timefold Solver 2.2.0")

data class SolveResponse(
    val status: String,
    val assignments: Map<String, String?> = emptyMap(),
    val score: ScoreDto? = null,
    val perConstraint: List<ConstraintScoreDto> = emptyList(),
    val solverStats: SolveStatsDto? = null,
    val validationErrors: List<ValidationError> = emptyList(),
    val validationWarnings: List<ValidationWarning> = emptyList(),
    val message: String? = null
)

data class MatchDto(val score: ScoreDto, val involved: List<String>, val text: String)
data class ConstraintExplanationDto(val id: String, val score: ScoreDto, val matchCount: Int, val matches: List<MatchDto>)
data class ExplainResponse(
    val score: ScoreDto,
    val perConstraint: List<ConstraintExplanationDto>,
    val summary: String
)

data class DiagnoseResponse(
    val status: String,
    val score: ScoreDto? = null,
    val brokenHardConstraints: List<ConstraintExplanationDto> = emptyList(),
    val feasibleSingleRelaxations: List<String> = emptyList(),
    val message: String? = null
)

class SolverService {
    private val solveSemaphore = Semaphore(2)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "solver-mcp-solve").apply { isDaemon = true }
    }

    fun validate(specInput: Any?): Map<String, Any?> {
        val spec = try {
            SpecJson.parseAny(specInput)
        } catch (ex: Exception) {
            return mapOf("valid" to false, "errors" to listOf(parseError(ex)), "warnings" to emptyList<ValidationWarning>())
        }
        val result = SpecValidator.validate(spec)
        return mapOf("valid" to result.valid, "errors" to result.errors, "warnings" to result.warnings)
    }

    fun solve(specInput: Any?, requestedTimeLimitSeconds: Long? = null): SolveResponse {
        val spec = try {
            SpecJson.parseAny(specInput)
        } catch (ex: Exception) {
            return SolveResponse(status = "ERROR", validationErrors = listOf(parseError(ex)), message = "Spec parsing failed")
        }
        val lock = locks.computeIfAbsent(hash(SpecJson.toJson(spec))) { ReentrantLock() }
        return lock.withLock {
            solveSemaphore.acquire()
            try {
                solveValidated(spec, requestedTimeLimitSeconds ?: 10L)
            } finally {
                solveSemaphore.release()
            }
        }
    }

    fun explain(specInput: Any?, assignmentsInput: Any?, topN: Int = 10): ExplainResponse {
        val spec = SpecJson.parseAny(specInput)
        val validation = SpecValidator.validate(spec)
        if (!validation.valid) throw IllegalArgumentException("Spec validation failed: ${validation.errors.joinToString { it.message }}")
        val assignments = parseAssignments(assignmentsInput)
        val solution = buildSolution(spec, assignments)
        val summary = tryTimefoldSummary(solution)
        return manualExplain(solution, topN, summary)
    }

    fun diagnose(specInput: Any?, requestedTimeLimitSeconds: Long? = null): DiagnoseResponse {
        val spec = try {
            SpecJson.parseAny(specInput)
        } catch (ex: Exception) {
            return DiagnoseResponse("ERROR", message = "Spec parsing failed: ${ex.message ?: ex::class.java.name}")
        }
        val solved = solveValidated(spec, requestedTimeLimitSeconds ?: 10L)
        if (solved.status == "ERROR") {
            return DiagnoseResponse("ERROR", message = solved.message)
        }
        val score = solved.score ?: return DiagnoseResponse("ERROR", message = "No score was produced")
        val explanation = explain(spec, solved.assignments, 20)
        val broken = explanation.perConstraint.filter { it.score.hard < 0 }
        if (score.hard >= 0) {
            return DiagnoseResponse("FEASIBLE", score = score, message = "The best-found solution is hard-feasible.")
        }
        val relaxSeconds = min(3L, (requestedTimeLimitSeconds ?: 10L).coerceAtLeast(1L))
        val feasibleRelaxations = spec.constraints
            .filter { it.level == ScoreLevel.HARD }
            .mapNotNull { constraint ->
                val relaxed = spec.copy(constraints = spec.constraints.filterNot { it.id == constraint.id })
                val response = solveValidated(relaxed, relaxSeconds)
                if ((response.score?.hard ?: Long.MIN_VALUE) >= 0L) constraint.id else null
            }
        return DiagnoseResponse(
            status = "HARD_INFEASIBLE",
            score = score,
            brokenHardConstraints = broken,
            feasibleSingleRelaxations = feasibleRelaxations,
            message = "Single-constraint relaxations are best-effort brief re-solves; they are diagnostic hints, not proofs."
        )
    }

    private fun solveValidated(spec: ProblemSpec, requestedSeconds: Long): SolveResponse {
        val validation = SpecValidator.validate(spec)
        if (!validation.valid) {
            return SolveResponse(
                status = "ERROR",
                validationErrors = validation.errors,
                validationWarnings = validation.warnings,
                message = "Spec validation failed"
            )
        }
        val seconds = requestedSeconds.coerceIn(1L, 60L)
        val started = System.nanoTime()
        return try {
            val factory = solverFactory(spec, seconds)
            val initial = buildSolution(spec)
            val best = runSolver(factory, initial, seconds)
            val elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis()
            val score = best.score ?: manualScore(best)
            SolveResponse(
                status = if (score.hardScore() < 0L) "HARD_INFEASIBLE" else "FEASIBLE_BEST_FOUND",
                assignments = assignments(best),
                score = scoreDto(score),
                perConstraint = manualExplain(best, 0).perConstraint.map { ConstraintScoreDto(it.id, it.score.hard, it.score.soft, it.matchCount) },
                solverStats = SolveStatsDto(seconds, elapsedMillis),
                validationWarnings = validation.warnings,
                message = "Best-found solution within the time limit; not a proven optimum."
            )
        } catch (ex: Exception) {
            SolveResponse(status = "ERROR", validationWarnings = validation.warnings, message = ex.message ?: ex::class.java.name)
        }
    }

    private fun buildSolution(spec: ProblemSpec, assignments: Map<String, String?> = emptyMap()): AssignmentSolution {
        val values = spec.values.map { GenericElement(it.id, it.attrs) }
        val valueById = values.associateBy { it.id }
        return AssignmentSolution().apply {
            this.values = values.toMutableList()
            this.entities = spec.entities.map { element ->
                GenericEntity(GenericElement(element.id, element.attrs)).apply {
                    value = assignments[element.id]?.let { valueId ->
                        valueById[valueId] ?: throw IllegalArgumentException("Assignment for '${element.id}' references unknown value '$valueId'")
                    }
                }
            }.toMutableList()
            this.specItems = SpecCompiler.compileItems(spec)
        }
    }

    private fun solverFactory(spec: ProblemSpec, seconds: Long = 10L): SolverFactory<AssignmentSolution> =
        SolverFactory.create(
            SolverConfig()
                .withSolutionClass(AssignmentSolution::class.java)
                .withEntityClasses(GenericEntity::class.java)
                .withMoveThreadCount(SolverConfig.MOVE_THREAD_COUNT_NONE)
                .withRandomSeed(0L)
                .withScoreDirectorFactory(
                    ScoreDirectorFactoryConfig()
                        .withConstraintProviderClass(GenericConstraintProvider::class.java)
                        .withConstraintStreamAutomaticNodeSharing(true)
                )
                .withTerminationConfig(termination(spec, seconds))
                .withPhases(ConstructionHeuristicPhaseConfig(), LocalSearchPhaseConfig())
        )

    private fun termination(spec: ProblemSpec, seconds: Long): TerminationConfig {
        val hasSoft = spec.constraints.any { it.level == ScoreLevel.SOFT }
        val config = TerminationConfig().withSecondsSpentLimit(seconds)
        if (!hasSoft) config.withBestScoreLimit("0hard/0soft")
        return config
    }

    private fun runSolver(factory: SolverFactory<AssignmentSolution>, initial: AssignmentSolution, seconds: Long): AssignmentSolution {
        val solver = factory.buildSolver()
        val future = executor.submit(Callable { solver.solve(initial) })
        return try {
            future.get(seconds + 5L, TimeUnit.SECONDS)
        } catch (ex: TimeoutException) {
            solver.terminateEarly()
            future.cancel(true)
            throw IllegalStateException("Solver exceeded hard timeout after ${seconds + 5}s")
        }
    }

    private fun tryTimefoldSummary(solution: AssignmentSolution): String? =
        runCatching {
            SolutionManager.create<AssignmentSolution, HardSoftScore>(solverFactory(problemFromSolution(solution)))
                .analyze(solution, ScoreAnalysisFetchPolicy.FETCH_ALL)
                .summarize(20)
        }.getOrNull()

    private fun problemFromSolution(solution: AssignmentSolution): ProblemSpec =
        ProblemSpec(
            name = "analysis",
            archetype = org.solvermcp.spec.Archetype.ASSIGNMENT,
            entities = solution.entities.map { org.solvermcp.spec.ElementSpec(it.id, it.attrs) },
            values = solution.values.map { org.solvermcp.spec.ElementSpec(it.id, it.attrs) },
            constraints = emptyList()
        ).copy(constraints = solution.specItems.map {
            org.solvermcp.spec.ConstraintSpec(
                id = it.id,
                type = it.primitive,
                level = it.level,
                weight = it.weight,
                whenExpr = it.whenExprText,
                expr = it.exprText,
                direction = it.direction,
                pairFilter = it.pairFilterText,
                forbid = it.forbidText,
                penalizeIf = it.penalizeIfText,
                groupBy = it.groupByText,
                filter = it.filterText,
                sumExpr = it.sumExprText,
                min = it.min?.toInt(),
                max = it.max?.toInt(),
                valueFilter = it.valueFilterText
            )
        })

    private fun manualExplain(solution: AssignmentSolution, topN: Int, timefoldSummary: String? = null): ExplainResponse {
        val matches = manualMatches(solution)
        val byId = matches.groupBy { it.constraintId }
        val constraints = solution.specItems.sortedBy { it.id }.map { item ->
            val itemMatches = byId[item.id].orEmpty()
            val total = itemMatches.fold(HardSoftScore.ZERO) { acc, match -> acc.add(match.score) }
            ConstraintExplanationDto(
                id = item.id,
                score = scoreDto(total),
                matchCount = itemMatches.size,
                matches = itemMatches.sortedBy { it.involved.joinToString("|") }
                    .take(topN.coerceAtLeast(0))
                    .map { MatchDto(scoreDto(it.score), it.involved, it.involved.joinToString(", ")) }
            )
        }
        val score = constraints.fold(HardSoftScore.ZERO) { acc, c -> acc.add(HardSoftScore.of(c.score.hard, c.score.soft)) }
        return ExplainResponse(
            score = scoreDto(score),
            perConstraint = constraints,
            summary = timefoldSummary ?: "Manual score explanation from solver-mcp DSL matches."
        )
    }

    private fun manualScore(solution: AssignmentSolution): HardSoftScore =
        manualMatches(solution).fold(HardSoftScore.ZERO) { acc, match -> acc.add(match.score) }

    private fun manualMatches(solution: AssignmentSolution): List<ManualMatch> {
        val matches = mutableListOf<ManualMatch>()
        val entities = solution.entities.sortedBy { it.id }
        val values = solution.values.sortedBy { it.id }
        for (item in solution.specItems) {
            when (item.primitive) {
                "require" -> entities.filter { it.value != null && !evalBool(item.whenExpr, EvalContext().withEntity(it)) }
                    .forEach { matches += match(item, 1, false, "entity:${it.id}->${it.value?.id}") }
                "forbid" -> entities.filter { it.value != null && evalBool(item.whenExpr, EvalContext().withEntity(it)) }
                    .forEach { matches += match(item, 1, false, "entity:${it.id}->${it.value?.id}") }
                "all_different" -> entityPairs(entities).filter { (a, b) ->
                    a.value != null && b.value != null && evalAny(item.expr, EvalContext().withEntity(a)) == evalAny(item.expr, EvalContext().withEntity(b))
                }.forEach { (a, b) -> matches += match(item, 1, false, "entity:${a.id}->${a.value?.id}", "entity:${b.id}->${b.value?.id}") }
                "pair_rule" -> entityPairs(entities).filter { (a, b) -> a.value != null && b.value != null && pairRuleViolation(item, a, b) }
                    .forEach { (a, b) -> matches += match(item, 1, false, "entity:${a.id}->${a.value?.id}", "entity:${b.id}->${b.value?.id}") }
                "group_count" -> groupEntities(item, entities).mapValues { it.value.size.toLong() }
                    .forEach { (group, count) -> addBoundMatch(matches, item, group, count) }
                "group_sum" -> groupEntities(item, entities).mapValues { (_, grouped) -> grouped.sumOf { evalLong(item.sumExpr, EvalContext().withEntity(it)) } }
                    .forEach { (group, sum) -> addBoundMatch(matches, item, group, sum) }
                "cardinality" -> values.filter { valuePasses(item, it) }.forEach { value ->
                    val count = entities.count { it.value === value }.toLong()
                    addBoundMatch(matches, item, value.id, count)
                }
                "balance" -> groupEntities(item, entities).mapValues { it.value.size.toLong() }
                    .forEach { (group, count) -> matches += match(item, count * count, false, "group:$group", "count:$count") }
                "objective" -> entities.filter { it.value != null && (item.filter == null || evalBool(item.filter, EvalContext().withEntity(it))) }
                    .forEach {
                        val reward = item.direction.equals("maximize", ignoreCase = true)
                        matches += match(item, evalLong(item.expr, EvalContext().withEntity(it)), reward, "entity:${it.id}->${it.value?.id}")
                    }
            }
        }
        return matches
    }

    private fun groupEntities(item: ConstraintSpecItem, entities: List<GenericEntity>): Map<Any?, List<GenericEntity>> =
        entities.filter { it.value != null && (item.filter == null || evalBool(item.filter, EvalContext().withEntity(it))) }
            .groupBy { evalAny(item.groupBy, EvalContext().withEntity(it)) }

    private fun addBoundMatch(matches: MutableList<ManualMatch>, item: ConstraintSpecItem, group: Any?, value: Long) {
        val violation = boundViolation(value, item)
        if (violation > 0) matches += match(item, violation, false, "group:$group", "value:$value")
    }

    private fun entityPairs(entities: List<GenericEntity>): Sequence<Pair<GenericEntity, GenericEntity>> = sequence {
        for (i in entities.indices) {
            for (j in i + 1 until entities.size) yield(entities[i] to entities[j])
        }
    }

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

    private fun boundViolation(value: Long, item: ConstraintSpecItem): Long {
        val below = item.min?.let { (it - value).coerceAtLeast(0L) } ?: 0L
        val above = item.max?.let { (value - it).coerceAtLeast(0L) } ?: 0L
        return below + above
    }

    private fun match(item: ConstraintSpecItem, impact: Long, reward: Boolean, vararg involved: String): ManualMatch {
        val weighted = impact * item.weight
        val score = when (item.level) {
            ScoreLevel.HARD -> HardSoftScore.ofHard(-weighted)
            ScoreLevel.SOFT -> HardSoftScore.ofSoft(if (reward) weighted else -weighted)
        }
        return ManualMatch(item.id, score, involved.toList().sorted())
    }

    private fun evalBool(expr: Expr?, ctx: EvalContext): Boolean =
        asBoolean(requireNotNull(expr) { "Missing boolean expression" }.eval(ctx), expr.pos)

    private fun evalLong(expr: Expr?, ctx: EvalContext): Long =
        asScoreLong(requireNotNull(expr) { "Missing numeric expression" }.eval(ctx), expr.pos)

    private fun evalAny(expr: Expr?, ctx: EvalContext): Any? =
        requireNotNull(expr) { "Missing expression" }.eval(ctx)

    private fun assignments(solution: AssignmentSolution): Map<String, String?> =
        solution.entities.sortedBy { it.id }.associate { it.id to it.value?.id }

    @Suppress("UNCHECKED_CAST")
    private fun parseAssignments(input: Any?): Map<String, String?> = when (input) {
        null -> emptyMap()
        is Map<*, *> -> input.entries.associate { it.key.toString() to it.value?.toString() }
        is String -> SpecJson.mapper.readValue(input, Map::class.java).entries.associate { it.key.toString() to it.value?.toString() }
        else -> throw IllegalArgumentException("assignments must be an object or JSON string")
    }

    private fun scoreDto(score: HardSoftScore): ScoreDto = ScoreDto(score.hardScore(), score.softScore())

    private fun parseError(ex: Exception): ValidationError =
        ValidationError(
            path = "$",
            code = "spec_parse",
            message = ex.message ?: ex::class.java.name,
            hint = "Provide a JSON object matching spec-schema.json, or call modeling_guide for the expected shape."
        )

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class ManualMatch(val constraintId: String, val score: HardSoftScore, val involved: List<String>)
}
