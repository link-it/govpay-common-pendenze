package it.govpay.pendenze.model;

/**
 * Stato persistito di una {@code Pendenza}.
 *
 * <p>Valori allineati esattamente a {@code StatoVersamento} del legacy, tutti e 8
 * (decisione del lead, 2026-09-25: sono i valori legacy quelli validi, lo YAML v3 va
 * corretto di conseguenza — non un converter di traduzione) — necessario perche'
 * {@code Pendenza} e' ora mappata sulla stessa colonna {@code versamenti.stato_versamento}:
 * un valore grammaticalmente "in italiano corretto per pendenza" (es.
 * {@code NON_ESEGUITA} invece di {@code NON_ESEGUITO}) non e' una costante valida di
 * {@code StatoVersamento} e farebbe fallire con eccezione qualunque codice legacy che la
 * leggesse con {@code StatoVersamento.valueOf(...)}, non solo una interpretazione
 * sbagliata. Tutti e 8, non solo i 6 che v3 scrive, perche' la colonna e' condivisa: questo
 * enum deve poter leggere senza eccezioni qualunque valore vi compaia, anche uno che v3
 * stesso non scrive mai.</p>
 *
 * <p><b>Follow-up sullo YAML v3</b> (fuori da questo repo, decisione del lead,
 * 2026-09-25): aggiungere {@link #ESEGUITO_SENZA_RPT} allo schema {@code StatoPendenza} —
 * non e' solo una stranezza legacy da tollerare in lettura, e' uno stato che una pendenza
 * v3 puo' raggiungere davvero (rendicontazione che conferma il pagamento senza che l'RPT
 * sia mai stata vista, stesso caso di {@code pagamenti.stato=PAGATO_SENZA_RPT} gia' noto
 * dal punto 6 su ricevute/rendicontazioni). {@link #ESEGUITO_ALTRO_CANALE} resta invece
 * solo tollerato in lettura, non ancora confermato per l'esposizione in API.</p>
 *
 * <p>Lo schema {@code StatoPendenza} dello YAML v3 elenca inoltre {@code SCADUTA}, che
 * pero' e' derivato (pendenza {@link #NON_ESEGUITO} con scadenza nel passato), non
 * persistito: continuita' con la decisione A6 del disegno precedente. Questo enum non lo
 * include: la derivazione di {@code SCADUTA} e il calcolo dello stato applicativo esposto
 * in API sono responsabilita' di un livello successivo (servizi di lettura), non
 * dell'entita'.</p>
 */
public enum StatoPendenza {
    NON_ESEGUITO,
    ESEGUITO,
    PARZIALMENTE_ESEGUITO,
    ANNULLATO,
    ESEGUITO_ALTRO_CANALE,
    ANOMALO,
    ESEGUITO_SENZA_RPT,
    INCASSATO
}
