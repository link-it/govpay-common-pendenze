package it.govpay.pendenze.exception;

/**
 * Sollevata quando una ricerca pensata per restituire al piu' una riga ne trova piu' di una —
 * tipicamente perche' il criterio usato (es. {@code numeroAvviso} per dominio) non e'
 * garantito univoco quanto l'identificativo del gestionale suggerisce: le rate di uno stesso
 * piano rateale con avviso cumulativo condividono legittimamente lo stesso NAV, e nessun
 * vincolo DB lo impedisce.
 *
 * <p>Runtime, non checked: e' compito del consumatore (es. il livello REST) decidere come
 * tradurla (tipicamente un 409: la richiesta in se' non e' scorretta, ma non e' possibile
 * disambiguare quale risultato restituire), non di questa libreria imporre una gestione
 * forzata con {@code try/catch} a ogni chiamata.</p>
 */
public class RicercaNonUnivocaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RicercaNonUnivocaException(String messaggio) {
        super(messaggio);
    }
}
