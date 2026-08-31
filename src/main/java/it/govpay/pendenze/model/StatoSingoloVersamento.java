package it.govpay.pendenze.model;

/**
 * Stato di una voce di pendenza, persistito in
 * {@code singoli_versamenti.stato_singolo_versamento}. La codifica coincide con il nome
 * della costante.
 */
public enum StatoSingoloVersamento {

    NON_ESEGUITO,
    ESEGUITO
}
