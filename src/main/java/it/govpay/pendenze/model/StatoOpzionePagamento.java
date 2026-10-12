package it.govpay.pendenze.model;

/**
 * Stato della macchina a stati di {@code OpzionePagamento}, come nello schema
 * {@code StatoOpzionePagamento} dello YAML v3.
 *
 * <p>Un'opzione nasce {@link #DISPONIBILE}. Quando arriva un pagamento su una delle sue
 * pendenze passa automaticamente ad {@link #ATTIVATA}; nello stesso momento tutte le
 * altre opzioni ancora {@code DISPONIBILE} della stessa posizione debitoria passano
 * automaticamente ad {@link #ANNULLATA} (alternative non piu' applicabili). Una
 * {@code DISPONIBILE} puo' anche essere annullata manualmente. Una {@code ATTIVATA} non
 * puo' mai essere annullata: corrisponde a un pagamento gia' eseguito.
 * {@code ANNULLATA} e' uno stato definitivo.</p>
 */
public enum StatoOpzionePagamento {
    DISPONIBILE,
    ATTIVATA,
    ANNULLATA
}
