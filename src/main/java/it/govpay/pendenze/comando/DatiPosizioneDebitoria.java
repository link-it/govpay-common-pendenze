package it.govpay.pendenze.comando;

import it.govpay.pendenze.entity.PosizioneDebitoria;
import java.util.List;
import java.time.LocalDate;

/** Dati di ingresso a valore: nessuno stato, identita JPA o riferimento all'aggregato gestito. */
public record DatiPosizioneDebitoria(
        String codDocumento,
        Long idApplicazione,
        Long idDominio,
        Long idUnitaOperativa,
        String descrizione,
        LocalDate dataPubblicazione,
        boolean notificaSend,
        String navNotifica,
        List<DatiSoggettoDebitore> soggettiDebitori,
        List<DatiOpzionePagamento> opzioniPagamento) {
    public DatiPosizioneDebitoria {
        soggettiDebitori = List.copyOf(soggettiDebitori);
        opzioniPagamento = List.copyOf(opzioniPagamento);
    }

    /** Copia i soli campi ammessi; successive modifiche all'entita non modificano il comando. */
    public static DatiPosizioneDebitoria da(PosizioneDebitoria e) {
        return new DatiPosizioneDebitoria(e.getCodDocumento(),
                e.getIdApplicazione(),
                e.getIdDominio(),
                e.getIdUnitaOperativa(),
                e.getDescrizione(),
                e.getDataPubblicazione(),
                e.isNotificaSend(),
                e.getNavNotifica(),
                e.getSoggettiDebitori().stream().map(DatiSoggettoDebitore::da).toList(),
                e.getOpzioniPagamento().stream().map(DatiOpzionePagamento::da).toList());
    }
}
