package it.govpay.pendenze.exception;

/**
 * Sollevata quando una risorsa dell'aggregato pendenza (posizione debitoria, opzione di
 * pagamento, pendenza) non viene trovata con la chiave richiesta.
 *
 * <p>Runtime, non checked: e' compito del consumatore (es. il livello REST) decidere come
 * tradurla (tipicamente un 404), non di questa libreria imporre una gestione forzata con
 * {@code try/catch} a ogni chiamata.</p>
 */
public class RisorsaNonTrovataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RisorsaNonTrovataException(String messaggio) {
        super(messaggio);
    }
}
