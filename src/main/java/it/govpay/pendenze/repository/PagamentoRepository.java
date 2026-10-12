package it.govpay.pendenze.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.Pagamento;

/**
 * Repository Spring Data per {@link Pagamento}.
 *
 * <p>Nessun metodo di ricerca proprio per ora: la lettura della "ricevuta" dello YAML v3
 * passa direttamente da {@link RptRepository} (vedi Javadoc di classe di
 * {@link it.govpay.pendenze.entity.Rpt#getIur()} — {@code rpt.ccp} e' fisicamente lo
 * IUR). Questa entita' resta mappata per un possibile uso futuro (es. una risorsa
 * "riscossioni"), non ancora nello scopo di questa libreria.</p>
 */
public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {
}
