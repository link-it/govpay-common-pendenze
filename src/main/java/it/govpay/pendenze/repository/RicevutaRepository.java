package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.govpay.pendenze.entity.Ricevuta;

/**
 * Repository Spring Data per {@link Ricevuta}.
 */
public interface RicevutaRepository extends JpaRepository<Ricevuta, Long> {

    /**
     * Elenco delle ricevute di una pendenza ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute}
     * dello YAML v3): proietta solo {@code iur}/{@code tipo}/{@code data} (vedi
     * {@link RicevutaElenco}), senza trasferire né allocare {@link Ricevuta#getContenuto()},
     * non richiesto dall'elenco (bug del lead, 2026-09-24).
     *
     * @param idPendenza chiave interna della pendenza
     * @param pageable   paginazione/ordinamento richiesti
     * @return la pagina di ricevute della pendenza, in forma sintetica
     */
    @Query("select r.iur as iur, r.tipo as tipo, r.data as data from Ricevuta r where r.idPendenza = :idPendenza")
    Page<RicevutaElenco> findElencoByIdPendenza(@Param("idPendenza") Long idPendenza, Pageable pageable);

    /**
     * Dettaglio di una ricevuta ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}}
     * dello YAML v3).
     *
     * @param idPendenza chiave interna della pendenza
     * @param iur        identificativo univoco di riscossione
     * @return la ricevuta, se esiste
     */
    Optional<Ricevuta> findByIdPendenzaAndIur(Long idPendenza, String iur);
}
