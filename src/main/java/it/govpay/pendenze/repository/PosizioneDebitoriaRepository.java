package it.govpay.pendenze.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.govpay.pendenze.entity.PosizioneDebitoria;

/**
 * Repository Spring Data per {@link PosizioneDebitoria}.
 */
public interface PosizioneDebitoriaRepository extends JpaRepository<PosizioneDebitoria, Long> {

    /**
     * Cerca per la chiave logica (applicazione + identificativo della posizione). {@code idA2A}
     * (parametro pubblico del servizio) e' risolto in {@code idApplicazione} dal chiamante:
     * {@code idA2A} non e' piu' una colonna propria di
     * {@code PosizioneDebitoria} da quando e' mappata su {@code documenti} — coincide con
     * {@code Applicazione.codApplicazione}.
     *
     * <p><b>Non filtra per {@link PosizioneDebitoria#getDataPubblicazione()}</b> (non riaprire
     * senza rileggere §24/§27 di {@code proposta-modello-nativo-v3.md}): lo YAML v3 dice che una
     * posizione non ancora pubblicata "si comporta come se non esistesse per qualsiasi
     * ricerca/pagamento <i>esterno</i>... resta invece sempre visibile e gestibile per
     * l'applicazione che l'ha creata". Ogni chiamante di questo metodo (tramite
     * {@code PosizioneDebitoriaService}) e' sempre l'applicazione proprietaria, identificata da
     * {@code idApplicazione}/{@code idA2A} — mai un consumatore realmente esterno (es. la
     * verifica pagamento verso il Nodo dei Pagamenti, che non passa da questa API e non esiste
     * ancora come servizio). Filtrare qui avrebbe nascosto la posizione proprio a chi la spec
     * dice debba vederla sempre.</p>
     *
     * @param idApplicazione       FK verso l'anagrafica esterna del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @return la posizione, se esiste
     */
    Optional<PosizioneDebitoria> findByIdApplicazioneAndIdPosizioneDebitoria(Long idApplicazione,
            String idPosizioneDebitoria);

    /**
     * @param idApplicazione       FK verso l'anagrafica esterna del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @return {@code true} se esiste gia' una posizione con questa chiave logica
     */
    boolean existsByIdApplicazioneAndIdPosizioneDebitoria(Long idApplicazione, String idPosizioneDebitoria);

    /**
     * Ricerca per debitore ({@code GET /posizioni-debitorie/{idA2A}} dello YAML v3:
     * {@code idDebitore} e' l'unico criterio di ricerca ammesso, oltre a {@code idA2A}).
     * Trova la posizione se l'identificativo corrisponde a <b>qualunque</b> soggetto in
     * {@code soggettiDebitori}, non solo al primo (semantica esplicita dello YAML per i
     * debitori in solido). {@code distinct} evita duplicati se piu' soggetti della stessa
     * posizione avessero — per un dato scorretto — lo stesso identificativo.
     *
     * <p>Non filtra per {@link PosizioneDebitoria#getDataPubblicazione()} — vedi Javadoc di
     * {@link #findByIdApplicazioneAndIdPosizioneDebitoria}.</p>
     *
     * @param idApplicazione FK verso l'anagrafica esterna del gestionale responsabile
     * @param idDebitore     identificativo (codice fiscale/partita IVA) di un soggetto debitore
     * @param pageable       paginazione e ordinamento richiesti
     * @return la pagina di posizioni debitorie che rispettano il filtro
     */
    Page<PosizioneDebitoria> findDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo(Long idApplicazione,
            String idDebitore, Pageable pageable);

    /**
     * Come {@link #findDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo}, ma senza
     * {@code COUNT(*)}: usata per la paginazione a offset con {@code total=false}. Un
     * {@code List} (invece di {@code Page}) con {@link Pageable} non fa eseguire a Spring Data
     * la query di conteggio — il chiamante passa {@code limit + 1} come dimensione di pagina
     * per determinare l'esistenza di una pagina successiva (vedi
     * {@link it.govpay.pendenze.criteri.PaginaSenzaConteggio}).
     */
    List<PosizioneDebitoria> findAllDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo(Long idApplicazione,
            String idDebitore, Pageable pageable);

    /**
     * Paginazione a cursore (keyset), ordinamento fisso {@code dataCreazione DESC, id DESC}
     * (non influenzato da {@code sort}, coerente con lo YAML v3). Il predicato keyset e'
     * omesso (tramite i parametri {@code null}) alla prima pagina, dove il chiamante non ha
     * ancora un cursore. Il chiamante passa {@code limit + 1} come {@code Pageable} per
     * determinare l'esistenza di una pagina successiva.
     *
     * @param cursorDataCreazione {@code dataCreazione} dell'ultimo elemento della pagina
     *                            precedente, o {@code null} alla prima pagina
     * @param cursorId            {@code id} dell'ultimo elemento della pagina precedente, o
     *                            {@code null} alla prima pagina
     */
    @Query("select distinct p from PosizioneDebitoria p join p.soggettiDebitori s "
            + "where p.idApplicazione = :idApplicazione and s.identificativo = :idDebitore "
            + "and (:cursorDataCreazione is null or p.dataCreazione < :cursorDataCreazione "
            + "or (p.dataCreazione = :cursorDataCreazione and p.id < :cursorId)) "
            + "order by p.dataCreazione desc, p.id desc")
    List<PosizioneDebitoria> findByIdApplicazioneAndSoggettiDebitori_IdentificativoDaCursore(
            @Param("idApplicazione") Long idApplicazione, @Param("idDebitore") String idDebitore,
            @Param("cursorDataCreazione") OffsetDateTime cursorDataCreazione, @Param("cursorId") Long cursorId,
            Pageable pageable);
}
