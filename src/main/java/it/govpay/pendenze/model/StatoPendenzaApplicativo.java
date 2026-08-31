package it.govpay.pendenze.model;

import java.time.OffsetDateTime;

/**
 * Stato della pendenza come lo vedono le applicazioni e le API. **Non e' persistito**: e'
 * derivato da {@link StatoVersamento} e dalla data di scadenza.
 *
 * <p>La differenza rispetto allo stato in banca dati e' {@link #SCADUTA}, che non esiste
 * come valore di {@code versamenti.stato_versamento}: una pendenza non eseguita la cui
 * data di scadenza e' passata risulta scaduta pur restando {@code NON_ESEGUITO} in
 * colonna. La regola e' qui, e non nei singoli consumatori, perche' tutti la applichino
 * allo stesso modo.</p>
 */
public enum StatoPendenzaApplicativo {

    NON_PAGATA,
    PAGATA,
    PAGATA_PARZIALE,
    RICONCILIATA,
    ANNULLATA,
    SCADUTA,
    ANOMALA;

    /**
     * Deriva lo stato applicativo.
     *
     * @param stato        stato persistito, non nullo
     * @param dataScadenza data di scadenza della pendenza, eventualmente {@code null}
     * @param riferimento  istante rispetto al quale valutare la scadenza; e' un parametro
     *                     e non {@code now()} interno, per restare coerente con il
     *                     {@code Clock} configurato e per essere verificabile
     * @return lo stato applicativo corrispondente
     */
    public static StatoPendenzaApplicativo da(StatoVersamento stato,
                                              OffsetDateTime dataScadenza,
                                              OffsetDateTime riferimento) {
        StatoPendenzaApplicativo base = switch (stato) {
            case ESEGUITO, ESEGUITO_ALTRO_CANALE, ESEGUITO_SENZA_RPT -> PAGATA;
            case PARZIALMENTE_ESEGUITO -> PAGATA_PARZIALE;
            case INCASSATO -> RICONCILIATA;
            case ANNULLATO -> ANNULLATA;
            case ANOMALO -> ANOMALA;
            case NON_ESEGUITO -> NON_PAGATA;
        };

        if (base == NON_PAGATA && dataScadenza != null && dataScadenza.isBefore(riferimento)) {
            return SCADUTA;
        }
        return base;
    }
}
