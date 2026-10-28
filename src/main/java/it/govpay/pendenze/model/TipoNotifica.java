package it.govpay.pendenze.model;

/** Evento di cui una {@link it.govpay.pendenze.entity.Notifica} informa il connettore di integrazione. */
public enum TipoNotifica {
    ATTIVAZIONE,
    RICEVUTA,
    ANNULLAMENTO,
    FALLIMENTO
}
