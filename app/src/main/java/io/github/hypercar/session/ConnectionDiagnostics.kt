package io.github.hypercar.session

/** Bounded evidence journal. Tokens reject late events; a missing milestone is never inferred as success. */
class ConnectionDiagnostics(private val clockMillis: () -> Long) {
    enum class Stage {
        REQUESTED, DEPENDENCIES_CHECKED, BLUETOOTH_LISTENING, IAP2_STARTED,
        IAP2_PARAMETERS, WIFI_BOUND, CTRL_REGISTERED, CTRL_INVITED, AIRPLAY_START,
        RECORD, UI_STARTED,
    }
    private data class Attempt(
        val id: Long, val source: String, val profile: String, val started: Long,
        val reached: LinkedHashMap<Stage, Long> = linkedMapOf(),
        val notes: LinkedHashMap<Stage, String> = linkedMapOf(),
        var outcome: String = "WAITING", var reason: String = "", var ended: Long? = null,
    )
    private var serial = 0L
    private val history = ArrayDeque<Attempt>()

    @Synchronized fun begin(source: String, profile: String): Long {
        history.lastOrNull()?.takeIf { it.ended == null }?.let {
            it.outcome = "SUPERSEDED"; it.ended = clockMillis()
        }
        val attempt = Attempt(++serial, clean(source), clean(profile), clockMillis())
        attempt.reached[Stage.REQUESTED] = 0L
        history.addLast(attempt)
        while (history.size > 8) history.removeFirst()
        return attempt.id
    }

    @Synchronized fun mark(id: Long, stage: Stage, detail: String = "") {
        val a = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return
        a.reached.putIfAbsent(stage, (clockMillis() - a.started).coerceAtLeast(0))
        if (stage == Stage.RECORD) a.outcome = "STREAMING"
        if (detail.isNotBlank()) a.notes[stage] = clean(detail)
    }

    @Synchronized fun finish(id: Long, outcome: String, reason: String) {
        val a = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return
        a.outcome = clean(outcome); a.reason = clean(reason); a.ended = clockMillis()
    }

    @Synchronized fun report(): List<String> = history.map { a ->
        val elapsed = ((a.ended ?: clockMillis()) - a.started).coerceAtLeast(0)
        val milestones = a.reached.entries.joinToString(",") { (stage, ms) -> "$stage@${ms}ms" }
        val notes = a.notes.entries.joinToString("; ") { (stage, note) -> "$stage:$note" }
        "ATTEMPT#${a.id} source=${a.source} profile=${a.profile} outcome=${a.outcome} elapsed=${elapsed}ms " +
            "record=${Stage.RECORD in a.reached} invite=${Stage.CTRL_INVITED in a.reached} " +
            "last=${a.reached.keys.last()} milestones=[$milestones] notes=[$notes] reason=${a.reason}"
    }

    private fun clean(value: String): String = value.replace(Regex("[\\r\\n\\t]"), " ").take(300)
}
