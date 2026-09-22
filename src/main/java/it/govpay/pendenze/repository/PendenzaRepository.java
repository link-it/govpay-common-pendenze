package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.Pendenza;

/**
 * Repository Spring Data per {@link Pendenza}.
 */
public interface PendenzaRepository extends JpaRepository<Pendenza, Long> {

    /**
     * @param numeroAvviso NAV: identificativo dell'avviso di pagamento pagoPA, univoco
     *                     (vincolo {@code unique_pendenze_numero_avviso})
     * @return la pendenza, se esiste
     */
    Optional<Pendenza> findByNumeroAvviso(String numeroAvviso);

    /**
     * @param iuv Identificativo Univoco di Versamento, univoco
     *            (vincolo {@code unique_pendenze_iuv})
     * @return la pendenza, se esiste
     */
    Optional<Pendenza> findByIuv(String iuv);
}
