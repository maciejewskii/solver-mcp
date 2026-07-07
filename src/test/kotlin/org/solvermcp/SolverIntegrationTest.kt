package org.solvermcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.solvermcp.solver.SolverService
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SolverIntegrationTest {
    private val service = SolverService()

    @Test
    fun `n queens 8 solves hard feasible`() {
        val response = service.solve(nQueens(8), 10)
        assertEquals("FEASIBLE_BEST_FOUND", response.status, response.message)
        assertEquals(0, response.score?.hard)
    }

    @Test
    fun `graph coloring solves and explains assignments`() {
        val spec = graphColoring()
        val response = service.solve(spec, 5)
        assertEquals("FEASIBLE_BEST_FOUND", response.status, response.message)
        assertEquals(0, response.score?.hard)
        val explanation = service.explain(spec, response.assignments, 5)
        assertEquals(0, explanation.score.hard)
    }

    @Test
    fun `infeasible spec diagnoses single relaxation`() {
        val spec = """
            {
              "name":"infeasible",
              "archetype":"ASSIGNMENT",
              "entities":[{"id":"a","attrs":{}},{"id":"b","attrs":{}}],
              "values":[{"id":"x","attrs":{}}],
              "constraints":[{"id":"different","type":"all_different","expr":"value.id","level":"HARD"}]
            }
        """.trimIndent()
        val diagnosis = service.diagnose(spec, 5)
        assertEquals("HARD_INFEASIBLE", diagnosis.status, diagnosis.message)
        assertTrue(diagnosis.feasibleSingleRelaxations.contains("different"))
    }

    @Test
    fun `validation returns structured errors and warnings`() {
        val result = service.validate(
            """
            {"name":"bad","archetype":"ASSIGNMENT","entities":[{"id":"a","attrs":{}}],"values":[],"constraints":[{"id":"x","type":"no_overlap"}]}
            """.trimIndent()
        )
        assertEquals(false, result["valid"])
        assertTrue((result["errors"] as List<*>).isNotEmpty())
    }

    @Test
    fun `malformed spec returns structured parse errors`() {
        val validation = service.validate("""{"name":""")
        assertEquals(false, validation["valid"])
        assertTrue((validation["errors"] as List<*>).isNotEmpty())

        val solved = service.solve("""{"name":""", 5)
        assertEquals("ERROR", solved.status)
        assertEquals("spec_parse", solved.validationErrors.first().code)
    }

    @Test
    fun `full solve writes nothing to stdout`() {
        val original = System.out
        val capture = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(capture))
            service.solve(graphColoring(), 5)
        } finally {
            System.setOut(original)
        }
        assertEquals("", capture.toString())
    }

    private fun graphColoring(): String = """
        {
          "name":"triangle 3 coloring",
          "archetype":"ASSIGNMENT",
          "entities":[{"id":"A","attrs":{}},{"id":"B","attrs":{}},{"id":"C","attrs":{}}],
          "values":[{"id":"red","attrs":{}},{"id":"green","attrs":{}},{"id":"blue","attrs":{}}],
          "constraints":[
            {"id":"edges","type":"pair_rule","pairFilter":"(a.id == \"A\" && b.id == \"B\") || (a.id == \"A\" && b.id == \"C\") || (a.id == \"B\" && b.id == \"C\")","forbid":"a.value.id == b.value.id","level":"HARD"}
          ]
        }
    """.trimIndent()

    private fun nQueens(n: Int): String {
        val entities = (0 until n).joinToString(",") { """{"id":"q$it","attrs":{"row":$it}}""" }
        val values = (0 until n).joinToString(",") { """{"id":"c$it","attrs":{"col":$it}}""" }
        return """
            {
              "name":"n queens $n",
              "archetype":"ASSIGNMENT",
              "entities":[$entities],
              "values":[$values],
              "constraints":[
                {"id":"one_per_column","type":"all_different","expr":"value.id","level":"HARD"},
                {"id":"diagonals","type":"pair_rule","forbid":"a.attrs.row + a.value.attrs.col == b.attrs.row + b.value.attrs.col || a.attrs.row - a.value.attrs.col == b.attrs.row - b.value.attrs.col","level":"HARD"}
              ]
            }
        """.trimIndent()
    }
}
