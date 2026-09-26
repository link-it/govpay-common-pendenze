package it.govpay.pendenze.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoPendenza;

/**
 * Pendenza (rata): mappata sulla tabella legacy {@code versamenti} (decisione del lead,
 * 2026-09-25: riuso diretto invece di una tabella v3 separata — vedi
 * {@code proposta-modello-nativo-v3.md} §17). Questa e' l'entita' con la mappatura piu'
 * estesa dell'intero riuso: {@code versamenti} ha ~50 colonne rilevanti, non le ~15 della
 * precedente {@code pendenze} nativa.
 *
 * <p><b>Colonne aggiunte</b> (assenti nel legacy): {@link #idOpzionePagamento} (FK
 * nullable — {@code NULL} per ogni riga creata da v2, che non conosce questo concetto),
 * {@link #numeroRata} ({@code cod_rata} resta per la sola compatibilita' di lettura v2,
 * v3 lo lascia sempre {@code NULL} sulle proprie righe — vedi Javadoc di
 * {@link #numeroRata}), {@link #dataCaricamento} (concetto YAML v3 — "data di emissione
 * della pendenza" — distinto da {@link #dataCreazione}, senza equivalente legacy).</p>
 *
 * <p><b>Due FK per il tipo pendenza</b> ({@link #idTipoPendenza}/{@link #idTipoVersamento}):
 * il legacy ha due livelli, {@code tipi_versamento} (catalogo astratto, es. "IMU") e
 * {@code tipi_vers_domini} (istanza/override per dominio) — {@code versamenti.id_tipo_versamento}
 * e' una denormalizzazione di comodo di quanto gia' risolvibile da
 * {@code tipi_vers_domini.id_tipo_versamento}, non un'informazione indipendente. Decisione
 * del lead, 2026-09-25: per semplicita' della libreria, e' il chiamante a fornire
 * entrambi gli ID (M4 puro, nessuna query di questa libreria verso l'anagrafica esterna,
 * nemmeno per derivare il secondo dal primo).</p>
 *
 * <p><b>Debitore denormalizzato: placeholder fissi, non sincronizzati</b> (decisione del lead,
 * 2026-09-26, dopo un tentativo intermedio di sincronizzarli davvero, poi scartato):
 * {@link #debitoreTipo}/{@link #debitoreIdentificativo}/{@link #debitoreAnagrafica}/
 * {@link #srcDebitoreIdentificativo} restano {@code NOT NULL} in produzione (tranne
 * {@code debitoreTipo}, nullable — lasciato indefinito). Verificato che il motore di
 * pagamento legacy (attivazione RPT verso il Nodo, {@code CtPaymentPABuilder.buildSoggettoPagatore};
 * stampa dell'avviso PDF, {@code AvvisoPagamentoUtils.impostaAnagraficaDebitore}) legge
 * queste colonne direttamente — ma quella pipeline e' essa stessa parte di cio' che verra'
 * sostituito/adattato a fine transizione v3 (quando leggera' {@code soggetti_debitori}
 * direttamente, non piu' queste colonne): fino ad allora resta un gap noto e accettato per
 * l'attivazione di un pagamento reale su una pendenza v3 attraverso l'endpoint non ancora
 * adattato, non qualcosa che questa libreria deve compensare fingendo un dato che potrebbe
 * comunque disallinearsi (il soggetto di ordine 0 e' modificabile dopo la creazione).
 * Placeholder fissi ed esplicativi soddisfano solo il vincolo {@code NOT NULL} reale.</p>
 *
 * <p><b>{@link #srcIuv}</b> e' invece una denormalizzazione viva, calcolata dal chiamante:
 * {@code UPPERCASE(iuv)}, per la ricerca case-insensitive (verificato in
 * {@code proposta-libreria-pendenze.md} §4.5) — applicando la regola corretta su ogni
 * percorso di scrittura (il legacy ha un bug qui su un solo percorso, non replicato).</p>
 *
 * <p><b>Stato allineato al legacy</b>: {@link StatoPendenza} ha esattamente gli 8 valori
 * di {@code StatoVersamento} (non i 6 dello YAML v3 attuale, da correggere — vedi Javadoc
 * di {@link StatoPendenza}). {@link #statoPagamento} e' un concetto legacy distinto
 * ({@code StatoPagamento}: {@code PAGATO}/{@code INCASSATO}/{@code NON_PAGATO}), sempre
 * {@code NON_PAGATO} alla creazione (verificato in {@code Versamento.java:293} del
 * business layer legacy).</p>
 *
 * <p><b>Altri default legacy alla creazione</b> (colonne operative senza uso proprio in
 * v3): {@code tipo="DOVUTO"} (coerente con la decisione che v3 carica solo pendenze
 * DOVUTO), {@code ack=true}, {@code anomalo=false}, {@code aggiornabile=false},
 * {@code importoPagato}/{@code importoIncassato=0}, {@code sendAbilitato} sincronizzato
 * con {@link PosizioneDebitoria#isNotificaSend()} della posizione.</p>
 */
@Entity
@Table(name = "versamenti", uniqueConstraints = @UniqueConstraint(
        name = "unique_versamenti_1", columnNames = {"cod_versamento_ente", "id_applicazione"}))
@SequenceGenerator(name = "seq_versamenti", sequenceName = "seq_versamenti", allocationSize = 1)
public class Pendenza {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_versamenti")
    @Column(name = "id")
    private Long id;

    /** {@code NULL} per le righe create da v2, che non conosce questo concetto. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_opzione_pagamento")
    private OpzionePagamento opzionePagamento;

    /** FK piatta verso l'anagrafica esterna di govpay-common (M4). */
    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    /** FK piatta verso l'anagrafica esterna di govpay-common (M4). */
    @Column(name = "id_applicazione", nullable = false)
    private Long idApplicazione;

    /** {@code idPendenza} dello YAML v3. */
    @Column(name = "cod_versamento_ente", nullable = false, length = 35)
    private String idPendenza;

    /** {@code id_tipo_versamento_dominio}: l'istanza/override per dominio, vedi nota di classe. */
    @Column(name = "id_tipo_versamento_dominio", nullable = false)
    private Long idTipoPendenza;

    /** {@code id_tipo_versamento}: il catalogo astratto, vedi nota di classe. */
    @Column(name = "id_tipo_versamento", nullable = false)
    private Long idTipoVersamento;

    /**
     * Codifica IUV del tipo pendenza, usata per risolvere i placeholder {@code %(p)}/
     * {@code %(t)} del prefisso IUV di dominio in
     * {@link it.govpay.pendenze.spi.GeneratoreIuv#genera}. Transiente, mai persistita:
     * questa libreria non ha accesso all'anagrafica {@code tipi_versamento}/
     * {@code tipi_vers_domini} (M4), il chiamante che la conosce la valorizza qui.
     */
    @Transient
    private String codificaIuvTipoPendenza;

    /**
     * Posizione di questa pendenza nell'elenco {@code pendenze} della sua opzione di
     * pagamento, per qualunque tipologia (non solo {@code PIANO_RATEALE}) — colonna
     * aggiunta: {@code cod_rata} non basta piu' perche' mischiava tipologia e posizione
     * nello stesso formato, e verrebbe frainteso da un lettore legacy (una
     * {@code SOLUZIONE_UNICA_ENTRO} con {@code cod_rata="1"} sarebbe indistinguibile da
     * una vera rata di piano rateale). {@code cod_rata} resta {@code NULL} sulle righe v3.
     */
    @Column(name = "numero_rata", nullable = false)
    private int numeroRata;

    @Column(name = "importo_totale", nullable = false)
    private double importo;

    /** NAV: identificativo dell'avviso di pagamento pagoPA associato alla pendenza. */
    @Column(name = "numero_avviso", length = 35)
    private String numeroAvviso;

    @Column(name = "iuv_versamento", length = 35)
    private String iuv;

    /** IUV con cui e' stato effettivamente registrato il pagamento, se diverso da {@link #iuv}. */
    @Column(name = "iuv_pagamento", length = 35)
    private String iuvPagamento;

    /** {@code UPPERCASE(iuv)}, denormalizzazione per ricerca case-insensitive — vedi nota di classe. */
    @Column(name = "src_iuv", length = 35)
    private String srcIuv;

    // ── Debitore denormalizzato (sincronizzato dal servizio) ────────────────────

    /** Nullable in produzione: lasciato indefinito, vedi nota di classe. */
    @Column(name = "debitore_tipo", length = 1)
    private String debitoreTipo;

    /** Placeholder fisso — vedi nota di classe. */
    @Column(name = "debitore_identificativo", nullable = false, length = 35)
    private String debitoreIdentificativo = "VEDERE_SOGGETTI_DEBITORI";

    /** Placeholder fisso — vedi nota di classe. */
    @Column(name = "debitore_anagrafica", nullable = false, length = 70)
    private String debitoreAnagrafica = "Vedere tabella soggetti_debitori";

    /** Placeholder fisso (gia' in maiuscolo) — vedi nota di classe. */
    @Column(name = "src_debitore_identificativo", nullable = false, length = 35)
    private String srcDebitoreIdentificativo = "VEDERE_SOGGETTI_DEBITORI";

    @Column(name = "stato_versamento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoPendenza stato = StatoPendenza.NON_ESEGUITO;

    /** Concetto legacy distinto da {@link #stato}, sempre {@code NON_PAGATO} alla creazione. */
    @Column(name = "stato_pagamento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoPagamento statoPagamento = StatoPagamento.NON_PAGATO;

    /** Sempre {@code "DOVUTO"}: v3 carica solo pendenze DOVUTO (decisione del lead). */
    @Column(name = "tipo", nullable = false, length = 35)
    private String tipo = "DOVUTO";

    @Column(name = "ack", nullable = false)
    private boolean ack = true;

    @Column(name = "anomalo", nullable = false)
    private boolean anomalo = false;

    @Column(name = "aggiornabile", nullable = false)
    private boolean aggiornabile = false;

    @Column(name = "importo_pagato", nullable = false)
    private double importoPagato = 0d;

    @Column(name = "importo_incassato", nullable = false)
    private double importoIncassato = 0d;

    /** Sincronizzato con {@link PosizioneDebitoria#isNotificaSend()} dal servizio di caricamento. */
    @Column(name = "send_abilitato", nullable = false)
    private boolean sendAbilitato = false;

    /** {@code TIMESTAMP} in produzione (come {@code data_validita}/{@code data_scadenza} sotto), non DATE. */
    @Column(name = "data_pagamento")
    private OffsetDateTime dataPagamento;

    /** Colonna aggiunta: "data di emissione della pendenza" (YAML v3) — vedi nota di classe. */
    @Column(name = "data_caricamento", nullable = false)
    private LocalDate dataCaricamento;

    @Column(name = "data_validita")
    private OffsetDateTime dataValidita;

    /**
     * Se assente, si usa la scadenza dell'opzione di pagamento (semantica dello YAML v3).
     * Mappata su {@code versamenti.data_scadenza} ({@code TIMESTAMP} in produzione, non DATE).
     */
    @Column(name = "data_scadenza")
    private OffsetDateTime dataScadenzaAvviso;

    /** Valorizzata da questa libreria: fa prendere in carico la pendenza dal batch ACA. */
    @Column(name = "data_ultima_modifica_aca")
    private OffsetDateTime dataUltimaModificaAca;

    /** Scritta esclusivamente dal batch ACA, mai da questa libreria. */
    @Column(name = "data_ultima_comunicazione_aca")
    private OffsetDateTime dataUltimaComunicazioneAca;

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "data_ora_ultimo_aggiornamento", nullable = false)
    private OffsetDateTime dataUltimoAggiornamento;

    @OneToMany(mappedBy = "pendenza", cascade = CascadeType.ALL)
    @OrderBy("indice ASC")
    private List<VocePendenza> voci = new ArrayList<>();

    /**
     * Aggiunge una voce mantenendo coerente il lato inverso della relazione.
     *
     * @param voce voce da aggiungere, non nulla
     */
    public void addVocePendenza(VocePendenza voce) {
        Objects.requireNonNull(voce, "la voce da aggiungere non puo' essere nulla");
        voci.add(voce);
        voce.setPendenza(this);
    }

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OpzionePagamento getOpzionePagamento() {
        return opzionePagamento;
    }

    public void setOpzionePagamento(OpzionePagamento opzionePagamento) {
        this.opzionePagamento = opzionePagamento;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public Long getIdApplicazione() {
        return idApplicazione;
    }

    public void setIdApplicazione(Long idApplicazione) {
        this.idApplicazione = idApplicazione;
    }

    public String getIdPendenza() {
        return idPendenza;
    }

    public void setIdPendenza(String idPendenza) {
        this.idPendenza = idPendenza;
    }

    public Long getIdTipoPendenza() {
        return idTipoPendenza;
    }

    public void setIdTipoPendenza(Long idTipoPendenza) {
        this.idTipoPendenza = idTipoPendenza;
    }

    public Long getIdTipoVersamento() {
        return idTipoVersamento;
    }

    public void setIdTipoVersamento(Long idTipoVersamento) {
        this.idTipoVersamento = idTipoVersamento;
    }

    public String getCodificaIuvTipoPendenza() {
        return codificaIuvTipoPendenza;
    }

    public void setCodificaIuvTipoPendenza(String codificaIuvTipoPendenza) {
        this.codificaIuvTipoPendenza = codificaIuvTipoPendenza;
    }

    public int getNumeroRata() {
        return numeroRata;
    }

    public void setNumeroRata(int numeroRata) {
        this.numeroRata = numeroRata;
    }

    public double getImporto() {
        return importo;
    }

    public void setImporto(double importo) {
        this.importo = importo;
    }

    public String getNumeroAvviso() {
        return numeroAvviso;
    }

    public void setNumeroAvviso(String numeroAvviso) {
        this.numeroAvviso = numeroAvviso;
    }

    public String getIuv() {
        return iuv;
    }

    public void setIuv(String iuv) {
        this.iuv = iuv;
    }

    public String getIuvPagamento() {
        return iuvPagamento;
    }

    public void setIuvPagamento(String iuvPagamento) {
        this.iuvPagamento = iuvPagamento;
    }

    public String getSrcIuv() {
        return srcIuv;
    }

    public void setSrcIuv(String srcIuv) {
        this.srcIuv = srcIuv;
    }

    public String getDebitoreTipo() {
        return debitoreTipo;
    }

    public void setDebitoreTipo(String debitoreTipo) {
        this.debitoreTipo = debitoreTipo;
    }

    public String getDebitoreIdentificativo() {
        return debitoreIdentificativo;
    }

    public void setDebitoreIdentificativo(String debitoreIdentificativo) {
        this.debitoreIdentificativo = debitoreIdentificativo;
    }

    public String getDebitoreAnagrafica() {
        return debitoreAnagrafica;
    }

    public void setDebitoreAnagrafica(String debitoreAnagrafica) {
        this.debitoreAnagrafica = debitoreAnagrafica;
    }

    public String getSrcDebitoreIdentificativo() {
        return srcDebitoreIdentificativo;
    }

    public void setSrcDebitoreIdentificativo(String srcDebitoreIdentificativo) {
        this.srcDebitoreIdentificativo = srcDebitoreIdentificativo;
    }

    public StatoPendenza getStato() {
        return stato;
    }

    public void setStato(StatoPendenza stato) {
        this.stato = stato;
    }

    public StatoPagamento getStatoPagamento() {
        return statoPagamento;
    }

    public void setStatoPagamento(StatoPagamento statoPagamento) {
        this.statoPagamento = statoPagamento;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public boolean isAck() {
        return ack;
    }

    public void setAck(boolean ack) {
        this.ack = ack;
    }

    public boolean isAnomalo() {
        return anomalo;
    }

    public void setAnomalo(boolean anomalo) {
        this.anomalo = anomalo;
    }

    public boolean isAggiornabile() {
        return aggiornabile;
    }

    public void setAggiornabile(boolean aggiornabile) {
        this.aggiornabile = aggiornabile;
    }

    public double getImportoPagato() {
        return importoPagato;
    }

    public void setImportoPagato(double importoPagato) {
        this.importoPagato = importoPagato;
    }

    public double getImportoIncassato() {
        return importoIncassato;
    }

    public void setImportoIncassato(double importoIncassato) {
        this.importoIncassato = importoIncassato;
    }

    public boolean isSendAbilitato() {
        return sendAbilitato;
    }

    public void setSendAbilitato(boolean sendAbilitato) {
        this.sendAbilitato = sendAbilitato;
    }

    public OffsetDateTime getDataPagamento() {
        return dataPagamento;
    }

    public void setDataPagamento(OffsetDateTime dataPagamento) {
        this.dataPagamento = dataPagamento;
    }

    public LocalDate getDataCaricamento() {
        return dataCaricamento;
    }

    public void setDataCaricamento(LocalDate dataCaricamento) {
        this.dataCaricamento = dataCaricamento;
    }

    public OffsetDateTime getDataValidita() {
        return dataValidita;
    }

    public void setDataValidita(OffsetDateTime dataValidita) {
        this.dataValidita = dataValidita;
    }

    public OffsetDateTime getDataScadenzaAvviso() {
        return dataScadenzaAvviso;
    }

    public void setDataScadenzaAvviso(OffsetDateTime dataScadenzaAvviso) {
        this.dataScadenzaAvviso = dataScadenzaAvviso;
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

    public List<VocePendenza> getVoci() {
        return voci;
    }
}
