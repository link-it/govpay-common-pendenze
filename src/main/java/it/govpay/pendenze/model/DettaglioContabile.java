package it.govpay.pendenze.model;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Dettaglio di riconciliazione contabile secondo il Dizionario dei metadata pagoPA
 * (sezione "Riconciliazione contabile", issue #877 dello YAML v3), attaccato a una
 * {@link it.govpay.pendenze.entity.VocePendenza} di tipo {@code RIFERIMENTO_ENTRATA}/
 * {@code ENTRATA} (mai {@code BOLLO}, che si classifica solo tramite {@code tassonomia}).
 *
 * <p>4 varianti scrivibili ({@link CorrispettivoDl118}/{@link IncassoTipico}/
 * {@link Civilistico}/{@link SpeseNotifica}) discriminate dal campo {@code tipo}, piu' una
 * sola variante di sola lettura ({@link Sconosciuto}, "UNKNOWN_ENTRIES"): fallback per
 * metadata generati da un intermediario/tecnologia terza, mai da GovPay in scrittura —
 * {@code ValidatorePosizioneDebitoria} la rifiuta esplicitamente in creazione.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "tipo")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DettaglioContabile.CorrispettivoDl118.class, name = "CORRISPETTIVO_DL118"),
        @JsonSubTypes.Type(value = DettaglioContabile.IncassoTipico.class, name = "INCASSO_TIPICO"),
        @JsonSubTypes.Type(value = DettaglioContabile.Civilistico.class, name = "CIVILISTICO"),
        @JsonSubTypes.Type(value = DettaglioContabile.SpeseNotifica.class, name = "SPESE_NOTIFICA"),
        @JsonSubTypes.Type(value = DettaglioContabile.Sconosciuto.class, name = "UNKNOWN_ENTRIES"),
})
public sealed interface DettaglioContabile {

    /**
     * RC_AC/RC_CU/RC_CAP/RC_ACC/RC_ART/RC_PF5/RC_IMP. Esattamente uno tra {@code capitolo},
     * {@code accertamento} e {@code pianoFinanziario5Livello} deve essere presente (vincolo
     * verificato da {@code ValidatorePosizioneDebitoria}, non esprimibile in un record).
     */
    record CorrispettivoDl118(String annoCompetenza, String codiceUfficio, String capitolo, String accertamento,
            String articolo, String pianoFinanziario5Livello, BigDecimal importo) implements DettaglioContabile {
    }

    /**
     * RC_AC/RC_CU/RC_TI/RC_EF/RC_ND/RC_IMP. {@code emissioneFattura} e {@code nrDocumento}
     * sono alternativi (al piu' uno dei due), non richiesti.
     */
    record IncassoTipico(String annoCompetenza, String codiceUfficio, String tipoIncasso, String emissioneFattura,
            String nrDocumento, BigDecimal importo) implements DettaglioContabile {
    }

    /**
     * RC_AC/RC_CU/RC_CON/RC_COM/RC_ND/RC_IMP. Esattamente uno tra {@code conto},
     * {@code commessa} e {@code nrDocumento} deve essere presente.
     */
    record Civilistico(String annoCompetenza, String codiceUfficio, String conto, String commessa,
            String nrDocumento, BigDecimal importo) implements DettaglioContabile {
    }

    /**
     * Spese di notifica SEND già incluse nell'importo della voce. Se presente su una voce
     * della posizione, {@code notificaSend} non deve essere attivo sulla posizione stessa
     * (altrimenti le spese verrebbero applicate due volte).
     */
    record SpeseNotifica(BigDecimal importo) implements DettaglioContabile {
    }

    /**
     * Sola lettura: metadata non riconosciuti, generati da un intermediario/tecnologia
     * terza. Mai accettata in scrittura da questa libreria.
     */
    record Sconosciuto(List<Voce> entries) implements DettaglioContabile {

        public record Voce(String chiave, String valore) {
        }
    }
}
