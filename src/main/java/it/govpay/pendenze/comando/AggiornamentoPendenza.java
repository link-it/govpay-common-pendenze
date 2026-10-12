package it.govpay.pendenze.comando;

import it.govpay.pendenze.entity.Pendenza;
import java.util.List;
import java.time.OffsetDateTime;

/** Dati di ingresso a valore: nessuno stato, identita JPA o riferimento all'aggregato gestito. */
public record AggiornamentoPendenza(
        String idPendenza,
        Long idTipoPendenza,
        Long idTipoVersamento,
        String codificaIuvTipoPendenza,
        double importo,
        String numeroAvviso,
        OffsetDateTime dataValidita,
        OffsetDateTime dataScadenzaAvviso,
        List<DatiVocePendenza> voci) {
    public AggiornamentoPendenza {
        voci = List.copyOf(voci);
    }

    /** Copia i soli campi ammessi; successive modifiche all'entita non modificano il comando. */
    public static AggiornamentoPendenza da(Pendenza e) {
        return new AggiornamentoPendenza(e.getIdPendenza(),
                e.getIdTipoPendenza(),
                e.getIdTipoVersamento(),
                e.getCodificaIuvTipoPendenza(),
                e.getImporto(),
                e.getNumeroAvviso(),
                e.getDataValidita(),
                e.getDataScadenzaAvviso(),
                e.getVoci().stream().map(DatiVocePendenza::da).toList());
    }
}
