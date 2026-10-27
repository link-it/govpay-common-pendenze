package it.govpay.pendenze.model;

/**
 * Modalita' con cui puo' essere estinta una posizione debitoria, come nello schema
 * {@code NuovaOpzionePagamento} (discriminatore {@code tipologia}) dello YAML v3.
 *
 * <p>Le 6 tipologie condividono la stessa tabella ({@code opzioni_pagamento}): non
 * differiscono per struttura ma solo per la presenza di {@code giorni} (vedi
 * {@link #richiedeGiorni()}) e per la cardinalita' massima ammessa dell'elenco pendenze
 * (vedi {@link #cardinalitaPendenzeMassima()}) — la cardinalita' minima e' invece sempre 1
 * in questa libreria, vedi {@link #cardinalitaPendenzeMinima()}.</p>
 *
 * <p>{@link #SOLUZIONE_UNICA} e {@link #PIANO_RATEALE} non hanno limite massimo di
 * pendenze. Le altre quattro ({@link #SOLUZIONE_UNICA_ENTRO}/{@link #SOLUZIONE_UNICA_OLTRE}/
 * {@link #SOLUZIONE_UNICA_RIDOTTO}/{@link #SOLUZIONE_UNICA_SCONTATO}) restano sempre a
 * esattamente 1 pendenza.</p>
 */
public enum TipologiaOpzionePagamento {
    PIANO_RATEALE,
    SOLUZIONE_UNICA,
    SOLUZIONE_UNICA_ENTRO,
    SOLUZIONE_UNICA_OLTRE,
    SOLUZIONE_UNICA_RIDOTTO,
    SOLUZIONE_UNICA_SCONTATO;

    /**
     * @return {@code true} se questa tipologia richiede il campo {@code giorni}
     */
    public boolean richiedeGiorni() {
        return this == SOLUZIONE_UNICA_ENTRO || this == SOLUZIONE_UNICA_OLTRE;
    }

    /**
     * @return il numero minimo di pendenze ammesse per questa tipologia — sempre 1. Per
     *         {@link #PIANO_RATEALE} il vincolo "servono almeno due rate, altrimenti si usi
     *         {@link #SOLUZIONE_UNICA}" resta valido ma vive nel contratto REST v3
     *         ({@code NuovaOpzionePagamentoPianoRateale.pendenze}, {@code minItems: 2},
     *         applicato da Spring tramite {@code @Valid} sul controller prima che la
     *         richiesta arrivi a questa libreria): qui non e' imposto perche' questa libreria
     *         e' usata anche da chiamanti diretti in Java che costruiscono l'aggregato un
     *         passo alla volta (es. il wrapper verso i client v2: una rata per chiamata, la
     *         prima persistita subito come {@code PIANO_RATEALE} di 1 sola pendenza, che
     *         diventa 2+ solo con la seconda chiamata).
     */
    public int cardinalitaPendenzeMinima() {
        return 1;
    }

    /**
     * @return il numero massimo di pendenze ammesse per questa tipologia, vuoto se
     *         illimitato
     */
    public Integer cardinalitaPendenzeMassima() {
        return this == SOLUZIONE_UNICA_ENTRO || this == SOLUZIONE_UNICA_OLTRE
                || this == SOLUZIONE_UNICA_RIDOTTO || this == SOLUZIONE_UNICA_SCONTATO ? 1 : null;
    }
}
