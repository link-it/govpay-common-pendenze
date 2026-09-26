package it.govpay.pendenze.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.Pendenza;

/**
 * Repository Spring Data per {@link Pendenza}.
 */
public interface PendenzaRepository extends JpaRepository<Pendenza, Long> {

    /**
     * IUV e NAV sono univoci **per dominio**, non globalmente (vedi la nota di classe su
     * {@link Pendenza}): la ricerca richiede sempre entrambi, mai il solo NAV.
     *
     * @param idDominio    dominio creditore
     * @param numeroAvviso NAV: identificativo dell'avviso di pagamento pagoPA
     * @return la pendenza, se esiste
     */
    Optional<Pendenza> findByIdDominioAndNumeroAvviso(Long idDominio, String numeroAvviso);

    /**
     * @param idDominio dominio creditore
     * @param iuv       Identificativo Univoco di Versamento
     * @return la pendenza, se esiste
     */
    Optional<Pendenza> findByIdDominioAndIuv(Long idDominio, String iuv);

    /**
     * Ricerca per numero avviso ({@code GET /pendenze/{idA2A}} dello YAML v3:
     * {@code numeroAvviso} e' l'unico criterio di ricerca, richiesto). A differenza di
     * {@link #findByIdDominioAndNumeroAvviso}, qui {@code idDominio} e' assente: puo'
     * restituire piu' risultati, perche' lo stesso numero avviso puo' legittimamente esistere
     * su domini diversi (M13 — unicita' per dominio, non globale). Filtrata per
     * {@code idApplicazione} (il gestionale proprietario, risolto da {@code idA2A} dal
     * chiamante — vedi Javadoc di {@link PosizioneDebitoriaRepository}), non solo per numero
     * avviso, altrimenti un gestionale potrebbe vedere pendenze di un altro indovinando un NAV.
     * {@code Pendenza.idApplicazione} coincide con quella della sua {@code Pendenza} stessa
     * ora che entrambe vivono su {@code versamenti}: filtra direttamente su
     * {@code idApplicazione}, non piu' passando per {@code opzionePagamento}.
     *
     * @param idApplicazione FK verso l'anagrafica esterna del gestionale responsabile
     * @param numeroAvviso   NAV: identificativo dell'avviso di pagamento pagoPA
     * @param pageable       paginazione e ordinamento richiesti
     * @return la pagina di pendenze che rispettano il filtro
     */
    Page<Pendenza> findByIdApplicazioneAndNumeroAvviso(Long idApplicazione, String numeroAvviso, Pageable pageable);

    /**
     * Come {@link #findByIdApplicazioneAndNumeroAvviso}, con il filtro aggiuntivo opzionale
     * {@code idDominio} previsto dallo YAML v3 (utilizzabile solo insieme a
     * {@code numeroAvviso}, mai da solo): restringe a una sola pendenza, dato il vincolo di
     * unicita' per dominio.
     *
     * @param idApplicazione FK verso l'anagrafica esterna del gestionale responsabile
     * @param numeroAvviso   NAV: identificativo dell'avviso di pagamento pagoPA
     * @param idDominio      dominio creditore
     * @param pageable       paginazione e ordinamento richiesti
     * @return la pagina di pendenze che rispettano il filtro (al piu' una)
     */
    Page<Pendenza> findByIdApplicazioneAndNumeroAvvisoAndIdDominio(Long idApplicazione, String numeroAvviso,
            Long idDominio, Pageable pageable);
}
