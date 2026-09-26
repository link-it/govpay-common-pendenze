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
     * <p>Per {@code codDominio}, non {@code idDominio} (decisione del lead, 2026-09-26):
     * verificato che {@code FrBD} (e tutto il resto del business layer legacy su
     * {@code fr}/{@code rpt}/{@code pagamenti}) cerca sempre per codice del dominio, mai per
     * id numerico — vedi Javadoc di classe di {@link FlussoRendicontazione}.</p>
     *
     * @param codDominio codice del dominio creditore del flusso
     * @param codFlusso  identificativo del flusso di rendicontazione
     * @return la revisione corrente del flusso, se esiste
     */
    Optional<FlussoRendicontazione> findByCodDominioAndCodFlussoAndObsoletoFalse(String codDominio, String codFlusso);
}
