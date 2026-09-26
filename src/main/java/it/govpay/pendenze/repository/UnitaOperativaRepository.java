package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.UnitaOperativa;

/**
 * Repository Spring Data per {@link UnitaOperativa}.
 */
public interface UnitaOperativaRepository extends JpaRepository<UnitaOperativa, Long> {

    /**
     * Risoluzione di {@code idUnitaOperativa} (codice, non id numerico — vedi Javadoc di
     * classe di {@link UnitaOperativa}) per un dominio: stesso principio di
     * {@code PosizioneDebitoriaService#risolviIdApplicazione}, un chiamante che indica un
     * codice ignoto ottiene un {@code Optional} vuoto, non un'eccezione.
     *
     * @param idDominio FK piatta verso il dominio proprietario
     * @param codUo     codice dell'unità operativa
     * @return l'unità operativa, se esiste
     */
    Optional<UnitaOperativa> findByIdDominioAndCodUo(Long idDominio, String codUo);
}
