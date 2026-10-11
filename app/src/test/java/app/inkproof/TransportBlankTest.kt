package app.inkproof

import app.inkproof.ai.HttpAnalysisTransport
import app.inkproof.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportBlankTest {
    @Test
    fun blankBackendDoesNotPretendSuccess() {
        val transport = HttpAnalysisTransport { AppSettings(backendUrl = "  ") }
        val health = transport.health()
        assertEquals(0, health.httpStatus)
        assertEquals("NETWORK", health.failure)
        val check = transport.check(
            app.inkproof.core.ai.buildCheckRequest(
                "n",
                app.inkproof.core.model.Page(
                    id = "p",
                    notebookId = "n",
                    questions = listOf(app.inkproof.core.model.Question(id = "q", y = 0f, promptText = "2x+6=14")),
                ),
                "q",
            ),
        )
        assertEquals("NETWORK", check.failure)
    }
}
