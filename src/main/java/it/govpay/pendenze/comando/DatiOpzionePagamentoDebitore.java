package it.govpay.pendenze.comando;

import java.util.Objects;

/**
 * Opzione di pagamento destinata a un debitore della posizione, indicato dal suo
 * identificativo fiscale (univoco nella posizione).
 */
public record DatiOpzionePagamentoDebitore(
        String identificativoDebitore,
        DatiOpzionePagamento opzionePagamento) {
    public DatiOpzionePagamentoDebitore {
        Objects.requireNonNull(identificativoDebitore);
        Objects.requireNonNull(opzionePagamento);
    }
}
