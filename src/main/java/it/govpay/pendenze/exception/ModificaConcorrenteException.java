package it.govpay.pendenze.exception;

/**
 * Sollevata quando un'operazione di scrittura su una {@code PosizioneDebitoria} fallisce
 * perche' l'aggregato e' stato modificato concorrentemente da un'altra operazione (lock
 * ottimistico, {@code PosizioneDebitoria#getVersione()}) — es. l'aggiunta di una nuova
 * opzione di pagamento in corsa con l'attivazione di un'altra opzione della stessa
 * posizione.
 *
 * <p>Runtime, non checked: come {@link RisorsaGiaEsistenteException}, e' compito del
 * consumatore (es. il livello REST) decidere come tradurla (tipicamente un 409, con
 * l'indicazione di riprovare la richiesta).</p>
 */
public class ModificaConcorrenteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModificaConcorrenteException(String messaggio) {
        super(messaggio);
    }
}
