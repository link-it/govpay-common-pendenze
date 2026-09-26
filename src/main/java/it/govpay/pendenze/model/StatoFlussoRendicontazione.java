package it.govpay.pendenze.model;

/**
 * Stato di un {@link it.govpay.pendenze.entity.FlussoRendicontazione} (schema
 * {@code StatoFlussoRendicontazione} dello YAML v3).
 *
 * <p>Valori allineati esattamente al legacy {@code Fr.StatoFr} (verificato,
 * 2026-09-25): {@code fr.stato} e' una colonna condivisa con la tabella legacy
 * ({@link FlussoRendicontazione}, riuso diretto) — un valore diverso da questi tre
 * farebbe fallire {@code valueOf(...)} su qualunque riga scritta da codice legacy.
 * Da riportare nello YAML v3 (nomi grammaticalmente diversi da quelli precedenti:
 * {@code ACQUISITO}&#8594;{@code ACCETTATA}, {@code RIFIUTATO}&#8594;{@code RIFIUTATA}).</p>
 */
public enum StatoFlussoRendicontazione {
    ACCETTATA,
    ANOMALA,
    RIFIUTATA
}
