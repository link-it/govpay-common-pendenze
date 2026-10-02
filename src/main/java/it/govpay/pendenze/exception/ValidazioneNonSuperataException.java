package it.govpay.pendenze.exception;

/**
 * Sollevata quando un aggregato non rispetta i vincoli semantici richiesti (cardinalità
 * delle pendenze per tipologia di opzione, coerenza fra importo della pendenza e somma
 * delle sue voci, ecc.), a valle delle validazioni strutturali già garantite dallo schema
 * (campi obbligatori, lunghezze) che restano a carico del livello che espone i bean API.
 */
public class ValidazioneNonSuperataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidazioneNonSuperataException(String messaggio) {
        super(messaggio);
    }
}
