package it.govpay.pendenze.model;

/**
 * Stato di un {@link it.govpay.pendenze.entity.Pagamento} (colonna legacy
 * {@code pagamenti.stato}, nullable in produzione). Valori allineati esattamente al
 * legacy {@code Pagamento.Stato} (verificato, 2026-09-25).
 *
 * <p>Nome distinto da {@link StatoPagamento} apposta: quest'ultimo e' un concetto
 * diverso, {@code versamenti.stato_pagamento} di {@link it.govpay.pendenze.entity.Pendenza}
 * ({@code PAGATO}/{@code INCASSATO}/{@code NON_PAGATO}) — stessa tabella legacy
 * ({@code pagamenti}), ma nomi di valori quasi identici e semantica differente, da non
 * confondere.</p>
 */
public enum StatoPagamentoRicevuta {
    PAGATO,
    INCASSATO,
    PAGATO_SENZA_RPT
}
