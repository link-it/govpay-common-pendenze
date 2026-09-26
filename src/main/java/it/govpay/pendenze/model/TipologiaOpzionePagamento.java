package it.govpay.pendenze.model;

/**
 * Modalita' con cui puo' essere estinta una posizione debitoria, come nello schema
 * {@code NuovaOpzionePagamento} (discriminatore {@code tipologia}) dello YAML v3.
 *
 * <p>Le 4 tipologie condividono la stessa tabella ({@code opzioni_pagamento}): non
 * differiscono per struttura ma solo per la presenza di {@code giorni} (richiesto solo da
 * {@link #SOLUZIONE_UNICA_ENTRO} e {@link #SOLUZIONE_UNICA_OLTRE}, vedi
 * {@link #richiedeGiorni()}) e per la cardinalita' ammessa dell'elenco pendenze (validata
 * in applicazione, non a livello di schema).</p>
 *
 * <p><b>{@link #SOLUZIONE_UNICA} senza limite di pendenze</b> (deciso dal lead,
 * 2026-09-22, in aggiornamento allo YAML v3 che oggi impone ancora
 * {@code maxItems: 1}): un dovuto con piu' di 5 voci richiede piu' di un avviso di
 * pagamento (ogni {@code Pendenza} ammette al massimo 5 {@code VocePendenza}), quindi piu'
 * di una pendenza anche per un pagamento concettualmente "in un'unica soluzione" —
 * cambia solo il layout di stampa, non la logica: la posizione risulta pagata quando
 * tutte le sue pendenze sono pagate, esattamente come {@link #PIANO_RATEALE}. Le due
 * tipologie con termine ({@link #SOLUZIONE_UNICA_ENTRO}/{@link #SOLUZIONE_UNICA_OLTRE})
 * restano invece a esattamente 1 pendenza: confermato dal lead (2026-09-24) che non è
 * previsto che ammettano più pendenze come {@code SOLUZIONE_UNICA}.</p>
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
        return this == SOLUZIONE_UNICA_ENTRO || this == SOLUZIONE_UNICA_OLTRE ? 1 : null;
    }
}
