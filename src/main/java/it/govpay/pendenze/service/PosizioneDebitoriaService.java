package it.govpay.pendenze.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.OpzionePagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.spi.GeneratoreIuv;
import it.govpay.pendenze.spi.IdentificativiPagamento;
import it.govpay.pendenze.validazione.ValidatorePosizioneDebitoria;

/**
 * Servizio applicativo sull'aggregato {@link PosizioneDebitoria}.
 *
 * <p><b>Un metodo per evento di dominio, non un "salva" generico</b> (continuita' con D3
 * del disegno precedente): non c'e' un {@code aggiorna(PosizioneDebitoria)} onnicomprensivo,
 * ogni operazione ha un nome e un contratto propri. Questa prima versione copre solo
 * creazione, lettura e la macchina a stati di {@link OpzionePagamento} — le operazioni di
 * aggiornamento sui campi della posizione/pendenza (PATCH, annullamento pendenza, ecc.)
 * sono previste come sviluppo successivo.</p>
 *
 * <p>Gli istanti passano sempre dal {@link Clock} della libreria (bean
 * {@code pendenzeClock}), mai da {@code OffsetDateTime.now()}: stesso principio del
 * disegno precedente (F1-2/D10), cosi' i test possono fissare il tempo e il fuso resta
 * quello configurato, non quello della JVM.</p>
 */
@Service
@Transactional
public class PosizioneDebitoriaService {

    private final PosizioneDebitoriaRepository posizioneDebitoriaRepository;
    private final OpzionePagamentoRepository opzionePagamentoRepository;
    private final PendenzaRepository pendenzaRepository;
    private final Clock clock;
    private final ObjectProvider<GeneratoreIuv> generatoreIuvProvider;

    /**
     * {@code generatoreIuvProvider} e' un {@link ObjectProvider}, non una dipendenza
     * diretta: {@link GeneratoreIuv} e' una SPI opzionale (vedi Javadoc
     * dell'interfaccia). Stesso idioma gia' usato in
     * {@code PendenzeAutoConfiguration.proprietaPendenzaCodec} per non far fallire
     * l'avvio del consumatore se non fornisce un'implementazione — semplicemente
     * {@link #crea(PosizioneDebitoria)} rifiutera' le pendenze prive di IUV/numero
     * avviso proprio.
     */
    public PosizioneDebitoriaService(PosizioneDebitoriaRepository posizioneDebitoriaRepository,
            OpzionePagamentoRepository opzionePagamentoRepository, PendenzaRepository pendenzaRepository, Clock clock,
            ObjectProvider<GeneratoreIuv> generatoreIuvProvider) {
        this.posizioneDebitoriaRepository = posizioneDebitoriaRepository;
        this.opzionePagamentoRepository = opzionePagamentoRepository;
        this.pendenzaRepository = pendenzaRepository;
        this.clock = clock;
        this.generatoreIuvProvider = generatoreIuvProvider;
    }

    /**
     * Crea una posizione debitoria con le sue opzioni di pagamento, pendenze e voci, gia'
     * collegate tramite {@code addXxx(...)} dal chiamante. Valorizza qui gli istanti di
     * creazione/aggiornamento su tutta la gerarchia (M10/D10: audit su ogni entita'
     * dell'aggregato, non solo sulla radice) e genera {@link OpzionePagamento#getIdOpzionePagamento()}
     * se assente.
     *
     * <p>Prima di tutto valida i vincoli semantici dell'aggregato con
     * {@link ValidatorePosizioneDebitoria} (cardinalità delle pendenze per tipologia,
     * importo di ogni pendenza coerente con la somma delle sue voci): quelli che lo
     * schema JSON non può esprimere da solo.</p>
     *
     * <p>Assegna {@link SoggettoDebitore#getOrdine()}, {@link Pendenza#getNumeroRata()} e
     * {@link VocePendenza#getIndice()} dalla posizione nelle rispettive liste, sempre —
     * mai lasciati al chiamante: coincidono per definizione con l'ordine delle liste
     * (semantica dello YAML v3 per {@code numeroRata}: "Non richiesto in scrittura: è
     * GovPay ad assegnarlo in base all'ordine delle pendenze nell'array").</p>
     *
     * <p>Per ogni pendenza priva sia di IUV sia di numero avviso, richiede una coppia nuova a
     * {@link GeneratoreIuv#genera} (semantica dello YAML v3: {@code numeroAvviso} "opzionale,
     * se non fornito viene generato automaticamente"). Se il chiamante fornisce già
     * <b>entrambi</b>, non vengono toccati. Se fornisce solo il <b>numero avviso</b>, l'IUV
     * viene ricavato da esso con {@link GeneratoreIuv#convertiDaNumeroAvviso}: una conversione
     * di formato, non una generazione — non consuma alcun progressivo (vedi
     * {@code proposta-modello-nativo-v3.md}). Se fornisce solo lo <b>IUV</b>, la creazione
     * fallisce esplicitamente: non esiste un percorso legacy verificato per ricostruire il
     * numero avviso dal solo IUV. Se manca l'identificativo necessario (coppia intera, o solo
     * l'IUV da ricavare) e nessuna implementazione di {@link GeneratoreIuv} è disponibile nel
     * contesto, la creazione fallisce anch'essa invece di inserire colonne {@code NOT NULL}
     * vuote.</p>
     *
     * <p>Infine valida/assegna {@link PosizioneDebitoria#getNavNotifica()}: se fornito,
     * deve corrispondere al {@code numeroAvviso} di una pendenza della posizione
     * (altrimenti 400, semantica dello YAML v3); se assente e
     * {@link PosizioneDebitoria#isNotificaSend()} è attivo, viene assegnato
     * automaticamente alla pendenza dell'opzione {@code SOLUZIONE_UNICA} se presente,
     * altrimenti alla rata 1 dell'unico {@code PIANO_RATEALE}.</p>
     *
     * @param posizione posizione da creare, con l'intero aggregato gia' collegato
     * @return la posizione persistita
     * @throws ValidazioneNonSuperataException se l'aggregato non rispetta i vincoli
     *                                          semantici richiesti (inclusi navNotifica
     *                                          e la coppia IUV/numero avviso parziale)
     * @throws IllegalStateException se una pendenza e' priva sia di IUV sia di numero
     *                                avviso e non e' disponibile un {@link GeneratoreIuv}
     */
    public PosizioneDebitoria crea(PosizioneDebitoria posizione) {
        ValidatorePosizioneDebitoria.valida(posizione);
        assegnaIndici(posizione);

        OffsetDateTime adesso = OffsetDateTime.now(clock);

        posizione.setDataCreazione(adesso);
        posizione.setDataUltimoAggiornamento(adesso);
        posizione.setDataUltimaModificaAca(adesso);

        for (OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            if (opzione.getIdOpzionePagamento() == null) {
                opzione.setIdOpzionePagamento(UUID.randomUUID());
            }
            if (opzione.getStato() == null) {
                opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
            }
            opzione.setDataCreazione(adesso);
            opzione.setDataUltimoAggiornamento(adesso);

            for (Pendenza pendenza : opzione.getPendenze()) {
                pendenza.setDataCreazione(adesso);
                pendenza.setDataUltimoAggiornamento(adesso);
                pendenza.setDataUltimaModificaAca(adesso);
                if (pendenza.getDataCaricamento() == null) {
                    pendenza.setDataCaricamento(adesso.toLocalDate());
                }
                // IUV/NAV sono univoci per dominio, non globalmente: la pendenza deve
                // portare lo stesso dominio della posizione per poter far rispettare
                // quel vincolo (unique_pendenze_numero_avviso/iuv su (id_dominio, ...)).
                pendenza.setIdDominio(posizione.getIdDominio());

                assegnaIdentificativiPagamento(posizione, pendenza);
            }
        }

        assegnaOValidaNavNotifica(posizione);

        return posizioneDebitoriaRepository.save(posizione);
    }

    /**
     * Assegna {@code ordine} (0-based, sui soggetti) e {@code numeroRata} (1-based, sulle
     * pendenze di ciascuna opzione) dalla posizione nelle rispettive liste. Sovrascrive
     * sempre, anche se il chiamante li aveva gia' valorizzati: nessuno dei due e'
     * scrivibile in API (per {@code numeroRata} lo YAML lo dice esplicitamente), quindi
     * l'unica fonte di verita' e' l'ordine delle liste stesse — un valore "a mano"
     * diverso dalla posizione reale sarebbe un'incoerenza, non un'informazione in piu'.
     */
    private void assegnaIndici(PosizioneDebitoria posizione) {
        int ordine = 0;
        for (SoggettoDebitore soggetto : posizione.getSoggettiDebitori()) {
            soggetto.setOrdine(ordine++);
        }
        for (OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            int numeroRata = 1;
            for (Pendenza pendenza : opzione.getPendenze()) {
                pendenza.setNumeroRata(numeroRata++);

                int indice = 1;
                for (VocePendenza voce : pendenza.getVoci()) {
                    voce.setIndice(indice++);
                }
            }
        }
    }

    private void assegnaIdentificativiPagamento(PosizioneDebitoria posizione, Pendenza pendenza) {
        boolean iuvAssente = pendenza.getIuv() == null;
        boolean numeroAvvisoAssente = pendenza.getNumeroAvviso() == null;

        if (iuvAssente && numeroAvvisoAssente) {
            IdentificativiPagamento identificativi = generaIdentificativi(posizione, pendenza);
            pendenza.setIuv(identificativi.iuv());
            pendenza.setNumeroAvviso(identificativi.numeroAvviso());
        } else if (iuvAssente) {
            pendenza.setIuv(convertiNumeroAvviso(posizione, pendenza).iuv());
        } else if (numeroAvvisoAssente) {
            throw new ValidazioneNonSuperataException(
                    "la pendenza [" + pendenza.getIdPendenza() + "] ha valorizzato lo iuv [" + pendenza.getIuv()
                            + "] senza il numeroAvviso corrispondente: fornire entrambi, oppure solo il"
                            + " numeroAvviso (lo iuv viene ricavato automaticamente)");
        }
    }

    private IdentificativiPagamento generaIdentificativi(PosizioneDebitoria posizione, Pendenza pendenza) {
        GeneratoreIuv generatore = generatoreIuvProvider.getIfAvailable();
        if (generatore == null) {
            throw new IllegalStateException(
                    "la pendenza [" + pendenza.getIdPendenza() + "] e' priva di IUV/numero avviso e nessun "
                            + GeneratoreIuv.class.getSimpleName() + " e' configurato nel contesto");
        }
        return generatore.genera(posizione.getIdDominio(), posizione.getIdA2A(), pendenza.getIdPendenza(),
                pendenza.getCodificaIuvTipoPendenza());
    }

    private IdentificativiPagamento convertiNumeroAvviso(PosizioneDebitoria posizione, Pendenza pendenza) {
        GeneratoreIuv generatore = generatoreIuvProvider.getIfAvailable();
        if (generatore == null) {
            throw new IllegalStateException(
                    "la pendenza [" + pendenza.getIdPendenza() + "] ha solo il numeroAvviso ["
                            + pendenza.getNumeroAvviso() + "] e nessun " + GeneratoreIuv.class.getSimpleName()
                            + " e' configurato nel contesto per ricavarne lo iuv");
        }
        return generatore.convertiDaNumeroAvviso(posizione.getIdDominio(), pendenza.getNumeroAvviso());
    }

    /**
     * Se {@link PosizioneDebitoria#getNavNotifica()} è già valorizzato, verifica che
     * corrisponda al {@code numeroAvviso} di una pendenza della posizione (semantica
     * dello YAML v3: "GovPay lo verifica e rifiuta la richiesta se non corrisponde a
     * nessuna"). Se è assente e {@link PosizioneDebitoria#isNotificaSend()} è attivo, lo
     * assegna automaticamente: alla pendenza dell'opzione {@code SOLUZIONE_UNICA} se
     * presente, altrimenti alla rata 1 dell'unico {@code PIANO_RATEALE} (stessa
     * semantica). Se nessuno dei due casi noti si applica (es. solo opzioni
     * {@code SOLUZIONE_UNICA_ENTRO}/{@code OLTRE}) la creazione viene rifiutata: la regola
     * non copre esplicitamente questo caso, e persistere {@code notificaSend} attivo con
     * {@code navNotifica} nullo sarebbe una configurazione incompleta accettata in
     * silenzio — meglio richiedere che il chiamante lo indichi esplicitamente.
     */
    private void assegnaOValidaNavNotifica(PosizioneDebitoria posizione) {
        if (posizione.getNavNotifica() != null) {
            boolean corrisponde = posizione.getOpzioniPagamento().stream()
                    .flatMap(o -> o.getPendenze().stream())
                    .anyMatch(p -> posizione.getNavNotifica().equals(p.getNumeroAvviso()));
            if (!corrisponde) {
                throw new ValidazioneNonSuperataException(
                        "navNotifica [" + posizione.getNavNotifica()
                                + "] non corrisponde al numeroAvviso di alcuna pendenza della posizione");
            }
            return;
        }

        if (!posizione.isNotificaSend()) {
            return;
        }

        Optional<Pendenza> candidata = posizione.getOpzioniPagamento().stream()
                .filter(o -> o.getTipologia() == TipologiaOpzionePagamento.SOLUZIONE_UNICA)
                .flatMap(o -> o.getPendenze().stream())
                .findFirst()
                .or(() -> posizione.getOpzioniPagamento().stream()
                        .filter(o -> o.getTipologia() == TipologiaOpzionePagamento.PIANO_RATEALE)
                        .flatMap(o -> o.getPendenze().stream())
                        .filter(p -> p.getNumeroRata() == 1)
                        .findFirst());

        if (candidata.isEmpty()) {
            // Nessuna SOLUZIONE_UNICA ne' PIANO_RATEALE (es. solo ENTRO/OLTRE): la regola
            // nota non copre questo caso. Meglio rifiutare esplicitamente e richiedere un
            // navNotifica indicato dal chiamante che accettare in silenzio notificaSend
            // attivo con navNotifica nullo — una configurazione incompleta persistita
            // senza errore sarebbe peggio di un rifiuto.
            throw new ValidazioneNonSuperataException(
                    "notificaSend e' attivo ma non e' possibile assegnare automaticamente navNotifica "
                            + "(nessuna opzione SOLUZIONE_UNICA o PIANO_RATEALE nella posizione): indicare "
                            + "esplicitamente navNotifica");
        }
        posizione.setNavNotifica(candidata.get().getNumeroAvviso());
    }

    @Transactional(readOnly = true)
    public Optional<PosizioneDebitoria> trovaPerId(Long id) {
        return posizioneDebitoriaRepository.findById(id);
    }

    /**
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @return la posizione, se esiste
     */
    @Transactional(readOnly = true)
    public Optional<PosizioneDebitoria> trovaPerIdentificativo(String idA2A, String idPosizioneDebitoria) {
        return posizioneDebitoriaRepository.findByIdA2AAndIdPosizioneDebitoria(idA2A, idPosizioneDebitoria);
    }

    /**
     * Ricerca per debitore ({@code GET /posizioni-debitorie/{idA2A}} dello YAML v3):
     * {@code idDebitore} e' l'unico criterio di ricerca ammesso, oltre a {@code idA2A}. Trova
     * la posizione se l'identificativo corrisponde a qualunque soggetto in
     * {@code soggettiDebitori}, non solo al primo.
     *
     * @param idA2A      identificativo del gestionale responsabile
     * @param idDebitore identificativo (codice fiscale/partita IVA) di un soggetto debitore
     * @param pageable   paginazione/ordinamento richiesti (vedi {@code criteri.OffsetPageRequest}
     *                   per la paginazione a scorrimento libero usata dallo YAML v3)
     * @return la pagina di posizioni debitorie che rispettano il filtro
     */
    @Transactional(readOnly = true)
    public Page<PosizioneDebitoria> cercaPerDebitore(String idA2A, String idDebitore, Pageable pageable) {
        return posizioneDebitoriaRepository.findDistinctByIdA2AAndSoggettiDebitori_Identificativo(idA2A, idDebitore,
                pageable);
    }

    /**
     * Ricerca per numero avviso ({@code GET /pendenze/{idA2A}} dello YAML v3):
     * {@code numeroAvviso} e' l'unico criterio di ricerca, richiesto; {@code idDominio} e' un
     * filtro aggiuntivo opzionale, utilizzabile solo insieme a {@code numeroAvviso} (mai da
     * solo — coerente con lo YAML). Senza {@code idDominio} puo' restituire piu' risultati,
     * perche' lo stesso numero avviso puo' esistere legittimamente su domini diversi (M13).
     *
     * @param idA2A        identificativo del gestionale responsabile
     * @param numeroAvviso NAV: identificativo dell'avviso di pagamento pagoPA
     * @param idDominio    dominio creditore, o {@code null} per non filtrare per dominio
     * @param pageable     paginazione/ordinamento richiesti
     * @return la pagina di pendenze che rispettano il filtro
     */
    @Transactional(readOnly = true)
    public Page<Pendenza> cercaPendenze(String idA2A, String numeroAvviso, Long idDominio, Pageable pageable) {
        return idDominio == null
                ? pendenzaRepository.findByOpzionePagamento_PosizioneDebitoria_IdA2AAndNumeroAvviso(idA2A,
                        numeroAvviso, pageable)
                : pendenzaRepository.findByOpzionePagamento_PosizioneDebitoria_IdA2AAndNumeroAvvisoAndIdDominio(idA2A,
                        numeroAvviso, idDominio, pageable);
    }

    /**
     * Attiva un'opzione di pagamento a seguito di un pagamento su una delle sue pendenze:
     * la porta ad {@link StatoOpzionePagamento#ATTIVATA} e annulla automaticamente tutte
     * le altre opzioni ancora {@link StatoOpzionePagamento#DISPONIBILE} della stessa
     * posizione debitoria, essendo alternative non piu' applicabili (semantica dello YAML
     * v3, schema {@code StatoOpzionePagamento}).
     *
     * <p><b>Concorrenza.</b> {@link OpzionePagamento#getVersione()} e' un lock ottimistico:
     * se questo metodo e {@link #annulla(UUID)} vengono chiamati concorrentemente sulla
     * stessa opzione, chi scrive per secondo su una versione ormai superata riceve un
     * {@code OptimisticLockException} invece di sovrascrivere in silenzio la transizione
     * gia' registrata dall'altro — un annullamento non puo' quindi vincere su
     * un'attivazione appena avvenuta (o viceversa) solo perche' arrivato dopo nel tempo di
     * esecuzione. Il chiamante deve gestire l'eccezione (tipicamente: rileggere lo stato
     * attuale e decidere di conseguenza), non ignorarla.</p>
     *
     * @param idOpzionePagamento identificativo dell'opzione che risulta pagata
     * @return l'opzione appena attivata
     * @throws RisorsaNonTrovataException        se l'opzione non esiste
     * @throws TransizioneStatoNonAmmessaException se l'opzione non e' {@code DISPONIBILE}
     */
    public OpzionePagamento attiva(UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);
        if (opzione.getStato() != StatoOpzionePagamento.DISPONIBILE) {
            throw new TransizioneStatoNonAmmessaException(
                    "l'opzione di pagamento [" + idOpzionePagamento + "] non e' DISPONIBILE (stato attuale: "
                            + opzione.getStato() + "): non puo' essere attivata");
        }

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        opzione.setStato(StatoOpzionePagamento.ATTIVATA);
        opzione.setDataUltimoAggiornamento(adesso);
        marcaModificaAca(opzione, adesso);

        for (OpzionePagamento altra : opzione.getPosizioneDebitoria().getOpzioniPagamento()) {
            if (!altra.getId().equals(opzione.getId()) && altra.getStato() == StatoOpzionePagamento.DISPONIBILE) {
                altra.setStato(StatoOpzionePagamento.ANNULLATA);
                altra.setDataUltimoAggiornamento(adesso);
                marcaModificaAca(altra, adesso);
            }
        }

        return opzione;
    }

    /**
     * Annulla manualmente un'opzione di pagamento ancora {@code DISPONIBILE}. Idempotente
     * se gia' {@code ANNULLATA} (stesso comportamento di
     * {@code business.Versamento.annullaVersamento} nel legacy); rifiuta l'annullamento
     * di un'opzione gia' {@code ATTIVATA}, perche' corrisponde a un pagamento gia'
     * eseguito (semantica dello YAML v3).
     *
     * @param idOpzionePagamento identificativo dell'opzione da annullare
     * @return l'opzione annullata
     * @throws RisorsaNonTrovataException        se l'opzione non esiste
     * @throws TransizioneStatoNonAmmessaException se l'opzione e' {@code ATTIVATA}
     */
    public OpzionePagamento annulla(UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);

        if (opzione.getStato() == StatoOpzionePagamento.ANNULLATA) {
            return opzione;
        }
        if (opzione.getStato() == StatoOpzionePagamento.ATTIVATA) {
            throw new TransizioneStatoNonAmmessaException(
                    "l'opzione di pagamento [" + idOpzionePagamento
                            + "] e' ATTIVATA: corrisponde a un pagamento gia' eseguito, non puo' essere annullata");
        }

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        opzione.setStato(StatoOpzionePagamento.ANNULLATA);
        opzione.setDataUltimoAggiornamento(adesso);
        marcaModificaAca(opzione, adesso);
        return opzione;
    }

    private OpzionePagamento trovaOpzionePagamento(UUID idOpzionePagamento) {
        return opzionePagamentoRepository.findByIdOpzionePagamento(idOpzionePagamento)
                .orElseThrow(() -> new RisorsaNonTrovataException(
                        "nessuna opzione di pagamento con identificativo [" + idOpzionePagamento + "]"));
    }

    /**
     * Marca come modificati ai fini ACA sia la posizione debitoria sia tutte le pendenze
     * dell'opzione appena transitata: senza questo, il batch ACA (che si basa su
     * {@code dataUltimaModificaAca > dataUltimaComunicazioneAca}, vedi
     * {@code riconciliazione-legacy-v3.md} punto 18) non rileverebbe mai una transizione
     * di stato avvenuta dopo l'ultima sincronizzazione — l'avviso di una pendenza appena
     * attivata/annullata resterebbe segnalato come gia' comunicato quando non lo e' piu'.
     *
     * @param opzione opzione appena transitata (attivata o annullata)
     * @param adesso  istante della transizione, dallo stesso {@link Clock} della libreria
     */
    private void marcaModificaAca(OpzionePagamento opzione, OffsetDateTime adesso) {
        opzione.getPosizioneDebitoria().setDataUltimaModificaAca(adesso);
        for (Pendenza pendenza : opzione.getPendenze()) {
            pendenza.setDataUltimaModificaAca(adesso);
        }
    }
}
