package it.govpay.pendenze.model;

/**
 * Tipo di un {@link it.govpay.pendenze.entity.Pagamento} (colonna legacy
 * {@code pagamenti.tipo}). Valori allineati esattamente al legacy
 * {@code Pagamento.TipoPagamento} — riuso diretto della tabella, non un'invenzione
 * per lo YAML v3.
 */
public enum TipoPagamento {
    ENTRATA,
    MBT,
    ALTRO_INTERMEDIARIO,
    ENTRATA_PA_NON_INTERMEDIATA
}
