package it.govpay.pendenze.comando;

import java.util.List;

import it.govpay.pendenze.entity.PdDebitore;

/**
 * Un debitore della posizione con le proprie opzioni di pagamento. Dati di ingresso a valore:
 * nessuno stato, identita JPA o riferimento all'aggregato gestito.
 */
public record DatiPdDebitore(
        DatiDebitore debitore,
        boolean notificaSendPayed,
        String notificaSendNav,
        List<DatiOpzionePagamento> opzioniPagamento) {
    public DatiPdDebitore {
        opzioniPagamento = List.copyOf(opzioniPagamento);
    }

    /** Copia i soli campi ammessi; successive modifiche all'entita non modificano il comando. */
    public static DatiPdDebitore da(PdDebitore e) {
        return new DatiPdDebitore(DatiDebitore.da(e.getDebitore()),
                e.isNotificaSendPayed(),
                e.getNotificaSendNav(),
                e.getOpzioniPagamento().stream().map(DatiOpzionePagamento::da).toList());
    }
}
