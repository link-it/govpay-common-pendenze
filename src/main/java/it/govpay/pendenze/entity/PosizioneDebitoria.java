package it.govpay.pendenze.entity;

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

/**
 * Posizione debitoria: radice dell'aggregato pendenza, mappata sulla tabella legacy
 * {@code documenti} (decisione del lead, 2026-09-25: riuso diretto invece di uno schema
 * v3 separato, per minimizzare la differenza strutturale da v2 e ridurre al minimo la
 * migrazione dati — vedi {@code proposta-modello-nativo-v3.md} §17).
 *
 * <p><b>{@code idA2A} non e' una colonna</b>: e' esattamente
 * {@code Applicazione.codApplicazione} (confermato dal lead, 2026-09-24) — questa entita'
 * espone solo {@link #idApplicazione}, FK piatta verso l'anagrafica esterna di
 * govpay-common (M4: nessuna relazione JPA). La risoluzione idA2A &#8596; idApplicazione
 * e' compito del chiamante (repository/service), non di questa entita'.</p>
 *
 * <p><b>Colonne aggiunte a {@code documenti}</b> (assenti nel legacy, dove il concetto non
 * esiste affatto): {@link #idUnitaOperativa}, {@link #notificaSend}, {@link #navNotifica},
 * {@link #dataUltimaModificaAca}, {@link #dataUltimaComunicazioneAca},
 * {@link #dataCreazione}, {@link #dataUltimoAggiornamento} — 7 colonne additive, non le 3
 * inizialmente stimate (mancava di considerare unita' operativa/nav-notifica/ACA, propri
 * solo di questa libreria).</p>
 *
 * <p><b>{@link #soggettiDebitori} punta a {@code soggetti_debitori}</b>, tabella nuova che
 * contiene TUTTI i debitori, incluso il primo (decisione del lead, 2026-09-25, corregge una
 * proposta precedente che teneva il primo solo su {@code versamenti.debitore_*}): il
 * debitore appartiene logicamente al documento, non al singolo versamento. Il primo soggetto
 * (ordine 0) NON viene sincronizzato su {@code versamenti.debitore_*} (decisione del lead,
 * 2026-09-26, dopo un tentativo intermedio di sincronizzarlo davvero, poi scartato: quella
 * lista resta modificabile dopo la creazione, tenerli allineati nel tempo sarebbe complessita'
 * pura) — quelle colonne restano {@code NOT NULL} in produzione ma valorizzate con placeholder
 * fissi, vedi Javadoc di {@link Pendenza}.</p>
 *
 * <p><b>{@code unique_documenti_applicazione}</b> ({@code cod_documento}+{@code id_applicazione},
 * senza {@code id_dominio}): vincolo aggiunto in migrazione (decisione del lead, 2026-09-26)
 * per far corrispondere l'identita' pubblica di {@code idPosizioneDebitoria} (chiavata solo su
 * {@code idA2A}+{@code idPosizioneDebitoria} nello YAML v3) al vincolo DB reale — senza,
 * {@link it.govpay.pendenze.repository.PosizioneDebitoriaRepository#findByIdApplicazioneAndIdPosizioneDebitoria}
 * potrebbe trovare piu' righe (stesso {@code idPosizioneDebitoria} su domini diversi) e il
 * controllo applicativo in {@code PosizioneDebitoriaService#crea} da solo non basterebbe
 * contro creazioni concorrenti. Il vincolo storico {@code unique_documenti_1} (con
 * {@code id_dominio}) resta, ridondante ma innocuo.</p>
 */
@Entity
@Table(name = "documenti", uniqueConstraints = {
        @UniqueConstraint(name = "unique_documenti_1", columnNames = {"cod_documento", "id_applicazione", "id_dominio"}),
        @UniqueConstraint(name = "unique_documenti_applicazione", columnNames = {"cod_documento", "id_applicazione"})})
@SequenceGenerator(name = "seq_documenti", sequenceName = "seq_documenti", allocationSize = 1)
public class PosizioneDebitoria {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_documenti")
    @Column(name = "id")
    private Long id;

    /** {@code idPosizioneDebitoria} dello YAML v3. */
    @Column(name = "cod_documento", nullable = false, length = 35)
    private String idPosizioneDebitoria;

    /** FK piatta verso l'anagrafica esterna di govpay-common (M4) — corrisponde a {@code idA2A}. */
    @Column(name = "id_applicazione", nullable = false)
    private Long idApplicazione;

    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    /** Colonna aggiunta: concetto assente in {@code documenti} legacy. */
    @Column(name = "id_unita_operativa")
    private Long idUnitaOperativa;

    @Column(name = "descrizione", nullable = false, length = 255)
    private String descrizione;

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

    public String getIdPosizioneDebitoria() {
        return idPosizioneDebitoria;
    }

    public void setIdPosizioneDebitoria(String idPosizioneDebitoria) {
        this.idPosizioneDebitoria = idPosizioneDebitoria;
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
