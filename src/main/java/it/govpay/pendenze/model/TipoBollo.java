package it.govpay.pendenze.model;

import java.util.Optional;

/**
 * Tipo di bollo telematico di una voce di pendenza, persistito in
 * {@code singoli_versamenti.tipo_bollo} con la codifica pagoPA.
 *
 * <p>Le due codifiche non coincidono: in colonna e nei messaggi pagoPA si usa
 * {@code "01"}, nei payload JSON delle API la descrizione {@code "Imposta di bollo"}.</p>
 */
public enum TipoBollo {

    IMPOSTA_BOLLO("01", "Imposta di bollo");

    private final String codificaPagoPa;
    private final String codificaJson;

    TipoBollo(String codificaPagoPa, String codificaJson) {
        this.codificaPagoPa = codificaPagoPa;
        this.codificaJson = codificaJson;
    }

    /**
     * @return il valore scritto in colonna e nei messaggi pagoPA
     */
    public String codificaPagoPa() {
        return codificaPagoPa;
    }

    /**
     * @return il valore usato nei payload JSON delle API
     */
    public String codificaJson() {
        return codificaJson;
    }

    /**
     * Risolve la costante dal valore in colonna.
     *
     * @param codifica valore letto dalla colonna, eventualmente {@code null}
     * @return la costante corrispondente, vuoto se il valore e' assente o non riconosciuto
     */
    public static Optional<TipoBollo> daCodificaPagoPa(String codifica) {
        if (codifica == null || codifica.isBlank()) {
            return Optional.empty();
        }
        for (TipoBollo tipo : values()) {
            if (tipo.codificaPagoPa.equals(codifica.trim())) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
