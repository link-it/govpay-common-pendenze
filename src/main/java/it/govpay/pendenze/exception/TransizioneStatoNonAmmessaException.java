package it.govpay.pendenze.exception;

/**
 * Sollevata quando si tenta una transizione di stato non ammessa dalla macchina a stati
 * di {@code OpzionePagamento} (es. annullare un'opzione gia' {@code ATTIVATA}).
 */
public class TransizioneStatoNonAmmessaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransizioneStatoNonAmmessaException(String messaggio) {
        super(messaggio);
    }
}
