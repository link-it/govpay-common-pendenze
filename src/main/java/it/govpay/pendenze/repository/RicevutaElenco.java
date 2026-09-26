package it.govpay.pendenze.repository;

import java.time.OffsetDateTime;

import it.govpay.pendenze.entity.Rpt;

/**
 * Proiezione per l'elenco ricevute ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute}
 * dello YAML v3, schema {@code RicevutaIndex}: {@code iur}/{@code tipo}/{@code data}) —
 * il dettaglio, compreso il contenuto XML, si recupera separatamente per singolo IUR.
 *
 * <p>Costruita direttamente da {@link Rpt} (vedi Javadoc di classe su {@link Rpt#getIur()}),
 * per evitare di trasferire e allocare l'XML della ricevuta solo per scartarlo subito dopo.</p>
 */
public interface RicevutaElenco {

    String getIur();

    /** Valore legacy grezzo di {@code VersioneRPT} — vedi Javadoc di {@link Rpt#getVersione()}. */
    String getVersione();

    OffsetDateTime getDataMsgRicevuta();
}
