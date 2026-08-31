package it.govpay.pendenze.model;

/**
 * Stato del pagamento di una pendenza, persistito in {@code versamenti.stato_pagamento}.
 * La codifica coincide con il nome della costante.
 */
public enum StatoPagamento {

    NON_PAGATO,
    PAGATO,
    INCASSATO
}
