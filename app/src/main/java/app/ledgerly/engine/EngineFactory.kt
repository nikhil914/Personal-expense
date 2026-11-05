package app.ledgerly.engine

import android.content.Context
import app.ledgerly.engine.fallback.FallbackAskService
import app.ledgerly.engine.fallback.FallbackAuditService
import app.ledgerly.engine.fallback.FallbackBudgetService
import app.ledgerly.engine.fallback.FallbackDeduper
import app.ledgerly.engine.fallback.FallbackFxService
import app.ledgerly.engine.fallback.FallbackInsightsService
import app.ledgerly.engine.fallback.FallbackParser

/**
 * Single wiring point for the engine modules. Replace each fallback with an adapter over the matching
 * core module once its API is stable, e.g.
 *   parser = CoreParserAdapter(app.ledgerly.core.parser.<Engine>())
 */
object EngineFactory {
    fun create(context: Context): Engines {
        val llm: LlmBackend = GeminiNanoLlmBackend(context)
        return Engines(
            parser = FallbackParser(),            // TODO(core-parser)
            deduper = FallbackDeduper(),          // TODO(core-dedup)
            fx = FallbackFxService(),             // TODO(core-fx)
            audit = FallbackAuditService(),       // TODO(core-audit)
            budget = FallbackBudgetService(),     // TODO(core-budget)
            insights = FallbackInsightsService(), // TODO(core-insights)
            ask = FallbackAskService(llm),        // TODO(core-insights) NL query engine
            llm = llm,
        )
    }
}
