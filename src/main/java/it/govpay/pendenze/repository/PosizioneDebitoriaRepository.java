package it.govpay.pendenze.repository;

import java.util.Optional;

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
}
