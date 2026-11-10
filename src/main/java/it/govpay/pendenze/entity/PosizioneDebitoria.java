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
 * esiste affatto): {@link #idUnitaOperativa}, {@link #dataPubblicazione},
 * {@link #dataUltimaModificaAca}, {@link #dataUltimaComunicazioneAca},
 * {@link #dataCreazione}, {@link #dataUltimoAggiornamento} — colonne additive, proprie
 * solo di questa libreria. {@link #dataPubblicazione} era gia' nel primissimo disegno
 * (§3.1 di {@code proposta-modello-nativo-v3.md}), persa durante il pivot al riuso delle
 * tabelle legacy (§17) e ripristinata successivamente.</p>
 *
 * <p><b>{@link #pdDebitori} punta a {@code pd_debitori}</b>, tabella nuova con i debitori
 * della posizione: ciascuno ha le proprie {@link OpzionePagamento}, quindi il proprio piano di
 * pagamento e i propri NAV. L'anagrafica del debitore NON viene sincronizzata su
 * {@code versamenti.debitore_*}: quelle colonne restano {@code NOT NULL} in produzione ma
 * valorizzate con placeholder fissi, vedi Javadoc di {@link Pendenza}.</p>
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
public class PosizioneDebitoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    /** Colonna aggiunta: analoga a {@link Pendenza#isAnomalo()}, a livello di posizione. */
    @Column(name = "anomalo", nullable = false)
    private boolean anomalo = false;

    /** Colonna aggiunta: analoga a {@link Pendenza#getDescrizioneStato()}, a livello di posizione. */
    @Column(name = "descrizione_stato", length = 255)
    private String descrizioneStato;

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

    /** I debitori della posizione, ciascuno con le proprie opzioni di pagamento. */
    @OneToMany(mappedBy = "posizioneDebitoria", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("posizione ASC")
    private List<PdDebitore> pdDebitori = new ArrayList<>();

    /**
     * Aggiunge un debitore mantenendo coerente il lato inverso della relazione.
     * L'assegnazione di {@code posizione} resta a carico del chiamante (servizio di
     * caricamento): l'entita' non decide da sola la posizione nell'elenco.
     *
     * @param pdDebitore debitore da aggiungere, non nullo
     */
    public void addPdDebitore(PdDebitore pdDebitore) {
        Objects.requireNonNull(pdDebitore, "il debitore da aggiungere non puo' essere nullo");
        pdDebitori.add(pdDebitore);
        pdDebitore.setPosizioneDebitoria(this);
    }

    /**
     * Tutte le opzioni di pagamento della posizione, di tutti i suoi debitori: e' l'insieme su
     * cui vale la mutua esclusione. Vista di sola lettura, calcolata a ogni chiamata.
     */
    public List<OpzionePagamento> getOpzioniPagamento() {
        return pdDebitori.stream().flatMap(d -> d.getOpzioniPagamento().stream()).toList();
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

    public boolean isAnomalo() {
        return anomalo;
    }

    public void setAnomalo(boolean anomalo) {
        this.anomalo = anomalo;
    }

    public String getDescrizioneStato() {
        return descrizioneStato;
    }

    public void setDescrizioneStato(String descrizioneStato) {
        this.descrizioneStato = descrizioneStato;
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

    public List<PdDebitore> getPdDebitori() {
        return pdDebitori;
    }
}
