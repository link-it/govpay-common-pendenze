package it.govpay.pendenze.comando;

import it.govpay.pendenze.entity.VocePendenza;
import java.util.List;
import it.govpay.pendenze.model.DettaglioContabile;

/** Dati di ingresso a valore: nessuno stato, identita JPA o riferimento all'aggregato gestito. */
public record DatiVocePendenza(
        String idVocePendenza,
        double importo,
        String descrizione,
        Long idDominio,
        Long idTributo,
        Long idIbanAccredito,
        Long idIbanAppoggio,
        String tassonomia,
        String tipoBollo,
        String hashDocumento,
        String provinciaResidenza,
        List<DettaglioContabile> dettaglioContabile) {
    public DatiVocePendenza {
        dettaglioContabile = dettaglioContabile.stream().map(d -> d instanceof DettaglioContabile.Sconosciuto s
                ? new DettaglioContabile.Sconosciuto(List.copyOf(s.entries())) : d).toList();
    }

    /** Copia i soli campi ammessi; successive modifiche all'entita non modificano il comando. */
    public static DatiVocePendenza da(VocePendenza e) {
        return new DatiVocePendenza(e.getIdVocePendenza(),
                e.getImporto(),
                e.getDescrizione(),
                e.getIdDominio(),
                e.getIdTributo(),
                e.getIdIbanAccredito(),
                e.getIdIbanAppoggio(),
                e.getTassonomia(),
                e.getTipoBollo(),
                e.getHashDocumento(),
                e.getProvinciaResidenza(),
                e.getDettaglioContabile());
    }
}
