package it.govpay.pendenze.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.OpzionePagamento;

/**
 * Repository Spring Data per {@link OpzionePagamento}.
 */
public interface OpzionePagamentoRepository extends JpaRepository<OpzionePagamento, Long> {

    /**
     * @param idOpzionePagamento identificativo esposto in API, generato da GovPay alla
     *                           creazione
     * @return l'opzione di pagamento, se esiste
     */
    Optional<OpzionePagamento> findByIdOpzionePagamento(UUID idOpzionePagamento);
}
