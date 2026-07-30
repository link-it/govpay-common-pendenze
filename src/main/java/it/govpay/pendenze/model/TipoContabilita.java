package it.govpay.pendenze.model;

import java.util.Optional;

/**
 * Tipo di contabilita' di una voce di pendenza, persistito come singolo carattere in
 * {@code singoli_versamenti.tipo_contabilita}.
 *
 * <p>La stessa codifica e' usata da {@code tributi.tipo_contabilita} e
 * {@code tipi_tributo.tipo_contabilita}: quando l'anagrafica sara' disponibile in
 * {@code govpay-common} (issue {@code link-it/govpay-common#9}) questa enum va spostata
 * la', per non mantenerne due copie.</p>
 */
public enum TipoContabilita {

    CAPITOLO("0"),
    SPECIALE("1"),
    SIOPE("2"),
    SRTP_ESCLUSA_RAVV_OPEROSO("6"),
    SRTP_ESCLUSA_ALTRO_OPERATORE("7"),
    SRTP_ESCLUSA("8"),
    ALTRO("9");

    private final String codifica;

    TipoContabilita(String codifica) {
        this.codifica = codifica;
    }

    /**
     * @return il valore scritto in colonna
     */
    public String codifica() {
        return codifica;
    }

    /**
     * Risolve la costante dal valore in colonna.
     *
     * @param codifica valore letto dalla colonna, eventualmente {@code null}
     * @return la costante corrispondente, vuoto se il valore e' assente o non riconosciuto
     */
    public static Optional<TipoContabilita> daCodifica(String codifica) {
        if (codifica == null || codifica.isBlank()) {
            return Optional.empty();
        }
        for (TipoContabilita tipo : values()) {
            if (tipo.codifica.equals(codifica.trim())) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
