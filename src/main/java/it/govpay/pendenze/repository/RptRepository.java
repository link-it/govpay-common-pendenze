package it.govpay.pendenze.repository;

import java.time.OffsetDateTime;
import java.util.List;
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
     * <p>Filtra {@code dataMsgRicevuta is not null}: una riga
     * {@code rpt} esiste gia' dal momento in cui la richiesta di pagamento (RPT) viene
     * inviata al Nodo, ben prima che la ricevuta (RT) arrivi — senza questo filtro l'elenco
     * includerebbe anche pendenze per cui nessuna ricevuta e' ancora stata acquisita
     * ({@code xmlRt}/{@code dataMsgRicevuta} entrambi {@code null}).</p>
     *
     * @param idVersamento chiave interna della pendenza ({@code Rpt.idVersamento})
     * @param pageable     paginazione/ordinamento richiesti
     * @return la pagina di ricevute della pendenza, in forma sintetica
     */
    @Query("select r.id as id, r.iur as iur, r.versione as versione, r.dataMsgRicevuta as dataMsgRicevuta "
            + "from Rpt r where r.idVersamento = :idVersamento and r.dataMsgRicevuta is not null")
    Page<RicevutaElenco> findElencoByIdVersamento(@Param("idVersamento") Long idVersamento, Pageable pageable);

    /**
     * Come {@link #findElencoByIdVersamento}, in modalita' cursore (keyset su
     * {@code dataMsgRicevuta desc, id desc}) — stesso schema di
     * {@code PendenzaRepository#findByIdApplicazioneAndNumeroAvvisoDaCursore}: nessun filtro
     * sul cursore se {@code cursorDataMsgRicevuta} e' {@code null} (prima pagina).
     *
     * @param idVersamento          chiave interna della pendenza ({@code Rpt.idVersamento})
     * @param cursorDataMsgRicevuta valore {@code dataMsgRicevuta} dell'ultimo elemento della
     *                              pagina precedente, {@code null} per la prima pagina
     * @param cursorId              valore {@code id} dell'ultimo elemento della pagina
     *                              precedente (spareggio a parita' di {@code dataMsgRicevuta})
     * @param pageable              solo per il {@code limit} (l'ordinamento e' fisso)
     * @return al piu' {@code pageable.getPageSize()} ricevute successive al cursore
     */
    @Query("select r.id as id, r.iur as iur, r.versione as versione, r.dataMsgRicevuta as dataMsgRicevuta "
            + "from Rpt r where r.idVersamento = :idVersamento and r.dataMsgRicevuta is not null "
            + "and (:cursorDataMsgRicevuta is null or r.dataMsgRicevuta < :cursorDataMsgRicevuta "
            + "or (r.dataMsgRicevuta = :cursorDataMsgRicevuta and r.id < :cursorId)) "
            + "order by r.dataMsgRicevuta desc, r.id desc")
    List<RicevutaElenco> findElencoByIdVersamentoDaCursore(@Param("idVersamento") Long idVersamento,
            @Param("cursorDataMsgRicevuta") OffsetDateTime cursorDataMsgRicevuta, @Param("cursorId") Long cursorId,
            Pageable pageable);

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
