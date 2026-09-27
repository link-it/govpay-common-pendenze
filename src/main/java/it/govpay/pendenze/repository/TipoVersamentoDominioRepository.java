package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.govpay.pendenze.entity.TipoVersamentoDominio;

/**
 * Repository Spring Data per {@link TipoVersamentoDominio}.
 */
public interface TipoVersamentoDominioRepository extends JpaRepository<TipoVersamentoDominio, Long> {

    /**
     * Risolve in un'unica interrogazione sia {@code idTipoPendenza}
     * ({@link TipoVersamentoDominio#getId()}) sia {@code idTipoVersamento}
     * ({@link TipoVersamentoDominio#getTipoVersamento()}{@code .getId()}) richiesti da
     * {@link it.govpay.pendenze.entity.Pendenza}, dato il codice pubblico
     * {@code idTipoPendenza} dello YAML v3 e l'{@code idDominio} della posizione — stessa
     * risoluzione del legacy ({@code AnagraficaManager.getTipoVersamentoDominio(configWrapper,
     * idDominio, codTipoVersamento)}: per una richiesta di creazione, un codice esistente nel
     * catalogo ma privo di override per questo dominio va comunque rifiutato, non e' un
     * fallback su un dominio "di default"). Un chiamante che indica una combinazione ignota
     * ottiene un {@code Optional} vuoto, non un'eccezione.
     *
     * {@code join fetch} su {@code tipoVersamento} (bug del lead, 2026-09-27): senza,
     * {@link TipoVersamentoDominio#getTipoVersamento()} resta un proxy LAZY che solleva
     * {@code LazyInitializationException} non appena letto fuori dalla transazione in cui e'
     * stato risolto — tipicamente quando un chiamante costruisce una risposta REST dopo che
     * la scrittura e' gia' tornata (transazione propria gia' chiusa, {@code open-in-view=false}).
     *
     * @param codTipoVersamento codice della tipologia (campo pubblico {@code idTipoPendenza})
     * @param idDominio         FK piatta verso il dominio della posizione
     * @return l'override per quel dominio, se esiste
     */
    @Query("select tvd from TipoVersamentoDominio tvd join fetch tvd.tipoVersamento tv "
            + "where tv.codTipoVersamento = :codTipoVersamento and tvd.idDominio = :idDominio")
    Optional<TipoVersamentoDominio> findByCodTipoVersamentoAndIdDominio(
            @Param("codTipoVersamento") String codTipoVersamento, @Param("idDominio") Long idDominio);

    /**
     * Come {@link #findById(Long)} ma con {@link TipoVersamentoDominio#getTipoVersamento()}
     * caricato eagerly ({@code join fetch}) — stessa ragione di
     * {@link #findByCodTipoVersamentoAndIdDominio}: serve a chi risolve
     * {@code codTipoVersamento} da {@code Pendenza.idTipoPendenza} per costruire una
     * risposta, tipicamente fuori dalla transazione di scrittura originale.
     *
     * @param id {@link TipoVersamentoDominio#getId()}
     * @return l'override, con il suo {@link TipoVersamento} gia' caricato, se esiste
     */
    @Query("select tvd from TipoVersamentoDominio tvd join fetch tvd.tipoVersamento where tvd.id = :id")
    Optional<TipoVersamentoDominio> findByIdFetchTipoVersamento(@Param("id") Long id);
}
