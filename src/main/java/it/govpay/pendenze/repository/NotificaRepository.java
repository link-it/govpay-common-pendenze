package it.govpay.pendenze.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.Notifica;

/** Repository Spring Data per {@link Notifica}. */
public interface NotificaRepository extends JpaRepository<Notifica, Long> {
}
