package it.govpay.pendenze.repository;

import java.time.LocalDate;
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
     * (parametro pubblico del servizio) e' risolto in {@code idApplicazione} dal chiamante
     * (decisione del lead, 2026-09-25: {@code idA2A} non e' piu' una colonna propria di
     * {@code PosizioneDebitoria} da quando e' mappata su {@code documenti} — coincide con
     * {@code Applicazione.codApplicazione}).
     *
     * <p>Filtra {@link PosizioneDebitoria#getDataPubblicazione()} (decisione del lead,
     * 2026-09-26): una posizione non ancora pubblicata "si comporta come se non esistesse per
     * qualsiasi ricerca" (semantica dello YAML v3) — {@code NULL} significa sempre visibile
     * (pubblicata subito, compreso il caso delle posizioni create da v2/migrazione, che non
     * hanno mai avuto questo concetto). {@code oggi} viene dal {@link java.time.Clock} del
     * chiamante, mai da una funzione DB, per restare coerente col resto della libreria.</p>
     *
     * @param idApplicazione       FK verso l'anagrafica esterna del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @param oggi                 data odierna, dal {@code Clock} del chiamante
     * @return la posizione, se esiste ed e' gia' pubblicata
     */
    @Query("select p from PosizioneDebitoria p where p.idApplicazione = :idApplicazione "
            + "and p.idPosizioneDebitoria = :idPosizioneDebitoria "
            + "and (p.dataPubblicazione is null or p.dataPubblicazione <= :oggi)")
    Optional<PosizioneDebitoria> findByIdApplicazioneAndIdPosizioneDebitoria(
            @Param("idApplicazione") Long idApplicazione,
            @Param("idPosizioneDebitoria") String idPosizioneDebitoria, @Param("oggi") LocalDate oggi);

    /**
     * Non filtra per {@link PosizioneDebitoria#getDataPubblicazione()}: la duplicazione
     * dell'identificativo va rifiutata comunque, indipendentemente da quando la posizione
     * esistente diventera' visibile — non ha senso lasciarne creare una seconda "perche' la
     * prima non e' ancora pubblicata".
     *
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
     * <p>Filtra {@link PosizioneDebitoria#getDataPubblicazione()} — vedi Javadoc di
     * {@link #findByIdApplicazioneAndIdPosizioneDebitoria}.</p>
     *
     * @param idApplicazione FK verso l'anagrafica esterna del gestionale responsabile
     * @param idDebitore     identificativo (codice fiscale/partita IVA) di un soggetto debitore
     * @param oggi           data odierna, dal {@code Clock} del chiamante
     * @param pageable       paginazione e ordinamento richiesti
     * @return la pagina di posizioni debitorie che rispettano il filtro
     */
    @Query("select distinct p from PosizioneDebitoria p join p.soggettiDebitori sd "
            + "where p.idApplicazione = :idApplicazione and sd.identificativo = :idDebitore "
            + "and (p.dataPubblicazione is null or p.dataPubblicazione <= :oggi)")
    Page<PosizioneDebitoria> findDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo(
            @Param("idApplicazione") Long idApplicazione, @Param("idDebitore") String idDebitore,
            @Param("oggi") LocalDate oggi, Pageable pageable);
}
