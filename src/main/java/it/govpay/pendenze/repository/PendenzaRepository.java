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

/**
 * Repository Spring Data per {@link Pendenza}.
 */
public interface PendenzaRepository extends JpaRepository<Pendenza, Long> {

    /**
     * IUV e NAV sono univoci **per dominio**, non globalmente (vedi la nota di classe su
     * {@link Pendenza}): la ricerca richiede sempre entrambi, mai il solo NAV.
     *
     * @param idDominio    dominio creditore
     * @param numeroAvviso NAV: identificativo dell'avviso di pagamento pagoPA
     * @return la pendenza, se esiste
     */
    Optional<Pendenza> findByIdDominioAndNumeroAvviso(Long idDominio, String numeroAvviso);

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
     * <p><b>Non filtra per {@code PosizioneDebitoria.dataPubblicazione}</b> (decisione del
     * lead, 2026-09-27, dopo un tentativo intermedio di filtrare poi scartato — vedi Javadoc di
     * {@link PosizioneDebitoriaRepository#findByIdApplicazioneAndIdPosizioneDebitoria} per il
     * ragionamento completo: ogni chiamante di questo metodo e' sempre l'applicazione
     * proprietaria, mai un consumatore esterno, e la spec dice che a lei la posizione deve
     * restare sempre visibile).</p>
     *
     * <p><b>{@code OpzionePagamentoIsNotNull}</b> (bug del lead, 2026-09-28): {@code opzionePagamento}
     * e' {@code NULL} per le righe create da v2 (vedi Javadoc di campo su {@link Pendenza}), che
     * questa ricerca deve escludere — lo schema di risposta {@code PendenzaIndex} richiede sia
     * {@code opzionePagamento} sia {@code posizioneDebitoria} (raggiunta passando per
     * {@code opzionePagamento}). Il filtro va applicato qui, non dopo aver gia' paginato: un
     * filtro post-hoc sul contenuto di una {@code Page} gia' costruita da questa query
     * lascerebbe {@code numRisultati}/{@code prossimiRisultati} calcolati sul conteggio SENZA
     * filtro, producendo pagine vuote o incomplete rispetto al totale dichiarato (bug segnalato
     * dal lead in revisione: {@code numRisultati: 1} con {@code risultati: []}).</p>
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
