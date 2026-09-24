package it.govpay.pendenze.service;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.entity.Ricevuta;
import it.govpay.pendenze.repository.RendicontazioneRepository;
import it.govpay.pendenze.repository.RicevutaElenco;
import it.govpay.pendenze.repository.RicevutaRepository;

/**
 * Letture di {@link Ricevuta}/{@link Rendicontazione} di una pendenza.
 *
 * <p><b>Nessuna scrittura qui</b>: questa libreria non riceve né interpreta i flussi pagoPA
 * (RT/rendicontazioni) — chi li acquisisce (un batch/consumer dedicato) persiste
 * direttamente con i repository, senza passare da un servizio con regole di validazione
 * proprie: non ce ne sono, il contenuto è prodotto integralmente da pagoPA (vedi Javadoc di
 * {@link Ricevuta}).</p>
 *
 * <p>Servizio separato da {@link PosizioneDebitoriaService} apposta: {@link Ricevuta}/
 * {@link Rendicontazione} sono fuori dall'aggregato {@code PosizioneDebitoria} (decisione del
 * lead, 2026-09-24, per non ripetere il problema del vecchio "dettaglio pendenza" — centinaia
 * di query per una singola lettura).</p>
 */
@Service
@Transactional(readOnly = true)
public class RicevutaRendicontazioneService {

    private final RicevutaRepository ricevutaRepository;
    private final RendicontazioneRepository rendicontazioneRepository;

    public RicevutaRendicontazioneService(RicevutaRepository ricevutaRepository,
            RendicontazioneRepository rendicontazioneRepository) {
        this.ricevutaRepository = ricevutaRepository;
        this.rendicontazioneRepository = rendicontazioneRepository;
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
        Page<RicevutaElenco> pagina = ricevutaRepository.findElencoByIdPendenza(idPendenza, pageable);
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
    public Optional<Ricevuta> trovaRicevuta(Long idPendenza, String iur) {
        return ricevutaRepository.findByIdPendenzaAndIur(idPendenza, iur);
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
        Page<Rendicontazione> pagina = rendicontazioneRepository.findByIdPendenza(idPendenza, pageable);
        return new PaginaRisultati<>(pagina.getContent(), pageable.getOffset(), pageable.getPageSize(),
                pagina.getTotalElements());
    }
}
