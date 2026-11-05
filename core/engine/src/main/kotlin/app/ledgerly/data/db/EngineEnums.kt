package app.ledgerly.data.db

// Shared by the engine contracts and Room entities; lives in core:engine so it compiles without Android.
enum class AuditEventType { CREATED, UPDATED, CATEGORISED, MERGED, SPLIT, DELETED, RESTORED, REVIEWED, FX_ENRICHED }

enum class Cadence { WEEKLY, MONTHLY, QUARTERLY, YEARLY, CUSTOM }
