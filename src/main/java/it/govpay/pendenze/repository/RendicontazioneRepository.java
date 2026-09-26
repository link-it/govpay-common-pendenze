package it.govpay.pendenze.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import it.govpay.pendenze.entity.FlussoRendicontazione;
import it.govpay.pendenze.entity.Rendicontazione;

/**
 * Repository Spring Data per {@link Rendicontazione}.
 */
public interface RendicontazioneRepository extends JpaRepository<Rendicontazione, Long> {

    /**
     * Elenco delle rendicontazioni di una pendenza
     * ({@code GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni} dello YAML v3), a
     * partire dallo IUV (vedi Javadoc di {@link Rendicontazione} sull'assenza di
     * {@code id_pendenza} nel legacy {@code rendicontazioni}) **e dal dominio** (bug del
     * lead, 2026-09-26): lo IUV e' univoco solo per dominio, non globalmente (stesso
     * principio di {@code Pendenza} — vedi la nota su IUV/NAV) — senza questo filtro, due
     * enti con lo stesso IUV vedrebbero anche le rendicontazioni reciproche. Il filtro passa
     * per {@code flusso.codDominio} (unica colonna dominio disponibile su questo gruppo di
     * tabelle "fuori aggregato" — vedi Javadoc di classe di {@link FlussoRendicontazione}),
     * non per un {@code idDominio} su {@link Rendicontazione} stessa (che non esiste).
     *
     * <p>{@code flusso} è a {@code fetch = LAZY} sull'entità (vedi {@link Rendicontazione}), ma
     * lo YAML v3 espone i dati di testata del flusso insieme a ogni rendicontazione elencata:
     * senza un caricamento esplicito, leggere {@code getFlusso()} dopo il ritorno dal servizio
     * (fuori dalla transazione di lettura) genera {@code LazyInitializationException} (bug del
     * lead, 2026-09-24). L'{@link EntityGraph} lo carica con un fetch join nella stessa query,
     * senza tornare a un fetch eager permanente sull'entità (che affetterebbe anche i casi in
     * cui il flusso non serve).</p>
     *
     * @param iuv        IUV della pendenza (risolto dal chiamante dall'{@code idPendenza})
     * @param codDominio codice del dominio della pendenza (risolto dal chiamante dal suo
     *                   {@code idDominio} tramite l'anagrafica esterna)
     * @param pageable   paginazione/ordinamento richiesti
     * @return la pagina di rendicontazioni della pendenza, con il flusso già inizializzato
     */
    @EntityGraph(attributePaths = "flusso")
    Page<Rendicontazione> findByIuvAndFlusso_CodDominio(String iuv, String codDominio, Pageable pageable);
}
