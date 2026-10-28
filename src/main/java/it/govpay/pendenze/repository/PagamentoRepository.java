package it.govpay.pendenze.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.Pagamento;

/**
 * Repository Spring Data per {@link Pagamento}.
 *
 * <p>La lettura della "ricevuta" dello YAML v3 passa direttamente da {@link RptRepository}
 * (vedi Javadoc di classe di {@link it.govpay.pendenze.entity.Rpt#getIur()} — {@code rpt.ccp}
 * e' fisicamente lo IUR): {@link #findByCodDominioAndIuv} serve invece a pafornode-api per il
 * dettaglio (IUR/data pagamento) di un fault {@code PAA_PAGAMENTO_DUPLICATO}.</p>
 */
public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {

	List<Pagamento> findByCodDominioAndIuv(String codDominio, String iuv);
}
