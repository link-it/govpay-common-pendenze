package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.govpay.pendenze.entity.Rpt;

/**
 * Repository Spring Data per {@link Rpt}.
 */
public interface RptRepository extends JpaRepository<Rpt, Long> {

    /**
     * Elenco delle ricevute di una pendenza ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute}
     * dello YAML v3, schema {@code RicevutaIndex}: {@code iur}/{@code tipo}/{@code data}).
     * Proietta solo {@code iur}/{@code versione}/{@code dataMsgRicevuta} (vedi
     * {@link RicevutaElenco}), senza trasferire ne' allocare {@link Rpt#getXmlRt()}, non
     * richiesto dall'elenco.
     *
     * <p>Filtra {@code dataMsgRicevuta is not null} (bug del lead, 2026-09-26): una riga
     * {@code rpt} esiste gia' dal momento in cui la richiesta di pagamento (RPT) viene
     * inviata al Nodo, ben prima che la ricevuta (RT) arrivi — senza questo filtro l'elenco
     * includerebbe anche pendenze per cui nessuna ricevuta e' ancora stata acquisita
     * ({@code xmlRt}/{@code dataMsgRicevuta} entrambi {@code null}).</p>
     *
     * @param idVersamento chiave interna della pendenza ({@code Rpt.idVersamento})
     * @param pageable     paginazione/ordinamento richiesti
     * @return la pagina di ricevute della pendenza, in forma sintetica
     */
    @Query("select r.iur as iur, r.versione as versione, r.dataMsgRicevuta as dataMsgRicevuta "
            + "from Rpt r where r.idVersamento = :idVersamento and r.dataMsgRicevuta is not null")
    Page<RicevutaElenco> findElencoByIdVersamento(@Param("idVersamento") Long idVersamento, Pageable pageable);

    /**
     * Dettaglio di una ricevuta ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}}
     * dello YAML v3). Filtra {@code dataMsgRicevuta is not null} — vedi Javadoc di
     * {@link #findElencoByIdVersamento}: senza ricevuta acquisita non c'e' nulla da
     * restituire, la richiesta deve risultare "non trovata", non un dettaglio vuoto.
     *
     * @param idVersamento chiave interna della pendenza ({@code Rpt.idVersamento})
     * @param iur          identificativo univoco di riscossione (colonna fisica {@code ccp}
     *                     — vedi Javadoc di classe di {@link Rpt})
     * @return la ricevuta, se esiste ed e' stata acquisita
     */
    Optional<Rpt> findByIdVersamentoAndIurAndDataMsgRicevutaIsNotNull(Long idVersamento, String iur);
}
