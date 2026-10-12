package it.govpay.pendenze.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.RptXmlRptEntity;

/**
 * Repository Spring Data per {@link RptXmlRptEntity} — vedi Javadoc di classe per il perche'
 * di una entity separata da {@link it.govpay.pendenze.entity.Rpt}.
 */
public interface RptXmlRptRepository extends JpaRepository<RptXmlRptEntity, Long> {
}
