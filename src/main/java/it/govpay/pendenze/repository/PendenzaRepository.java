package it.govpay.pendenze.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoPendenza;

/**
 * Repository Spring Data per {@link Pendenza}.
 */
public interface PendenzaRepository extends JpaRepository<Pendenza, Long> {

    /**
     * {@code from}+{@code where} condivisi da {@link #cercaFiltrate} (contenuto e conteggio) e
     * da {@link #cercaFiltrateSenzaConteggio} — un'unica definizione dei filtri, stesso
     * principio di {@code RptRepository#FILTRI_RICERCA_RPT}.
     *
     * <p>Usata dal wrapper v2 ({@code GET /pendenze} dello YAML v2, {@code it.govpay.pendenze
     * .v2.pendenza.PendenzaV2Controller#findPendenze} in govpay-pendenze-api) per replicare il
     * filtro di ricerca del legacy ({@code PendenzeDAO.listaPendenze}/{@code VersamentoFilter}),
     * molto piu' ampio di {@link #findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull}
     * (solo numero avviso). Ogni filtro e' opzionale (un parametro {@code null} lo disattiva):
     * la traduzione di un concetto applicativo v2 (es. l'enum {@code StatoPendenza} dello YAML
     * v2) in questi parametri e' responsabilita' del chiamante, non di questa libreria
     * v3-nativa (vedi {@code it.govpay.pendenze.v2.package-info}: "mai duplicare regole di
     * dominio").</p>
     *
     * <p><b>{@code idDebitore}</b>: match su QUALSIASI {@code SoggettoDebitore} della
     * {@code PosizioneDebitoria} (debitori "in solido"), non piu' sul placeholder legacy
     * {@code Pendenza.srcDebitoreIdentificativo} (sempre {@code "VEDERE_SOGGETTI_DEBITORI"}
     * per le pendenze scritte dal percorso v3-nativo/wrapper — vedi Javadoc di
     * {@link Pendenza}: quella colonna non e' mai sincronizzata, un confronto diretto non
     * troverebbe mai nulla per le pendenze non migrate). Sottoquery {@code EXISTS} invece di un
     * {@code join}, per non dover deduplicare righe quando una posizione ha piu' di un
     * debitore.</p>
     *
     * <p><b>{@code statoVersamento}/{@code statoVersamentoAlternativo}</b>: due valori invece
     * di una lista, solo per il caso "o l'uno o l'altro" (es. {@code ESEGUITO} OR
     * {@code ESEGUITO_SENZA_RPT}, per lo stato v2 {@code INCASSATA}) — {@code null} per non
     * usare la seconda alternativa. <b>{@code scaduta}</b>: {@code null} nessun filtro sulla
     * scadenza, altrimenti {@code true}/{@code false} filtra le pendenze
     * {@code NON_ESEGUITO} rispettivamente scadute/non scadute rispetto a
     * {@code oggiInizioGiorno} (mezzanotte del giorno corrente, stessa soglia di
     * {@code PendenzaV2Mapper#isScaduta}: scaduta solo dal giorno DOPO la data di scadenza).</p>
     *
     * <p><b>{@code escludiSpontaneiNonEseguiti}</b>: replica il filtro del legacy su
     * {@code mostraSpontaneiNonPagati} ({@code VersamentoFilter}, righe 402-410: esclude
     * {@code tipo=SPONTANEO AND stato=NON_ESEGUITO} quando {@code false}/non specificato).
     * {@code Pendenza.tipo} e' sempre {@code "DOVUTO"} per le righe scritte dal percorso
     * v3-nativo (vedi Javadoc del campo), ma {@code Pendenza} e' mappata sulla tabella legacy
     * {@code versamenti} RIUSATA in-place (M17): righe storiche con {@code tipo=SPONTANEO}
     * restano visibili attraverso questa stessa entity, il filtro non e' morto.</p>
     *
     * <p><b>Non esclude le pendenze senza {@code opzionePagamento}</b> (a differenza di
     * {@link #findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull}): il legacy
     * stesso non le esclude mai dalla ricerca (fedelta' richiesta per il wrapper v2, vedi
     * memoria {@code feedback_wrapper_v2_fedelta_legacy}).</p>
     */
    String FILTRI_RICERCA_PENDENZE = "from Pendenza p where p.idApplicazione = :idApplicazione "
            + "and (:idDominio is null or p.idDominio = :idDominio) "
            + "and (:idDebitore is null or exists (select 1 from SoggettoDebitore sd "
            + "     where sd.posizioneDebitoria = p.opzionePagamento.posizioneDebitoria "
            + "     and upper(sd.identificativo) = upper(:idDebitore))) "
            + "and (:direzione is null or p.direzione = :direzione) "
            + "and (:divisione is null or p.divisione = :divisione) "
            + "and (:dataDa is null or p.dataCreazione >= :dataDa) "
            + "and (:dataA is null or p.dataCreazione <= :dataA) "
            + "and (:statoVersamento is null or p.stato = :statoVersamento "
            + "     or p.stato = :statoVersamentoAlternativo) "
            + "and (:statoPagamento is null or p.statoPagamento = :statoPagamento) "
            + "and (:scaduta is null "
            + "     or (:scaduta = true and p.dataScadenzaAvviso is not null "
            + "         and p.dataScadenzaAvviso < :oggiInizioGiorno) "
            + "     or (:scaduta = false and (p.dataScadenzaAvviso is null "
            + "         or p.dataScadenzaAvviso >= :oggiInizioGiorno))) "
            + "and (:escludiSpontaneiNonEseguiti is null or :escludiSpontaneiNonEseguiti = false "
            + "     or not (p.tipo = 'SPONTANEO' and p.stato = 'NON_ESEGUITO'))";

    @Query(value = "select p " + FILTRI_RICERCA_PENDENZE, countQuery = "select count(p) " + FILTRI_RICERCA_PENDENZE)
    Page<Pendenza> cercaFiltrate(@Param("idApplicazione") Long idApplicazione, @Param("idDominio") Long idDominio,
            @Param("idDebitore") String idDebitore, @Param("direzione") String direzione,
            @Param("divisione") String divisione, @Param("dataDa") OffsetDateTime dataDa,
            @Param("dataA") OffsetDateTime dataA, @Param("statoVersamento") StatoPendenza statoVersamento,
            @Param("statoVersamentoAlternativo") StatoPendenza statoVersamentoAlternativo,
            @Param("statoPagamento") StatoPagamento statoPagamento, @Param("scaduta") Boolean scaduta,
            @Param("oggiInizioGiorno") OffsetDateTime oggiInizioGiorno,
            @Param("escludiSpontaneiNonEseguiti") Boolean escludiSpontaneiNonEseguiti, Pageable pageable);

    /** Come {@link #cercaFiltrate}, ma senza {@code COUNT(*)} — stesso meccanismo "limit+1" di
     *  {@link #findAllByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull}. */
    @Query("select p " + FILTRI_RICERCA_PENDENZE)
    List<Pendenza> cercaFiltrateSenzaConteggio(@Param("idApplicazione") Long idApplicazione,
            @Param("idDominio") Long idDominio, @Param("idDebitore") String idDebitore,
            @Param("direzione") String direzione, @Param("divisione") String divisione,
            @Param("dataDa") OffsetDateTime dataDa, @Param("dataA") OffsetDateTime dataA,
            @Param("statoVersamento") StatoPendenza statoVersamento,
            @Param("statoVersamentoAlternativo") StatoPendenza statoVersamentoAlternativo,
            @Param("statoPagamento") StatoPagamento statoPagamento, @Param("scaduta") Boolean scaduta,
            @Param("oggiInizioGiorno") OffsetDateTime oggiInizioGiorno,
            @Param("escludiSpontaneiNonEseguiti") Boolean escludiSpontaneiNonEseguiti, Pageable pageable);

    /**
     * IUV e NAV sono univoci **per dominio**, non globalmente (vedi la nota di classe su
     * {@link Pendenza}): la ricerca richiede sempre entrambi, mai il solo NAV.
     *
     * <p><b>Ritorna una lista, non un {@code Optional}</b>: {@code numeroAvviso} NON e'
     * garantito univoco per dominio nonostante il nome del metodo suggerisca una ricerca
     * puntuale — lo stesso NAV e' condiviso, legittimamente, da tutte le rate di un piano
     * rateale con avviso cumulativo (GovPay deriva lo stesso {@code iuv}/{@code numeroAvviso}
     * da quello fornito dal chiamante, non ne genera uno nuovo per ogni rata). Nessun vincolo
     * DB impedisce questa situazione (solo un indice non univoco su
     * {@code iuv_versamento, id_dominio}). Un {@code Optional} qui farebbe fallire la query a
     * runtime con {@code IncorrectResultSizeDataAccessException} proprio nel caso — comune,
     * non raro — di un piano rateale: il chiamante deve gestire esplicitamente 0, 1 o piu'
     * risultati.
     *
     * @param idDominio    dominio creditore
     * @param numeroAvviso NAV: identificativo dell'avviso di pagamento pagoPA
     * @return le pendenze con questo numero avviso in questo dominio (tipicamente 0 o 1, ma
     *         puo' essere piu' di 1 per le rate di un piano rateale con NAV condiviso)
     */
    List<Pendenza> findByIdDominioAndNumeroAvviso(Long idDominio, String numeroAvviso);

    /**
     * {@code idPendenza} e' univoco per applicazione, non per posizione (vincolo reale
     * {@code unique_versamenti_1} su {@code cod_versamento_ente, id_applicazione}): due
     * posizioni debitorie diverse della stessa applicazione non possono avere una pendenza
     * con lo stesso {@code idPendenza}.
     *
     * @param idApplicazione applicazione proprietaria
     * @param idPendenza     identificativo della pendenza nel gestionale
     * @return {@code true} se esiste gia' una pendenza con questa chiave
     */
    boolean existsByIdApplicazioneAndIdPendenza(Long idApplicazione, String idPendenza);

    /**
     * Ricerca per identificativo ({@code GET /pendenze/{idA2A}/{idPendenza}} dello YAML v3),
     * stessa chiave univoca di {@link #existsByIdApplicazioneAndIdPendenza} — a differenza di
     * quel metodo (usato per l'unicita' in scrittura, dove una pendenza v2/migrazione priva di
     * opzione di pagamento deve comunque contare come collisione), qui le pendenze senza
     * {@code opzionePagamento} sono escluse: senza questo filtro il
     * mapper della REST API le trova ma non sa rappresentarle — {@code IllegalStateException},
     * tradotta in 500 — mentre lo YAML v3 non ha ancora un modo di rappresentare una pendenza
     * v2/migrazione; devono restituire 404, per coerenza con
     * {@link #findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull}, che le esclude
     * gia' allo stesso modo per la ricerca per numero avviso).
     *
     * @param idApplicazione applicazione proprietaria
     * @param idPendenza     identificativo della pendenza nel gestionale
     * @return la pendenza, se esiste e ha un'opzione di pagamento
     */
    Optional<Pendenza> findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(Long idApplicazione,
            String idPendenza);

    /**
     * @param idDominio dominio creditore
     * @param iuv       Identificativo Univoco di Versamento
     * @return la pendenza, se esiste
     */
    Optional<Pendenza> findByIdDominioAndIuv(Long idDominio, String iuv);

    /**
     * Ricerca per numero avviso ({@code GET /pendenze/{idA2A}} dello YAML v3:
     * {@code numeroAvviso} e' l'unico criterio di ricerca, richiesto). A differenza di
     * {@link #findByIdDominioAndNumeroAvviso}, qui {@code idDominio} e' assente: puo'
     * restituire piu' risultati, perche' lo stesso numero avviso puo' legittimamente esistere
     * su domini diversi (M13 — unicita' per dominio, non globale). Filtrata per
     * {@code idApplicazione} (il gestionale proprietario, risolto da {@code idA2A} dal
     * chiamante — vedi Javadoc di {@link PosizioneDebitoriaRepository}), non solo per numero
     * avviso, altrimenti un gestionale potrebbe vedere pendenze di un altro indovinando un NAV.
     * {@code Pendenza.idApplicazione} coincide con quella della sua {@code Pendenza} stessa
     * ora che entrambe vivono su {@code versamenti}: filtra direttamente su
     * {@code idApplicazione}, non piu' passando per {@code opzionePagamento}.
     *
     * <p><b>Non filtra per {@code PosizioneDebitoria.dataPubblicazione}</b> — vedi Javadoc di
     * {@link PosizioneDebitoriaRepository#findByIdApplicazioneAndIdDominioAndCodDocumento} per il
     * ragionamento completo: ogni chiamante di questo metodo e' sempre l'applicazione
     * proprietaria, mai un consumatore esterno, e la spec dice che a lei la posizione deve
     * restare sempre visibile.</p>
     *
     * <p><b>{@code OpzionePagamentoIsNotNull}</b>: {@code opzionePagamento}
     * e' {@code NULL} per le righe create da v2 (vedi Javadoc di campo su {@link Pendenza}), che
     * questa ricerca deve escludere — lo schema di risposta {@code PendenzaIndex} richiede sia
     * {@code opzionePagamento} sia {@code posizioneDebitoria} (raggiunta passando per
     * {@code opzionePagamento}). Il filtro va applicato qui, non dopo aver gia' paginato: un
     * filtro post-hoc sul contenuto di una {@code Page} gia' costruita da questa query
     * lascerebbe {@code numRisultati}/{@code prossimiRisultati} calcolati sul conteggio SENZA
     * filtro, producendo pagine vuote o incomplete rispetto al totale dichiarato
     * ({@code numRisultati: 1} con {@code risultati: []}).</p>
     *
     * @param idApplicazione FK verso l'anagrafica esterna del gestionale responsabile
     * @param numeroAvviso   NAV: identificativo dell'avviso di pagamento pagoPA
     * @param pageable       paginazione e ordinamento richiesti
     * @return la pagina di pendenze che rispettano il filtro
     */
    Page<Pendenza> findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull(Long idApplicazione,
            String numeroAvviso, Pageable pageable);

    /**
     * Come {@link #findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull}, con il
     * filtro aggiuntivo opzionale {@code idDominio} previsto dallo YAML v3 (utilizzabile solo
     * insieme a {@code numeroAvviso}, mai da solo): restringe a una sola pendenza, dato il
     * vincolo di unicita' per dominio.
     *
     * @param idApplicazione FK verso l'anagrafica esterna del gestionale responsabile
     * @param numeroAvviso   NAV: identificativo dell'avviso di pagamento pagoPA
     * @param idDominio      dominio creditore
     * @param pageable       paginazione e ordinamento richiesti
     * @return la pagina di pendenze che rispettano il filtro (al piu' una)
     */
    Page<Pendenza> findByIdApplicazioneAndNumeroAvvisoAndIdDominioAndOpzionePagamentoIsNotNull(Long idApplicazione,
            String numeroAvviso, Long idDominio, Pageable pageable);

    /**
     * Come {@link #findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull}, ma senza
     * {@code COUNT(*)}: usata per la paginazione a offset con {@code total=false} (vedi Javadoc
     * di {@link PosizioneDebitoriaRepository#findAllDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo}
     * per il meccanismo — qui il {@code COUNT} sarebbe comunque economico per costruzione (M13),
     * ma la stessa modalita' e' offerta per uniformita' con {@code findPosizioniDebitorie} e con
     * lo standard di paginazione condiviso con govpay-console-api).
     */
    List<Pendenza> findAllByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull(Long idApplicazione,
            String numeroAvviso, Pageable pageable);

    /** Come sopra, con il filtro aggiuntivo opzionale {@code idDominio}. */
    List<Pendenza> findAllByIdApplicazioneAndNumeroAvvisoAndIdDominioAndOpzionePagamentoIsNotNull(Long idApplicazione,
            String numeroAvviso, Long idDominio, Pageable pageable);

    /**
     * Paginazione a cursore (keyset) per {@link #findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull},
     * ordinamento fisso {@code dataCreazione DESC, id DESC} — vedi Javadoc di
     * {@link PosizioneDebitoriaRepository#findByIdApplicazioneAndSoggettiDebitori_IdentificativoDaCursore}
     * per il meccanismo del keyset e del cursore assente alla prima pagina.
     */
    @Query("select p from Pendenza p where p.idApplicazione = :idApplicazione "
            + "and p.numeroAvviso = :numeroAvviso and p.opzionePagamento is not null "
            + "and (:cursorDataCreazione is null or p.dataCreazione < :cursorDataCreazione "
            + "or (p.dataCreazione = :cursorDataCreazione and p.id < :cursorId)) "
            + "order by p.dataCreazione desc, p.id desc")
    List<Pendenza> findByIdApplicazioneAndNumeroAvvisoDaCursore(
            @Param("idApplicazione") Long idApplicazione, @Param("numeroAvviso") String numeroAvviso,
            @Param("cursorDataCreazione") OffsetDateTime cursorDataCreazione, @Param("cursorId") Long cursorId,
            Pageable pageable);

    /** Come sopra, con il filtro aggiuntivo opzionale {@code idDominio}. */
    @Query("select p from Pendenza p where p.idApplicazione = :idApplicazione "
            + "and p.numeroAvviso = :numeroAvviso and p.idDominio = :idDominio "
            + "and p.opzionePagamento is not null "
            + "and (:cursorDataCreazione is null or p.dataCreazione < :cursorDataCreazione "
            + "or (p.dataCreazione = :cursorDataCreazione and p.id < :cursorId)) "
            + "order by p.dataCreazione desc, p.id desc")
    List<Pendenza> findByIdApplicazioneAndNumeroAvvisoAndIdDominioDaCursore(
            @Param("idApplicazione") Long idApplicazione, @Param("numeroAvviso") String numeroAvviso,
            @Param("idDominio") Long idDominio, @Param("cursorDataCreazione") OffsetDateTime cursorDataCreazione,
            @Param("cursorId") Long cursorId, Pageable pageable);
}
