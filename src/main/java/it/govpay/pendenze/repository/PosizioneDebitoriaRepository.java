package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * debitori in solido). {@code Distinct} evita duplicati se piu' soggetti della stessa
     * posizione avessero — per un dato scorretto — lo stesso identificativo.
     *
     * @param idApplicazione FK verso l'anagrafica esterna del gestionale responsabile
     * @param idDebitore     identificativo (codice fiscale/partita IVA) di un soggetto debitore
     * @param pageable       paginazione e ordinamento richiesti
     * @return la pagina di posizioni debitorie che rispettano il filtro
     */
    Page<PosizioneDebitoria> findDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo(Long idApplicazione,
            String idDebitore, Pageable pageable);
}
