package it.govpay.pendenze.exception;

/**
 * Sollevata quando la risorsa che si sta creando esiste gia' con la stessa chiave logica
 * (es. {@code idA2A}+{@code idPosizioneDebitoria}).
 *
 * <p>Runtime, non checked: e' compito del consumatore (es. il livello REST) decidere come
 * tradurla (tipicamente un 409), non di questa libreria imporre una gestione forzata con
 * {@code try/catch} a ogni chiamata.</p>
 */
public class RisorsaGiaEsistenteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RisorsaGiaEsistenteException(String messaggio) {
        super(messaggio);
    }
}
