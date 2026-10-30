package it.govpay.pendenze.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Elenco COMPLETO delle richieste di pagamento di una pendenza, RT acquisita o no — a
     * differenza di {@link #findElencoByIdVersamento}, che richiede {@code dataMsgRicevuta}
     * non nullo. Nessun consumer v3-nativo ne ha ancora bisogno: lo usa il wrapper v2
     * ({@code it.govpay.pendenze.v2.ricevuta.RppMapper} in govpay-pendenze-api) per
     * {@code Pendenza.rpp[]}, che mostra anche una richiesta inviata al Nodo ma non ancora
     * riscontrata (stesso comportamento del legacy). Ordine per {@code dataMsgRichiesta}
     * crescente, stesso ordinamento del legacy ({@code MessaggiPagoPARptUtils}/
     * {@code PendenzeDAO}, {@code DATA_MSG_RICHIESTA ASC}).
     *
     * @param idVersamento chiave interna della pendenza ({@code Rpt.idVersamento})
     * @return tutte le richieste di pagamento della pendenza, in ordine cronologico di invio
     */
    List<Rpt> findByIdVersamentoOrderByDataMsgRichiestaAsc(Long idVersamento);

    /**
     * Esiste gia' una richiesta di pagamento di questo modello per questa pendenza? Usata
     * da {@code PaSendRTService} per il trigger dello standin
     * ({@code CtReceiptUtils.ricostruisciRPT} nel legacy) su {@code paSendRT} (V1): il
     * legacy stesso cerca {@code rptBD.getRpt(codDominio, iuv,
     * ModelloPagamento.ATTIVATO_PRESSO_PSP, null)} — filtrato per modello. Una RPT di un
     * altro modello (es. {@code IMMEDIATO}/{@code DIFFERITO}, altri canali di pagamento)
     * non blocca lo standin su questa operazione.
     *
     * <p><b>Solo per V1</b>: su {@code paSendRTV2} il legacy stesso
     * ({@code CtReceiptV2Utils.acquisisciRT}) NON filtra per modello
     * ({@code rptBD.getRpt(codDominio, iuv, null, null)}) — vedi {@link
     * #existsByIdVersamento}, usato lì.
     *
     * @param idVersamento     chiave interna della pendenza ({@code Rpt.idVersamento})
     * @param modelloPagamento valore di {@link Rpt#getModelloPagamento()} da filtrare
     * @return {@code true} se esiste almeno una RPT di questo modello per questa pendenza
     */
    boolean existsByIdVersamentoAndModelloPagamento(Long idVersamento, String modelloPagamento);

    /**
     * Come {@link #existsByIdVersamentoAndModelloPagamento}, ma senza filtro per modello —
     * usata da {@code PaSendRTService} per il trigger dello standin su {@code
     * paSendRTV2}: il legacy ({@code CtReceiptV2Utils.acquisisciRT}) qui cerca
     * {@code rptBD.getRpt(codDominio, iuv, null, null)}, senza il filtro per modello che
     * invece applica {@code paSendRT} (V1) — una RPT di QUALUNQUE modello blocca lo
     * standin su questa operazione, asimmetria presente anche nel legacy.
     *
     * @param idVersamento chiave interna della pendenza ({@code Rpt.idVersamento})
     * @return {@code true} se esiste almeno una RPT di qualunque modello per questa pendenza
     */
    boolean existsByIdVersamento(Long idVersamento);

    /**
     * Identificazione della RPT per la chiave pagoPA ({@code GET /rpp/{idDominio}/{iuv}/{ccp}}
     * e varianti {@code /rpt}/{@code /rt} dello YAML v2) — stesso vincolo fisico della tabella
     * ({@code unique_rpt_id_transazione} su {@code iuv, ccp, cod_dominio}), usato dal wrapper v2
     * ({@code it.govpay.pendenze.v2.transazione.RppV2Controller} in govpay-pendenze-api) al
     * posto della chiave interna {@code idVersamento}: a differenza di
     * {@link #findByIdVersamentoOrderByDataMsgRichiestaAsc}, qui non si parte gia' da una
     * pendenza nota, e' questa ricerca stessa a risolverla (tramite {@code idVersamento}).
     *
     * @param codDominio dominio creditore (colonna fisica {@code cod_dominio})
     * @param iuv        identificativo univoco di versamento
     * @param iur        identificativo univoco di riscossione (colonna fisica {@code ccp} —
     *                   vedi Javadoc di classe di {@link Rpt})
     * @return la RPT, se esiste
     */
    Optional<Rpt> findByCodDominioAndIuvAndIur(String codDominio, String iuv, String iur);

    /**
     * La RPT in attesa di ricevuta per questa pendenza (tipicamente {@code stato =
     * "RPT_ACCETTATA_NODO"}, valore di {@code it.govpay.pafornode.rpt.StatoRpt} in
     * govpay-pafornode-api — qui solo una stringa, vedi Javadoc di classe) — usata da
     * {@code paSendRT} (V1) per trovare la riga a cui agganciare la ricevuta in arrivo,
     * con lock ({@code SELECT ... FOR UPDATE}, stesso principio di
     * {@link #findByCodDominioAndIuvAndStatoInAndModelloPagamento}: evita due ricevute
     * concorrenti sulla stessa RPT). Filtrata anche per {@code modelloPagamento}, stesso
     * filtro del legacy ({@code rptBD.getRpt(codDominio, iuv,
     * ModelloPagamento.ATTIVATO_PRESSO_PSP, null)}) — senza questo filtro, una RPT di un
     * ALTRO modello ancora in questo stato verrebbe selezionata ed usata al posto della
     * ricostruzione "standin". Su {@code paSendRTV2} si usa invece {@link
     * #findByIdVersamentoAndStato}, senza filtro — asimmetria presente anche nel legacy,
     * vedi Javadoc di {@link #existsByIdVersamento}.
     *
     * @param idVersamento     chiave interna della pendenza ({@code Rpt.idVersamento})
     * @param stato            stato da cercare
     * @param modelloPagamento valore di {@link Rpt#getModelloPagamento()} da filtrare
     * @return la RPT in attesa di ricevuta di questo modello, se esiste
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Rpt> findByIdVersamentoAndStatoAndModelloPagamento(Long idVersamento, String stato,
            String modelloPagamento);

    /**
     * Come {@link #findByIdVersamentoAndStatoAndModelloPagamento}, ma senza filtro per
     * modello — usata da {@code paSendRTV2}, che nel legacy ({@code
     * CtReceiptV2Utils.acquisisciRT}) non filtra per modello su questa ricerca.
     *
     * @param idVersamento chiave interna della pendenza ({@code Rpt.idVersamento})
     * @param stato        stato da cercare
     * @return la RPT in attesa di ricevuta, se esiste
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Rpt> findByIdVersamentoAndStato(Long idVersamento, String stato);

    /**
     * RPT pendenti sulla stessa coppia dominio/iuv, da annullare prima di attivarne una
     * nuova ({@code Rpt#annullaRPTPendenti} nel legacy, {@code SELECT ... FOR UPDATE} compreso)
     * — usata da pafornode-api in {@code paGetPayment}/{@code paGetPaymentV2}. Il lock da solo
     * non basta a evitare due RPT attive concorrenti quando non ce n'e' ancora nessuna
     * (risultato vuoto, niente da bloccare): il chiamante deve comunque serializzare
     * sulla pendenza stessa (vedi {@link PendenzaRepository#findByIdForUpdate}).
     *
     * @param codDominio       dominio creditore (colonna fisica {@code cod_dominio})
     * @param iuv              identificativo univoco di versamento
     * @param statoIn          valori di {@link Rpt#getStato()} considerati "pendenti"
     * @param modelloPagamento valore di {@link Rpt#getModelloPagamento()} da filtrare
     * @return le RPT pendenti da annullare
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Rpt> findByCodDominioAndIuvAndStatoInAndModelloPagamento(String codDominio, String iuv,
            List<String> statoIn, String modelloPagamento);

    /**
     * {@code from}+{@code where} condivisi da {@link #cercaFiltrate} (contenuto e conteggio) e da
     * {@link #cercaFiltrateSenzaConteggio}: un'unica definizione dei filtri, invece di tre copie
     * da tenere allineate a mano.
     */
    String FILTRI_RICERCA_RPT = "from Rpt r join Pendenza p on r.idVersamento = p.id "
            + "where p.idApplicazione = :idApplicazione "
            + "and (:codDominio is null or r.codDominio = :codDominio) "
            + "and (:iuv is null or upper(r.iuv) like concat('%', upper(:iuv), '%')) "
            + "and (:iur is null or upper(r.iur) like concat('%', upper(:iur), '%')) "
            + "and (:idPendenza is null or p.idPendenza = :idPendenza) "
            + "and (:idDebitore is null or exists (select 1 from SoggettoDebitore sd "
            + "     where sd.posizioneDebitoria = p.opzionePagamento.posizioneDebitoria "
            + "     and upper(sd.identificativo) = upper(:idDebitore))) "
            + "and (:codEsitoPagamento is null or r.codEsitoPagamento = :codEsitoPagamento) "
            + "and (:dataRichiestaDa is null or r.dataMsgRichiesta >= :dataRichiestaDa) "
            + "and (:dataRichiestaA is null or r.dataMsgRichiesta <= :dataRichiestaA) "
            + "and (:dataRicevutaDa is null or r.dataMsgRicevuta >= :dataRicevutaDa) "
            + "and (:dataRicevutaA is null or r.dataMsgRicevuta <= :dataRicevutaA)";

    /**
     * Ricerca trasversale delle richieste di pagamento di un'applicazione, su tutte le sue
     * pendenze e tutti i domini — usata dal wrapper v2 ({@code GET /rpp} dello YAML v2,
     * {@code it.govpay.pendenze.v2.transazione.RppV2Controller#findRpp} in govpay-pendenze-api)
     * per replicare il filtro di ricerca del legacy ({@code RptDAO.listaRpt}/
     * {@code it.govpay.bd.viste.filters.RptFilter} sulla vista {@code v_rpt_versamenti}, join
     * di {@code rpt} e {@code versamenti}).
     *
     * <p><b>Join ad-hoc</b> ({@code join Pendenza p on r.idVersamento = p.id}): nessuna
     * relazione JPA tra le due entita' (vedi Javadoc di classe di {@link Rpt}), ma i filtri
     * {@code idApplicazione}/{@code idPendenza}/{@code idDebitore} stanno sulla pendenza e il
     * chiamante ha bisogno di entrambe per costruire la risposta — vedi {@link RptConPendenza}.
     * Inner join, come la vista legacy: una RPT senza pendenza non e' rappresentabile (e il
     * vincolo FK reale su {@code rpt.id_versamento} lo impedisce comunque).</p>
     *
     * <p>Ogni filtro e' opzionale (un parametro {@code null} lo disattiva) e rispecchia
     * l'operatore del legacy: {@code codDominio}/{@code idPendenza}/{@code codEsitoPagamento}
     * uguaglianza; {@code iuv}/{@code iur} case-insensitive "contiene" (legacy
     * {@code ilike(..., LikeMode.ANYWHERE)} su entrambi); {@code idDebitore} case-insensitive
     * contro QUALSIASI {@code SoggettoDebitore} della posizione (debitori "in solido"
     * compresi), stessa forma di {@link PendenzaRepository#cercaFiltrate}; date a estremi
     * inclusi. La traduzione dei concetti v2 in questi parametri (es. l'enum {@code EsitoRpp}) e'
     * responsabilita' del chiamante, non di questa libreria.</p>
     *
     * <p>Ordinamento dal {@code Pageable}: i nomi delle proprieta' si risolvono su {@code r}
     * ({@link Rpt}), l'alias principale della query.</p>
     */
    @Query(value = "select new it.govpay.pendenze.repository.RptConPendenza(r, p) " + FILTRI_RICERCA_RPT,
            countQuery = "select count(r) " + FILTRI_RICERCA_RPT)
    Page<RptConPendenza> cercaFiltrate(@Param("idApplicazione") Long idApplicazione,
            @Param("codDominio") String codDominio, @Param("iuv") String iuv, @Param("iur") String iur,
            @Param("idPendenza") String idPendenza, @Param("idDebitore") String idDebitore,
            @Param("codEsitoPagamento") Integer codEsitoPagamento,
            @Param("dataRichiestaDa") OffsetDateTime dataRichiestaDa,
            @Param("dataRichiestaA") OffsetDateTime dataRichiestaA,
            @Param("dataRicevutaDa") OffsetDateTime dataRicevutaDa,
            @Param("dataRicevutaA") OffsetDateTime dataRicevutaA, Pageable pageable);

    /** Come {@link #cercaFiltrate}, ma senza {@code COUNT(*)} — stesso meccanismo "limit+1" di
     *  {@link PendenzaRepository#cercaFiltrateSenzaConteggio}. */
    @Query("select new it.govpay.pendenze.repository.RptConPendenza(r, p) " + FILTRI_RICERCA_RPT)
    List<RptConPendenza> cercaFiltrateSenzaConteggio(@Param("idApplicazione") Long idApplicazione,
            @Param("codDominio") String codDominio, @Param("iuv") String iuv, @Param("iur") String iur,
            @Param("idPendenza") String idPendenza, @Param("idDebitore") String idDebitore,
            @Param("codEsitoPagamento") Integer codEsitoPagamento,
            @Param("dataRichiestaDa") OffsetDateTime dataRichiestaDa,
            @Param("dataRichiestaA") OffsetDateTime dataRichiestaA,
            @Param("dataRicevutaDa") OffsetDateTime dataRicevutaDa,
            @Param("dataRicevutaA") OffsetDateTime dataRicevutaA, Pageable pageable);
}
