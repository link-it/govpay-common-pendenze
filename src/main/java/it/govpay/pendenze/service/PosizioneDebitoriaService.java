package it.govpay.pendenze.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import it.govpay.pendenze.comando.AggiornamentoPendenza;
import it.govpay.pendenze.comando.AggiornamentoPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiOpzionePagamento;
import it.govpay.pendenze.comando.DatiPendenza;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiVocePendenza;
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
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.criteri.FiltriPendenze;
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
import it.govpay.pendenze.identificativo.IdentificatorePosizioneDebitoria;
import it.govpay.pendenze.identificativo.IdentificatorePosizioneDebitoria.ComponentiIdentificativo;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPagamento;
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
 * <p>Le scritture ricevono comandi a valore. La costruzione e modifica delle entita,
 * le validazioni e il coordinamento degli stati restano nel servizio. Annullamento,
 * ripristino e registrazione di esito hanno metodi dedicati.</p>
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
    private final DominioRepository dominioRepository;
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
     * {@link #crea(DatiPosizioneDebitoria)} rifiutera' le pendenze prive di IUV/numero
     * avviso proprio.
     */
    public PosizioneDebitoriaService(PosizioneDebitoriaRepository posizioneDebitoriaRepository,
            OpzionePagamentoRepository opzionePagamentoRepository, PendenzaRepository pendenzaRepository,
            ApplicazioneRepository applicazioneRepository, DominioRepository dominioRepository,
            RendicontazioneRepository rendicontazioneRepository,
            Clock clock, ObjectProvider<GeneratoreIuv> generatoreIuvProvider) {
        this.posizioneDebitoriaRepository = posizioneDebitoriaRepository;
        this.opzionePagamentoRepository = opzionePagamentoRepository;
        this.pendenzaRepository = pendenzaRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.dominioRepository = dominioRepository;
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
     * Risoluzione inversa di {@link #risolviIdDominio}, stesso principio di {@link #risolviIdA2A}
     * (percorso di composizione dell'identita' pubblica: un dominio ignoto qui e' un'incoerenza
     * dei dati, non un caso di ricerca legittimo).
     */
    private String risolviCodDominio(Long idDominio) {
        return dominioRepository.findById(idDominio)
                .map(DominioEntity::getCodDominio)
                .orElseThrow(
                        () -> new IllegalStateException("Dominio [id:" + idDominio + "] non trovato in anagrafica"));
    }

    /**
     * Risoluzione inversa di {@link #risolviCodDominio}, stesso principio di
     * {@link #risolviIdApplicazione}: un {@code idDominio} (business code) sconosciuto e' un caso
     * legittimo di ricerca (es. decomposto da un {@code idPosizioneDebitoria} malformato o
     * riferito a un dominio inesistente), non un errore di configurazione.
     */
    private Optional<Long> risolviIdDominio(String idDominio) {
        return dominioRepository.findByCodDominio(idDominio).map(DominioEntity::getId);
    }

    /**
     * Compone l'{@code idPosizioneDebitoria} pubblico (IUPD, {@code idA2A}+{@code idDominio}+
     * {@link PosizioneDebitoria#getCodDocumento()} senza separatore — vedi Javadoc di
     * {@link PosizioneDebitoria}) a partire da un'entita' gestita. Usato dal livello REST per
     * popolare la risposta e il {@code Location} header, mai persistito da questa libreria.
     */
    public String idPosizioneDebitoriaPubblica(PosizioneDebitoria posizione) {
        return IdentificatorePosizioneDebitoria.comporre(risolviIdA2A(posizione.getIdApplicazione()),
                risolviCodDominio(posizione.getIdDominio()), posizione.getCodDocumento());
    }

    /**
     * Risolve una posizione dall'identita' pubblica opaca che arriva dal path HTTP
     * ({@code idA2A}+{@code idPosizioneDebitoria}): decompone il secondo (vedi
     * {@link IdentificatorePosizioneDebitoria#scomponi}), risolve {@code idA2A}/{@code idDominio}
     * nelle rispettive FK, e interroga il repository sulla chiave reale a tre colonne. Un
     * {@code idA2A} ignoto, un {@code idPosizioneDebitoria} sintatticamente malformato per questo
     * schema, o un {@code idDominio} decomposto che non corrisponde a nessun dominio censito sono
     * tutti trattati allo stesso modo: nessuna posizione corrisponde, {@link Optional#empty()} —
     * mai un'eccezione diversa da "non trovato", perche' nessuno di questi casi e' distinguibile
     * da un vero "non esiste" per un chiamante esterno.
     */
    private Optional<PosizioneDebitoria> trovaPerIdentificativoInterno(String idA2A, String idPosizioneDebitoria) {
        return risolviIdApplicazione(idA2A).flatMap(idApplicazione -> IdentificatorePosizioneDebitoria
                .scomponi(idA2A, idPosizioneDebitoria)
                .flatMap(componenti -> risolviIdDominio(componenti.idDominio())
                        .flatMap(idDominio -> posizioneDebitoriaRepository.findByIdApplicazioneAndIdDominioAndCodDocumento(
                                idApplicazione, idDominio, componenti.codDocumento()))));
    }

    /**
     * Crea l'aggregato a partire da dati a valore, privi di stati e identita JPA.
     * Il servizio costruisce nuove entita, inizializza opzioni DISPONIBILI e pendenze
     * NON_ESEGUITO, assegna UUID/indici/date e genera o verifica IUV e numero avviso.
     * Valida l'intero aggregato prima di salvarlo; i vincoli DB proteggono dai duplicati
     * concorrenti anche quando il controllo preliminare non li puo' rilevare.
     */
    public PosizioneDebitoria crea(DatiPosizioneDebitoria comando) {
        PosizioneDebitoria posizione = ApplicatoreComandi.posizioneDebitoria(comando);
        if (posizioneDebitoriaRepository.existsByIdApplicazioneAndIdDominioAndCodDocumento(
                posizione.getIdApplicazione(), posizione.getIdDominio(), posizione.getCodDocumento())) {
            throw new RisorsaGiaEsistenteException(
                    "esiste gia' una posizione debitoria con codDocumento ["
                            + posizione.getCodDocumento() + "] per questa applicazione e questo dominio");
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
                pendenza.setIdApplicazione(posizione.getIdApplicazione());

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
            // sopra e' un check-then-act, non atomico — due richieste concorrenti con lo stesso
            // codDocumento+idApplicazione+idDominio possono superarlo entrambe. La garanzia
            // reale e' il vincolo DB legacy unique_documenti_1: qui se ne traduce la violazione
            // nella stessa eccezione del controllo esplicito, cosi' il chiamante vede sempre
            // RisorsaGiaEsistenteException e non un'eccezione di persistenza generica.
            //
            // Riconosce il vincolo specifico: tradurre QUALUNQUE
            // DataIntegrityViolationException in "risorsa gia' esistente" e' scorretto — un
            // altro vincolo violato (es. NOT NULL su una colonna non valorizzata, un bug
            // diverso) verrebbe mascherato da un 409 fuorviante invece di propagarsi come
            // l'errore che e' davvero.
            if (violaVincolo(e, VINCOLO_UNICITA_IDENTIFICATIVO)) {
                throw new RisorsaGiaEsistenteException(
                        "esiste gia' una posizione debitoria con codDocumento ["
                                + posizione.getCodDocumento() + "] per questa applicazione e questo dominio");
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
     * Unico vincolo UNIQUE reale su {@code documenti} che segnala il duplicato pubblico
     * (codDocumento+idApplicazione+idDominio, gia' presente nello schema legacy) —
     * l'identita' pubblica (IUPD) incorpora tutte e tre le colonne per costruzione, quindi
     * nessun vincolo aggiuntivo serve (vedi Javadoc di {@link PosizioneDebitoria}).
     */
    private static final String VINCOLO_UNICITA_IDENTIFICATIVO = "unique_documenti_1";

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
     *
     * <p>{@link PendenzaRepository#findByIdDominioAndNumeroAvviso} ritorna una lista, non un
     * {@code Optional}: una o piu' corrispondenze sono comunque un duplicato da rifiutare, ma
     * un {@code Optional} qui farebbe fallire con un'eccezione di persistenza non gestita
     * proprio nel caso — dati migrati da v2 con NAV condiviso tra le rate di un piano rateale
     * — in cui questo controllo serve di piu'.</p>
     */
    private void verificaNumeroAvvisoNonDuplicato(PosizioneDebitoria posizione, Pendenza pendenza) {
        List<Pendenza> esistenti = pendenzaRepository.findByIdDominioAndNumeroAvviso(posizione.getIdDominio(),
                pendenza.getNumeroAvviso());
        if (!esistenti.isEmpty()) {
            throw new ValidazioneNonSuperataException(
                    "la pendenza [" + pendenza.getIdPendenza() + "] ha numeroAvviso [" + pendenza.getNumeroAvviso()
                            + "] gia' usato da un'altra pendenza dello stesso dominio ["
                            + esistenti.get(0).getIdPendenza() + "]");
        }
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
        return trovaPerIdentificativoInterno(idA2A, idPosizioneDebitoria);
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
     * Aggiorna esclusivamente i campi dichiarati dal comando della posizione.
     * Un campo assente resta invariato; un valore nullo rimuove solo i campi facoltativi.
     * Rivalida l'aggregato, inclusi gli stati, e aggiorna le marcature ACA.
     * Nessun callback riceve l'aggregato gestito.
     */
    public PosizioneDebitoria aggiorna(String idA2A, String idPosizioneDebitoria,
            AggiornamentoPosizioneDebitoria comando) {
        PosizioneDebitoria posizione = trovaPerIdentificativoInterno(idA2A, idPosizioneDebitoria)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        applicaAggiornamento(posizione, comando);

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

    private void applicaAggiornamento(PosizioneDebitoria posizione, AggiornamentoPosizioneDebitoria comando) {
        if (comando.descrizione().presente()) {
            String descrizione = comando.descrizione().valore();
            if (descrizione == null || descrizione.isBlank() || descrizione.length() > 140) {
                throw new ValidazioneNonSuperataException("'descrizione' deve contenere da 1 a 140 caratteri");
            }
            posizione.setDescrizione(descrizione);
        }
        if (comando.dataPubblicazione().presente()) posizione.setDataPubblicazione(comando.dataPubblicazione().valore());
        if (comando.notificaSend().presente()) {
            if (comando.notificaSend().valore() == null) {
                throw new ValidazioneNonSuperataException("'notificaSend' non puo' essere nullo");
            }
            posizione.setNotificaSend(comando.notificaSend().valore());
        }
        if (comando.navNotifica().presente()) posizione.setNavNotifica(comando.navNotifica().valore());
        if (comando.soggettiDebitori().presente()) {
            if (comando.soggettiDebitori().valore() == null || comando.soggettiDebitori().valore().isEmpty()) {
                throw new ValidazioneNonSuperataException("la posizione debitoria deve avere almeno un soggetto debitore");
            }
            posizione.sostituisciSoggettiDebitori(comando.soggettiDebitori().valore().stream()
                    .map(ApplicatoreComandi::soggettoDebitore).toList());
        }
    }

    /**
     * Aggiunge un'alternativa DISPONIBILE a una posizione senza opzioni ATTIVATE.
     * Il comando contiene i soli dati di creazione. Dominio e applicazione delle
     * pendenze sono assegnati dal servizio in base alla posizione proprietaria.
     * <p>Gli identificativi vengono assegnati PRIMA di collegare la nuova opzione
     * all'aggregato gestito: le query di unicita possono provocare un autoflush JPA,
     * che non deve trovare entita parzialmente inizializzate.</p>
     */
    public OpzionePagamento aggiungiOpzionePagamento(String idA2A, String idPosizioneDebitoria,
            DatiOpzionePagamento comando) {
        PosizioneDebitoria posizione = trovaPerIdentificativoInterno(idA2A, idPosizioneDebitoria)
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

        OpzionePagamento nuovaOpzione = ApplicatoreComandi.opzionePagamento(comando);

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
                pendenza.setIdApplicazione(posizione.getIdApplicazione());

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
     * Aggiunge una pendenza NON_ESEGUITO a un'opzione DISPONIBILE della posizione
     * indicata. Verifica appartenenza, cardinalita, importi e identificativi.
     * Come per l'aggiunta di un'opzione, completa gli identificativi prima di
     * collegare la nuova pendenza all'aggregato gestito.
     */
    public Pendenza aggiungiPendenza(String idA2A, String idPosizioneDebitoria, UUID idOpzionePagamento,
            DatiPendenza comando) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);
        PosizioneDebitoria posizione = opzione.getPosizioneDebitoria();
        if (!appartiene(posizione, idA2A, idPosizioneDebitoria)) {
            throw new RisorsaNonTrovataException("nessuna opzione di pagamento [" + idOpzionePagamento
                    + "] per idPosizioneDebitoria [" + idPosizioneDebitoria + "] e idA2A [" + idA2A + "]");
        }
        if (opzione.getStato() != StatoOpzionePagamento.DISPONIBILE) {
            throw new TransizioneStatoNonAmmessaException("l'opzione di pagamento [" + idOpzionePagamento + "] e' "
                    + opzione.getStato() + ": si puo' aggiungere una pendenza solo se DISPONIBILE");
        }

        Pendenza nuovaPendenza = ApplicatoreComandi.pendenza(comando);

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        int numeroRata = opzione.getPendenze().stream().mapToInt(Pendenza::getNumeroRata).max().orElse(0) + 1;
        nuovaPendenza.setNumeroRata(numeroRata);
        nuovaPendenza.setDataCreazione(adesso);
        nuovaPendenza.setDataUltimoAggiornamento(adesso);
        nuovaPendenza.setDataUltimaModificaAca(adesso);
        // Stesso principio di crea()/aggiungiOpzionePagamento: IUV/NAV sono univoci per
        // dominio, la pendenza deve portare lo stesso dominio della posizione.
        nuovaPendenza.setIdDominio(posizione.getIdDominio());
        nuovaPendenza.setIdApplicazione(posizione.getIdApplicazione());

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
     * Ricerca generica per {@code idApplicazione} con filtri opzionali ({@link FiltriPendenze}) —
     * usata dal wrapper v2 ({@code GET /pendenze} dello YAML v2) per replicare il filtro di
     * ricerca del legacy, molto piu' ampio di {@link #cercaPendenze} (solo numero avviso). Vedi
     * Javadoc di {@link PendenzaRepository#cercaFiltrate} per il significato di ciascun filtro
     * e per la scelta deliberata di non escludere le pendenze senza {@code opzionePagamento}.
     *
     * @param idA2A    identificativo del gestionale responsabile
     * @param filtri   filtri opzionali (ogni campo {@code null} lo disattiva)
     * @param pageable paginazione/ordinamento richiesti
     * @return la pagina di pendenze che rispettano il filtro
     */
    @Transactional(readOnly = true)
    public PaginaRisultati<Pendenza> cercaPendenzeFiltrate(String idA2A, FiltriPendenze filtri, Pageable pageable) {
        Optional<Long> idApplicazioneOpt = risolviIdApplicazione(idA2A);
        if (idApplicazioneOpt.isEmpty()) {
            return new PaginaRisultati<>(List.of(), pageable.getOffset(), pageable.getPageSize(), 0);
        }
        Page<Pendenza> pagina = pendenzaRepository.cercaFiltrate(idApplicazioneOpt.get(), filtri.idDominio(),
                filtri.idDebitore(), filtri.direzione(), filtri.divisione(), filtri.dataDa(), filtri.dataA(),
                filtri.statoVersamento(), filtri.statoVersamentoAlternativo(), filtri.statoPagamento(),
                filtri.scaduta(), inizioGiornoCorrente(), pageable);
        return paginaDa(pagina, pageable);
    }

    /** Come {@link #cercaPendenzeFiltrate}, ma senza {@code COUNT(*)} — stesso meccanismo
     *  "limit+1" di {@link #cercaPendenzeSenzaConteggio}. */
    @Transactional(readOnly = true)
    public PaginaSenzaConteggio<Pendenza> cercaPendenzeFiltrateSenzaConteggio(String idA2A, FiltriPendenze filtri,
            Pageable pageable) {
        Optional<Long> idApplicazioneOpt = risolviIdApplicazione(idA2A);
        if (idApplicazioneOpt.isEmpty()) {
            return new PaginaSenzaConteggio<>(List.of(), false);
        }
        Pageable pageablePiuUno = OffsetPageRequest.of(pageable.getOffset(), pageable.getPageSize() + 1,
                pageable.getSort());
        List<Pendenza> grezzi = pendenzaRepository.cercaFiltrateSenzaConteggio(idApplicazioneOpt.get(),
                filtri.idDominio(), filtri.idDebitore(), filtri.direzione(), filtri.divisione(), filtri.dataDa(),
                filtri.dataA(), filtri.statoVersamento(), filtri.statoVersamentoAlternativo(),
                filtri.statoPagamento(), filtri.scaduta(), inizioGiornoCorrente(), pageablePiuUno);
        return PaginaSenzaConteggio.daRisultatiGrezzi(grezzi, pageable.getPageSize());
    }

    /**
     * Mezzanotte del giorno corrente secondo il {@link Clock} della libreria — soglia di
     * "scaduta" per {@link FiltriPendenze#scaduta()}, stessa convenzione (scaduta solo dal
     * giorno DOPO la data di scadenza) di {@code PendenzaV2Mapper#isScaduta} nel wrapper v2,
     * qui espressa dal clock iniettato invece di {@code LocalDate.now()} per restare
     * testabile (vedi Javadoc di classe).
     */
    private OffsetDateTime inizioGiornoCorrente() {
        OffsetDateTime adesso = OffsetDateTime.now(clock);
        return adesso.toLocalDate().atStartOfDay().atOffset(adesso.getOffset());
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
        if (!appartiene(posizione, idA2A, idPosizioneDebitoria)) {
            throw new RisorsaNonTrovataException("nessuna opzione di pagamento [" + idOpzionePagamento
                    + "] per idPosizioneDebitoria [" + idPosizioneDebitoria + "] e idA2A [" + idA2A + "]");
        }
        OpzionePagamento risultato = annulla(idOpzionePagamento);
        ValidatorePosizioneDebitoria.validaStati(posizione);
        return risultato;
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
        if (!appartiene(posizione, idA2A, idPosizioneDebitoria)) {
            throw new RisorsaNonTrovataException("nessuna opzione di pagamento [" + idOpzionePagamento
                    + "] per idPosizioneDebitoria [" + idPosizioneDebitoria + "] e idA2A [" + idA2A + "]");
        }
        OpzionePagamento risultato = ripristina(idOpzionePagamento);
        ValidatorePosizioneDebitoria.validaStati(posizione);
        return risultato;
    }

    /**
     * Verifica che {@code posizione} sia davvero quella identificata da {@code idA2A}+
     * {@code idPosizioneDebitoria}: riusa la stessa risoluzione di {@link #trovaPerIdentificativoInterno}
     * invece di confrontare {@code idPosizioneDebitoria} con un valore ricomposto — se la
     * risoluzione trova una riga diversa (o nessuna), {@code posizione} non appartiene a quella
     * identita' pubblica, qualunque sia il motivo (altro dominio, altra applicazione, stringa
     * malformata).
     */
    private boolean appartiene(PosizioneDebitoria posizione, String idA2A, String idPosizioneDebitoria) {
        return trovaPerIdentificativoInterno(idA2A, idPosizioneDebitoria)
                .map(trovata -> trovata.getId().equals(posizione.getId()))
                .orElse(false);
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
        ValidatorePosizioneDebitoria.validaStati(opzione.getPosizioneDebitoria());
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
        ValidatorePosizioneDebitoria.validaStati(pendenza.getOpzionePagamento().getPosizioneDebitoria());
        pendenza.setSendImportoTotale(sendImportoTotale);
        pendenza.setSendDataAggiornamento(sendDataAggiornamento);
        return pendenza;
    }

    /**
     * Sostituisce i dati ammessi di una pendenza NON_ESEGUITO o ANNULLATO.
     * Identita, stato, IUV, riferimenti all'aggregato e dati di pagamento non sono
     * scrivibili tramite questo comando. Numero avviso omesso conserva l'originale;
     * se gia assegnato non puo' essere cambiato.
     * <p>Le voci vengono unite per identificativo di business, conservando PK e indice
     * per quelle mantenute. Una voce rimossa puo' cedere la propria riga a una nuova
     * solo se non e' referenziata da rendicontazioni. I riferimenti tributo/IBAN di
     * accredito delle voci mantenute restano immutabili. Al termine viene rivalidato
     * l'intero aggregato e vengono aggiornate le marcature ACA.</p>
     */
    public Pendenza aggiornaPendenza(String idA2A, String idPendenza, AggiornamentoPendenza comando) {
        Pendenza pendenza = trovaPendenzaScrivibile(idA2A, idPendenza);
        if (pendenza.getStato() != StatoPendenza.NON_ESEGUITO && pendenza.getStato() != StatoPendenza.ANNULLATO) {
            throw new TransizioneStatoNonAmmessaException("la pendenza [" + idPendenza + "] e' " + pendenza.getStato()
                    + ": puo' essere aggiornata solo se NON_ESEGUITO o ANNULLATO");
        }
        if (!Objects.equals(idPendenza, comando.idPendenza())) {
            throw new ValidazioneNonSuperataException("'idPendenza' nel body [" + comando.idPendenza()
                    + "] non corrisponde a quello nel path [" + idPendenza + "]");
        }
        if (pendenza.getNumeroAvviso() != null && comando.numeroAvviso() != null
                && !pendenza.getNumeroAvviso().equals(comando.numeroAvviso())) {
            throw new TransizioneStatoNonAmmessaException("'numeroAvviso' non puo' cambiare rispetto all'originale ["
                    + pendenza.getNumeroAvviso() + "]");
        }
        sostituisciVoci(pendenza, comando.voci());
        pendenza.setIdTipoPendenza(comando.idTipoPendenza());
        pendenza.setIdTipoVersamento(comando.idTipoVersamento());
        pendenza.setCodificaIuvTipoPendenza(comando.codificaIuvTipoPendenza());
        pendenza.setImporto(comando.importo());
        pendenza.setDataValidita(comando.dataValidita());
        pendenza.setDataScadenzaAvviso(comando.dataScadenzaAvviso());
        if (pendenza.getNumeroAvviso() == null) {
            pendenza.setNumeroAvviso(comando.numeroAvviso());
        }
        pendenza.setDataUltimoAggiornamento(OffsetDateTime.now(clock));
        aggiorna(idA2A, idPosizioneDebitoriaPubblica(pendenza.getOpzionePagamento().getPosizioneDebitoria()),
                AggiornamentoPosizioneDebitoria.nessunaModifica());
        return pendenza;
    }

    /**
     * Conserva gli indici 1..5 riusando le righe rimosse non referenziate: Hibernate
     * esegue INSERT prima di DELETE, quindi riusare soltanto l'indice causerebbe una
     * violazione transitoria di unicita. La verifica dello storico precede le mutazioni.
     */
    private void sostituisciVoci(it.govpay.pendenze.entity.Pendenza pendenza, List<DatiVocePendenza> richieste) {
        Map<String, VocePendenza> esistentiPerId = pendenza.getVoci().stream()
                .collect(Collectors.toMap(VocePendenza::getIdVocePendenza, v -> v));

        List<VocePendenza> candidate = richieste.stream()
                .map(ApplicatoreComandi::vocePendenza)
                .toList();
        // Stessa materializzazione fatta dal service in creazione
        // (PosizioneDebitoriaService#crea/#aggiungiOpzionePagamento, "se voce.idDominio e'
        // null lo valorizzo con quello della posizione"): qui non si passa da quel codice,
        // quindi senza questo passaggio una voce senza override esplicito perderebbe idDominio.
        for (VocePendenza candidata : candidate) {
            if (candidata.getIdDominio() == null) {
                candidata.setIdDominio(pendenza.getIdDominio());
            }
        }

        /*
         * Unicita' di idVocePendenza DENTRO la richiesta, verificata qui e non solo a valle da
         * ValidatorePosizioneDebitoria: se l'id duplicato corrisponde a una voce NUOVA, il
         * duplicato sopravvive nell'aggregato finale e il controllo comune lo intercetta — ma
         * se corrisponde a una voce ESISTENTE, il ciclo sotto applica entrambe le occorrenze
         * alla STESSA entity (match per idVocePendenza), quindi nell'aggregato finale non c'e'
         * mai un duplicato da intercettare: la richiesta viene accettata con 200 e prevale
         * silenziosamente l'ultima occorrenza (bug riscontrato). Controllo esplicito qui,
         * prima di qualunque match/mutazione, per rifiutare entrambi i casi allo stesso modo.
         */
        Set<String> idVisti = new HashSet<>();
        for (VocePendenza candidata : candidate) {
            if (!idVisti.add(candidata.getIdVocePendenza())) {
                throw new ValidazioneNonSuperataException(
                        "la voce [" + candidata.getIdVocePendenza() + "] e' presente piu' volte nella richiesta");
            }
        }

        Set<String> idPresentiNellaRichiesta = candidate.stream()
                .map(VocePendenza::getIdVocePendenza).collect(Collectors.toSet());

        Deque<VocePendenza> daRiusare = pendenza.getVoci().stream()
                .filter(v -> !idPresentiNellaRichiesta.contains(v.getIdVocePendenza()))
                .collect(Collectors.toCollection(ArrayDeque::new));
        // Controllare prima di mutare: le query non devono scaricare modifiche parziali.
        for (VocePendenza rimossa : daRiusare) {
            if (rendicontazioneRepository.existsByIdSingoloVersamento(rimossa.getId())) {
                throw new TransizioneStatoNonAmmessaException("la voce [" + rimossa.getIdVocePendenza()
                        + "] non puo' essere rimossa: esiste una rendicontazione che la referenzia");
            }
        }
        Set<Integer> indiciOccupati = pendenza.getVoci().stream()
                .map(VocePendenza::getIndice)
                .collect(Collectors.toCollection(HashSet::new));
        int prossimoIndiceLibero = 1;

        for (VocePendenza candidata : candidate) {
            VocePendenza esistente = esistentiPerId.get(candidata.getIdVocePendenza());
            if (esistente == null) {
                VocePendenza riusata = daRiusare.poll();
                if (riusata != null) {
                    sovrascriviCampiVoce(riusata, candidata);
                    continue;
                }
                while (indiciOccupati.contains(prossimoIndiceLibero)) {
                    prossimoIndiceLibero++;
                }
                candidata.setIndice(prossimoIndiceLibero);
                indiciOccupati.add(prossimoIndiceLibero);
                pendenza.addVocePendenza(candidata);
                continue;
            }
            if (!Objects.equals(esistente.getIdTributo(), candidata.getIdTributo())
                    || !Objects.equals(esistente.getIdIbanAccredito(), candidata.getIdIbanAccredito())) {
                throw new TransizioneStatoNonAmmessaException(
                        "la voce [" + candidata.getIdVocePendenza() + "] non puo' cambiare il riferimento "
                                + "(tributo/IBAN di accredito) rispetto all'originale");
            }
            sovrascriviCampiVoce(esistente, candidata);
        }

        // Voci rimaste senza una voce nuova che le riusi: rimozione effettiva — orphanRemoval
        // su Pendenza.voci le elimina su DB.
        pendenza.getVoci().removeAll(daRiusare);
    }

    /** Non tocca {@code indice} (va preservato sia sulle voci matchate sia su quelle riusate — vedi Javadoc del chiamante) ne' l'id JPA. */
    private void sovrascriviCampiVoce(VocePendenza target, VocePendenza candidata) {
        target.setIdVocePendenza(candidata.getIdVocePendenza());
        target.setImporto(candidata.getImporto());
        target.setDescrizione(candidata.getDescrizione());
        target.setIdDominio(candidata.getIdDominio());
        target.setIdTributo(candidata.getIdTributo());
        target.setIdIbanAccredito(candidata.getIdIbanAccredito());
        target.setIdIbanAppoggio(candidata.getIdIbanAppoggio());
        target.setTassonomia(candidata.getTassonomia());
        target.setTipoBollo(candidata.getTipoBollo());
        target.setHashDocumento(candidata.getHashDocumento());
        target.setProvinciaResidenza(candidata.getProvinciaResidenza());
        target.setDettaglioContabile(candidata.getDettaglioContabile());
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
