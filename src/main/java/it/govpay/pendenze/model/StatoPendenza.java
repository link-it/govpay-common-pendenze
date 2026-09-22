package it.govpay.pendenze.model;

/**
 * Stato persistito di una {@code Pendenza}.
 *
 * <p>Lo schema {@code StatoPendenza} dello YAML v3 elenca 7 valori, ma {@code SCADUTA} e'
 * derivato (pendenza {@link #NON_ESEGUITA} con scadenza nel passato), non persistito:
 * continuita' con la decisione A6 del disegno precedente. Questo enum ne ha quindi solo
 * 6: la derivazione di {@code SCADUTA} e il calcolo dello stato applicativo esposto in
 * API sono responsabilita' di un livello successivo (servizi di lettura), non
 * dell'entita'.</p>
 */
public enum StatoPendenza {
    NON_ESEGUITA,
    ESEGUITA,
    ESEGUITA_PARZIALE,
    ANNULLATA,
    ANOMALA,
    INCASSATA
}
