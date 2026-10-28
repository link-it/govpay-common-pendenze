package it.govpay.pendenze.comando;

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.service.CausaleVersamentoCodec;
import java.util.List;
import java.time.OffsetDateTime;

/**
 * Dati di ingresso a valore: nessuno stato, identita JPA o riferimento all'aggregato gestito.
 *
 * <p>{@code causale}, come in {@link DatiPendenza}, e' in chiaro — la codifica verso
 * {@code causale_versamento} e' compito di chi applica il comando. {@code null} sostituisce
 * (azzera) la causale esistente, stessa semantica di sostituzione piena degli altri campi di
 * questo comando.</p>
 */
public record AggiornamentoPendenza(
        String idPendenza,
        Long idTipoPendenza,
        Long idTipoVersamento,
        String codificaIuvTipoPendenza,
        double importo,
        String numeroAvviso,
        OffsetDateTime dataValidita,
        OffsetDateTime dataScadenzaAvviso,
        List<DatiVocePendenza> voci,
        String causale) {
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
                e.getVoci().stream().map(DatiVocePendenza::da).toList(),
                CausaleVersamentoCodec.decode(e.getCausaleVersamento()));
    }
}
