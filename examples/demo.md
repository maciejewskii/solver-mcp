# Demo Transcript

User: Model 8 queens and solve it.

Agent: I will build a declarative ASSIGNMENT spec: queens are entities, columns are values, and constraints forbid repeated columns and diagonal conflicts. I will validate before solving.

Tool call: `validate_spec` with `examples/n-queens-8.json`

Tool result:
```json
{ "valid": true, "errors": [], "warnings": [] }
```

Tool call: `solve` with timeLimitSeconds 10

Tool result:
```json
{
  "status": "FEASIBLE_BEST_FOUND",
  "assignments": { "q0": "c4", "q1": "c2", "q2": "c7" },
  "score": { "hard": 0, "soft": 0 },
  "message": "Best-found solution within the time limit; not a proven optimum."
}
```

Agent: The best-found solution is hard-feasible. I will explain it so every score contribution is traceable.

Tool call: `explain_solution` with the original spec and returned assignments.

Tool result:
```json
{
  "score": { "hard": 0, "soft": 0 },
  "perConstraint": [
    { "id": "diagonals", "score": { "hard": 0, "soft": 0 }, "matchCount": 0 },
    { "id": "one_per_column", "score": { "hard": 0, "soft": 0 }, "matchCount": 0 }
  ]
}
```

Agent: No hard constraint matches remain. This is a solver-computed feasible assignment, not a guessed answer.
