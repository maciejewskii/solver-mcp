package org.solvermcp.guide

object ModelingGuide {
    val text: String = """
# solver-mcp modeling guide

v1.0 supports the ASSIGNMENT archetype: each entity is assigned one value. See `spec-schema.json` in the repository for the JSON Schema. Workflow: validate_spec -> solve -> explain_solution. If solve returns HARD_INFEASIBLE, call diagnose_infeasible, repair the declarative spec, and validate again. Timefold returns the best solution found within the time limit, not a proven optimum.

Canonical spec shape:
```json
{
  "name": "problem name",
  "archetype": "ASSIGNMENT",
  "entities": [{"id":"e1","attrs":{"load":3}}],
  "values": [{"id":"v1","attrs":{"capacity":5}}],
  "constraints": [
    {"id":"cap","type":"require","when":"entity.attrs.load <= value.attrs.capacity","level":"HARD"}
  ]
}
```

Expression language:
- Identifiers: `entity`, `value`, `a`, `b`, `group`.
- Paths: `x.id`, `x.attrs.name`, `entity.value.id`, `a.value.attrs.day`.
- Literals: numbers, strings, booleans, null.
- Operators: `+ - * / %`, `< <= > >= == !=`, `&& || !`, parentheses.
- No calls, loops, reflection, or arbitrary code.

Primitive examples:
- require: `{"id":"cap","type":"require","when":"entity.attrs.load <= value.attrs.capacity","level":"HARD"}`
- forbid: `{"id":"blocked","type":"forbid","when":"value.attrs.blocked == true","level":"HARD"}`
- all_different: `{"id":"unique_slot","type":"all_different","expr":"value.id","level":"HARD"}`
- pair_rule: `{"id":"conflict","type":"pair_rule","pairFilter":"a.attrs.team == b.attrs.team","forbid":"a.value.id == b.value.id","level":"HARD"}`
- group_count: `{"id":"room_count","type":"group_count","groupBy":"value.id","max":3,"level":"HARD"}`
- group_sum: `{"id":"capacity","type":"group_sum","groupBy":"value.id","sumExpr":"entity.attrs.load","max":10,"level":"HARD"}`
- cardinality: `{"id":"table_size","type":"cardinality","max":8,"level":"HARD"}`
- balance: `{"id":"spread","type":"balance","groupBy":"value.id","expr":"1","level":"SOFT","weight":1}`
- objective: `{"id":"cost","type":"objective","expr":"entity.attrs.cost","direction":"minimize","level":"SOFT","weight":1}`

Full example specs:
- `examples/n-queens-8.json`
- `examples/graph-coloring.json`
- `examples/task-machine-capacity.json`
- `examples/wedding-seating.json`
- `examples/exam-timetabling.json`

Repair loop:
1. Call validate_spec. Fix JSON shape, unsupported primitives, missing fields, or expression errors.
2. Call solve with a small time limit.
3. Call explain_solution with the returned assignments to inspect per-constraint matches.
4. If hard infeasible, call diagnose_infeasible to see broken hard constraints and single relaxations that may restore feasibility.
5. Update only the declarative spec. Do not generate code.
""".trimIndent()
}
