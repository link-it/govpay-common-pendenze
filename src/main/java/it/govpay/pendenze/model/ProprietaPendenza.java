package it.govpay.pendenze.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Proprieta' accessorie di una pendenza, persistite come JSON nella colonna
 * {@code versamenti.proprieta}.
 *
 * <p><b>I nomi delle proprieta' JSON sono vincolati dai dati esistenti</b> e non vanno
 * "corretti": in particolare l'ultimo campo e' {@code dataScandenzaAvviso} — con il
 * refuso — perche' cosi' e' scritto nei record gia' persistiti dalla versione 3.x.
 * Rinominarlo renderebbe illeggibile lo storico. La mappatura dei nomi sta in
 * {@code ProprietaPendenzaCodec}.</p>
 *
 * @param linguaSecondaria                          lingua secondaria dell'avviso
 * @param descrizioneImporto                        voci con cui dettagliare l'importo sull'avviso
 * @param lineaTestoRicevuta1                       prima linea libera sulla ricevuta
 * @param lineaTestoRicevuta2                       seconda linea libera sulla ricevuta
 * @param linguaSecondariaCausale                   causale nella lingua secondaria
 * @param informativaImportoAvviso                  informativa sull'importo, sull'avviso
 * @param linguaSecondariaInformativaImportoAvviso  la stessa informativa nella lingua secondaria
 * @param dataScandenzaAvviso                       data di scadenza da stampare sull'avviso
 *                                                  (nome con refuso, vincolato dai dati)
 */
public record ProprietaPendenza(
        LinguaSecondaria linguaSecondaria,
        List<VoceDescrizioneImporto> descrizioneImporto,
        String lineaTestoRicevuta1,
        String lineaTestoRicevuta2,
        String linguaSecondariaCausale,
        String informativaImportoAvviso,
        String linguaSecondariaInformativaImportoAvviso,
        OffsetDateTime dataScandenzaAvviso) {

    public ProprietaPendenza {
        descrizioneImporto = descrizioneImporto == null ? null : List.copyOf(descrizioneImporto);
    }

    /**
     * Voce con cui dettagliare l'importo dell'avviso.
     *
     * @param voce    descrizione della voce
     * @param importo importo della voce
     */
    public record VoceDescrizioneImporto(String voce, BigDecimal importo) {
    }
}
