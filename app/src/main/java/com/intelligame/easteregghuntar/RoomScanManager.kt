package com.intelligame.easteregghuntar

import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * RoomScanManager — tiene traccia del progresso di scansione della stanza.
 *
 * La scansione è considerata completa quando:
 *  - Sono trascorsi almeno SCAN_DURATION_MS millisecondi
 *  - Sono stati rilevati almeno MIN_PLANES piani (pavimento, tavoli, ecc.)
 *
 * Il progresso è una combinazione pesata di:
 *  - 60% progresso temporale (fino a SCAN_DURATION_MS)
 *  - 40% progresso piani rilevati (fino a PLANES_TARGET)
 */
class RoomScanManager {

    companion object {
        const val SCAN_DURATION_MS = 30_000L   // 30 secondi per scansione completa
        const val MIN_PLANES       = 2          // minimo per sbloccare la modalità
        const val PLANES_TARGET    = 6          // piani per progresso 100%
    }

    private var scanStartMs = 0L
    var isScanning = false; private set

    // ─── Avvio/Stop ──────────────────────────────────────────────

    fun startScan() {
        scanStartMs = System.currentTimeMillis()
        isScanning  = true
    }

    fun stopScan() { isScanning = false }

    fun reset() { scanStartMs = 0L; isScanning = false }

    // ─── Progresso ───────────────────────────────────────────────

    /**
     * Restituisce il progresso combinato [0.0, 1.0].
     * Non dipende da Session per permettere l'aggiornamento anche fuori dal frame AR.
     */
    fun getProgress(session: Session): Float {
        if (!isScanning) return 0f
        val elapsed = System.currentTimeMillis() - scanStartMs
        val timeP   = (elapsed.toFloat() / SCAN_DURATION_MS).coerceIn(0f, 1f)
        val planes  = getTrackedPlaneCount(session)
        val planeP  = (planes.toFloat() / PLANES_TARGET).coerceIn(0f, 1f)
        return (timeP * 0.6f + planeP * 0.4f).coerceIn(0f, 1f)
    }

    fun getProgressPercent(session: Session) = (getProgress(session) * 100).toInt()

    fun getElapsedSeconds(): Long = if (!isScanning) 0L
        else (System.currentTimeMillis() - scanStartMs) / 1000L

    fun getRemainingSeconds(): Long = if (!isScanning) SCAN_DURATION_MS / 1000L
        else ((SCAN_DURATION_MS - (System.currentTimeMillis() - scanStartMs)) / 1000L).coerceAtLeast(0L)

    // ─── Completamento ───────────────────────────────────────────

    /**
     * La scansione è completa se il tempo minimo è trascorso E ci sono abbastanza piani.
     */
    fun isScanComplete(session: Session): Boolean {
        if (!isScanning) return false
        val elapsed = System.currentTimeMillis() - scanStartMs
        return elapsed >= SCAN_DURATION_MS && getTrackedPlaneCount(session) >= MIN_PLANES
    }

    /**
     * Sblocca la scansione forzata (usata dal pulsante "Salta scansione").
     */
    fun forceComplete(): Boolean {
        if (!isScanning) return false
        // Forziamo scanStartMs molto indietro nel tempo
        scanStartMs = System.currentTimeMillis() - SCAN_DURATION_MS
        return true
    }

    // ─── Stats scansione ─────────────────────────────────────────

    fun getTrackedPlaneCount(session: Session): Int =
        session.getAllTrackables(Plane::class.java).count {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
        }

    fun getSurfaceDescriptions(session: Session): String {
        var horizontal = 0; var vertical = 0; var ceiling = 0
        session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
        }.forEach {
            when (it.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING   -> horizontal++
                Plane.Type.VERTICAL                   -> vertical++
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> ceiling++
                else -> {}
            }
        }
        val parts = mutableListOf<String>()
        if (horizontal > 0) parts.add("$horizontal pavimento/tavolo")
        if (vertical   > 0) parts.add("$vertical parete")
        if (ceiling    > 0) parts.add("$ceiling soffitto")
        return if (parts.isEmpty()) "Nessuna superficie" else parts.joinToString(", ")
    }
}
