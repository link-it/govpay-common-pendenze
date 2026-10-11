package it.govpay.pendenze.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

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
import it.govpay.pendenze.exception.ModificaConcorrenteException;
import it.govpay.pendenze.exception.RisorsaGiaEsistenteException;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.OpzionePagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RendicontazioneRepository;
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
    private final RendicontazioneRepository rendicontazioneRepository;
    private final Clock clock;
    private final ObjectProvider<GeneratoreIuv> generatoreIuvProvider;

    /**
     * Iniettato per campo, non da costruttore (a differenza degli altri collaboratori di
     * questa classe): {@code @PersistenceContext} e' l'idioma standard JPA per
     * {@link EntityManager}, non un normale bean Spring — usato solo da {@link #attiva}/
     * {@link #annulla} per {@code entityManager.lock(..., OPTIMISTIC_FORCE_INCREMENT)} (vedi
     * i loro Javadoc).
     */
    @PersistenceContext
    private EntityManager entityManager;

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
            ApplicazioneRepository applicazioneRepository, RendicontazioneRepository rendicontazioneRepository,
            Clock clock, ObjectProvider<GeneratoreIuv> generatoreIuvProvider) {
        this.posizioneDebitoriaRepository = posizioneDebitoriaRepository;
        this.opzionePagamentoRepository = opzionePagamentoRepository;
        this.pendenzaRepository = pendenzaRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.rendicontazioneRepository = rendicontazioneRepository;
        this.clock = clock;
        this.generatoreIuvProvider = generatoreIuvProvider;
    }

    /**
     * Risolve {@code idA2A} (= {@code Applicazione.codApplicazione}) da
     * {@link PosizioneDebitoria#getIdApplicazione()}: da quando
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
     *                                {@code idA2A}+{@code idPosizioneDebitoria} (senza
     *                                questo controllo, due posizioni con la stessa chiave
     *                                logica ma dominio diverso verrebbero
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

                verificaIdPendenzaNonDuplicato(posizione, pendenza);
                assegnaIdentificativiPagamento(posizione, pendenza);
            }
        }

        assegnaOValidaNavNotifica(posizione);

        try {
            // saveAndFlush, non save: con id generato da
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
            // Rete di sicurezza contro le creazioni concorrenti: il controllo existsBy...
            // sopra e' un check-then-act, non atomico — due richieste
            // concorrenti con lo stesso idA2A+idPosizioneDebitoria possono superarlo entrambe.
            // La garanzia reale e' il vincolo DB unique_documenti_applicazione (migrazione
            // 01_documenti.sql, cod_documento+id_applicazione senza id_dominio, per far
            // corrispondere l'identita' pubblica al vincolo): qui se ne traduce la violazione
            // nella stessa eccezione del controllo esplicito, cosi' il chiamante vede sempre
            // RisorsaGiaEsistenteException e non un'eccezione di persistenza generica.
            //
            // Riconosce il vincolo specifico: tradurre QUALUNQUE
            // DataIntegrityViolationException in "risorsa gia' esistente" e' scorretto — un
            // altro vincolo violato (es. NOT NULL su una colonna non valorizzata, un bug
            // diverso) verrebbe mascherato da un 409 fuorviante invece di propagarsi come
            // l'errore che e' davvero.
            if (violaVincolo(e, VINCOLI_UNICITA_IDENTIFICATIVO)) {
                throw new RisorsaGiaEsistenteException(
                        "esiste gia' una posizione debitoria con idPosizioneDebitoria ["
                                + posizione.getIdPosizioneDebitoria() + "] per questa applicazione");
            }
            // Stessa rete di sicurezza, stessa motivazione, per il duplicato di idPendenza:
            // il controllo proattivo verificaIdPendenzaNonDuplicato
            // sopra e' anch'esso un check-then-act, non atomico.
            if (violaVincolo(e, VINCOLO_UNICITA_ID_PENDENZA)) {
                throw new RisorsaGiaEsistenteException(
                        "una pendenza di questa richiesta ha un idPendenza gia' usato da questa applicazione");
            }
            throw e;
        }
    }

    /**
     * Entrambi i vincoli UNIQUE reali su {@code documenti} che possono segnalare lo stesso
     * duplicato pubblico ({@code idA2A}+{@code idPosizioneDebitoria}): {@code
     * unique_documenti_applicazione} (cod_documento+id_applicazione, aggiunto in migrazione
     * per l'identita' pubblica) e {@code unique_documenti_1}
     * (cod_documento+id_applicazione+id_dominio, gia' presente nello schema legacy). Quando
     * il duplicato ha anche lo stesso {@code id_dominio}, ENTRAMBI i vincoli sono violati
     * dalla stessa riga — quale dei due il motore segnali dipende dall'ordine con cui
     * valuta gli indici, non e' deterministico lato applicativo: riconoscere solo
     * {@code unique_documenti_applicazione} lascerebbe questo caso — duplicato sullo stesso
     * dominio, individuato solo al flush — propagarsi come 500 anziche' 409.
     */
    private static final List<String> VINCOLI_UNICITA_IDENTIFICATIVO = List.of(
            "unique_documenti_applicazione", "unique_documenti_1");

    /**
     * Vincolo UNIQUE reale su {@code versamenti} ({@code cod_versamento_ente, id_applicazione}):
     * {@code idPendenza} e' univoco per applicazione, non per posizione — senza tradurre
     * questa violazione, riusare un idPendenza gia' esistente della stessa applicazione
     * fallirebbe con 500 invece che con una risposta applicativa.
     */
    private static final String VINCOLO_UNICITA_ID_PENDENZA = "unique_versamenti_1";

    /**
     * Riconosce se {@code e} e' dovuta specificamente a uno dei vincoli indicati, non a una
     * qualunque violazione di integrita'. {@code getConstraintName()} (dal
     * {@code org.hibernate.exception.ConstraintViolationException} sottostante) non e' sempre
     * popolato da ogni driver/dialetto — fallback sul testo del messaggio, che in pratica lo
     * riporta comunque (verificato su H2/PostgreSQL).
     */
    private boolean violaVincolo(DataIntegrityViolationException e, String... nomiVincoli) {
        return violaVincolo(e, List.of(nomiVincoli));
    }

    private boolean violaVincolo(DataIntegrityViolationException e, List<String> nomiVincoli) {
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
        return nomiVincoli.stream().anyMatch(daControllareMinuscolo::contains);
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
     * dichiarato {@code UNIQUE}), fa esattamente cosi'. Nessun vincolo {@code UNIQUE}
     * nuovo su {@code versamenti}, coerente con "minimizza le
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

    /**
     * {@code idPendenza} e' univoco per applicazione, non per posizione (vedi Javadoc di
     * {@link PendenzaRepository#existsByIdApplicazioneAndIdPendenza}) — senza questo
     * controllo, riusare l'idPendenza di una pendenza gia' esistente della stessa
     * applicazione (anche di un'ALTRA posizione) fallirebbe con 500 (violazione del vincolo
     * UNIQUE {@code unique_versamenti_1} mai tradotta), non con una risposta applicativa.
     * Controllo proattivo — la rete di sicurezza reattiva contro la finestra di corsa e' nel
     * {@code catch} di {@link #crea}/{@link #aggiungiOpzionePagamento}.
     */
    private void verificaIdPendenzaNonDuplicato(PosizioneDebitoria posizione, Pendenza pendenza) {
        if (pendenzaRepository.existsByIdApplicazioneAndIdPendenza(posizione.getIdApplicazione(),
                pendenza.getIdPendenza())) {
            throw new RisorsaGiaEsistenteException("esiste gia' una pendenza con idPendenza ["
                    + pendenza.getIdPendenza() + "] per questa applicazione");
        }
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
     * Ricerca per identificativo ({@code GET /pendenze/{idA2A}/{idPendenza}} dello YAML v3):
     * a differenza di {@link #cercaPendenze} (ricerca per {@code numeroAvviso}, l'identificativo
     * pagoPA), qui {@code idPendenza} e' l'identificativo proprio del gestionale — stessa
     * chiave univoca per applicazione di {@link PendenzaRepository#existsByIdApplicazioneAndIdPendenza}.
     *
     * <p>Esclude le pendenze prive di {@code opzionePagamento} (v2/migrazione: senza questo
     * filtro il mapper della REST API solleverebbe {@code IllegalStateException}, tradotta
     * in 500), stessa esclusione gia' applicata da
     * {@link #cercaPendenze} per la ricerca per numero avviso: lo YAML v3 non ha ancora un modo
     * di rappresentare una pendenza v2/migrazione, quindi per il chiamante e' come se non
     * esistesse (404), non un errore interno.</p>
     *
     * @param idA2A      identificativo del gestionale responsabile
     * @param idPendenza identificativo della pendenza nel gestionale
     * @return la pendenza, se esiste e ha un'opzione di pagamento
     */
    @Transactional(readOnly = true)
    public Optional<Pendenza> trovaPendenzaPerIdentificativo(String idA2A, String idPendenza) {
        return risolviIdApplicazione(idA2A)
                .flatMap(idApplicazione -> pendenzaRepository
                        .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, idPendenza));
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

        try {
            // saveAndFlush, non save — stesso motivo di crea()/aggiungiOpzionePagamento: da
            // quando PosizioneDebitoria ha un @Version (vedi Javadoc del campo),
            // un aggiornamento concorrente sulla stessa posizione (es.
            // un'attivazione o un'altra PATCH) puo' far fallire il salvataggio con un
            // conflitto di lock ottimistico — va intercettato qui, non lasciato propagare
            // grezzo fino al livello REST.
            return posizioneDebitoriaRepository.saveAndFlush(posizione);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new ModificaConcorrenteException("la posizione debitoria [" + idPosizioneDebitoria
                    + "] e' stata modificata concorrentemente: riprovare");
        }
    }

    /**
     * Aggiunge una nuova opzione di pagamento a una posizione debitoria esistente
     * ({@code POST .../posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento}
     * dello YAML v3). A differenza di {@link #aggiorna} (che muta l'aggregato gestito senza
     * restituire nulla di nuovo) qui il chiamante deve costruire l'opzione stessa — da cui
     * {@link Function}, non {@link Consumer}: la costruzione (conversione DTO→entita',
     * risoluzione di {@code idTipoPendenza}/tributo/IBAN) e' a carico del chiamante, che ha
     * bisogno di {@code posizione.getIdDominio()} per farlo correttamente (stesso principio
     * di {@link #aggiorna}: questo servizio non conosce i DTO REST del chiamante).
     *
     * <p><b>Ordine delle operazioni non banale</b>: gli
     * identificativi di pagamento (numero avviso/IUV, incluso l'eventuale
     * {@link #assegnaIdentificativiPagamento} che interroga il DB per unicita' —
     * {@link #verificaNumeroAvvisoNonDuplicato}) vengono assegnati alla nuova opzione MENTRE
     * e' ancora un oggetto autonomo, PRIMA di collegarla con
     * {@link PosizioneDebitoria#addOpzionePagamento} all'aggregato {@code posizione}, che qui
     * e' gia' un'entita' JPA gestita (caricata dal DB, a differenza di {@link #crea} dove
     * l'intero aggregato e' ancora transient fino al {@code save} finale). Se si collegasse
     * prima: una successiva query JPQL nella stessa sessione (es. proprio quella di
     * {@code verificaNumeroAvvisoNonDuplicato}, o quella interna a un {@link GeneratoreIuv})
     * innesca l'auto-flush di Hibernate, che scrive l'INSERT gia' pendente della nuova pendenza
     * (cascata dalla collezione CASCADE.ALL di {@code posizione}) PRIMA che la query stessa
     * venga eseguita — la query la trova quindi gia' su DB e la segnala come "duplicata di se
     * stessa". Al contrario, {@link ValidatorePosizioneDebitoria#valida} e {@code numeroRata}/
     * {@code indice} (assegnati qui direttamente sulla nuova opzione, non con
     * {@link #assegnaIndici} che opererebbe sull'intera posizione senza bisogno — l'ordinamento
     * e' comunque scoped per-opzione) sono puro Java, nessuna query: possono girare prima o
     * dopo l'aggancio senza rischio, e qui girano DOPO (serve la nuova opzione gia' collegata
     * per rivalidare l'aggregato completo, es. {@code numeroAvviso} duplicato con opzioni
     * gia' esistenti).</p>
     *
     * <p>Contropartita accettata di questo ordine: se la rivalidazione fallisce DOPO che un
     * identificativo e' stato generato da {@link GeneratoreIuv} (progressivo consumato), quel
     * valore resta "bucato" — stesso comportamento accettato altrove in questa libreria per i
     * progressivi pagoPA (mai pensati per essere densi/riusabili).</p>
     *
     * <p><b>Tre controlli aggiuntivi</b>:</p>
     * <ul>
     * <li>rifiuta se la posizione ha gia' un'opzione {@code ATTIVATA} (pagamento gia'
     * eseguito): un'alternativa aggiunta dopo quel momento non sarebbe mai passata per
     * l'annullamento automatico che scatta quando un'opzione si attiva (vedi Javadoc di
     * {@code StatoOpzionePagamento});</li>
     * <li>protezione dalla corsa fra questo metodo e {@link #attiva}/{@link #annulla} sulla
     * stessa posizione (il controllo sopra da solo legge uno snapshot, non basta): grazie a
     * {@link PosizioneDebitoria#getVersione()} (lock ottimistico), se un'attivazione
     * concorrente committa DOPO che questo metodo ha gia' superato il controllo ma PRIMA del
     * suo commit, il {@code saveAndFlush} fallisce con un conflitto di versione, tradotto in
     * {@link ModificaConcorrenteException} invece di lasciare un'alternativa orfana mai
     * annullata;</li>
     * <li>{@code idPendenza} duplicato (gia' usato da un'altra pendenza della stessa
     * applicazione, anche di un'altra posizione) — {@link #verificaIdPendenzaNonDuplicato}
     * (proattivo) + traduzione di {@code unique_versamenti_1} nel {@code catch} (reattivo,
     * stessa coppia di reti di sicurezza di {@link #crea}).</li>
     * </ul>
     *
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @param costruisciOpzione    costruisce la nuova opzione (con le sue pendenze/voci) a
     *                             partire dalla posizione risolta, NON ancora collegata
     *                             all'aggregato
     * @return la nuova opzione di pagamento, persistita
     * @throws RisorsaNonTrovataException        se non esiste una posizione con questa chiave
     * @throws TransizioneStatoNonAmmessaException se la posizione ha gia' un'opzione ATTIVATA
     * @throws ValidazioneNonSuperataException   se l'aggregato risultante non rispetta i
     *                                            vincoli semantici (stessi di {@link #crea})
     * @throws RisorsaGiaEsistenteException      se una pendenza della nuova opzione ha un
     *                                            {@code idPendenza} gia' usato da questa
     *                                            applicazione
     * @throws ModificaConcorrenteException      se la posizione e' stata modificata
     *                                            concorrentemente prima del commit
     */
    public OpzionePagamento aggiungiOpzionePagamento(String idA2A, String idPosizioneDebitoria,
            java.util.function.Function<PosizioneDebitoria, OpzionePagamento> costruisciOpzione) {
        PosizioneDebitoria posizione = risolviIdApplicazione(idA2A)
                .flatMap(idApplicazione -> posizioneDebitoriaRepository
                        .findByIdApplicazioneAndIdPosizioneDebitoria(idApplicazione, idPosizioneDebitoria))
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        // Un'alternativa aggiunta dopo che un'altra opzione e' gia'
        // ATTIVATA (pagamento gia' eseguito) contraddice la semantica dello YAML v3 — quando
        // un'opzione si attiva, tutte le altre DISPONIBILI vengono annullate automaticamente
        // perche' "non piu' applicabili" (vedi Javadoc di StatoOpzionePagamento): una nuova
        // alternativa creata DOPO quel momento sarebbe la stessa situazione, mai passata da
        // quell'annullamento automatico perche' non esisteva ancora. Controllo puramente in
        // memoria sulla collezione gia' caricata, nessuna query aggiuntiva.
        boolean esisteGiaUnaAttivata = posizione.getOpzioniPagamento().stream()
                .anyMatch(o -> o.getStato() == StatoOpzionePagamento.ATTIVATA);
        if (esisteGiaUnaAttivata) {
            throw new TransizioneStatoNonAmmessaException("la posizione debitoria [" + idPosizioneDebitoria
                    + "] ha gia' un'opzione di pagamento ATTIVATA (pagamento gia' eseguito): non e' piu' "
                    + "possibile aggiungere alternative");
        }

        OpzionePagamento nuovaOpzione = costruisciOpzione.apply(posizione);

        OffsetDateTime adesso = OffsetDateTime.now(clock);

        if (nuovaOpzione.getIdOpzionePagamento() == null) {
            nuovaOpzione.setIdOpzionePagamento(UUID.randomUUID());
        }
        if (nuovaOpzione.getStato() == null) {
            nuovaOpzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        }
        nuovaOpzione.setDataCreazione(adesso);
        nuovaOpzione.setDataUltimoAggiornamento(adesso);

        int numeroRata = 1;
        for (Pendenza pendenza : nuovaOpzione.getPendenze()) {
            pendenza.setNumeroRata(numeroRata++);
            pendenza.setDataCreazione(adesso);
            pendenza.setDataUltimoAggiornamento(adesso);
            pendenza.setDataUltimaModificaAca(adesso);
            // Stesso principio di crea(): IUV/NAV sono univoci per dominio, la pendenza deve
            // portare lo stesso dominio della posizione.
            pendenza.setIdDominio(posizione.getIdDominio());

            int indice = 1;
            for (VocePendenza voce : pendenza.getVoci()) {
                voce.setIndice(indice++);
                if (voce.getIdDominio() == null) {
                    voce.setIdDominio(posizione.getIdDominio());
                }
            }

            // Ancora una pendenza autonoma, non collegata a "posizione" (entita' gestita):
            // vedi Javadoc del metodo sul perche' l'ordine e' importante.
            verificaIdPendenzaNonDuplicato(posizione, pendenza);
            assegnaIdentificativiPagamento(posizione, pendenza);
        }

        posizione.addOpzionePagamento(nuovaOpzione);

        ValidatorePosizioneDebitoria.valida(posizione);

        posizione.setDataUltimoAggiornamento(adesso);
        posizione.setDataUltimaModificaAca(adesso);

        try {
            // saveAndFlush, non save — stessa ragione di crea() (intercettare qui la
            // violazione del vincolo invece di lasciarla propagare grezza al commit fuori da
            // questo metodo) per DUE reti di sicurezza reattive: il duplicato di idPendenza
            // (controllo proattivo sopra, ma
            // check-then-act) e il conflitto di lock ottimistico sulla posizione (il
            // controllo "nessuna opzione ATTIVATA" sopra legge uno snapshot che
            // un'attivazione concorrente puo' rendere obsoleto prima del commit — vedi
            // Javadoc di {@code PosizioneDebitoria#getVersione()}).
            posizioneDebitoriaRepository.saveAndFlush(posizione);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new ModificaConcorrenteException("la posizione debitoria [" + idPosizioneDebitoria
                    + "] e' stata modificata concorrentemente (es. un pagamento appena registrato): riprovare");
        } catch (DataIntegrityViolationException e) {
            if (violaVincolo(e, VINCOLO_UNICITA_ID_PENDENZA)) {
                throw new RisorsaGiaEsistenteException(
                        "la nuova opzione ha una pendenza con idPendenza gia' usato da questa applicazione");
            }
            throw e;
        }
        return nuovaOpzione;
    }

    /**
     * Aggiunge una pendenza a un'opzione di pagamento ESISTENTE
     * ({@code POST .../posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/
     * {idOpzionePagamento}/pendenze} dello YAML v3) — richiesta esplicita di questa evolutiva,
     * nessun corrispettivo diretto nel legacy (dove le pendenze/rate si caricano sempre
     * insieme all'opzione, non aggiunte in un secondo momento).
     *
     * <p>Permesso solo se l'opzione e' ancora {@code DISPONIBILE}: un'opzione {@code ATTIVATA}
     * (pagamento gia' eseguito) o {@code ANNULLATA} non ammette piu' modifiche al suo piano di
     * pendenze.</p>
     *
     * <p><b>Nessun controllo esplicito sulla tipologia qui</b>: la cardinalita' massima
     * ({@code TipologiaOpzionePagamento.cardinalitaPendenzeMassima()} — {@code null} per
     * {@code SOLUZIONE_UNICA}/{@code PIANO_RATEALE} (nessun limite), {@code 1} per
     * {@code SOLUZIONE_UNICA_ENTRO}/{@code OLTRE}) e' gia' verificata da
     * {@code ValidatorePosizioneDebitoria.validaOpzione}, eseguita qui come ovunque dopo aver
     * collegato la nuova pendenza all'opzione: un tentativo su un'opzione gia' al massimo (es.
     * una {@code SOLUZIONE_UNICA_ENTRO} che ne ha gia' una) viene quindi rifiutato dalla stessa
     * fonte unica usata in creazione, non da un controllo duplicato qui.</p>
     *
     * <p>Stesso controllo di appartenenza di {@link #annulla(String, String, UUID)}: l'opzione
     * deve appartenere davvero a questa posizione/applicazione, altrimenti un chiamante
     * autenticato come un'altra applicazione potrebbe aggiungere una pendenza a un'opzione che
     * non e' sua semplicemente indovinandone lo UUID.</p>
     *
     * <p>Stessa preparazione per-pendenza di {@link #aggiungiOpzionePagamento} (identificativi
     * di pagamento, indici voci, dominio ereditato dalla posizione), qui per una sola pendenza
     * aggiunta a un'opzione gia' persistita: {@code numeroRata} continua la sequenza esistente
     * (massimo + 1), non riparte da 1.</p>
     *
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @param idOpzionePagamento   identificativo dell'opzione a cui aggiungere la pendenza
     * @param costruisciPendenza   costruisce la nuova pendenza (con le sue voci) a partire
     *                             dall'opzione risolta, NON ancora collegata all'aggregato
     * @return la nuova pendenza, persistita
     * @throws RisorsaNonTrovataException          se l'opzione non esiste, o esiste ma non
     *                                              appartiene a questa posizione/applicazione
     * @throws TransizioneStatoNonAmmessaException se l'opzione non e' {@code DISPONIBILE}
     * @throws ValidazioneNonSuperataException     se l'aggregato risultante non rispetta i
     *                                              vincoli semantici (incluso il superamento
     *                                              della cardinalita' massima di pendenze per
     *                                              questa tipologia di opzione)
     * @throws RisorsaGiaEsistenteException        se la nuova pendenza ha un {@code idPendenza}
     *                                              gia' usato da questa applicazione
     * @throws ModificaConcorrenteException        se la posizione e' stata modificata
     *                                              concorrentemente prima del commit
     */
    public Pendenza aggiungiPendenza(String idA2A, String idPosizioneDebitoria, UUID idOpzionePagamento,
            Function<OpzionePagamento, Pendenza> costruisciPendenza) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);
        PosizioneDebitoria posizione = opzione.getPosizioneDebitoria();
        boolean appartiene = idPosizioneDebitoria.equals(posizione.getIdPosizioneDebitoria())
                && idA2A.equals(risolviIdA2A(posizione.getIdApplicazione()));
        if (!appartiene) {
            throw new RisorsaNonTrovataException("nessuna opzione di pagamento [" + idOpzionePagamento
                    + "] per idPosizioneDebitoria [" + idPosizioneDebitoria + "] e idA2A [" + idA2A + "]");
        }
        if (opzione.getStato() != StatoOpzionePagamento.DISPONIBILE) {
            throw new TransizioneStatoNonAmmessaException("l'opzione di pagamento [" + idOpzionePagamento + "] e' "
                    + opzione.getStato() + ": si puo' aggiungere una pendenza solo se DISPONIBILE");
        }

        Pendenza nuovaPendenza = costruisciPendenza.apply(opzione);

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        int numeroRata = opzione.getPendenze().stream().mapToInt(Pendenza::getNumeroRata).max().orElse(0) + 1;
        nuovaPendenza.setNumeroRata(numeroRata);
        nuovaPendenza.setDataCreazione(adesso);
        nuovaPendenza.setDataUltimoAggiornamento(adesso);
        nuovaPendenza.setDataUltimaModificaAca(adesso);
        // Stesso principio di crea()/aggiungiOpzionePagamento: IUV/NAV sono univoci per
        // dominio, la pendenza deve portare lo stesso dominio della posizione.
        nuovaPendenza.setIdDominio(posizione.getIdDominio());

        int indice = 1;
        for (VocePendenza voce : nuovaPendenza.getVoci()) {
            voce.setIndice(indice++);
            if (voce.getIdDominio() == null) {
                voce.setIdDominio(posizione.getIdDominio());
            }
        }

        // Ancora una pendenza autonoma, non collegata a "opzione" (entita' gestita): stesso
        // ordine (e stesso motivo) di aggiungiOpzionePagamento, vedi il suo Javadoc.
        verificaIdPendenzaNonDuplicato(posizione, nuovaPendenza);
        assegnaIdentificativiPagamento(posizione, nuovaPendenza);

        opzione.addPendenza(nuovaPendenza);

        ValidatorePosizioneDebitoria.valida(posizione);

        posizione.setDataUltimoAggiornamento(adesso);
        posizione.setDataUltimaModificaAca(adesso);
        opzione.setDataUltimoAggiornamento(adesso);

        try {
            posizioneDebitoriaRepository.saveAndFlush(posizione);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new ModificaConcorrenteException("la posizione debitoria [" + idPosizioneDebitoria
                    + "] e' stata modificata concorrentemente: riprovare");
        } catch (DataIntegrityViolationException e) {
            if (violaVincolo(e, VINCOLO_UNICITA_ID_PENDENZA)) {
                throw new RisorsaGiaEsistenteException(
                        "la nuova pendenza ha un idPendenza gia' usato da questa applicazione");
            }
            throw e;
        }
        return nuovaPendenza;
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
     * <p><b>Privato, non piu' un metodo pubblico del service</b>: l'attivazione e'
     * concettualmente un EFFETTO del pagamento di una pendenza (e quindi della scelta di
     * un'opzione), non un'azione indipendente — se la congruenza fra
     * {@code OpzionePagamento.stato} e gli stati delle pendenze fosse sempre garantita (ed
     * e' esattamente quello che {@link #verificaAlmenoUnaPendenzaEseguita}/
     * {@link #verificaNessunaPendenzaEseguita}/{@link #verificaTutteLePendenzeNelloStato}
     * fanno rispettare), un'opzione {@code DISPONIBILE} avrebbe per definizione tutte le
     * pendenze {@code NON_ESEGUITO}: chiamare questo metodo da solo, su un'opzione
     * genuinamente congruente, fallirebbe sempre (nessuna pendenza "eseguita" da trovare).
     * Riesce solo perche' {@link #registraEsitoPendenza} muta la pendenza PRIMA di
     * richiamarlo, nella stessa transazione, sullo stesso oggetto gestito — una violazione
     * transitoria e intenzionale della congruenza, interna a un'unica operazione atomica,
     * non un contratto pubblico indipendente. {@link #registraEsitoPendenza} e' quindi
     * l'unico chiamante legittimo.</p>
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
     * <p><b>Nota per il futuro motore di registrazione pagamenti</b> (verificato con una
     * prova a due transazioni sovrapposte: il conflitto viene rilevato correttamente,
     * l'operazione perdente va in eccezione): questo metodo NON cattura ne' traduce il
     * conflitto di lock ottimistico, lo lascia propagare grezzo
     * ({@code ObjectOptimisticLockingFailureException}), e lo stesso fa oggi
     * {@link #registraEsitoPendenza} — corretto per un endpoint REST sincrono (dove il
     * livello REST puo' tradurlo in 409 e il client puo' decidere se riprovare, vedi
     * {@code ProblemExceptionHandler} in govpay-pendenze-api), ma SBAGLIATO per un futuro
     * motore di registrazione pagamenti guidato da una notifica pagoPA: quel chiamante non
     * ha un client interattivo a cui delegare la decisione — dovrebbe invece rileggere
     * l'aggregato e rieseguire l'intera transazione applicativa (non solo questa chiamata),
     * con un numero limitato di tentativi, prima di arrendersi in modo osservabile (es. un
     * evento di errore). {@link #registraEsitoPendenza} e' volutamente il percorso minimo
     * (vedi il suo Javadoc) e non implementa ancora questa strategia di retry.</p>
     *
     * <p><b>Lock esplicito sulla posizione</b>: {@code LockModeType.OPTIMISTIC_FORCE_INCREMENT} su
     * {@code opzione.getPosizioneDebitoria()} forza l'incremento di
     * {@link PosizioneDebitoria#getVersione()} a questo commit, indipendentemente da quali
     * campi propri della posizione vengano toccati. Senza questa richiesta esplicita,
     * l'incremento sarebbe dipeso implicitamente dal fatto che {@link #marcaModificaAca}
     * scrive {@code posizione.dataUltimaModificaAca} — un accoppiamento fragile fra due
     * concern non correlati (marcatura ACA vs. controllo di concorrenza): se in futuro
     * quella scrittura cambiasse o sparisse, la protezione sulla posizione smetterebbe di
     * funzionare in silenzio. Protegge {@link PosizioneDebitoriaService#aggiungiOpzionePagamento}
     * dall'aggiungere un'alternativa DISPONIBILE in corsa con un'attivazione qui.</p>
     *
     * @param idOpzionePagamento identificativo dell'opzione che risulta pagata
     * @return l'opzione appena attivata
     * @throws RisorsaNonTrovataException        se l'opzione non esiste
     * @throws TransizioneStatoNonAmmessaException se l'opzione non e' {@code DISPONIBILE}, o
     *                                              se nessuna pendenza e' eseguita
     */
    private OpzionePagamento attiva(UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);
        if (opzione.getStato() != StatoOpzionePagamento.DISPONIBILE) {
            throw new TransizioneStatoNonAmmessaException(
                    "l'opzione di pagamento [" + idOpzionePagamento + "] non e' DISPONIBILE (stato attuale: "
                            + opzione.getStato() + "): non puo' essere attivata");
        }
        verificaAlmenoUnaPendenzaEseguita(opzione, idOpzionePagamento);
        entityManager.lock(opzione.getPosizioneDebitoria(), LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        opzione.setStato(StatoOpzionePagamento.ATTIVATA);
        opzione.setDataUltimoAggiornamento(adesso);
        marcaModificaAca(opzione, adesso);

        for (OpzionePagamento altra : opzione.getPosizioneDebitoria().getOpzioniPagamento()) {
            if (!altra.getId().equals(opzione.getId()) && altra.getStato() == StatoOpzionePagamento.DISPONIBILE) {
                verificaNessunaPendenzaEseguita(altra, altra.getIdOpzionePagamento(), "annullata");
                altra.setStato(StatoOpzionePagamento.ANNULLATA);
                altra.setDataUltimoAggiornamento(adesso);
                marcaModificaAca(altra, adesso);
                cascataStatoPendenze(altra, StatoPendenza.ANNULLATO, adesso);
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
     * <p><b>Cascata sulle pendenze</b>: imposta {@code ANNULLATO} su tutte le pendenze
     * dell'opzione (vedi {@link #cascataStatoPendenze}) — l'unico punto di scrittura dove
     * lo stato di una pendenza cambia a seguito dell'opzione, dato che la v3 non espone piu'
     * un annullamento indipendente della singola pendenza (deciso dopo aver verificato che
     * il legacy, al contrario, tratta lo stato come proprieta' del singolo versamento: qui
     * si e' scelto deliberatamente il livello dell'opzione come unica fonte di verita'). Prima
     * di eseguirla, {@link #verificaNessunaPendenzaEseguita} verifica che nessuna pendenza sia
     * gia' in uno stato diverso da {@code NON_ESEGUITO}/{@code ANNULLATO} — senza, la cascata
     * sovrascriverebbe silenziosamente una pendenza realmente eseguita, scenario riprodotto e
     * segnalato in revisione (il commento precedente assumeva l'invariante senza che il codice
     * la garantisse). Controllo eseguito anche sul ramo idempotente (opzione gia'
     * {@code ANNULLATA}): un disallineamento va segnalato comunque, non ignorato solo perche'
     * non ci sarebbe nulla da mutare.</p>
     *
     * <p>Stesso lock esplicito di {@link #attiva(UUID)} sulla posizione — vedi il suo
     * Javadoc — tranne quando il metodo e' idempotente (opzione gia' {@code ANNULLATA}):
     * in quel caso non c'e' alcuna mutazione, quindi nessun bisogno di forzare un
     * incremento di versione.</p>
     *
     * <p><b>Privato</b>: {@code idOpzionePagamento} da solo non e' legato a nessun controllo
     * di appartenenza — qualunque chiamante esterno (govpay-pendenze-api, la futura
     * govpay-console-api) deve passare per {@link #annulla(String, String, UUID)}, che
     * verifica prima che l'opzione appartenga davvero a quella posizione/applicazione.
     * Questo resta il metodo di base su cui quello scoped si appoggia.</p>
     *
     * @param idOpzionePagamento identificativo dell'opzione da annullare
     * @return l'opzione annullata
     * @throws RisorsaNonTrovataException        se l'opzione non esiste
     * @throws TransizioneStatoNonAmmessaException se l'opzione e' {@code ATTIVATA}, o se una
     *                                              sua pendenza e' gia' in uno stato diverso
     *                                              da {@code NON_ESEGUITO}/{@code ANNULLATO}
     */
    private OpzionePagamento annulla(UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);

        if (opzione.getStato() == StatoOpzionePagamento.ATTIVATA) {
            throw new TransizioneStatoNonAmmessaException(
                    "l'opzione di pagamento [" + idOpzionePagamento
                            + "] e' ATTIVATA: corrisponde a un pagamento gia' eseguito, non puo' essere annullata");
        }
        if (opzione.getStato() == StatoOpzionePagamento.ANNULLATA) {
            verificaTutteLePendenzeNelloStato(opzione, StatoPendenza.ANNULLATO, idOpzionePagamento, "annullata");
            return opzione;
        }
        verificaNessunaPendenzaEseguita(opzione, idOpzionePagamento, "annullata");
        entityManager.lock(opzione.getPosizioneDebitoria(), LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        opzione.setStato(StatoOpzionePagamento.ANNULLATA);
        opzione.setDataUltimoAggiornamento(adesso);
        marcaModificaAca(opzione, adesso);
        cascataStatoPendenze(opzione, StatoPendenza.ANNULLATO, adesso);
        return opzione;
    }

    /**
     * Ripristina a {@code DISPONIBILE} un'opzione di pagamento {@code ANNULLATA} —
     * simmetrico ad {@link #annulla(UUID)}: stesso guard (rifiuta se {@code ATTIVATA}),
     * stessa verifica di congruenza sulle pendenze e stessa cascata (qui a
     * {@code NON_ESEGUITO}, vedi {@link #cascataStatoPendenze}). Idempotente se gia'
     * {@code DISPONIBILE}.
     *
     * <p><b>Verifica anche l'assenza di un'altra opzione {@code ATTIVATA}</b> nella stessa
     * posizione ({@link #verificaNessunAltraOpzioneAttivata}): senza questo controllo si
     * potrebbe ripristinare un'alternativa gia' esclusa automaticamente da {@link #attiva}
     * (vedi il suo ciclo sulle "altre" opzioni), vanificando l'esclusione reciproca — scenario
     * riprodotto e segnalato in revisione (attiva(A) → B annullata automaticamente →
     * ripristina(B) la riporta DISPONIBILE con A ancora ATTIVATA, rendendo B di nuovo
     * attivabile). Controllo e lock PRIMA del possibile ritorno idempotente (a differenza di
     * {@link #annulla(UUID)}, che per questo non puo' saltare il lock come fa il suo ramo
     * idempotente): la lettura delle "altre" opzioni deve restare protetta dalla stessa
     * garanzia di concorrenza di {@link #attiva(UUID)} (incremento forzato della versione
     * della posizione), altrimenti una {@code attiva(UUID)} concorrente su un'altra opzione
     * potrebbe commitare fra questa lettura e la mutazione senza essere rilevata.</p>
     *
     * <p><b>Privato</b>, stesso motivo di {@link #annulla(UUID)}: qualunque chiamante
     * esterno deve passare per {@link #ripristina(String, String, UUID)}, che verifica
     * l'appartenenza dell'opzione.</p>
     *
     * @param idOpzionePagamento identificativo dell'opzione da ripristinare
     * @return l'opzione ripristinata
     * @throws RisorsaNonTrovataException        se l'opzione non esiste
     * @throws TransizioneStatoNonAmmessaException se l'opzione e' {@code ATTIVATA}, se un'altra
     *                                              opzione della stessa posizione e'
     *                                              {@code ATTIVATA}, o se una pendenza e' gia'
     *                                              in uno stato diverso da
     *                                              {@code NON_ESEGUITO}/{@code ANNULLATO}
     */
    private OpzionePagamento ripristina(UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);

        if (opzione.getStato() == StatoOpzionePagamento.ATTIVATA) {
            throw new TransizioneStatoNonAmmessaException(
                    "l'opzione di pagamento [" + idOpzionePagamento
                            + "] e' ATTIVATA: corrisponde a un pagamento gia' eseguito, non puo' essere ripristinata");
        }
        entityManager.lock(opzione.getPosizioneDebitoria(), LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        verificaNessunAltraOpzioneAttivata(opzione, idOpzionePagamento);
        if (opzione.getStato() == StatoOpzionePagamento.DISPONIBILE) {
            verificaTutteLePendenzeNelloStato(opzione, StatoPendenza.NON_ESEGUITO, idOpzionePagamento,
                    "ripristinata");
            return opzione;
        }
        verificaNessunaPendenzaEseguita(opzione, idOpzionePagamento, "ripristinata");

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataUltimoAggiornamento(adesso);
        marcaModificaAca(opzione, adesso);
        cascataStatoPendenze(opzione, StatoPendenza.NON_ESEGUITO, adesso);
        return opzione;
    }

    /**
     * {@code true} se lo stato e' diverso da {@code NON_ESEGUITO}/{@code ANNULLATO} — cioe'
     * se descrive un qualunque esito di pagamento registrato da pagoPA (eseguita,
     * parzialmente eseguita, anomala, ecc.). Base dell'invariante fra
     * {@code OpzionePagamento.stato} e gli stati delle sue pendenze: {@code DISPONIBILE} e
     * {@code ANNULLATA} richiedono che NESSUNA pendenza la soddisfi, {@code ATTIVATA} richiede
     * che ALMENO UNA la soddisfi. Usato sia su uno stato gia' persistito ({@link #pendenzaEseguita})
     * sia su un {@code nuovoStato} ancora da validare ({@link #registraEsitoPendenza}).
     */
    private static boolean statoEseguito(StatoPendenza stato) {
        return stato != StatoPendenza.NON_ESEGUITO && stato != StatoPendenza.ANNULLATO;
    }

    private static boolean pendenzaEseguita(Pendenza pendenza) {
        return statoEseguito(pendenza.getStato());
    }

    /**
     * Precondizione di {@link #annulla(UUID)}/{@link #ripristina(UUID)} (anche sul ramo
     * dell'"altra" opzione annullata automaticamente da {@link #attiva(UUID)}): nessuna
     * pendenza dell'opzione deve essere gia' {@link #pendenzaEseguita(Pendenza) eseguita},
     * altrimenti la cascata sulle pendenze la sovrascriverebbe silenziosamente.
     *
     * @param azione participio usato nel messaggio ("annullata"/"ripristinata")
     * @throws TransizioneStatoNonAmmessaException se almeno una pendenza e' eseguita
     */
    private void verificaNessunaPendenzaEseguita(OpzionePagamento opzione, UUID idOpzionePagamento, String azione) {
        boolean qualcunaEseguita = opzione.getPendenze().stream().anyMatch(PosizioneDebitoriaService::pendenzaEseguita);
        if (qualcunaEseguita) {
            throw new TransizioneStatoNonAmmessaException("l'opzione di pagamento [" + idOpzionePagamento
                    + "] ha almeno una pendenza in uno stato diverso da NON_ESEGUITO/ANNULLATO: non puo' essere "
                    + azione + " senza sovrascrivere silenziosamente quel dato");
        }
    }

    /**
     * Precondizione dei rami idempotenti di {@link #annulla(UUID)}/{@link #ripristina(UUID)}
     * (opzione gia' nello stato di destinazione, nessuna cascata verrebbe applicata): a
     * differenza di {@link #verificaNessunaPendenzaEseguita} (che accetta qualunque mix di
     * {@code NON_ESEGUITO}/{@code ANNULLATO}, corretto solo come precondizione di una
     * transizione REALE, perche' la cascata seguente uniforma comunque tutte le pendenze),
     * qui serve la congruenza ESATTA — {@code statoAtteso} identico su tutte le pendenze —
     * perche' nessuna cascata sta per essere eseguita: un mix non verrebbe mai corretto.
     * Senza questo controllo specifico, es. una pendenza rimasta {@code NON_ESEGUITO} mentre
     * l'opzione e' gia' {@code ANNULLATA} (o viceversa una {@code ANNULLATO} mentre l'opzione
     * e' gia' {@code DISPONIBILE}) attraverserebbe il ritorno idempotente senza essere
     * rilevata.
     *
     * @param statoAtteso stato che OGNI pendenza deve avere ({@code ANNULLATO} per
     *                    {@code ANNULLATA}, {@code NON_ESEGUITO} per {@code DISPONIBILE})
     * @param azione      participio usato nel messaggio ("annullata"/"ripristinata")
     * @throws TransizioneStatoNonAmmessaException se almeno una pendenza non e' nello stato atteso
     */
    private void verificaTutteLePendenzeNelloStato(OpzionePagamento opzione, StatoPendenza statoAtteso,
            UUID idOpzionePagamento, String azione) {
        boolean tutteCongruenti = opzione.getPendenze().stream().allMatch(p -> p.getStato() == statoAtteso);
        if (!tutteCongruenti) {
            throw new TransizioneStatoNonAmmessaException("l'opzione di pagamento [" + idOpzionePagamento
                    + "] e' gia' " + opzione.getStato() + " ma almeno una delle sue pendenze non e' " + statoAtteso
                    + ": disallineamento rilevato, richiesta di " + azione + " rifiutata");
        }
    }

    /**
     * Precondizione di {@link #attiva(UUID)}: l'opzione deve avere ALMENO UNA pendenza
     * {@link #pendenzaEseguita(Pendenza) eseguita} — altrimenti diventerebbe {@code ATTIVATA}
     * senza che nulla lo giustifichi, violando l'invariante nel verso opposto a
     * {@link #verificaNessunaPendenzaEseguita}.
     *
     * @throws TransizioneStatoNonAmmessaException se nessuna pendenza e' eseguita
     */
    private void verificaAlmenoUnaPendenzaEseguita(OpzionePagamento opzione, UUID idOpzionePagamento) {
        boolean qualcunaEseguita = opzione.getPendenze().stream().anyMatch(PosizioneDebitoriaService::pendenzaEseguita);
        if (!qualcunaEseguita) {
            throw new TransizioneStatoNonAmmessaException("l'opzione di pagamento [" + idOpzionePagamento
                    + "] non ha nessuna pendenza in uno stato diverso da NON_ESEGUITO/ANNULLATO: non puo' essere "
                    + "attivata senza che almeno una pendenza risulti eseguita");
        }
    }

    /**
     * Precondizione di {@link #ripristina(UUID)}: nessun'altra opzione della stessa posizione
     * deve essere {@code ATTIVATA} — altrimenti si riabiliterebbe un'alternativa gia' esclusa
     * da {@link #attiva(UUID)} dopo un pagamento effettivo, vanificando la mutua esclusione.
     *
     * @throws TransizioneStatoNonAmmessaException se un'altra opzione e' ATTIVATA
     */
    private void verificaNessunAltraOpzioneAttivata(OpzionePagamento opzione, UUID idOpzionePagamento) {
        boolean altraAttivata = opzione.getPosizioneDebitoria().getOpzioniPagamento().stream()
                .anyMatch(altra -> !altra.getId().equals(opzione.getId())
                        && altra.getStato() == StatoOpzionePagamento.ATTIVATA);
        if (altraAttivata) {
            throw new TransizioneStatoNonAmmessaException("l'opzione di pagamento [" + idOpzionePagamento
                    + "] non puo' essere ripristinata: un'altra opzione della stessa posizione e' ATTIVATA "
                    + "(pagamento gia' eseguito tramite un'alternativa)");
        }
    }

    private OpzionePagamento trovaOpzionePagamento(UUID idOpzionePagamento) {
        return opzionePagamentoRepository.findByIdOpzionePagamento(idOpzionePagamento)
                .orElseThrow(() -> new RisorsaNonTrovataException(
                        "nessuna opzione di pagamento con identificativo [" + idOpzionePagamento + "]"));
    }

    /**
     * Come {@link #annulla(UUID)}, ma per il percorso REST
     * ({@code PATCH .../posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}}
     * dello YAML v3), dove {@code idOpzionePagamento} arriva dal path insieme a
     * {@code idA2A}/{@code idPosizioneDebitoria}: verifica che l'opzione appartenga davvero a
     * QUELLA posizione/applicazione prima di annullarla — altrimenti un chiamante autenticato
     * come applicazione "A" potrebbe annullare un'opzione dell'applicazione "B" semplicemente
     * indovinandone lo UUID (che non e' altrimenti legato a nessun controllo di appartenenza).
     * {@link #annulla(UUID)} resta il metodo di base, privato: qualunque chiamante (anche
     * interno, es. un futuro processo di registrazione pagamenti) deve comunque passare da
     * qui, perche' la verifica di appartenenza non e' mai opzionale — l'esperienza con
     * {@link #attiva(UUID)} (idem privato) mostra che un metodo pubblico "per un futuro
     * chiamante" senza quel chiamante reale e' solo superficie non verificata.
     *
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @param idOpzionePagamento   identificativo dell'opzione da annullare
     * @return l'opzione annullata
     * @throws RisorsaNonTrovataException          se l'opzione non esiste, o esiste ma non
     *                                              appartiene a questa posizione/applicazione
     *                                              (stesso trattamento: nessuna delle due
     *                                              informazioni va rivelata a un chiamante non
     *                                              autorizzato su quell'opzione)
     * @throws TransizioneStatoNonAmmessaException se l'opzione e' {@code ATTIVATA}
     */
    public OpzionePagamento annulla(String idA2A, String idPosizioneDebitoria, UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);
        PosizioneDebitoria posizione = opzione.getPosizioneDebitoria();
        boolean appartiene = idPosizioneDebitoria.equals(posizione.getIdPosizioneDebitoria())
                && idA2A.equals(risolviIdA2A(posizione.getIdApplicazione()));
        if (!appartiene) {
            throw new RisorsaNonTrovataException("nessuna opzione di pagamento [" + idOpzionePagamento
                    + "] per idPosizioneDebitoria [" + idPosizioneDebitoria + "] e idA2A [" + idA2A + "]");
        }
        return annulla(idOpzionePagamento);
    }

    /**
     * Come {@link #ripristina(UUID)}, ma per il percorso REST — mirror esatto di
     * {@link #annulla(String, String, UUID)}, stesso controllo di appartenenza.
     *
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @param idOpzionePagamento   identificativo dell'opzione da ripristinare
     * @return l'opzione ripristinata
     * @throws RisorsaNonTrovataException          se l'opzione non esiste, o esiste ma non
     *                                              appartiene a questa posizione/applicazione
     * @throws TransizioneStatoNonAmmessaException se l'opzione e' {@code ATTIVATA}
     */
    public OpzionePagamento ripristina(String idA2A, String idPosizioneDebitoria, UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);
        PosizioneDebitoria posizione = opzione.getPosizioneDebitoria();
        boolean appartiene = idPosizioneDebitoria.equals(posizione.getIdPosizioneDebitoria())
                && idA2A.equals(risolviIdA2A(posizione.getIdApplicazione()));
        if (!appartiene) {
            throw new RisorsaNonTrovataException("nessuna opzione di pagamento [" + idOpzionePagamento
                    + "] per idPosizioneDebitoria [" + idPosizioneDebitoria + "] e idA2A [" + idA2A + "]");
        }
        return ripristina(idOpzionePagamento);
    }

    /**
     * Registra l'esito di un pagamento su una pendenza e, nella STESSA transazione,
     * determina se la sua opzione deve diventare {@code ATTIVATA} — se sì, applica la
     * stessa cascata di {@link #attiva(UUID)} (annullamento automatico delle alternative e
     * delle loro pendenze). Chiude il verso dell'invariante mancante finora: i controlli
     * aggiunti ad {@link #attiva(UUID)}/{@link #annulla(UUID)}/{@link #ripristina(UUID)}
     * impediscono alcune transizioni incoerenti, ma da soli NON garantiscono che aggiornare
     * una pendenza allinei l'opzione — serve un percorso che lo faccia esplicitamente, in
     * un'unica operazione di questo service, cosi' un chiamante (es. il futuro motore di
     * registrazione pagamenti, o govpay-console-api) non deve ricordarsi di invocare
     * separatamente {@code attiva(UUID)} dopo aver scritto la pendenza. Deliberatamente non
     * delegato al setter JPA della pendenza (nessun listener/callback): l'orchestrazione resta
     * esplicita qui, non implicita nell'entita'.
     *
     * <p><b>Scope volutamente minimo</b>: aggiorna solo {@code pendenza.stato} (e
     * {@code dataUltimoAggiornamento}) — non e' il futuro motore di registrazione pagamenti
     * completo (che dovra' scrivere anche {@code importoPagato}/{@code dataPagamento}/
     * {@code iuvPagamento}/ecc., vedi la voce U9 della tabella di mapping legacy in
     * {@code proposta-libreria-pendenze.md}): e' il percorso minimo che garantisce il
     * coordinamento richiesto, su cui quel futuro motore potra' appoggiarsi o che potra'
     * estendere.</p>
     *
     * <p>Rifiuta {@code nuovoStato} nullo o uguale a {@code NON_ESEGUITO}/{@code ANNULLATO}:
     * questo metodo registra un ESITO di pagamento, non una transizione qualunque — quelle
     * due si gestiscono tramite {@link #annulla(UUID)}/{@link #ripristina(UUID)}
     * sull'opzione, mai scrivendo direttamente sulla singola pendenza (esattamente il
     * percorso indipendente che la v3 ha deliberatamente eliminato: senza questo controllo,
     * {@code registraEsitoPendenza(..., NON_ESEGUITO)} lascerebbe l'opzione
     * incoerentemente {@code ATTIVATA} con la pendenza "appena pagata" tornata
     * {@code NON_ESEGUITO}, e {@code registraEsitoPendenza(..., ANNULLATO)} reintrodurrebbe
     * l'annullamento della singola pendenza da una porta laterale).</p>
     *
     * <p>Rifiuta se l'opzione della pendenza e' {@code ANNULLATA} (stato definitivo): non ha
     * senso registrare un esito di pagamento su una pendenza la cui opzione e' stata esclusa
     * come alternativa non piu' applicabile.</p>
     *
     * <p>Se l'opzione e' invece gia' {@code ATTIVATA} (es. un'altra pendenza era gia' stata
     * eseguita prima — caso tipico: seconda rata di un piano rateale dopo l'attivazione
     * sulla prima), {@link #attiva(UUID)} non viene richiamato una seconda volta — non ha
     * senso ripetere la cascata sulle "altre" opzioni, gia' avvenuta la prima volta — ma
     * {@link #marcaModificaAca} va comunque invocato esplicitamente: questa pendenza e'
     * appena stata modificata, il batch ACA deve rilevarlo indipendentemente dal fatto che
     * l'opzione cambi stato o no (stessa regola trasversale di {@link #marcaModificaAca},
     * "nessuna eccezione").</p>
     *
     * @param idA2A      identificativo del gestionale responsabile
     * @param idPendenza identificativo della pendenza nel gestionale
     * @param nuovoStato esito di pagamento da registrare ({@code ESEGUITO},
     *                   {@code PARZIALMENTE_ESEGUITO}, ecc. — qualunque valore diverso da
     *                   {@code NON_ESEGUITO}/{@code ANNULLATO}/{@code null})
     * @return la pendenza aggiornata
     * @throws RisorsaNonTrovataException          se non esiste una pendenza con questa chiave
     * @throws TransizioneStatoNonAmmessaException se {@code nuovoStato} e' nullo o e'
     *                                              {@code NON_ESEGUITO}/{@code ANNULLATO}, o
     *                                              se l'opzione della pendenza e'
     *                                              {@code ANNULLATA}
     */
    public Pendenza registraEsitoPendenza(String idA2A, String idPendenza, StatoPendenza nuovoStato) {
        if (nuovoStato == null || !statoEseguito(nuovoStato)) {
            throw new TransizioneStatoNonAmmessaException("'" + nuovoStato + "' non e' un esito di pagamento "
                    + "valido per questo metodo: deve essere diverso da NON_ESEGUITO/ANNULLATO/null (quelle "
                    + "transizioni si gestiscono tramite annulla(UUID)/ripristina(UUID) sull'opzione, non "
                    + "registrando un falso esito sulla singola pendenza)");
        }
        Pendenza pendenza = trovaPendenzaScrivibile(idA2A, idPendenza);
        OpzionePagamento opzione = pendenza.getOpzionePagamento();
        if (opzione.getStato() == StatoOpzionePagamento.ANNULLATA) {
            throw new TransizioneStatoNonAmmessaException("la pendenza [" + idPendenza + "] appartiene a "
                    + "un'opzione di pagamento ANNULLATA (stato definitivo): non e' possibile registrare un "
                    + "esito di pagamento su di essa");
        }

        entityManager.lock(opzione.getPosizioneDebitoria(), LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        OffsetDateTime adesso = OffsetDateTime.now(clock);
        pendenza.setStato(nuovoStato);
        pendenza.setDataUltimoAggiornamento(adesso);

        if (opzione.getStato() == StatoOpzionePagamento.DISPONIBILE) {
            attiva(opzione.getIdOpzionePagamento());
        } else {
            marcaModificaAca(opzione, adesso);
        }
        return pendenza;
    }

    /**
     * Registra l'importo delle spese di notifica SEND quantificato dal servizio di
     * pagamento interrogando il connettore SEND del dominio (percorso automatico,
     * {@code sendAbilitato} {@code true} sulla pendenza) — equivalente di
     * {@code VersamentoUtils.aggiornaImportoSend} del legacy, ma qui separato dal calcolo
     * stesso: questa libreria si limita a persistere un valore gia' quantificato altrove
     * (il connettore SEND, la politica di retention e la chiamata al servizio restano di
     * competenza del servizio di pagamento, non ancora implementato).
     *
     * <p>Non marca modifica ACA: il legacy {@code comunicaAggiornamentoPendenzaAllArchivioCentralizzato}
     * non considera questi due campi tra le condizioni che richiedono una comunicazione
     * all'archivio centralizzato (vedi {@code VersamentoUtils.java:1374-1409}).</p>
     *
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPendenza           identificativo della pendenza nel gestionale
     * @param sendImportoTotale    importo delle spese di notifica SEND, in euro
     * @param sendDataAggiornamento istante dell'interrogazione che ha prodotto questo importo
     * @return la pendenza aggiornata
     * @throws RisorsaNonTrovataException se non esiste una pendenza con questa chiave
     */
    public Pendenza registraImportoSend(String idA2A, String idPendenza, Double sendImportoTotale,
            OffsetDateTime sendDataAggiornamento) {
        Pendenza pendenza = trovaPendenzaScrivibile(idA2A, idPendenza);
        pendenza.setSendImportoTotale(sendImportoTotale);
        pendenza.setSendDataAggiornamento(sendDataAggiornamento);
        return pendenza;
    }

    /**
     * Aggiorna una singola pendenza — {@code PUT .../pendenze/{idA2A}/{idPendenza}} dello YAML
     * v3: l'equivalente v3 della "PUT profonda" legacy ({@code PendenzeDAO.createOrUpdate},
     * {@code aggiornaSeEsiste=true}), limitato ai campi che hanno un corrispettivo diretto su
     * {@link Pendenza} ({@code idTipoPendenza}/{@code numeroAvviso}/{@code dataScadenzaAvviso}/
     * {@code dataValidita}/{@code importo}/{@code voci} — vedi
     * {@code PosizioneDebitoriaMapper#applicaPutPendenza} per i path esatti). Non e' piu' il
     * punto di annullamento/ripristino della singola pendenza (vedi nota di classe): quella
     * superficie e' stata rimossa insieme a {@code annullaPendenza}/{@code ripristinaPendenza}.
     *
     * <p>Permesso solo se lo stato attuale e' {@code NON_ESEGUITO} o {@code ANNULLATO} —
     * mirror del legacy ({@code VersamentoUtils.validazioneSemanticaAggiornamento},
     * controllo VER_003): non ha senso aggiornare scadenza/importo/voci di una pendenza
     * gia' eseguita.</p>
     *
     * <p>Riusa interamente {@link #aggiorna} per rivalidazione (incluse cardinalita'/scala/
     * somma delle voci, {@code ValidatorePosizioneDebitoria.validaPendenza}), lock
     * ottimistico e marcatura ACA: {@code applicaModifiche} e' invocato PRIMA di delegare,
     * ma sulla stessa istanza gestita che {@link #aggiorna} ri-otterra' (stesso contesto di
     * persistenza JPA, nessuna query duplicata in pratica) — evita di duplicare qui la
     * stessa logica di validazione/locking/salvataggio.</p>
     *
     * <p><b>Invariante residuo sulle voci esistenti, garantito qui e non solo nel mapper REST</b>:
     * nessuna voce gia' presente, identificata dall'{@code idVocePendenza} (la stessa identita'
     * di business usata dal path REST, non l'{@code id} di database — quest'ultimo puo' essere
     * riusato da una voce nuova diversa, vedi Javadoc di
     * {@code PosizioneDebitoriaMapper#sostituisciVoci}), puo' cambiare {@code idTributo}/
     * {@code idIbanAccredito} — stesso confronto prima/dopo per qualunque chiamante di questo
     * metodo, non solo {@code PosizioneDebitoriaMapper#applicaPutPendenza} (che fa un
     * controllo equivalente, ma solo sul path REST): senza questa verifica qui, un
     * consumatore diretto della libreria (es. la futura console-api) potrebbe eludere
     * involontariamente questa regola chiamando {@code applicaModifiche} con una mutazione che
     * reindirizza una voce. {@code idIbanAppoggio} non e' protetto (mirror esatto del legacy,
     * {@code VersamentoUtils.validazioneSemanticaAggiornamento} non lo controlla — scelta
     * deliberata, non un'omissione). La RIMOZIONE di una voce esistente e' invece ammessa
     * (richiesta esplicita per questo endpoint, a differenza del legacy che la rifiuta sempre —
     * VER_005/VER_006): il minimo di una voce residua non e' verificato qui, ma e' comunque
     * garantito a valle da {@code ValidatorePosizioneDebitoria.validaPendenza} (cardinalita'
     * 1-5), eseguito da {@link #aggiorna} prima del salvataggio.</p>
     *
     * <p><b>Eccezione alla rimozione ammessa: una voce gia' rendicontata</b> — verificato per
     * confronto diretto sulla FK fisica tramite
     * {@link RendicontazioneRepository#existsByIdSingoloVersamento} (vedi il suo Javadoc):
     * rimuoverla, o riusarne la riga per una voce nuova (vedi Javadoc di
     * {@code PosizioneDebitoriaMapper#sostituisciVoci}), attribuirebbe lo storico sbagliato. Non
     * e' legato allo stato della pendenza (una rendicontazione anomala puo' arrivare senza, o
     * prima, del pagamento corrispondente, indipendentemente da {@code NON_ESEGUITO}/
     * {@code ANNULLATO}) — per questo e' un controllo separato, non assorbito da quello sullo
     * stato in testa al metodo. Un {@link it.govpay.pendenze.entity.Pagamento}, al contrario,
     * NON e' controllato qui: la sua creazione e' atomica con l'uscita da {@code NON_ESEGUITO}
     * nel legacy, e {@code ANNULLATO} e' raggiungibile solo da {@code NON_ESEGUITO} (mai da
     * {@code ESEGUITO}) — una pendenza che supera il controllo di stato sopra non puo' quindi
     * avere un pagamento su nessuna voce.</p>
     *
     * <p><b>{@code numeroAvviso}, se gia' assegnato, non puo' cambiare</b> (mirror VER_024) —
     * stesso controllo gia' presente in {@code PosizioneDebitoriaMapper#applicaPutPendenza},
     * duplicato qui per lo stesso motivo del controllo sulle voci sopra: garantirlo per
     * qualunque chiamante della libreria (es. la futura console-api), non solo per il percorso
     * REST. Se ancora assente, {@code applicaModifiche} puo' impostarlo liberamente.</p>
     *
     * <p>Marca anche {@code pendenza.dataUltimoAggiornamento}: {@link #aggiorna} marca il
     * timestamp della POSIZIONE e la modifica ACA di tutte le sue pendenze, ma non il
     * timestamp di aggiornamento della singola pendenza toccata da questo metodo — senza
     * questa riga resterebbe invariato anche quando cambiano date/importo/voci.</p>
     *
     * @param idA2A            identificativo del gestionale responsabile
     * @param idPendenza       identificativo della pendenza nel gestionale
     * @param applicaModifiche mutazione da applicare alla pendenza gestita
     * @return la pendenza aggiornata
     * @throws RisorsaNonTrovataException           se non esiste una pendenza con questa chiave
     * @throws TransizioneStatoNonAmmessaException se lo stato attuale non e' {@code NON_ESEGUITO}
     *                                              ne' {@code ANNULLATO}, se {@code applicaModifiche}
     *                                              cambia un {@code numeroAvviso} gia' assegnato
     *                                              o il riferimento (tributo/IBAN) di una voce
     *                                              esistente, o se rimuove una voce gia'
     *                                              rendicontata
     */
    public Pendenza aggiornaPendenza(String idA2A, String idPendenza, Consumer<Pendenza> applicaModifiche) {
        Pendenza pendenza = trovaPendenzaScrivibile(idA2A, idPendenza);
        if (pendenza.getStato() != StatoPendenza.NON_ESEGUITO && pendenza.getStato() != StatoPendenza.ANNULLATO) {
            throw new TransizioneStatoNonAmmessaException("la pendenza [" + idPendenza + "] e' " + pendenza.getStato()
                    + ": puo' essere aggiornata solo se NON_ESEGUITO o ANNULLATO");
        }
        String idPosizioneDebitoria = pendenza.getOpzionePagamento().getPosizioneDebitoria()
                .getIdPosizioneDebitoria();
        // Snapshot a valore (String e' immutabile di per se', ma catturato PRIMA di
        // applicaModifiche per lo stesso motivo delle voci sotto): se gia' assegnato, non puo'
        // cambiare (mirror VER_024) — stesso controllo del mapper REST
        // (PosizioneDebitoriaMapper#applicaPutPendenza), qui per qualunque chiamante della
        // libreria, non solo quel percorso.
        String numeroAvvisoPrima = pendenza.getNumeroAvviso();

        // Snapshot a VALORI (record immutabile), non a riferimenti di entity: VocePendenza e'
        // mutabile e applicaModifiche muta le STESSE istanze gestite, quindi un riferimento
        // qui rifletterebbe anche la mutazione successiva, rendendo il confronto "prima" vs
        // "dopo" sempre vero (bug riscontrato in fase di test: nessuna eccezione lanciata
        // anche cambiando idTributo, perche' "prima" e "dopo" erano lo stesso oggetto).
        //
        // Indicizzato per idVocePendenza (identita' lato business), non per l'id di database:
        // il mapper REST (PosizioneDebitoriaMapper#sostituisciVoci) riusa la riga di una voce
        // rimossa per una voce nuova (stesso id di database, indice, per evitare una collisione
        // transitoria sul vincolo di unicita' DB quando Hibernate esegue gli insert prima dei
        // delete nello stesso flush) — indicizzare per id di database tratterebbe quel riuso
        // come se la STESSA voce avesse cambiato tributo/IBAN, bloccando una rimozione+aggiunta
        // del tutto legittima. Per idVocePendenza invece no: la vecchia chiave non si ritrova
        // piu' in "dopo" (la riga ora rappresenta un'altra voce, con un altro idVocePendenza) e
        // viene trattata correttamente come rimozione.
        //
        // Merge function che tiene la prima occorrenza, non il default di Collectors.toMap
        // (che lancia IllegalStateException su chiavi duplicate): due voci con lo stesso
        // idVocePendenza non sono un caso valido, ma il rifiuto pulito (400) e' compito di
        // ValidatorePosizioneDebitoria.validaPendenza, eseguito poco sotto da aggiorna() — non
        // di questo confronto, che altrimenti andrebbe in crash con un 500 grezzo prima di
        // arrivarci.
        Map<String, SnapshotVoce> vociPrima = pendenza.getVoci().stream()
                .collect(Collectors.toMap(VocePendenza::getIdVocePendenza, SnapshotVoce::di, (a, b) -> a));

        applicaModifiche.accept(pendenza);

        if (numeroAvvisoPrima != null && !numeroAvvisoPrima.equals(pendenza.getNumeroAvviso())) {
            throw new TransizioneStatoNonAmmessaException(
                    "'numeroAvviso' non puo' cambiare rispetto all'originale [" + numeroAvvisoPrima + "]");
        }

        Map<String, VocePendenza> vociDopo = pendenza.getVoci().stream()
                .collect(Collectors.toMap(VocePendenza::getIdVocePendenza, v -> v, (a, b) -> a));
        for (Map.Entry<String, SnapshotVoce> voceEsistente : vociPrima.entrySet()) {
            SnapshotVoce prima = voceEsistente.getValue();
            VocePendenza dopo = vociDopo.get(voceEsistente.getKey());
            if (dopo == null) {
                // Rimossa (fisicamente, o perche' la sua riga viene riusata da una voce nuova —
                // PosizioneDebitoriaMapper#sostituisciVoci, stesso id di database): ammesso per
                // questo endpoint, vedi Javadoc del metodo. Ma se quella riga e' gia' referenziata
                // da una rendicontazione, rimuoverla (o riusarla per un'altra voce) attribuirebbe
                // lo storico sbagliato — vedi Javadoc di
                // RendicontazioneRepository#existsByIdSingoloVersamento: puo' succedere anche
                // senza alcun pagamento associato (rendicontazione anomala), non solo quando la
                // pendenza e' ESEGUITO — quindi non e' coperto dal controllo di stato in testa a
                // questo metodo.
                if (rendicontazioneRepository.existsByIdSingoloVersamento(prima.id())) {
                    throw new TransizioneStatoNonAmmessaException("la voce [" + prima.idVocePendenza()
                            + "] non puo' essere rimossa: esiste una rendicontazione che la referenzia");
                }
                // Il minimo di una voce residua e' verificato a valle da
                // ValidatorePosizioneDebitoria.
                continue;
            }
            if (!Objects.equals(prima.idTributo(), dopo.getIdTributo())
                    || !Objects.equals(prima.idIbanAccredito(), dopo.getIdIbanAccredito())) {
                throw new TransizioneStatoNonAmmessaException("la voce [" + prima.idVocePendenza()
                        + "] non puo' cambiare il riferimento (tributo/IBAN di accredito) rispetto all'originale");
            }
        }

        pendenza.setDataUltimoAggiornamento(OffsetDateTime.now(clock));
        aggiorna(idA2A, idPosizioneDebitoria, posizione -> {
            // la mutazione e' gia' stata applicata sopra, sulla stessa istanza gestita che
            // questa chiamata ri-ottiene dal contesto di persistenza — vedi Javadoc del metodo.
        });
        return pendenza;
    }

    /** Copia a valori di una {@link VocePendenza} esistente, per {@link #aggiornaPendenza}. */
    private record SnapshotVoce(Long id, String idVocePendenza, Long idTributo, Long idIbanAccredito) {
        static SnapshotVoce di(VocePendenza voce) {
            return new SnapshotVoce(voce.getId(), voce.getIdVocePendenza(), voce.getIdTributo(),
                    voce.getIdIbanAccredito());
        }
    }

    /**
     * Risolve la pendenza per {@link #registraEsitoPendenza}/{@link #registraImportoSend}/
     * {@link #aggiornaPendenza}: a differenza di {@link #trovaPendenzaPerIdentificativo}
     * (pubblico, {@code readOnly}, per la sola lettura) qui l'entita' va restituita gestita
     * nella transazione di scrittura corrente, altrimenti le mutazioni seguenti non
     * verrebbero mai salvate.
     */
    private Pendenza trovaPendenzaScrivibile(String idA2A, String idPendenza) {
        return risolviIdApplicazione(idA2A)
                .flatMap(idApplicazione -> pendenzaRepository
                        .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, idPendenza))
                .orElseThrow(() -> new RisorsaNonTrovataException(
                        "nessuna pendenza con idPendenza [" + idPendenza + "] per idA2A [" + idA2A + "]"));
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

    /**
     * Imposta lo stesso stato su tutte le pendenze dell'opzione, a seguito di una
     * transizione dell'opzione stessa (annullamento/ripristino, manuale o automatico come
     * effetto collaterale di {@link #attiva} sulle "altre" opzioni). Il verso opzione→pendenze:
     * il verso inverso (una singola pendenza che fa transitare l'opzione) non passa da questo
     * metodo, ma da {@link #registraEsitoPendenza}, che poi richiama {@link #attiva(UUID)} —
     * quest'ultima, a sua volta, usa questo metodo solo per le "altre" opzioni annullate
     * automaticamente, mai per l'opzione appena attivata (le cui pendenze NON vanno
     * sovrascritte, e' proprio il pagamento su una di esse ad aver causato la transizione).
     *
     * <p><b>Non sicura di per se'</b>: l'invariante "nessuna pendenza e' gia' eseguita" non e'
     * garantita dal solo stato dell'opzione (es. {@code attiva(UUID)} non verificava, prima
     * della correzione in revisione, che almeno una pendenza fosse davvero eseguita prima di
     * passare ad {@code ATTIVATA} — un'opzione {@code DISPONIBILE} poteva quindi avere una
     * pendenza gia' {@code ESEGUITO}). Ogni chiamante DEVE aver gia' invocato
     * {@link #verificaNessunaPendenzaEseguita} (o, per {@link #attiva(UUID)}, non chiamare
     * affatto questo metodo sull'opzione che sta attivando) prima di questa cascata, altrimenti
     * sovrascriverebbe silenziosamente una pendenza realmente eseguita.</p>
     *
     * @param opzione   opzione appena transitata
     * @param nuovoStato stato da impostare su ciascuna pendenza ({@code ANNULLATO} o
     *                   {@code NON_ESEGUITO})
     * @param adesso    istante della transizione, dallo stesso {@link Clock} della libreria
     */
    private void cascataStatoPendenze(OpzionePagamento opzione, StatoPendenza nuovoStato, OffsetDateTime adesso) {
        for (Pendenza pendenza : opzione.getPendenze()) {
            pendenza.setStato(nuovoStato);
            pendenza.setDataUltimoAggiornamento(adesso);
        }
    }
}
