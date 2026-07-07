# solver-mcp

`solver-mcp` is a local MCP server that gives AI agents a real Timefold Solver backend for assignment problems. The agent writes a declarative JSON spec with generic entities, values, attributes, and constraint primitives; the server validates it, solves it, and returns assignments with a score breakdown. The LLM writes a declarative spec, never code; Timefold computes the answer; nothing is hallucinated.

Unofficial project powered by Timefold Solver Community (Apache 2.0).

## Quickstart

Prerequisites:

- JDK 21
- A local MCP client that can start stdio servers

Build the runnable fat jar:

```bash
./gradlew build
```

Run the server:

```bash
java -jar build/libs/solver-mcp-0.1.0-all.jar
```

For MCP clients, use the jar command above. Do not launch the MCP server through `./gradlew run`; Gradle writes progress output to stdout, and stdio MCP requires stdout to contain protocol frames only.

Or build and run the Docker image:

```bash
docker build -t solver-mcp:0.1.0 .
docker run -i --rm solver-mcp:0.1.0
```

Docker MCP clients must keep stdin open with `-i`; the server speaks MCP over stdio.

## Claude Code

From this repository:

```bash
claude mcp add solver-mcp -- java -jar /absolute/path/to/solver-mcp/build/libs/solver-mcp-0.1.0-all.jar
```

Docker alternative:

```bash
claude mcp add solver-mcp -- docker run -i --rm solver-mcp:0.1.0
```

Then start Claude Code and ask it to use `solver-mcp` to validate and solve a spec.

## Claude Desktop

Add a stdio server entry to your Claude Desktop MCP config:

```json
{
  "mcpServers": {
    "solver-mcp": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/solver-mcp/build/libs/solver-mcp-0.1.0-all.jar"
      ]
    }
  }
}
```

Restart Claude Desktop after editing the config.

Docker alternative:

```json
{
  "mcpServers": {
    "solver-mcp": {
      "type": "stdio",
      "command": "docker",
      "args": ["run", "-i", "--rm", "solver-mcp:0.1.0"]
    }
  }
}
```

## Tools

| Tool | Purpose |
| --- | --- |
| `modeling_guide` | Returns the DSL guide, expression syntax, primitive examples, full examples, and repair workflow. |
| `validate_spec` | Checks JSON shape, expression parsing, attribute references, primitive compatibility, and size warnings. |
| `solve` | Solves the ASSIGNMENT spec with a bounded Timefold run and returns the best-found assignment. |
| `explain_solution` | Rebuilds a supplied assignment and returns deterministic per-constraint totals and top matches. |
| `diagnose_infeasible` | Lists broken hard constraints and briefly re-solves with one hard constraint relaxed at a time. |

## Spec

The v0.1 schema is published at [spec-schema.json](spec-schema.json). The current archetype is `ASSIGNMENT`: every entity gets one value from the values list.

```json
{
  "name": "exam timetabling",
  "archetype": "ASSIGNMENT",
  "entities": [
    { "id": "examA", "attrs": { "students": 120, "teacher": "kim" } }
  ],
  "values": [
    { "id": "slot1_roomX", "attrs": { "day": 1, "slot": 1, "capacity": 150 } }
  ],
  "constraints": [
    {
      "id": "capacity",
      "type": "require",
      "when": "entity.attrs.students <= value.attrs.capacity",
      "level": "HARD"
    }
  ]
}
```

Constraint weights use the canonical schema:

```json
{ "level": "HARD" }
```

```json
{ "level": "SOFT", "weight": 3 }
```

## Expression Language

Expressions are parsed by a small hand-rolled parser and evaluated against the generic model. They support identifiers `entity`, `value`, `a`, `b`, and `group`; paths such as `entity.attrs.load`, `value.id`, and `a.value.attrs.day`; number, string, boolean, and null literals; arithmetic, comparisons, `&&`, `||`, `!`, and parentheses.

There are no method calls, loops, reflection, or arbitrary code execution.

## Primitive Reference

| Primitive | Level | Required fields | Meaning |
| --- | --- | --- | --- |
| `require` | HARD or SOFT | `when` | Penalizes each assignment where `when` is false. |
| `forbid` | HARD or SOFT | `when` | Penalizes each assignment where `when` is true. |
| `all_different` | HARD or SOFT | `expr` | Penalizes entity pairs with equal `expr` values. |
| `pair_rule` | HARD or SOFT | `forbid` or `penalizeIf` | Penalizes entity pairs selected by optional `pairFilter` when the pair condition is true. |
| `group_count` | HARD or SOFT | `groupBy`, `min` or `max` | Bounds the number of assigned entities per group. |
| `group_sum` | HARD or SOFT | `groupBy`, `sumExpr`, `min` or `max` | Bounds a numeric sum per group. |
| `cardinality` | HARD or SOFT | `min` or `max` | Bounds how many entities are assigned to each value, optionally limited by `valueFilter`. |
| `balance` | SOFT | `groupBy` | Penalizes `(group count)^2` for each group. |
| `objective` | SOFT | `expr` | `minimize` penalizes the sum; `maximize` rewards the sum. |

## Examples

The repository includes five complete specs:

- [N-queens 8](examples/n-queens-8.json)
- [Graph coloring](examples/graph-coloring.json)
- [Task to machine with capacities](examples/task-machine-capacity.json)
- [Wedding seating with incompatible pairs](examples/wedding-seating.json)
- [Exam timetabling](examples/exam-timetabling.json)

See [examples/demo.md](examples/demo.md) for a transcript of the intended agent loop:

```text
validate_spec -> solve -> explain_solution -> repair the spec if hard infeasible
```

## Architecture

`solver-mcp` deliberately avoids domain classes such as employees, shifts, rooms, or jobs. The Timefold model has generic `Element` facts, generic planning entities, and constraint spec items stored on the planning solution as problem facts.

The `ConstraintProvider` contains always-present streams for every primitive and score level. Each stream starts from `ConstraintSpecItem`, filters by primitive and level, joins generic entities or values, and evaluates the cached expression AST. This keeps the solver data-driven without static registries or generated code.

`solve` returns the best-found solution within the requested time limit. `explain_solution` rebuilds the solution from `{ spec, assignments }` and produces deterministic constraint totals and top matches so every score contribution is traceable.

## Limitations

- v0.1 supports ASSIGNMENT only. SEQUENCE is planned for v0.2.
- Timefold returns the best-found solution within the time limit unless configured otherwise; this project does not prove optimality.
- The expression language is intentionally small and bounded.
- The generic constraint streams are flexible, not a replacement for a hand-tuned domain solver on large production instances.
- `validate_spec` warns above 500 entities or 50,000 estimated pairs.

## Roadmap

- SEQUENCE archetype with list-variable shadow variables: `@IndexShadowVariable`, `@PreviousElementShadowVariable`, and `@CascadingUpdateShadowVariable`.
- CP-SAT backend for optimality proofs where the DSL can be compiled exactly.
- More primitive constraints and stronger static type checks.
- Packaged desktop extension distribution.

## Development

Run the full suite:

```bash
./gradlew build
```

Build the container:

```bash
docker build -t solver-mcp:0.1.0 .
```

The tests cover expression parsing and evaluation, every assignment primitive via Timefold `ConstraintVerifier`, end-to-end N-queens and graph coloring, infeasible diagnosis, validation errors, stdout discipline, and all checked-in examples.

## License

Apache License 2.0. See [LICENSE](LICENSE).
