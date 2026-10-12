package it.govpay.pendenze.model;

/**
 * Concetto legacy distinto da {@link StatoPendenza}, mappato su
 * {@code versamenti.stato_pagamento}. Valori identici a {@code StatoPagamento} legacy —
 * verificato in {@code Versamento.java} del legacy: sempre {@code NON_PAGATO} alla
 * creazione (business layer, {@code Versamento.java:293}).
 */
public enum StatoPagamento {
    PAGATO,
    INCASSATO,
    NON_PAGATO
}
