package app.ledgerly.engine

import android.content.Context
import app.ledgerly.engine.fallback.FallbackAskService
import app.ledgerly.engine.fallback.FallbackAuditService
import app.ledgerly.engine.fallback.FallbackBudgetService
import app.ledgerly.engine.fallback.FallbackFxService
import app.ledgerly.engine.fallback.FallbackInsightsService

/**
 * Single wiring point for the engine modules. Replace each fallback with an adapter over the matching
 * core module once its API is stable, e.g.
 *   parser = CoreParserAdapter(app.ledgerly.core.parser.<Engine>())
 */
object EngineFactory {
    fun create(context: Context): Engines {
        val llm: LlmBackend = GeminiNanoLlmBackend(context)
        return Engines(
            parser = CoreParserAdapter(),
            deduper = CoreDeduperAdapter(),
            fx = FallbackFxService(),             // TODO(core-fx)
            audit = FallbackAuditService(),       // TODO(core-audit)
            budget = FallbackBudgetService(),     // TODO(core-budget)
            insights = FallbackInsightsService(), // TODO(core-insights)
            ask = CoreAskAdapter(FallbackAskService(llm)),
            llm = llm,
        )
    }
}
