package it.govpay.pendenze.criteri;

import java.time.OffsetDateTime;

/**
 * Filtri di basso livello per {@link it.govpay.pendenze.repository.RptRepository#cercaFiltrate}/
 * {@link it.govpay.pendenze.repository.RptRepository#cercaFiltrateSenzaConteggio} — ogni campo
 * {@code null} disattiva il filtro corrispondente. Stesso principio di {@link FiltriPendenze}:
 * nessun campo e' un concetto v2/v3 specifico, la traduzione di un filtro applicativo (es.
 * l'enum {@code EsitoRpp} dello YAML v2 nel codice numerico di {@code codEsitoPagamento}) e'
 * responsabilita' del chiamante.
 *
 * @param codDominio          dominio creditore, confrontato direttamente con
 *                            {@code Rpt.codDominio} (colonna fisica {@code cod_dominio}, gia' il
 *                            codice e non una FK numerica), o {@code null}
 * @param iuv                 frammento di {@code Rpt.iuv}, confrontato case-insensitive "contiene",
 *                            o {@code null}
 * @param iur                 frammento di {@code Rpt.iur} (colonna fisica {@code ccp}),
 *                            confrontato case-insensitive "contiene", o {@code null}
 * @param idPendenza          {@code Pendenza.idPendenza} esatto, o {@code null}
 * @param idDebitore          identificativo del debitore (confrontato case-insensitive contro
 *                            QUALSIASI {@code SoggettoDebitore} della posizione, debitori "in
 *                            solido" compresi), o {@code null}
 * @param codEsitoPagamento   valore esatto di {@code Rpt.codEsitoPagamento}, o {@code null}
 * @param dataRichiestaDa     inizio (incluso) della finestra su {@code Rpt.dataMsgRichiesta}, o
 *                            {@code null}
 * @param dataRichiestaA      fine (inclusa) della finestra su {@code Rpt.dataMsgRichiesta}, o
 *                            {@code null}
 * @param dataRicevutaDa      inizio (incluso) della finestra su {@code Rpt.dataMsgRicevuta}, o
 *                            {@code null}
 * @param dataRicevutaA       fine (inclusa) della finestra su {@code Rpt.dataMsgRicevuta}, o
 *                            {@code null}
 */
public record FiltriRpt(String codDominio, String iuv, String iur, String idPendenza, String idDebitore,
        Integer codEsitoPagamento, OffsetDateTime dataRichiestaDa, OffsetDateTime dataRichiestaA,
        OffsetDateTime dataRicevutaDa, OffsetDateTime dataRicevutaA) {

    /** Nessun filtro oltre a {@code idApplicazione} (che il chiamante fornisce separatamente). */
    public static FiltriRpt nessuno() {
        return new FiltriRpt(null, null, null, null, null, null, null, null, null, null);
    }
}
