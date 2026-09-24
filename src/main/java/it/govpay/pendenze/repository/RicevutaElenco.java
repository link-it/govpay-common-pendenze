package it.govpay.pendenze.repository;

import java.time.OffsetDateTime;

import it.govpay.pendenze.entity.Ricevuta;
import it.govpay.pendenze.model.TipoRicevuta;

/**
 * Proiezione di {@link Ricevuta} per l'elenco ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute}
 * dello YAML v3, che espone solo {@code iur}/{@code tipo}/{@code data} — il dettaglio, compreso
 * {@link Ricevuta#getContenuto() contenuto}, si recupera separatamente per singola ricevuta).
 *
 * <p>Usata da {@link RicevutaRepository#findElencoByIdPendenza} per evitare di trasferire e
 * allocare l'XML di ogni ricevuta solo per scartarlo subito dopo (bug del lead, 2026-09-24).</p>
 */
public interface RicevutaElenco {

    String getIur();

    TipoRicevuta getTipo();

    OffsetDateTime getData();
}
