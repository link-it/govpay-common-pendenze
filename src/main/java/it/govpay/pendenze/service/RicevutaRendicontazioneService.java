package it.govpay.pendenze.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RendicontazioneRepository;
import it.govpay.pendenze.repository.RicevutaElenco;
import it.govpay.pendenze.repository.RptRepository;

/**
 * Letture di {@link Rpt}/{@link Rendicontazione} di una pendenza.
 *
 * <p><b>Nessuna scrittura qui</b>: questa libreria non riceve né interpreta i flussi pagoPA
 * (RT/rendicontazioni) — chi li acquisisce (un batch/consumer dedicato) persiste
 * direttamente con i repository, senza passare da un servizio con regole di validazione
 * proprie: non ce ne sono, il contenuto è prodotto integralmente da pagoPA (vedi Javadoc di
 * {@link Rpt}).</p>
 *
 * <p>Servizio separato da {@link PosizioneDebitoriaService} apposta: {@link Rpt}/
 * {@link Rendicontazione} sono fuori dall'aggregato {@code PosizioneDebitoria} (decisione del
 * lead, 2026-09-24, per non ripetere il problema del vecchio "dettaglio pendenza" — centinaia
 * di query per una singola lettura).</p>
 *
 * <p><b>Le letture di {@link Rpt} usano direttamente {@code idPendenza}</b> ({@code Rpt.idVersamento}
 * e' una FK piatta reale verso {@code versamenti} — vedi Javadoc di classe di {@link Rpt}),
 * mentre {@link Rendicontazione} non ha alcuna FK verso la pendenza (ne' diretta ne' fisica:
 * {@code rendicontazioni} non ha una colonna del genere) e va risolta per {@code iuv}
 * <b>e per dominio</b> (bug del lead, 2026-09-26): lo IUV e' univoco solo per dominio, non
 * globalmente — senza il filtro sul dominio, due enti con lo stesso IUV vedrebbero anche le
 * rendicontazioni reciproche (vedi Javadoc di
 * {@link it.govpay.pendenze.repository.RendicontazioneRepository#findByIuvAndFlusso_CodDominio}).
 * Il {@code codDominio} si risolve dall'{@code idDominio} della pendenza tramite
 * {@link DominioRepository} (stesso principio di
 * {@code PosizioneDebitoriaService#risolviIdA2A}: qui la pendenza esiste gia', un dominio
 * che non risolve e' un'incoerenza dei dati, non un caso di ricerca legittimo). Un
 * {@code idPendenza} sconosciuto invece e' un caso legittimo di ricerca (stesso principio di
 * {@code PosizioneDebitoriaService#risolviIdApplicazione}): il chiamante ottiene una pagina
 * vuota, non un'eccezione.</p>
 */
@Service
@Transactional(readOnly = true)
public class RicevutaRendicontazioneService {

    private final PendenzaRepository pendenzaRepository;
    private final RptRepository rptRepository;
    private final RendicontazioneRepository rendicontazioneRepository;
    private final DominioRepository dominioRepository;

    public RicevutaRendicontazioneService(PendenzaRepository pendenzaRepository, RptRepository rptRepository,
            RendicontazioneRepository rendicontazioneRepository, DominioRepository dominioRepository) {
        this.pendenzaRepository = pendenzaRepository;
        this.rptRepository = rptRepository;
        this.rendicontazioneRepository = rendicontazioneRepository;
        this.dominioRepository = dominioRepository;
    }

    /**
     * Elenco delle ricevute di una pendenza ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute}
     * dello YAML v3).
     *
     * @param idPendenza chiave interna della pendenza
     * @param pageable   paginazione/ordinamento richiesti
     * @return la pagina di ricevute, in forma sintetica (vedi {@link RicevutaElenco})
     */
    public PaginaRisultati<RicevutaElenco> cercaRicevute(Long idPendenza, Pageable pageable) {
        Page<RicevutaElenco> pagina = rptRepository.findElencoByIdVersamento(idPendenza, pageable);
        return new PaginaRisultati<>(pagina.getContent(), pageable.getOffset(), pageable.getPageSize(),
                pagina.getTotalElements());
    }

    /**
     * Dettaglio di una ricevuta ({@code GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}}
     * dello YAML v3).
     *
     * @param idPendenza chiave interna della pendenza
     * @param iur        identificativo univoco di riscossione
     * @return la ricevuta, se esiste
     */
    public Optional<Rpt> trovaRicevuta(Long idPendenza, String iur) {
        return rptRepository.findByIdVersamentoAndIurAndDataMsgRicevutaIsNotNull(idPendenza, iur);
    }

    /**
     * Elenco delle rendicontazioni di una pendenza
     * ({@code GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni} dello YAML v3).
     *
     * @param idPendenza chiave interna della pendenza
     * @param pageable   paginazione/ordinamento richiesti
     * @return la pagina di rendicontazioni
     */
    public PaginaRisultati<Rendicontazione> cercaRendicontazioni(Long idPendenza, Pageable pageable) {
        Optional<Pendenza> pendenza = pendenzaRepository.findById(idPendenza);
        if (pendenza.isEmpty()) {
            return new PaginaRisultati<>(List.of(), pageable.getOffset(), pageable.getPageSize(), 0);
        }
        String codDominio = risolviCodDominio(pendenza.get().getIdDominio());
        Page<Rendicontazione> pagina = rendicontazioneRepository.findByIuvAndFlusso_CodDominio(
                pendenza.get().getIuv(), codDominio, pageable);
        return new PaginaRisultati<>(pagina.getContent(), pageable.getOffset(), pageable.getPageSize(),
                pagina.getTotalElements());
    }

    /**
     * Risolve {@code codDominio} dall'{@code idDominio} di una pendenza gia' trovata (vedi
     * Javadoc di classe): qui un dominio ignoto e' un'incoerenza dei dati, non un caso di
     * ricerca legittimo — a differenza di
     * {@code PosizioneDebitoriaService#risolviIdApplicazione}, che risolve un identificativo
     * fornito dal chiamante esterno.
     */
    private String risolviCodDominio(Long idDominio) {
        return dominioRepository.findById(idDominio)
                .map(DominioEntity::getCodDominio)
                .orElseThrow(() -> new IllegalStateException("Dominio [id:" + idDominio + "] non trovato in anagrafica"));
    }
}
