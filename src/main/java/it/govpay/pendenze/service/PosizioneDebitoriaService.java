package it.govpay.pendenze.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.RisorsaGiaEsistenteException;
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
    private final ApplicazioneRepository applicazioneRepository;
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
            OpzionePagamentoRepository opzionePagamentoRepository, PendenzaRepository pendenzaRepository,
            ApplicazioneRepository applicazioneRepository, Clock clock,
            ObjectProvider<GeneratoreIuv> generatoreIuvProvider) {
        this.posizioneDebitoriaRepository = posizioneDebitoriaRepository;
        this.opzionePagamentoRepository = opzionePagamentoRepository;
        this.pendenzaRepository = pendenzaRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.clock = clock;
        this.generatoreIuvProvider = generatoreIuvProvider;
    }

    /**
     * Risolve {@code idA2A} (= {@code Applicazione.codApplicazione}, confermato dal lead,
     * 2026-09-24) da {@link PosizioneDebitoria#getIdApplicazione()}: da quando
     * {@code PosizioneDebitoria} e' mappata su {@code documenti}, non esiste piu' una
     * colonna {@code idA2A} propria — solo la FK piatta verso l'anagrafica esterna (M4).
     */
    private String risolviIdA2A(Long idApplicazione) {
        return applicazioneRepository.findById(idApplicazione)
                .map(ApplicazioneEntity::getCodApplicazione)
                .orElseThrow(() -> new IllegalStateException(
                        "Applicazione [id:" + idApplicazione + "] non trovata in anagrafica"));
    }

    /**
     * Risoluzione inversa di {@link #risolviIdA2A}, per i metodi di ricerca che ricevono
     * {@code idA2A} dal chiamante esterno (contratto pubblico invariato) ma devono
     * interrogare i repository per {@code idApplicazione} (l'unica FK persistita — vedi
     * nota di classe di {@link it.govpay.pendenze.repository.PosizioneDebitoriaRepository}).
     * A differenza di {@link #risolviIdA2A} (percorso di scrittura, dove un'applicazione
     * ignota e' un errore di configurazione) qui un {@code idA2A} sconosciuto e' un caso
     * legittimo di ricerca: il chiamante ottiene semplicemente nessun risultato, non
     * un'eccezione.
     */
    private Optional<Long> risolviIdApplicazione(String idA2A) {
        return applicazioneRepository.findByCodApplicazione(idA2A).map(ApplicazioneEntity::getId);
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
     * @throws RisorsaGiaEsistenteException se esiste gia' una posizione con lo stesso
     *                                {@code idA2A}+{@code idPosizioneDebitoria} (bug del
     *                                lead, 2026-09-26: senza questo controllo, due posizioni
     *                                con la stessa chiave logica ma dominio diverso vengono
     *                                create entrambe — il vincolo DB reale su
     *                                {@code documenti} include anche {@code id_dominio},
     *                                mentre la ricerca pubblica per identificativo
     *                                ({@code trovaPerIdentificativo}) e il 409 dello YAML v3
     *                                sono chiavati solo su {@code idA2A}+{@code idPosizioneDebitoria})
     */
    public PosizioneDebitoria crea(PosizioneDebitoria posizione) {
        if (posizioneDebitoriaRepository.existsByIdApplicazioneAndIdPosizioneDebitoria(
                posizione.getIdApplicazione(), posizione.getIdPosizioneDebitoria())) {
            throw new RisorsaGiaEsistenteException(
                    "esiste gia' una posizione debitoria con idPosizioneDebitoria ["
                            + posizione.getIdPosizioneDebitoria() + "] per questa applicazione");
        }
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
                // IUV/NAV sono univoci per dominio, non globalmente: la pendenza deve
                // portare lo stesso dominio della posizione per poter far rispettare
                // quel vincolo (unique_pendenze_numero_avviso/iuv su (id_dominio, ...)).
                pendenza.setIdDominio(posizione.getIdDominio());

                for (VocePendenza voce : pendenza.getVoci()) {
                    // Multi-beneficiario pagoPA: se il chiamante non indica un dominio
                    // diverso per questa voce, eredita quello della posizione — mai
                    // lasciato implicito (stesso principio di Pendenza.idDominio sopra,
                    // vedi Javadoc di VocePendenza.idDominio).
                    if (voce.getIdDominio() == null) {
                        voce.setIdDominio(posizione.getIdDominio());
                    }
                }

                assegnaIdentificativiPagamento(posizione, pendenza);
            }
        }

        assegnaOValidaNavNotifica(posizione);

        try {
            // saveAndFlush, non save (bug del lead, 2026-09-27): con id generato da
            // SEQUENCE Hibernate puo' differire l'INSERT fisico oltre il ritorno di save(),
            // fino al commit della transazione — che avviene FUORI da questo metodo (al
            // ritorno di crea() al chiamante). La violazione del vincolo UNIQUE emergeva
            // quindi dopo che questo try/catch era gia' uscito, propagandosi come
            // DataIntegrityViolationException grezza fino al livello REST (500 anziche' 409)
            // — la posizione restava comunque salvata (il commit va a buon fine se nessuno
            // la intercetta), riproducibile anche solo ritentando la stessa richiesta, che
            // riceveva invece 409 dal controllo existsBy... sopra. saveAndFlush forza
            // l'INSERT dentro questo blocco, dove puo' essere intercettato.
            return posizioneDebitoriaRepository.saveAndFlush(posizione);
        } catch (DataIntegrityViolationException e) {
            // Rete di sicurezza contro le creazioni concorrenti (bug del lead, 2026-09-26):
            // il controllo existsBy... sopra e' un check-then-act, non atomico — due richieste
            // concorrenti con lo stesso idA2A+idPosizioneDebitoria possono superarlo entrambe.
            // La garanzia reale e' il vincolo DB unique_documenti_applicazione (migrazione
            // 01_documenti.sql, cod_documento+id_applicazione senza id_dominio, per far
            // corrispondere l'identita' pubblica al vincolo): qui se ne traduce la violazione
            // nella stessa eccezione del controllo esplicito, cosi' il chiamante vede sempre
            // RisorsaGiaEsistenteException e non un'eccezione di persistenza generica.
            //
            // Riconosce il vincolo specifico (bug del lead, 2026-09-27): tradurre QUALUNQUE
            // DataIntegrityViolationException in "risorsa gia' esistente" e' scorretto — un
            // altro vincolo violato (es. NOT NULL su una colonna non valorizzata, un bug
            // diverso) verrebbe mascherato da un 409 fuorviante invece di propagarsi come
            // l'errore che e' davvero.
            if (!violaVincoloUnicitaIdentificativo(e)) {
                throw e;
            }
            throw new RisorsaGiaEsistenteException(
                    "esiste gia' una posizione debitoria con idPosizioneDebitoria ["
                            + posizione.getIdPosizioneDebitoria() + "] per questa applicazione");
        }
    }

    /**
     * Entrambi i vincoli UNIQUE reali su {@code documenti} che possono segnalare lo stesso
     * duplicato pubblico ({@code idA2A}+{@code idPosizioneDebitoria}): {@code
     * unique_documenti_applicazione} (cod_documento+id_applicazione, aggiunto in migrazione
     * per l'identita' pubblica — vedi bug del 2026-09-26 sopra) e {@code unique_documenti_1}
     * (cod_documento+id_applicazione+id_dominio, gia' presente nello schema legacy). Quando
     * il duplicato ha anche lo stesso {@code id_dominio}, ENTRAMBI i vincoli sono violati
     * dalla stessa riga — quale dei due il motore segnali dipende dall'ordine con cui
     * valuta gli indici, non e' deterministico lato applicativo (bug del lead, 2026-09-27:
     * riconoscere solo {@code unique_documenti_applicazione} lasciava questo caso — duplicato
     * sullo stesso dominio, individuato solo al flush — propagarsi come 500 anziche' 409;
     * riprodotto con uno spy su {@code existsBy...} per simulare la race condition).
     */
    private static final List<String> VINCOLI_UNICITA_IDENTIFICATIVO = List.of(
            "unique_documenti_applicazione", "unique_documenti_1");

    /**
     * Riconosce se {@code e} e' dovuta specificamente a uno dei
     * {@link #VINCOLI_UNICITA_IDENTIFICATIVO}, non a una qualunque violazione di integrita'.
     * {@code getConstraintName()} (dal {@code org.hibernate.exception.ConstraintViolationException}
     * sottostante) non e' sempre popolato da ogni driver/dialetto — fallback sul testo del
     * messaggio, che in pratica lo riporta comunque (verificato su H2/PostgreSQL).
     */
    private boolean violaVincoloUnicitaIdentificativo(DataIntegrityViolationException e) {
        Throwable causa = e.getCause();
        String nomeVincolo = null;
        if (causa instanceof org.hibernate.exception.ConstraintViolationException cve) {
            nomeVincolo = cve.getConstraintName();
        }
        String daControllare = nomeVincolo != null ? nomeVincolo
                : (causa != null ? causa.getMessage() : e.getMessage());
        if (daControllare == null) {
            return false;
        }
        String daControllareMinuscolo = daControllare.toLowerCase();
        return VINCOLI_UNICITA_IDENTIFICATIVO.stream().anyMatch(daControllareMinuscolo::contains);
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

        if (!numeroAvvisoAssente) {
            verificaNumeroAvvisoNonDuplicato(posizione, pendenza);
        }

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

    /**
     * Replica {@code VER_025} del legacy (business layer, {@code Versamento.java:184-189}):
     * quando il chiamante fornisce {@code numeroAvviso}, verifica che nessun'altra pendenza
     * dello stesso dominio lo usi gia' prima di inserire. Controllo applicativo, non un
     * vincolo DB — il legacy stesso non ne ha mai avuto uno su {@code versamenti}
     * (verificato: solo un indice non univoco su {@code iuv_versamento, id_dominio}, mai
     * dichiarato {@code UNIQUE}), fa esattamente cosi'. Decisione del lead, 2026-09-25:
     * nessun vincolo {@code UNIQUE} nuovo su {@code versamenti}, coerente con "minimizza le
     * variazioni al DB" — IUV/numeroAvviso generati da {@link GeneratoreIuv} non passano da
     * qui, la loro unicita' e' gia' garantita per costruzione dal progressivo atomico.
     */
    private void verificaNumeroAvvisoNonDuplicato(PosizioneDebitoria posizione, Pendenza pendenza) {
        pendenzaRepository.findByIdDominioAndNumeroAvviso(posizione.getIdDominio(), pendenza.getNumeroAvviso())
                .ifPresent(esistente -> {
                    throw new ValidazioneNonSuperataException(
                            "la pendenza [" + pendenza.getIdPendenza() + "] ha numeroAvviso ["
                                    + pendenza.getNumeroAvviso()
                                    + "] gia' usato da un'altra pendenza dello stesso dominio ["
                                    + esistente.getIdPendenza() + "]");
                });
    }

    private IdentificativiPagamento generaIdentificativi(PosizioneDebitoria posizione, Pendenza pendenza) {
        GeneratoreIuv generatore = generatoreIuvProvider.getIfAvailable();
        if (generatore == null) {
            throw new IllegalStateException(
                    "la pendenza [" + pendenza.getIdPendenza() + "] e' priva di IUV/numero avviso e nessun "
                            + GeneratoreIuv.class.getSimpleName() + " e' configurato nel contesto");
        }
        return generatore.genera(posizione.getIdDominio(), risolviIdA2A(posizione.getIdApplicazione()),
                pendenza.getIdPendenza(), pendenza.getCodificaIuvTipoPendenza());
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
        return risolviIdApplicazione(idA2A)
                .flatMap(idApplicazione -> posizioneDebitoriaRepository
                        .findByIdApplicazioneAndIdPosizioneDebitoria(idApplicazione, idPosizioneDebitoria));
    }

    /**
     * Aggiorna una posizione debitoria esistente ({@code PATCH .../posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}}
     * dello YAML v3): non conosce la sintassi JSON Patch (RFC 6902) del chiamante — quella
     * traduzione e' a carico del chiamante stesso, che riceve l'aggregato gestito e vi
     * applica le mutazioni (es. {@code descrizione}, {@code soggettiDebitori} tramite
     * {@link PosizioneDebitoria#sostituisciSoggettiDebitori}) prima che questo metodo
     * rivalidi l'aggregato e lo persista. Le opzioni di pagamento non si toccano qui (vedi
     * Javadoc dello YAML: {@code POST}/{@code PATCH .../opzioni-pagamento} dedicati).
     *
     * @param idA2A              identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @param applicaModifiche   muta l'aggregato gestito prima della rivalidazione
     * @return la posizione aggiornata
     * @throws RisorsaNonTrovataException      se non esiste una posizione con questa chiave
     * @throws ValidazioneNonSuperataException se l'aggregato risultante non rispetta i
     *                                          vincoli semantici (stessi di {@link #crea},
     *                                          inclusi quelli su {@code navNotifica}/
     *                                          {@code notificaSend})
     */
    public PosizioneDebitoria aggiorna(String idA2A, String idPosizioneDebitoria,
            Consumer<PosizioneDebitoria> applicaModifiche) {
        PosizioneDebitoria posizione = risolviIdApplicazione(idA2A)
                .flatMap(idApplicazione -> posizioneDebitoriaRepository
                        .findByIdApplicazioneAndIdPosizioneDebitoria(idApplicazione, idPosizioneDebitoria))
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        applicaModifiche.accept(posizione);

        ValidatorePosizioneDebitoria.valida(posizione);
        assegnaOValidaNavNotifica(posizione);

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        posizione.setDataUltimoAggiornamento(adesso);
        posizione.setDataUltimaModificaAca(adesso);
        for (OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            for (Pendenza pendenza : opzione.getPendenze()) {
                pendenza.setDataUltimaModificaAca(adesso);
            }
        }

        return posizioneDebitoriaRepository.save(posizione);
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
    public PaginaRisultati<PosizioneDebitoria> cercaPerDebitore(String idA2A, String idDebitore, Pageable pageable) {
        Optional<Long> idApplicazione = risolviIdApplicazione(idA2A);
        if (idApplicazione.isEmpty()) {
            return new PaginaRisultati<>(List.of(), pageable.getOffset(), pageable.getPageSize(), 0);
        }
        return paginaDa(posizioneDebitoriaRepository.findDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo(
                idApplicazione.get(), idDebitore, pageable), pageable);
    }

    /**
     * Ricerca per numero avviso ({@code GET /pendenze/{idA2A}} dello YAML v3):
     * {@code numeroAvviso} e' l'unico criterio di ricerca, richiesto; {@code idDominio} e' un
     * filtro aggiuntivo opzionale, utilizzabile solo insieme a {@code numeroAvviso} (mai da
     * solo — coerente con lo YAML). Senza {@code idDominio} puo' restituire piu' risultati,
     * perche' lo stesso numero avviso puo' esistere legittimamente su domini diversi (M13).
     *
     * <p>Esclude le righe con {@code opzionePagamento} {@code NULL} (create da v2) gia' nella
     * query del repository, non con un filtro qui sopra — vedi Javadoc di
     * {@link PendenzaRepository#findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull}
     * per il perche' (un filtro dopo aver gia' paginato romperebbe {@code numRisultati}).</p>
     *
     * @param idA2A        identificativo del gestionale responsabile
     * @param numeroAvviso NAV: identificativo dell'avviso di pagamento pagoPA
     * @param idDominio    dominio creditore, o {@code null} per non filtrare per dominio
     * @param pageable     paginazione/ordinamento richiesti
     * @return la pagina di pendenze che rispettano il filtro
     */
    @Transactional(readOnly = true)
    public PaginaRisultati<Pendenza> cercaPendenze(String idA2A, String numeroAvviso, Long idDominio,
            Pageable pageable) {
        Optional<Long> idApplicazioneOpt = risolviIdApplicazione(idA2A);
        if (idApplicazioneOpt.isEmpty()) {
            return new PaginaRisultati<>(List.of(), pageable.getOffset(), pageable.getPageSize(), 0);
        }
        Long idApplicazione = idApplicazioneOpt.get();
        Page<Pendenza> pagina = idDominio == null
                ? pendenzaRepository.findByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull(idApplicazione,
                        numeroAvviso, pageable)
                : pendenzaRepository.findByIdApplicazioneAndNumeroAvvisoAndIdDominioAndOpzionePagamentoIsNotNull(
                        idApplicazione, numeroAvviso, idDominio, pageable);
        return paginaDa(pagina, pageable);
    }

    /**
     * Converte il {@link Page} di Spring Data (necessario internamente per la query con
     * conteggio) in {@link PaginaRisultati}: offset e limit vengono presi da {@code pageable}
     * (il valore effettivamente richiesto), non dai metodi derivati di {@code Page}
     * ({@code getNumber()}/{@code getSize()}), inaffidabili per un offset non allineato — vedi
     * Javadoc di {@link it.govpay.pendenze.criteri.OffsetPageRequest#getPageNumber()}.
     */
    private <T> PaginaRisultati<T> paginaDa(Page<T> pagina, Pageable pageable) {
        return new PaginaRisultati<>(pagina.getContent(), pageable.getOffset(), pageable.getPageSize(),
                pagina.getTotalElements());
    }

    /**
     * Come {@link #cercaPerDebitore}, ma senza {@code COUNT(*)} (paginazione a offset con
     * {@code total=false}): richiede al repository {@code limit + 1} righe per determinare
     * l'esistenza di una pagina successiva, senza calcolare il totale.
     */
    @Transactional(readOnly = true)
    public PaginaSenzaConteggio<PosizioneDebitoria> cercaPerDebitoreSenzaConteggio(String idA2A, String idDebitore,
            Pageable pageable) {
        Optional<Long> idApplicazione = risolviIdApplicazione(idA2A);
        if (idApplicazione.isEmpty()) {
            return new PaginaSenzaConteggio<>(List.of(), false);
        }
        Pageable pageablePiuUno = OffsetPageRequest.of(pageable.getOffset(), pageable.getPageSize() + 1,
                pageable.getSort());
        List<PosizioneDebitoria> grezzi = posizioneDebitoriaRepository
                .findAllDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo(idApplicazione.get(), idDebitore,
                        pageablePiuUno);
        return PaginaSenzaConteggio.daRisultatiGrezzi(grezzi, pageable.getPageSize());
    }

    /**
     * Paginazione a cursore (keyset) per le posizioni debitorie di un debitore, ordinamento
     * fisso {@code dataCreazione DESC, id DESC} — vedi Javadoc di
     * {@link PosizioneDebitoriaRepository#findByIdApplicazioneAndSoggettiDebitori_IdentificativoDaCursore}.
     *
     * @param cursorDataCreazione {@code dataCreazione} dell'ultimo elemento della pagina
     *                            precedente, o {@code null} alla prima pagina
     * @param cursorId            {@code id} dell'ultimo elemento della pagina precedente, o
     *                            {@code null} alla prima pagina
     */
    @Transactional(readOnly = true)
    public PaginaSenzaConteggio<PosizioneDebitoria> cercaPerDebitoreDaCursore(String idA2A, String idDebitore,
            OffsetDateTime cursorDataCreazione, Long cursorId, int limit) {
        Optional<Long> idApplicazione = risolviIdApplicazione(idA2A);
        if (idApplicazione.isEmpty()) {
            return new PaginaSenzaConteggio<>(List.of(), false);
        }
        List<PosizioneDebitoria> grezzi = posizioneDebitoriaRepository
                .findByIdApplicazioneAndSoggettiDebitori_IdentificativoDaCursore(idApplicazione.get(), idDebitore,
                        cursorDataCreazione, cursorId, OffsetPageRequest.of(0, limit + 1));
        return PaginaSenzaConteggio.daRisultatiGrezzi(grezzi, limit);
    }

    /**
     * Come {@link #cercaPendenze}, ma senza {@code COUNT(*)} (paginazione a offset con
     * {@code total=false}) — vedi Javadoc di {@link #cercaPerDebitoreSenzaConteggio} per il
     * meccanismo. Per questo endpoint il {@code COUNT} sarebbe comunque economico per
     * costruzione (IUV/NAV univoci per dominio, M13): la modalita' e' offerta per uniformita'
     * con {@code findPosizioniDebitorie} e con lo standard di paginazione condiviso con
     * govpay-console-api, non per necessita' di performance qui.
     */
    @Transactional(readOnly = true)
    public PaginaSenzaConteggio<Pendenza> cercaPendenzeSenzaConteggio(String idA2A, String numeroAvviso,
            Long idDominio, Pageable pageable) {
        Optional<Long> idApplicazioneOpt = risolviIdApplicazione(idA2A);
        if (idApplicazioneOpt.isEmpty()) {
            return new PaginaSenzaConteggio<>(List.of(), false);
        }
        Long idApplicazione = idApplicazioneOpt.get();
        Pageable pageablePiuUno = OffsetPageRequest.of(pageable.getOffset(), pageable.getPageSize() + 1,
                pageable.getSort());
        List<Pendenza> grezzi = idDominio == null
                ? pendenzaRepository.findAllByIdApplicazioneAndNumeroAvvisoAndOpzionePagamentoIsNotNull(
                        idApplicazione, numeroAvviso, pageablePiuUno)
                : pendenzaRepository.findAllByIdApplicazioneAndNumeroAvvisoAndIdDominioAndOpzionePagamentoIsNotNull(
                        idApplicazione, numeroAvviso, idDominio, pageablePiuUno);
        return PaginaSenzaConteggio.daRisultatiGrezzi(grezzi, pageable.getPageSize());
    }

    /**
     * Paginazione a cursore (keyset) per le pendenze di un numero avviso, ordinamento fisso
     * {@code dataCreazione DESC, id DESC} — vedi Javadoc di
     * {@link #cercaPerDebitoreDaCursore}.
     */
    @Transactional(readOnly = true)
    public PaginaSenzaConteggio<Pendenza> cercaPendenzeDaCursore(String idA2A, String numeroAvviso, Long idDominio,
            OffsetDateTime cursorDataCreazione, Long cursorId, int limit) {
        Optional<Long> idApplicazioneOpt = risolviIdApplicazione(idA2A);
        if (idApplicazioneOpt.isEmpty()) {
            return new PaginaSenzaConteggio<>(List.of(), false);
        }
        Long idApplicazione = idApplicazioneOpt.get();
        Pageable pageable = OffsetPageRequest.of(0, limit + 1);
        List<Pendenza> grezzi = idDominio == null
                ? pendenzaRepository.findByIdApplicazioneAndNumeroAvvisoDaCursore(idApplicazione, numeroAvviso,
                        cursorDataCreazione, cursorId, pageable)
                : pendenzaRepository.findByIdApplicazioneAndNumeroAvvisoAndIdDominioDaCursore(idApplicazione,
                        numeroAvviso, idDominio, cursorDataCreazione, cursorId, pageable);
        return PaginaSenzaConteggio.daRisultatiGrezzi(grezzi, limit);
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
