package it.govpay.pendenze.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.govpay.pendenze.entity.FlussoRendicontazione;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.model.StatoRendicontazione;

/**
 * Repository Spring Data per {@link Rendicontazione}.
 */
public interface RendicontazioneRepository extends JpaRepository<Rendicontazione, Long> {

    /**
     * Elenco delle rendicontazioni di una pendenza
     * ({@code GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni} dello YAML v3), a
     * partire dallo IUV (vedi Javadoc di {@link Rendicontazione} sull'assenza di
     * {@code id_pendenza} nel legacy {@code rendicontazioni}) **e dal dominio**: lo IUV e'
     * univoco solo per dominio, non globalmente (stesso
     * principio di {@code Pendenza} — vedi la nota su IUV/NAV) — senza questo filtro, due
     * enti con lo stesso IUV vedrebbero anche le rendicontazioni reciproche. Il filtro passa
     * per {@code flusso.codDominio} (unica colonna dominio disponibile su questo gruppo di
     * tabelle "fuori aggregato" — vedi Javadoc di classe di {@link FlussoRendicontazione}),
     * non per un {@code idDominio} su {@link Rendicontazione} stessa (che non esiste).
     *
     * <p>{@code flusso} è a {@code fetch = LAZY} sull'entità (vedi {@link Rendicontazione}), ma
     * lo YAML v3 espone i dati di testata del flusso insieme a ogni rendicontazione elencata:
     * senza un caricamento esplicito, leggere {@code getFlusso()} dopo il ritorno dal servizio
     * (fuori dalla transazione di lettura) genera {@code LazyInitializationException}.
     * L'{@link EntityGraph} lo carica con un fetch join nella stessa query,
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

    /**
     * Come {@link #findByIuvAndFlusso_CodDominio}, in modalita' cursore (keyset) — stesso
     * schema di {@code RptRepository#findElencoByIdVersamentoDaCursore}. L'ordinamento usa
     * {@code flusso.dataOraFlusso} (colonna {@code NOT NULL} sul flusso, vedi Javadoc di
     * {@link FlussoRendicontazione}) invece di {@code Rendicontazione.data} (che e'
     * {@code nullable}, inadatto a un confronto {@code <}/{@code =} su un keyset).
     *
     * @param iuv                  IUV della pendenza
     * @param codDominio           codice del dominio della pendenza
     * @param cursorDataOraFlusso  valore {@code flusso.dataOraFlusso} dell'ultimo elemento
     *                             della pagina precedente, {@code null} per la prima pagina
     * @param cursorId             valore {@code id} dell'ultimo elemento della pagina
     *                             precedente (spareggio a parita' di {@code dataOraFlusso})
     * @param pageable             solo per il {@code limit} (l'ordinamento e' fisso)
     * @return al piu' {@code pageable.getPageSize()} rendicontazioni successive al cursore,
     *         con il flusso già inizializzato
     */
    @EntityGraph(attributePaths = "flusso")
    @Query("select r from Rendicontazione r where r.iuv = :iuv and r.flusso.codDominio = :codDominio "
            + "and (:cursorDataOraFlusso is null or r.flusso.dataOraFlusso < :cursorDataOraFlusso "
            + "or (r.flusso.dataOraFlusso = :cursorDataOraFlusso and r.id < :cursorId)) "
            + "order by r.flusso.dataOraFlusso desc, r.id desc")
    List<Rendicontazione> findByIuvAndFlusso_CodDominioDaCursore(@Param("iuv") String iuv,
            @Param("codDominio") String codDominio,
            @Param("cursorDataOraFlusso") OffsetDateTime cursorDataOraFlusso, @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * Verifica se esiste una rendicontazione per questa voce, per
     * {@code PosizioneDebitoriaService#aggiornaPendenza}: una voce la cui riga viene rimossa o
     * riusata da un'altra (vedi Javadoc di {@code PosizioneDebitoriaMapper#sostituisciVoci})
     * non deve perdere lo storico se e' gia' stata rendicontata — anche senza alcun
     * {@link it.govpay.pendenze.entity.Pagamento} associato: il legacy ({@code Rendicontazioni.java})
     * puo' registrare una rendicontazione anomala (standin, o un disservizio che la fa arrivare
     * prima/senza il pagamento corrispondente) indipendentemente dallo stato della pendenza.
     *
     * <p>Confronto diretto sulla FK fisica {@link Rendicontazione#getIdSingoloVersamento()}
     * (vedi il suo Javadoc) con l'{@code id} di database della voce — non per {@code iuv}+
     * {@code indiceDati}: evita di dover risolvere {@code codDominio} dall'anagrafica (lo IUV
     * da solo non e' univoco globalmente) perche' la corrispondenza e' gia' fisica, fatta a
     * monte dalla riconciliazione del legacy stesso.</p>
     *
     * @param idSingoloVersamento {@code id} di database della {@link it.govpay.pendenze.entity.VocePendenza}
     * @return {@code true} se esiste almeno una rendicontazione che referenzia questa voce
     */
    boolean existsByIdSingoloVersamento(Long idSingoloVersamento);

    /**
     * Rendicontazione ANOMALA arrivata PRIMA della ricevuta corrispondente, con gli stessi
     * estremi del pagamento (dominio/iuv/iur/indiceDati) e non ancora collegata a nessun
     * pagamento — usata da {@code AcquisizioneRicevutaService
     * #riconciliaRendicontazioneAnomala} (pafornode-api) per richiuderla quando la
     * ricevuta arriva. Porting di {@code RendicontazioniBD#getRendicontazione(String,
     * String, String, Integer, StatoRendicontazione, boolean)} del legacy: stesso
     * confronto su {@code indiceDati} (una rendicontazione con indice assente, {@code
     * null}, corrisponde a QUALUNQUE indice richiesto — il legacy non lo tratta come un
     * mancato confronto) e stesso filtro {@code idPagamento is null}.
     *
     * @param codDominio dominio creditore (tramite {@code flusso.codDominio})
     * @param iuv        identificativo univoco di versamento
     * @param iur        identificativo univoco di riscossione
     * @param indiceDati indice del trasferimento nella ricevuta
     * @param stato      stato da cercare (sempre {@code ANOMALA} dal chiamante)
     * @return le rendicontazioni che soddisfano il criterio (il chiamante ne usa al più una)
     */
    @Query("select r from Rendicontazione r where r.flusso.codDominio = :codDominio and r.iuv = :iuv "
            + "and r.iur = :iur and (r.indiceDati = :indiceDati or r.indiceDati is null) "
            + "and r.stato = :stato and r.idPagamento is null")
    List<Rendicontazione> findAnomalaNonCollegata(@Param("codDominio") String codDominio, @Param("iuv") String iuv,
            @Param("iur") String iur, @Param("indiceDati") Integer indiceDati,
            @Param("stato") StatoRendicontazione stato);
}
