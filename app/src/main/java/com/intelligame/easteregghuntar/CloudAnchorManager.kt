package com.intelligame.easteregghuntar

/**
 * CloudAnchorManager — SOSTITUITO da LocalAnchorStore.
 *
 * ARCore Cloud Anchors richiedono Google Cloud API (account + fatturazione).
 * Questa app usa LocalAnchorStore: persistenza locale gratuita, illimitata,
 * senza rete, senza API key.
 *
 * Questo file esiste solo per retrocompatibilità durante la compilazione.
 * Nessun metodo fa chiamate di rete o Google Cloud.
 */
@Deprecated("Usa LocalAnchorStore — completamente gratuito e senza limiti")
class CloudAnchorManager {
    fun clear() {}
    fun hasPendingOperations() = false
    fun onFrameUpdate() {}
}
