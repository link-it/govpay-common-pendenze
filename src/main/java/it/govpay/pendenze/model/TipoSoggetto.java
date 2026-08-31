package it.govpay.pendenze.model;

import java.util.Optional;

/**
 * Natura del soggetto debitore, persistita in {@code versamenti.debitore_tipo} come
 * singolo carattere.
 */
public enum TipoSoggetto {

    PERSONA_FISICA("F"),
    PERSONA_GIURIDICA("G");

    private final String codifica;

    TipoSoggetto(String codifica) {
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
    public static Optional<TipoSoggetto> daCodifica(String codifica) {
        if (codifica == null || codifica.isBlank()) {
            return Optional.empty();
        }
        for (TipoSoggetto tipo : values()) {
            if (tipo.codifica.equals(codifica.trim())) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
