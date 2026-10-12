package it.govpay.pendenze.comando;

import it.govpay.pendenze.entity.Pendenza;
import java.util.List;
import java.time.OffsetDateTime;

/** Dati di ingresso a valore: nessuno stato, identita JPA o riferimento all'aggregato gestito. */
public record DatiPendenza(
        String idPendenza,
        Long idTipoPendenza,
        Long idTipoVersamento,
        String codificaIuvTipoPendenza,
        double importo,
        String numeroAvviso,
        String iuv,
        OffsetDateTime dataValidita,
        OffsetDateTime dataScadenzaAvviso,
        List<DatiVocePendenza> voci) {
    public DatiPendenza {
        voci = List.copyOf(voci);
    }

    /** Copia i soli campi ammessi; successive modifiche all'entita non modificano il comando. */
    public static DatiPendenza da(Pendenza e) {
        return new DatiPendenza(e.getIdPendenza(),
                e.getIdTipoPendenza(),
                e.getIdTipoVersamento(),
                e.getCodificaIuvTipoPendenza(),
                e.getImporto(),
                e.getNumeroAvviso(),
                e.getIuv(),
                e.getDataValidita(),
                e.getDataScadenzaAvviso(),
                e.getVoci().stream().map(DatiVocePendenza::da).toList());
    }
}
