package org.solvermcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.solvermcp.solver.SolverService
import java.nio.file.Files
import java.nio.file.Path

class ExamplesTest {
    private val service = SolverService()

    @Test
    fun `all example specs validate solve and explain`() {
        val examplesDir = Path.of("examples")
        val exampleFiles = Files.list(examplesDir)
            .filter { it.fileName.toString().endsWith(".json") }
            .sorted()
            .toList()

        assertEquals(5, exampleFiles.size)

        exampleFiles.forEach { path ->
            val spec = Files.readString(path)
            val validation = service.validate(spec)
            assertEquals(true, validation["valid"], "${path.fileName} validation failed: ${validation["errors"]}")

            val solved = service.solve(spec, 10)
            assertEquals("FEASIBLE_BEST_FOUND", solved.status, "${path.fileName}: ${solved.message}")
            assertEquals(0, solved.score?.hard, "${path.fileName} hard score")

            val explained = service.explain(spec, solved.assignments, 5)
            assertEquals(solved.score?.hard, explained.score.hard, "${path.fileName} explanation hard score")
            assertEquals(solved.score?.soft, explained.score.soft, "${path.fileName} explanation soft score")
            assertTrue(explained.perConstraint.isNotEmpty(), "${path.fileName} explanations should include constraints")
        }
    }
}
