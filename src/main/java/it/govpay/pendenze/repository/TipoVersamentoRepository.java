package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.TipoVersamento;

/**
 * Repository Spring Data per {@link TipoVersamento}.
 */
public interface TipoVersamentoRepository extends JpaRepository<TipoVersamento, Long> {

    /**
     * Risoluzione di {@code idTipoPendenza} (codice, non id numerico — vedi Javadoc di
     * classe di {@link TipoVersamento}), indipendente dal dominio: {@code codTipoVersamento}
     * e' univoco globalmente nel catalogo (vedi {@code unique_tipi_versamento_1} nel legacy).
     * Un chiamante che indica un codice ignoto ottiene un {@code Optional} vuoto, non
     * un'eccezione.
     *
     * @param codTipoVersamento codice della tipologia
     * @return il tipo versamento, se esiste
     */
    Optional<TipoVersamento> findByCodTipoVersamento(String codTipoVersamento);
}
