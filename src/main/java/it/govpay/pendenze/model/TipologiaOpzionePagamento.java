package it.govpay.pendenze.model;

/**
 * Modalita' con cui puo' essere estinta una posizione debitoria, come nello schema
 * {@code NuovaOpzionePagamento} (discriminatore {@code tipologia}) dello YAML v3.
 *
 * <p>Le 4 tipologie condividono la stessa tabella ({@code opzioni_pagamento}): non
 * differiscono per struttura ma solo per la presenza di {@code giorni} (richiesto solo da
 * {@link #SOLUZIONE_UNICA_ENTRO} e {@link #SOLUZIONE_UNICA_OLTRE}, vedi
 * {@link #richiedeGiorni()}) e per la cardinalita' ammessa dell'elenco pendenze (validata
 * in applicazione, non a livello di schema): esattamente 1 per le tre tipologie
 * "soluzione unica", almeno 2 per {@link #PIANO_RATEALE}.</p>
 */
public enum TipologiaOpzionePagamento {
    PIANO_RATEALE,
    SOLUZIONE_UNICA,
    SOLUZIONE_UNICA_ENTRO,
    SOLUZIONE_UNICA_OLTRE;

    /**
     * @return {@code true} se questa tipologia richiede il campo {@code giorni}
     */
    public boolean richiedeGiorni() {
        return this == SOLUZIONE_UNICA_ENTRO || this == SOLUZIONE_UNICA_OLTRE;
    }

    /**
     * @return il numero minimo di pendenze ammesse per questa tipologia
     */
    public int cardinalitaPendenzeMinima() {
        return this == PIANO_RATEALE ? 2 : 1;
    }

    /**
     * @return il numero massimo di pendenze ammesse per questa tipologia, vuoto se
     *         illimitato
     */
    public Integer cardinalitaPendenzeMassima() {
        return this == PIANO_RATEALE ? null : 1;
    }
}
