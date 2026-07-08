package ai.shipeasy

import java.util.logging.Level
import java.util.logging.Logger

/**
 * SDK log verbosity, ordered `SILENT < ERROR < WARN < INFO < DEBUG`. A message at
 * level `L` is emitted iff the configured level is `>= L` (so `SILENT` mutes
 * everything, `WARN` — the default — emits errors and warnings, `DEBUG` emits
 * everything). Mirrors the cross-SDK `logLevel` contract.
 */
enum class LogLevel { SILENT, ERROR, WARN, INFO, DEBUG }

/**
 * Leveled front end over the SDK's `java.util.logging.Logger` named "shipeasy".
 * Every diagnostic in the SDK goes through here so the configured [LogLevel]
 * gates it before the JUL logger is ever touched. Logging must NEVER throw into
 * caller code — every emit is defensively wrapped.
 */
internal object Log {
    private val logger: Logger = Logger.getLogger("shipeasy")

    @Volatile
    private var level: LogLevel = LogLevel.WARN

    fun setLevel(newLevel: LogLevel) {
        level = newLevel
    }

    /** True iff a message at [at] should be emitted for the configured level. */
    private fun enabled(at: LogLevel): Boolean = level.ordinal >= at.ordinal

    fun error(message: String) = emit(LogLevel.ERROR, Level.SEVERE, message)

    fun warn(message: String) = emit(LogLevel.WARN, Level.WARNING, message)

    fun info(message: String) = emit(LogLevel.INFO, Level.INFO, message)

    fun debug(message: String) = emit(LogLevel.DEBUG, Level.FINE, message)

    private fun emit(at: LogLevel, julLevel: Level, message: String) {
        if (!enabled(at)) return
        // Logging must never throw into the caller.
        runCatching { logger.log(julLevel, message) }
    }
}
