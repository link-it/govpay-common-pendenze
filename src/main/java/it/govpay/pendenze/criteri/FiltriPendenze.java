package it.govpay.pendenze.criteri;

import java.time.OffsetDateTime;

import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoPendenza;

/**
 * Filtri di basso livello per {@link it.govpay.pendenze.repository.PendenzaRepository#cercaFiltrate}/
 * {@link it.govpay.pendenze.repository.PendenzaRepository#cercaFiltrateSenzaConteggio} — ogni
 * campo {@code null} disattiva il filtro corrispondente. Nessun campo e' un concetto v2/v3
 * specifico: la traduzione di un filtro applicativo (es. l'enum {@code StatoPendenza} dello
 * YAML v2) in questi valori e' responsabilita' del chiamante.
 *
 * @param idDominio                   dominio creditore, o {@code null}
 * @param idDebitore                  identificativo del debitore (confrontato case-insensitive
 *                                    contro QUALSIASI {@code SoggettoDebitore} della posizione,
 *                                    debitori "in solido" compresi), o {@code null}
 * @param direzione                   direzione interna all'ente creditore, o {@code null}
 * @param divisione                   divisione interna all'ente creditore, o {@code null}
 * @param dataDa                      inizio (incluso) della finestra su {@code dataCreazione},
 *                                     o {@code null}
 * @param dataA                       fine (inclusa) della finestra su {@code dataCreazione}, o
 *                                     {@code null}
 * @param statoVersamento             valore richiesto di {@code Pendenza.stato}, o {@code null}
 * @param statoVersamentoAlternativo  secondo valore ammesso in OR con {@code statoVersamento}
 *                                    (es. {@code ESEGUITO}/{@code ESEGUITO_SENZA_RPT}), o
 *                                    {@code null} per non averne uno
 * @param statoPagamento              valore richiesto di {@code Pendenza.statoPagamento}, o
 *                                    {@code null}
 * @param scaduta                     {@code null} nessun filtro sulla scadenza; altrimenti
 *                                     {@code true}/{@code false} per le pendenze
 *                                     {@code NON_ESEGUITO} scadute/non scadute (soglia: il
 *                                     giorno DOPO {@code dataScadenzaAvviso})
 * @param escludiSpontaneiNonEseguiti {@code true} esclude {@code tipo=SPONTANEO AND
 *                                     stato=NON_ESEGUITO} (replica {@code mostraSpontaneiNonPagati}
 *                                     del legacy quando {@code false}); {@code null}/{@code false}
 *                                     nessun filtro
 */
public record FiltriPendenze(Long idDominio, String idDebitore, String direzione, String divisione,
        OffsetDateTime dataDa, OffsetDateTime dataA, StatoPendenza statoVersamento,
        StatoPendenza statoVersamentoAlternativo, StatoPagamento statoPagamento, Boolean scaduta,
        Boolean escludiSpontaneiNonEseguiti) {

    /** Nessun filtro oltre a {@code idApplicazione} (che il chiamante fornisce separatamente). */
    public static FiltriPendenze nessuno() {
        return new FiltriPendenze(null, null, null, null, null, null, null, null, null, null, null);
    }
}
