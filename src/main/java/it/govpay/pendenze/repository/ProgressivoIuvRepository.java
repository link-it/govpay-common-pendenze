package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import it.govpay.pendenze.entity.ProgressivoIuv;
import it.govpay.pendenze.entity.ProgressivoIuvId;

public interface ProgressivoIuvRepository extends JpaRepository<ProgressivoIuv, ProgressivoIuvId> {

    /**
     * Legge la riga con lock pessimistico ({@code SELECT ... FOR UPDATE}): chi la chiama in
     * seguito deve farlo dentro una transazione dedicata (vedi
     * {@code AllocatoreBloccoProgressivoIuv}), altrimenti il lock non ha alcun effetto.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProgressivoIuv p where p.id = :id")
    Optional<ProgressivoIuv> findByIdForUpdate(@Param("id") ProgressivoIuvId id);
}
