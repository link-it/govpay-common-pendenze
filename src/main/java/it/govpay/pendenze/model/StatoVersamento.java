package it.govpay.pendenze.model;

/**
 * Stato di una pendenza, persistito nella colonna {@code versamenti.stato_versamento}.
 *
 * <p>La codifica in banca dati coincide con il nome della costante, quindi la mappatura
 * JPA usa {@code @Enumerated(EnumType.STRING)} senza converter. Un valore fuori dominio
 * in quella colonna e' corruzione di dati: la lettura fallisce, non viene mascherata.</p>
 */
public enum StatoVersamento {

    NON_ESEGUITO,
    ESEGUITO,
    PARZIALMENTE_ESEGUITO,
    ANNULLATO,
    ESEGUITO_ALTRO_CANALE,
    ANOMALO,
    ESEGUITO_SENZA_RPT,
    INCASSATO;

    /**
     * Indica se la pendenza attende ancora un pagamento. E' la condizione su cui
     * filtrano le query di avvisatura e la validazione degli aggiornamenti.
     *
     * @return {@code true} se lo stato e' {@link #NON_ESEGUITO}
     */
    public boolean attendePagamento() {
        return this == NON_ESEGUITO;
    }
}
