package it.govpay.pendenze.comando;

import it.govpay.pendenze.entity.OpzionePagamento;
import java.util.List;
import java.time.LocalDate;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/** Dati di ingresso a valore: nessuno stato, identita JPA o riferimento all'aggregato gestito. */
public record DatiOpzionePagamento(
        TipologiaOpzionePagamento tipologia,
        Integer giorni,
        LocalDate dataInizioValidita,
        LocalDate dataScadenza,
        List<DatiPendenza> pendenze) {
    public DatiOpzionePagamento {
        pendenze = List.copyOf(pendenze);
    }

    /** Copia i soli campi ammessi; successive modifiche all'entita non modificano il comando. */
    public static DatiOpzionePagamento da(OpzionePagamento e) {
        return new DatiOpzionePagamento(e.getTipologia(),
                e.getGiorni(),
                e.getDataInizioValidita(),
                e.getDataScadenza(),
                e.getPendenze().stream().map(DatiPendenza::da).toList());
    }
}
