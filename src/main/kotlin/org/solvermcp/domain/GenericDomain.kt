package org.solvermcp.domain

import ai.timefold.solver.core.api.domain.entity.PlanningEntity
import ai.timefold.solver.core.api.domain.common.PlanningId
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty
import ai.timefold.solver.core.api.domain.solution.PlanningScore
import ai.timefold.solver.core.api.domain.solution.PlanningSolution
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider
import ai.timefold.solver.core.api.domain.variable.PlanningVariable
import ai.timefold.solver.core.api.score.HardSoftScore
import org.solvermcp.spec.ConstraintSpecItem

open class GenericElement() {
    @field:PlanningId
    open var id: String = ""
    open var attrs: MutableMap<String, Any?> = mutableMapOf()

    constructor(id: String, attrs: Map<String, Any?> = emptyMap()) : this() {
        this.id = id
        this.attrs = attrs.toMutableMap()
    }

    override fun toString(): String = id
}

@PlanningEntity
open class GenericEntity() {
    @field:PlanningId
    open var id: String = ""
    open var element: GenericElement = GenericElement()

    @PlanningVariable(valueRangeProviderRefs = ["valueRange"])
    open var value: GenericElement? = null

    constructor(element: GenericElement) : this() {
        this.element = element
        this.id = element.id
    }

    val attrs: Map<String, Any?> get() = element.attrs

    override fun toString(): String = id
}

@PlanningSolution
open class AssignmentSolution() {
    @PlanningEntityCollectionProperty
    open var entities: MutableList<GenericEntity> = mutableListOf()

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "valueRange")
    open var values: MutableList<GenericElement> = mutableListOf()

    @ProblemFactCollectionProperty
    open var specItems: MutableList<ConstraintSpecItem> = mutableListOf()

    @PlanningScore
    open var score: HardSoftScore? = null
}
