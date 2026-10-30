package it.govpay.pendenze.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Posizione debitoria: radice dell'aggregato pendenza, mappata sulla tabella legacy
 * {@code documenti} (riuso diretto invece di uno schema
 * v3 separato, per minimizzare la differenza strutturale da v2 e ridurre al minimo la
 * migrazione dati — vedi {@code proposta-modello-nativo-v3.md} §17).
 *
 * <p><b>{@code idA2A} non e' una colonna</b>: e' esattamente
 * {@code Applicazione.codApplicazione} — questa entita'
 * espone solo {@link #idApplicazione}, FK piatta verso l'anagrafica esterna di
 * govpay-common (M4: nessuna relazione JPA). La risoluzione idA2A &#8596; idApplicazione
 * e' compito del chiamante (repository/service), non di questa entita'.</p>
 *
 * <p><b>Colonne aggiunte a {@code documenti}</b> (assenti nel legacy, dove il concetto non
 * esiste affatto): {@link #idUnitaOperativa}, {@link #dataPubblicazione}, {@link #notificaSend},
 * {@link #navNotifica}, {@link #dataUltimaModificaAca}, {@link #dataUltimaComunicazioneAca},
 * {@link #dataCreazione}, {@link #dataUltimoAggiornamento} — 8 colonne additive, non le 3
 * inizialmente stimate (mancava di considerare unita' operativa/nav-notifica/ACA, propri
 * solo di questa libreria). {@link #dataPubblicazione} era gia' nel primissimo disegno
 * (§3.1 di {@code proposta-modello-nativo-v3.md}), persa durante il pivot al riuso delle
 * tabelle legacy (§17) e ripristinata successivamente.</p>
 *
 * <p><b>{@link #soggettiDebitori} punta a {@code soggetti_debitori}</b>, tabella nuova che
 * contiene TUTTI i debitori, incluso il primo: il
 * debitore appartiene logicamente al documento, non al singolo versamento. Il primo soggetto
 * (ordine 0) NON viene sincronizzato su {@code versamenti.debitore_*} (quella
 * lista resta modificabile dopo la creazione, tenerli allineati nel tempo sarebbe complessita'
 * pura) — quelle colonne restano {@code NOT NULL} in produzione ma valorizzate con placeholder
 * fissi, vedi Javadoc di {@link Pendenza}.</p>
 *
 * <p><b>{@code idPosizioneDebitoria} dello YAML v3 non e' una colonna</b>: e' l'IUPD (standard
 * pagoPA GPD, {@code gpd-4-aca.json}) e si deriva al volo concatenando, senza separatore,
 * {@code idA2A}+{@code idDominio}+{@link #codDocumento} — mai persistito, mai una colonna
 * propria di questa entita'. Questa entita' espone solo {@link #codDocumento} (la parte scelta
 * liberamente dal gestionale); la composizione/decomposizione del valore pubblico e' compito di
 * {@code PosizioneDebitoriaService#idPosizioneDebitoriaPubblica} e di
 * {@code it.govpay.pendenze.identificativo.IdentificatorePosizioneDebitoria}. La decomposizione e'
 * deterministica senza separatore solo perche' {@code idDominio} ha lunghezza fissa (11 cifre,
 * codice fiscale ente pagoPA) e {@code idA2A} e' sempre gia' noto a priori (arriva separatamente
 * dal path HTTP): nessuna ambiguita' di parsing.</p>
 *
 * <p><b>Vincolo di unicita'</b>: solo quello legacy, {@code unique_documenti_1}
 * ({@code cod_documento}+{@code id_applicazione}+{@code id_dominio}) — nessun vincolo aggiuntivo
 * serve, perche' l'identita' pubblica (IUPD) incorpora gia' tutte e tre le colonne per
 * costruzione: due righe con lo stesso {@code cod_documento}+{@code id_applicazione}+
 * {@code id_dominio} avrebbero comunque lo stesso IUPD, e il vincolo legacy le impedisce gia'.</p>
 */
@Entity
@Table(name = "documenti", uniqueConstraints = {
        @UniqueConstraint(name = "unique_documenti_1", columnNames = {"cod_documento", "id_applicazione", "id_dominio"})})
@SequenceGenerator(name = "seq_documenti", sequenceName = "seq_documenti", allocationSize = 1)
public class PosizioneDebitoria {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_documenti")
    @Column(name = "id")
    private Long id;

    /**
     * Colonna aggiunta: concetto assente in {@code documenti} legacy. Lock ottimistico —
     * stessa tecnica gia' usata su {@link OpzionePagamento#getVersione()} per proteggere
     * {@code attiva}/{@code annulla} dalla concorrenza — ora estesa alla
     * posizione stessa: senza un {@code @Version} qui,
     * {@code PosizioneDebitoriaService#aggiungiOpzionePagamento} e {@code #attiva}/{@code #annulla}
     * possono correre in parallelo sulla stessa posizione senza che nessuno dei due si accorga
     * delle modifiche dell'altro — una nuova opzione DISPONIBILE puo' essere aggiunta subito
     * dopo che un'altra e' stata ATTIVATA (pagamento gia' eseguito), senza che l'aggiunta veda
     * quell'attivazione. {@code attiva}/{@code annulla} forzano esplicitamente l'incremento
     * con {@code entityManager.lock(posizione, LockModeType.OPTIMISTIC_FORCE_INCREMENT)}, per
     * non dipendere implicitamente dal fatto che
     * tocchino anche {@code dataUltimaModificaAca} — un accoppiamento fragile fra la
     * marcatura ACA e il controllo di concorrenza, vedi il loro Javadoc;
     * {@code aggiungiOpzionePagamento}/{@code aggiorna} non ne hanno bisogno: mutano gia'
     * direttamente campi propri della posizione, che Hibernate rileva da solo.
     */
    @Version
    @Column(name = "versione", nullable = false)
    private long versione;

    /** Business key scelta dal gestionale — vedi Javadoc di classe per {@code idPosizioneDebitoria}. */
    @Column(name = "cod_documento", nullable = false, length = 35)
    private String codDocumento;

    /** FK piatta verso l'anagrafica esterna di govpay-common (M4) — corrisponde a {@code idA2A}. */
    @Column(name = "id_applicazione", nullable = false)
    private Long idApplicazione;

    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    /** Colonna aggiunta: concetto assente in {@code documenti} legacy. */
    @Column(name = "id_unita_operativa")
    private Long idUnitaOperativa;

    /**
     * Ampliata da 255 a 1024 (stessa lunghezza di {@code versamenti.causale_versamento}): la
     * migrazione dati da un DB v2 scrive qui la causale legacy decodificata per i documenti
     * sintetizzati da versamenti orfani, e il cruscotto la vuole intera, non troncata.
     */
    @Column(name = "descrizione", nullable = false, length = 1024)
    private String descrizione;

    /**
     * Colonna aggiunta: concetto assente in {@code documenti} legacy. Data a partire dalla
     * quale la posizione (e tutte le sue pendenze) diventa visibile e pagabile — {@code NULL}
     * significa "pubblicata subito" (semantica dello YAML v3). Presente nel primissimo
     * disegno di questa entità (§3.1 di {@code proposta-modello-nativo-v3.md}), persa
     * durante il pivot al riuso delle tabelle legacy (§17) e ripristinata successivamente.
     *
     * <p><b>Non e' un filtro di lettura di questa libreria</b> (non riaprire senza
     * rileggere §24/§27 del documento): "diventa visibile" nello YAML v3 significa "per ricerca/
     * pagamento esterno (Nodo dei Pagamenti)... resta invece sempre visibile e gestibile per
     * l'applicazione che l'ha creata" — e ogni chiamante di
     * {@code PosizioneDebitoriaService#trovaPerIdentificativo}/{@code cercaPerDebitore} e'
     * sempre l'applicazione proprietaria. Un futuro consumatore che implementi la verifica
     * pagamento verso il Nodo (non ancora esistente) dovra' applicare questo filtro da se',
     * con una propria query — non riusando questi metodi.</p>
     */
    @Column(name = "data_pubblicazione")
    private LocalDate dataPubblicazione;

    /** Colonna aggiunta: concetto assente in {@code documenti} legacy. */
    @Column(name = "notifica_send", nullable = false)
    private boolean notificaSend;

    /** Colonna aggiunta. Deve corrispondere al {@code numeroAvviso} di una pendenza della posizione. */
    @Column(name = "nav_notifica", length = 18)
    private String navNotifica;

    /** Colonna aggiunta. Valorizzata da questa libreria: fa prendere in carico la posizione dal batch ACA. */
    @Column(name = "data_ultima_modifica_aca")
    private OffsetDateTime dataUltimaModificaAca;

    /** Colonna aggiunta. Scritta esclusivamente dal batch ACA, mai da questa libreria. */
    @Column(name = "data_ultima_comunicazione_aca")
    private OffsetDateTime dataUltimaComunicazioneAca;

    /** Colonna aggiunta. */
    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    /** Colonna aggiunta. */
    @Column(name = "data_ultimo_aggiornamento", nullable = false)
    private OffsetDateTime dataUltimoAggiornamento;

    /**
     * Elenco dei soggetti obbligati al pagamento, in ordine: il primo (ordine 0) e' per
     * convenzione il soggetto pagatore usato in RPT e nelle comunicazioni che richiedono un
     * unico destinatario (Nodo dei Pagamenti, ACA/GPD) — vedi nota di classe sulla
     * sincronizzazione verso {@code versamenti.debitore_*}.
     */
    @OneToMany(mappedBy = "posizioneDebitoria", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordine ASC")
    private List<SoggettoDebitore> soggettiDebitori = new ArrayList<>();

    @OneToMany(mappedBy = "posizioneDebitoria", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<OpzionePagamento> opzioniPagamento = new ArrayList<>();

    /**
     * Aggiunge un soggetto debitore mantenendo coerente il lato inverso della relazione.
     * L'assegnazione di {@code ordine} resta a carico del chiamante (servizio di
     * caricamento): l'entita' non decide da sola la posizione nell'elenco.
     *
     * @param soggetto soggetto da aggiungere, non nullo
     */
    public void addSoggettoDebitore(SoggettoDebitore soggetto) {
        Objects.requireNonNull(soggetto, "il soggetto da aggiungere non puo' essere nullo");
        soggettiDebitori.add(soggetto);
        soggetto.setPosizioneDebitoria(this);
    }

    /**
     * Sostituisce l'intero elenco dei soggetti debitori (PATCH), mantenendo coerente il
     * lato inverso della relazione.
     *
     * <p><b>Aggiunge prima i nuovi, rimuove dopo i vecchi</b> — mai il contrario. {@code ordine}
     * ha un vincolo {@code UNIQUE(id_documento, ordine)}: se si rimuovessero prima i vecchi
     * (es. via {@code clear()}) e poi si aggiungessero i nuovi con lo stesso {@code ordine}
     * (0-based, quindi tipicamente sovrapposto), Hibernate potrebbe eseguire l'{@code INSERT}
     * dei nuovi prima del {@code DELETE} (orphan removal) dei vecchi nello stesso flush —
     * viola il vincolo anche se lo stato finale sarebbe valido (riprodotto: sostituire un
     * solo soggetto con un altro, stesso {@code ordine=0}, senza un flush intermedio fra le
     * due fasi). Aggiungendo prima i nuovi con {@code ordine} strettamente maggiore del
     * massimo attuale (mai collidente con le righe esistenti), ne' l'{@code INSERT} ne' la
     * successiva {@code DELETE} possono mai violare il vincolo, qualunque sia l'ordine con
     * cui Hibernate le esegue nel flush — non serve alcun flush intermedio.</p>
     *
     * <p>Conseguenza: {@code ordine} cresce monotonicamente nel tempo, non torna a
     * azzerarsi a ogni sostituzione (dopo una PATCH, i soggetti sopravvissuti non hanno piu'
     * necessariamente {@code ordine} 0-based). Non e' un problema: l'unico uso di questo
     * campo e' come chiave di ordinamento ({@code @OrderBy("ordine ASC")} sopra) — nessun
     * codice ne legge il valore assoluto, solo l'ordine relativo fra i soggetti conta.</p>
     *
     * @param nuovi nuovo elenco di soggetti debitori, non nullo (puo' essere vuoto: la
     *              validazione "almeno un soggetto" e' a carico del chiamante)
     */
    public void sostituisciSoggettiDebitori(List<SoggettoDebitore> nuovi) {
        Objects.requireNonNull(nuovi, "i soggetti debitori non possono essere null");
        List<SoggettoDebitore> vecchi = new ArrayList<>(soggettiDebitori);
        int ordine = vecchi.stream().mapToInt(SoggettoDebitore::getOrdine).max().orElse(-1) + 1;
        for (SoggettoDebitore soggetto : nuovi) {
            soggetto.setOrdine(ordine++);
            addSoggettoDebitore(soggetto);
        }
        soggettiDebitori.removeAll(vecchi);
    }

    /**
     * Aggiunge un'opzione di pagamento mantenendo coerente il lato inverso della
     * relazione.
     *
     * @param opzione opzione da aggiungere, non nulla
     */
    public void addOpzionePagamento(OpzionePagamento opzione) {
        Objects.requireNonNull(opzione, "l'opzione da aggiungere non puo' essere nulla");
        opzioniPagamento.add(opzione);
        opzione.setPosizioneDebitoria(this);
    }

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getVersione() {
        return versione;
    }

    public String getCodDocumento() {
        return codDocumento;
    }

    public void setCodDocumento(String codDocumento) {
        this.codDocumento = codDocumento;
    }

    public Long getIdApplicazione() {
        return idApplicazione;
    }

    public void setIdApplicazione(Long idApplicazione) {
        this.idApplicazione = idApplicazione;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public Long getIdUnitaOperativa() {
        return idUnitaOperativa;
    }

    public void setIdUnitaOperativa(Long idUnitaOperativa) {
        this.idUnitaOperativa = idUnitaOperativa;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public LocalDate getDataPubblicazione() {
        return dataPubblicazione;
    }

    public void setDataPubblicazione(LocalDate dataPubblicazione) {
        this.dataPubblicazione = dataPubblicazione;
    }

    public boolean isNotificaSend() {
        return notificaSend;
    }

    public void setNotificaSend(boolean notificaSend) {
        this.notificaSend = notificaSend;
    }

    public String getNavNotifica() {
        return navNotifica;
    }

    public void setNavNotifica(String navNotifica) {
        this.navNotifica = navNotifica;
    }

    public OffsetDateTime getDataUltimaModificaAca() {
        return dataUltimaModificaAca;
    }

    public void setDataUltimaModificaAca(OffsetDateTime dataUltimaModificaAca) {
        this.dataUltimaModificaAca = dataUltimaModificaAca;
    }

    public OffsetDateTime getDataUltimaComunicazioneAca() {
        return dataUltimaComunicazioneAca;
    }

    public void setDataUltimaComunicazioneAca(OffsetDateTime dataUltimaComunicazioneAca) {
        this.dataUltimaComunicazioneAca = dataUltimaComunicazioneAca;
    }

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }

    public OffsetDateTime getDataUltimoAggiornamento() {
        return dataUltimoAggiornamento;
    }

    public void setDataUltimoAggiornamento(OffsetDateTime dataUltimoAggiornamento) {
        this.dataUltimoAggiornamento = dataUltimoAggiornamento;
    }

    public List<SoggettoDebitore> getSoggettiDebitori() {
        return soggettiDebitori;
    }

    public List<OpzionePagamento> getOpzioniPagamento() {
        return opzioniPagamento;
    }
}
