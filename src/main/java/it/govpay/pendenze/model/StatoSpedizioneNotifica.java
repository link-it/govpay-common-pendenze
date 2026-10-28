package it.govpay.pendenze.model;

/** Stato di spedizione di una {@link it.govpay.pendenze.entity.Notifica} verso il connettore di integrazione. */
public enum StatoSpedizioneNotifica {
    DA_SPEDIRE,
    SPEDITO,
    ANNULLATA
}
