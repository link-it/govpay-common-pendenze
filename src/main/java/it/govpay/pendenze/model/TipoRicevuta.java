package it.govpay.pendenze.model;

/**
 * Formato XML pagoPA da cui una {@link it.govpay.pendenze.entity.Ricevuta} è stata
 * convertita: {@code CT_RICEVUTA_TELEMATICA} è il formato SANP storico,
 * {@code CT_RECEIPT}/{@code CT_RECEIPT_V2} i formati più recenti del Nodo dei Pagamenti
 * (schema {@code TipoRicevuta} dello YAML v3 — la mappatura sui valori esatti della
 * property {@code tipo} in scrittura/lettura JSON è responsabilità del livello che espone
 * i bean API, non di questa libreria).
 */
public enum TipoRicevuta {
    CT_RICEVUTA_TELEMATICA,
    CT_RECEIPT,
    CT_RECEIPT_V2
}
