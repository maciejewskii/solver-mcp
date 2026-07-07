package org.solvermcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.solvermcp.domain.GenericElement
import org.solvermcp.domain.GenericEntity
import org.solvermcp.dsl.EvalContext
import org.solvermcp.dsl.Expressions
import org.solvermcp.dsl.ParseException

class ExpressionTest {
    @Test
    fun `evaluates arithmetic comparison and boolean logic`() {
        val value = GenericElement("v1", mapOf("capacity" to 10))
        val entity = GenericEntity(GenericElement("e1", mapOf("load" to 4, "kind" to "a"))).apply {
            this.value = value
        }
        val expr = Expressions.parse("entity.attrs.load * 2 <= value.attrs.capacity && entity.attrs.kind == \"a\"")
        assertEquals(true, expr.eval(EvalContext().withEntity(entity)))
    }

    @Test
    fun `rejects method-call syntax`() {
        assertThrows(ParseException::class.java) {
            Expressions.parse("entity.id.startsWith(\"a\")")
        }
    }
}
