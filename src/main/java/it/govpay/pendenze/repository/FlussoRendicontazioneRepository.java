package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.FlussoRendicontazione;

/**
 * Repository Spring Data per {@link FlussoRendicontazione}.
 */
public interface FlussoRendicontazioneRepository extends JpaRepository<FlussoRendicontazione, Long> {

    /**
     * Cerca la revisione corrente (non obsoleta) di un flusso, per dominio + identificativo —
     * al più una per costruzione (vedi Javadoc di classe di {@link FlussoRendicontazione} su
     * {@code revisione}/{@code obsoleto}), equivalente al legacy {@code FrBD.getFr(codDominio,
     * codFlusso)} (che filtra implicitamente {@code obsoleto=false}). Usata da chi acquisisce un
     * nuovo flusso per decidere se crearne la prima revisione o marcare questa come obsoleta e
     * inserirne una successiva.
     *
     * @param idDominio dominio creditore del flusso
     * @param idFlusso  identificativo del flusso di rendicontazione
     * @return la revisione corrente del flusso, se esiste
     */
    Optional<FlussoRendicontazione> findByIdDominioAndIdFlussoAndObsoletoFalse(Long idDominio, String idFlusso);
}
