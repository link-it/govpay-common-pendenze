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
     * Cerca per la chiave logica (identificativo del gestionale + identificativo della
     * posizione), univoca per costruzione (vincolo {@code unique_posizioni_debitorie_1}).
     *
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @return la posizione, se esiste
     */
    Optional<PosizioneDebitoria> findByIdA2AAndIdPosizioneDebitoria(String idA2A, String idPosizioneDebitoria);

    /**
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @return {@code true} se esiste gia' una posizione con questa chiave logica
     */
    boolean existsByIdA2AAndIdPosizioneDebitoria(String idA2A, String idPosizioneDebitoria);

    /**
     * Ricerca per debitore ({@code GET /posizioni-debitorie/{idA2A}} dello YAML v3:
     * {@code idDebitore} e' l'unico criterio di ricerca ammesso, oltre a {@code idA2A}).
     * Trova la posizione se l'identificativo corrisponde a <b>qualunque</b> soggetto in
     * {@code soggettiDebitori}, non solo al primo (semantica esplicita dello YAML per i
     * debitori in solido). {@code Distinct} evita duplicati se piu' soggetti della stessa
     * posizione avessero — per un dato scorretto — lo stesso identificativo.
     *
     * @param idA2A      identificativo del gestionale responsabile
     * @param idDebitore identificativo (codice fiscale/partita IVA) di un soggetto debitore
     * @param pageable   paginazione e ordinamento richiesti
     * @return la pagina di posizioni debitorie che rispettano il filtro
     */
    Page<PosizioneDebitoria> findDistinctByIdA2AAndSoggettiDebitori_Identificativo(String idA2A, String idDebitore,
            Pageable pageable);
}
