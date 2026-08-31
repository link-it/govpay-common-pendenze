package it.govpay.pendenze.model;

import java.util.Optional;

/**
 * Lingua secondaria richiesta per l'avviso di pagamento, dentro le proprieta' della
 * pendenza. {@link #FALSE} indica "nessuna lingua secondaria" e non e' un errore: e' il
 * valore usato dai payload legacy.
 */
public enum LinguaSecondaria {

    FALSE("false"),
    DE("de"),
    EN("en"),
    FR("fr"),
    SL("sl");

    private final String codifica;

    LinguaSecondaria(String codifica) {
        this.codifica = codifica;
    }

    /**
     * @return il valore usato nel JSON persistito
     */
    public String codifica() {
        return codifica;
    }

    /**
     * Risolve la costante dal valore nel JSON.
     *
     * @param codifica valore letto dal JSON, eventualmente {@code null}
     * @return la costante corrispondente, vuoto se assente o non riconosciuta
     */
    public static Optional<LinguaSecondaria> daCodifica(String codifica) {
        if (codifica == null || codifica.isBlank()) {
            return Optional.empty();
        }
        for (LinguaSecondaria lingua : values()) {
            if (lingua.codifica.equalsIgnoreCase(codifica.trim())) {
                return Optional.of(lingua);
            }
        }
        return Optional.empty();
    }
}
