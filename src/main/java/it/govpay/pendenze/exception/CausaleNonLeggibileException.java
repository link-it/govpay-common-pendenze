package it.govpay.pendenze.exception;

/**
 * Errore non recuperabile nella decodifica di {@code versamenti.causale_versamento}: formato
 * diverso da {@code "01"} ({@code CausaleSemplice} nel monolite) — gli altri due formati
 * storici ({@code CausaleSpezzoni}/{@code CausaleSpezzoniStrutturati}, prefissi {@code "02"}/
 * {@code "03"}) non sono mai prodotti da alcun path di scrittura oggi attivo nel legacy, solo
 * da dati storici (vedi {@code proposta-modello-nativo-v3.md} §28) — non decodificati qui per
 * lo stesso motivo per cui la migrazione li blocca invece di indovinare.
 */
public class CausaleNonLeggibileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CausaleNonLeggibileException(String message) {
        super(message);
    }
}
