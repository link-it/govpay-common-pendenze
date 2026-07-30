package it.govpay.pendenze.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.hibernate.annotations.UpdateTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.govpay.pendenze.entity.converter.ImportoConverter;
import it.govpay.pendenze.entity.converter.IncassoConverter;
import it.govpay.pendenze.entity.converter.TipoSoggettoConverter;
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoVersamento;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaTipoVersamento;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.UniqueConstraint;

/**
 * Pendenza: entita' mappata sulla tabella {@code versamenti} dello schema GovPay 3.10.x.
 *
 * <p>Il nome mantiene il doppio vocabolario dello schema: quello che le API chiamano
 * "pendenza" in banca dati e' un "versamento", e le sue voci sono "singoli
 * versamenti".</p>
 *
 * <p><b>Confine dell'aggregato.</b> Le chiavi esterne verso l'anagrafica
 * ({@code id_applicazione}, {@code id_dominio}, {@code id_uo},
 * {@code id_tipo_versamento}, {@code id_tipo_versamento_dominio}) sono mappate come
 * semplici {@code Long}, non come relazioni: dichiararle come {@code @ManyToOne} verso le
 * entita' di {@code govpay-common} accoppierebbe il grafo delle entita' e la persistence
 * unit di ogni consumatore, senza che serva. Relazioni vere solo dentro l'aggregato:
 * {@link SingoloVersamento} e {@link Documento}.</p>
 */
@Entity
@Table(name = "versamenti", uniqueConstraints = @UniqueConstraint(
        name = "unique_versamenti_1", columnNames = {"cod_versamento_ente", "id_applicazione"}))
@SequenceGenerator(name = "seq_versamenti", sequenceName = "seq_versamenti", allocationSize = 1)
public class Versamento {

    private static final Logger log = LoggerFactory.getLogger(Versamento.class);

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_versamenti")
    @Column(name = "id")
    private Long id;

    // ── Riferimenti all'anagrafica: FK, non relazioni ────────────────────────

    @Column(name = "id_applicazione", nullable = false)
    private Long idApplicazione;

    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    @Column(name = "id_uo")
    private Long idUo;

    @Column(name = "id_tipo_versamento", nullable = false)
    private Long idTipoVersamento;

    @Column(name = "id_tipo_versamento_dominio", nullable = false)
    private Long idTipoVersamentoDominio;

    // ── Anagrafica della pendenza ────────────────────────────────────────────

    @Column(name = "cod_versamento_ente", nullable = false, length = 35)
    private String codVersamentoEnte;

    @Column(name = "nome", length = 35)
    private String nome;

    /** Causale codificata: usare {@code CausaleCodec} per leggerla. */
    @Column(name = "causale_versamento", length = 1024)
    private String causaleVersamento;

    @Column(name = "importo_totale", nullable = false)
    @Convert(converter = ImportoConverter.class)
    private BigDecimal importoTotale;

    @Column(name = "tassonomia", length = 35)
    private String tassonomia;

    @Column(name = "tassonomia_avviso", length = 35)
    private String tassonomiaAvviso;

    @Column(name = "cod_lotto", length = 35)
    private String codLotto;

    @Column(name = "cod_versamento_lotto", length = 35)
    private String codVersamentoLotto;

    /** Colonna {@code VARCHAR(35)}: valore grezzo, vedi {@link #annoTributario()}. */
    @Column(name = "cod_anno_tributario", length = 35)
    private String codAnnoTributario;

    @Column(name = "cod_bundlekey", length = 256)
    private String codBundlekey;

    /** JSON opaco: la libreria non lo interpreta. */
    @Column(name = "dati_allegati", columnDefinition = "TEXT")
    private String datiAllegati;

    /** JSON delle proprieta': usare {@code ProprietaPendenzaCodec} per leggerlo. */
    @Column(name = "proprieta", columnDefinition = "TEXT")
    private String proprieta;

    /** Numero di rata **oppure** soglia: usare {@code CodRataCodec}. */
    @Column(name = "cod_rata", length = 35)
    private String codRata;

    @Column(name = "divisione", length = 35)
    private String divisione;

    @Column(name = "direzione", length = 35)
    private String direzione;

    @Column(name = "tipo", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private TipologiaTipoVersamento tipo;

    // ── Debitore ─────────────────────────────────────────────────────────────

    @Column(name = "debitore_tipo", length = 1)
    @Convert(converter = TipoSoggettoConverter.class)
    private TipoSoggetto debitoreTipo;

    @Column(name = "debitore_identificativo", nullable = false, length = 35)
    private String debitoreIdentificativo;

    @Column(name = "debitore_anagrafica", nullable = false, length = 70)
    private String debitoreAnagrafica;

    @Column(name = "debitore_indirizzo", length = 70)
    private String debitoreIndirizzo;

    @Column(name = "debitore_civico", length = 16)
    private String debitoreCivico;

    @Column(name = "debitore_cap", length = 16)
    private String debitoreCap;

    @Column(name = "debitore_localita", length = 35)
    private String debitoreLocalita;

    @Column(name = "debitore_provincia", length = 35)
    private String debitoreProvincia;

    @Column(name = "debitore_nazione", length = 2)
    private String debitoreNazione;

    @Column(name = "debitore_email", length = 256)
    private String debitoreEmail;

    @Column(name = "debitore_telefono", length = 35)
    private String debitoreTelefono;

    @Column(name = "debitore_cellulare", length = 35)
    private String debitoreCellulare;

    @Column(name = "debitore_fax", length = 35)
    private String debitoreFax;

    // ── Stato ────────────────────────────────────────────────────────────────

    @Column(name = "stato_versamento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoVersamento statoVersamento;

    @Column(name = "descrizione_stato", length = 255)
    private String descrizioneStato;

    @Column(name = "anomalo", nullable = false)
    private boolean anomalo;

    @Column(name = "anomalie", columnDefinition = "TEXT")
    private String anomalie;

    @Column(name = "ack", nullable = false)
    private boolean ack;

    /**
     * Indica se, decorsa la data di scadenza, la pendenza va aggiornata interrogando
     * l'ente creditore oppure considerata scaduta.
     */
    @Column(name = "aggiornabile", nullable = false)
    private boolean aggiornabile;

    // ── Avviso e IUV ─────────────────────────────────────────────────────────

    @Column(name = "iuv_versamento", length = 35)
    private String iuvVersamento;

    @Column(name = "numero_avviso", length = 35)
    private String numeroAvviso;

    @Column(name = "iuv_pagamento", length = 35)
    private String iuvPagamento;

    /** Colonna denormalizzata per la ricerca: sempre in maiuscolo. */
    @Column(name = "src_iuv", length = 35)
    private String srcIuv;

    /** Colonna denormalizzata per la ricerca: sempre in maiuscolo. */
    @Column(name = "src_debitore_identificativo", nullable = false, length = 35)
    private String srcDebitoreIdentificativo;

    // ── Pagamento ────────────────────────────────────────────────────────────

    @Column(name = "stato_pagamento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoPagamento statoPagamento;

    @Column(name = "importo_pagato", nullable = false)
    @Convert(converter = ImportoConverter.class)
    private BigDecimal importoPagato;

    @Column(name = "importo_incassato", nullable = false)
    @Convert(converter = ImportoConverter.class)
    private BigDecimal importoIncassato;

    @Column(name = "data_pagamento")
    private OffsetDateTime dataPagamento;

    @Column(name = "incasso", length = 1)
    @Convert(converter = IncassoConverter.class)
    private Boolean incasso;

    // ── Date ─────────────────────────────────────────────────────────────────

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "data_validita")
    private OffsetDateTime dataValidita;

    @Column(name = "data_scadenza")
    private OffsetDateTime dataScadenza;

    /**
     * Aggiornata a ogni scrittura dell'entita' da {@code @UpdateTimestamp}. Alla
     * creazione va valorizzata esplicitamente, perche' la colonna e' {@code NOT NULL} e
     * l'annotazione non interviene sull'insert.
     */
    @UpdateTimestamp
    @Column(name = "data_ora_ultimo_aggiornamento", nullable = false)
    private OffsetDateTime dataOraUltimoAggiornamento;

    // ── Avvisatura ───────────────────────────────────────────────────────────
    // I flag *Notificato sono tri-stato: null = nessuna notifica prevista,
    // false = da notificare (il batch la seleziona), true = notificata.

    @Column(name = "data_notifica_avviso")
    private OffsetDateTime dataNotificaAvviso;

    @Column(name = "avviso_notificato")
    private Boolean avvisoNotificato;

    @Column(name = "avv_mail_data_prom_scadenza")
    private OffsetDateTime avvMailDataPromemoriaScadenza;

    @Column(name = "avv_mail_prom_scad_notificato")
    private Boolean avvMailPromemoriaScadenzaNotificato;

    @Column(name = "avv_app_io_data_prom_scadenza")
    private OffsetDateTime avvAppIoDataPromemoriaScadenza;

    /** Nome della colonna troncato a 30 caratteri per il limite di Oracle. */
    @Column(name = "avv_app_io_prom_scad_notificat")
    private Boolean avvAppIoPromemoriaScadenzaNotificato;

    // ── Sincronizzazione con l'Archivio Centralizzato Avvisi ─────────────────

    /** Valorizzata dalla libreria: e' cio' che fa prendere in carico la pendenza dal batch ACA. */
    @Column(name = "data_ultima_modifica_aca")
    private OffsetDateTime dataUltimaModificaAca;

    /** Scritta esclusivamente dal batch ACA, mai da questa libreria. */
    @Column(name = "data_ultima_comunicazione_aca")
    private OffsetDateTime dataUltimaComunicazioneAca;

    // ── Sessione ─────────────────────────────────────────────────────────────

    @Column(name = "id_sessione", length = 35)
    private String idSessione;

    // ── Relazioni interne all'aggregato ──────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_documento")
    private Documento documento;

    /**
     * Voci della pendenza. {@code orphanRemoval} e' volutamente disattivato: la regola di
     * dominio non ammette la rimozione di voci in aggiornamento, e con
     * {@code orphanRemoval} una lista ricostruita male cancellerebbe righe in silenzio.
     */
    @OneToMany(mappedBy = "versamento", cascade = CascadeType.ALL)
    @OrderBy("indiceDati ASC")
    private List<SingoloVersamento> singoliVersamenti = new ArrayList<>();

    /**
     * Anno tributario come numero. La colonna e' {@code VARCHAR(35)} e in banca dati
     * possono esserci valori non numerici: in quel caso il risultato e' vuoto e viene
     * emesso un log a {@code WARN}, senza far fallire la lettura della pendenza. Il valore
     * persistito resta quello grezzo di {@link #getCodAnnoTributario()}.
     *
     * @return l'anno tributario, vuoto se assente o non numerico
     */
    public Optional<Integer> annoTributario() {
        if (codAnnoTributario == null || codAnnoTributario.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.valueOf(codAnnoTributario.trim()));
        } catch (NumberFormatException e) {
            log.warn("cod_anno_tributario non numerico sulla pendenza [{}]: [{}]",
                    codVersamentoEnte, codAnnoTributario);
            return Optional.empty();
        }
    }

    /**
     * Aggiunge una voce mantenendo coerente il lato inverso della relazione.
     *
     * @param voce voce da aggiungere, non nulla
     */
    public void addSingoloVersamento(SingoloVersamento voce) {
        singoliVersamenti.add(voce);
        voce.setVersamento(this);
    }

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getIdUo() {
        return idUo;
    }

    public void setIdUo(Long idUo) {
        this.idUo = idUo;
    }

    public Long getIdTipoVersamento() {
        return idTipoVersamento;
    }

    public void setIdTipoVersamento(Long idTipoVersamento) {
        this.idTipoVersamento = idTipoVersamento;
    }

    public Long getIdTipoVersamentoDominio() {
        return idTipoVersamentoDominio;
    }

    public void setIdTipoVersamentoDominio(Long idTipoVersamentoDominio) {
        this.idTipoVersamentoDominio = idTipoVersamentoDominio;
    }

    public String getCodVersamentoEnte() {
        return codVersamentoEnte;
    }

    public void setCodVersamentoEnte(String codVersamentoEnte) {
        this.codVersamentoEnte = codVersamentoEnte;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCausaleVersamento() {
        return causaleVersamento;
    }

    public void setCausaleVersamento(String causaleVersamento) {
        this.causaleVersamento = causaleVersamento;
    }

    public BigDecimal getImportoTotale() {
        return importoTotale;
    }

    public void setImportoTotale(BigDecimal importoTotale) {
        this.importoTotale = importoTotale;
    }

    public String getTassonomia() {
        return tassonomia;
    }

    public void setTassonomia(String tassonomia) {
        this.tassonomia = tassonomia;
    }

    public String getTassonomiaAvviso() {
        return tassonomiaAvviso;
    }

    public void setTassonomiaAvviso(String tassonomiaAvviso) {
        this.tassonomiaAvviso = tassonomiaAvviso;
    }

    public String getCodLotto() {
        return codLotto;
    }

    public void setCodLotto(String codLotto) {
        this.codLotto = codLotto;
    }

    public String getCodVersamentoLotto() {
        return codVersamentoLotto;
    }

    public void setCodVersamentoLotto(String codVersamentoLotto) {
        this.codVersamentoLotto = codVersamentoLotto;
    }

    public String getCodAnnoTributario() {
        return codAnnoTributario;
    }

    public void setCodAnnoTributario(String codAnnoTributario) {
        this.codAnnoTributario = codAnnoTributario;
    }

    public String getCodBundlekey() {
        return codBundlekey;
    }

    public void setCodBundlekey(String codBundlekey) {
        this.codBundlekey = codBundlekey;
    }

    public String getDatiAllegati() {
        return datiAllegati;
    }

    public void setDatiAllegati(String datiAllegati) {
        this.datiAllegati = datiAllegati;
    }

    public String getProprieta() {
        return proprieta;
    }

    public void setProprieta(String proprieta) {
        this.proprieta = proprieta;
    }

    public String getCodRata() {
        return codRata;
    }

    public void setCodRata(String codRata) {
        this.codRata = codRata;
    }

    public String getDivisione() {
        return divisione;
    }

    public void setDivisione(String divisione) {
        this.divisione = divisione;
    }

    public String getDirezione() {
        return direzione;
    }

    public void setDirezione(String direzione) {
        this.direzione = direzione;
    }

    public TipologiaTipoVersamento getTipo() {
        return tipo;
    }

    public void setTipo(TipologiaTipoVersamento tipo) {
        this.tipo = tipo;
    }

    public TipoSoggetto getDebitoreTipo() {
        return debitoreTipo;
    }

    public void setDebitoreTipo(TipoSoggetto debitoreTipo) {
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

    public String getDebitoreIndirizzo() {
        return debitoreIndirizzo;
    }

    public void setDebitoreIndirizzo(String debitoreIndirizzo) {
        this.debitoreIndirizzo = debitoreIndirizzo;
    }

    public String getDebitoreCivico() {
        return debitoreCivico;
    }

    public void setDebitoreCivico(String debitoreCivico) {
        this.debitoreCivico = debitoreCivico;
    }

    public String getDebitoreCap() {
        return debitoreCap;
    }

    public void setDebitoreCap(String debitoreCap) {
        this.debitoreCap = debitoreCap;
    }

    public String getDebitoreLocalita() {
        return debitoreLocalita;
    }

    public void setDebitoreLocalita(String debitoreLocalita) {
        this.debitoreLocalita = debitoreLocalita;
    }

    public String getDebitoreProvincia() {
        return debitoreProvincia;
    }

    public void setDebitoreProvincia(String debitoreProvincia) {
        this.debitoreProvincia = debitoreProvincia;
    }

    public String getDebitoreNazione() {
        return debitoreNazione;
    }

    public void setDebitoreNazione(String debitoreNazione) {
        this.debitoreNazione = debitoreNazione;
    }

    public String getDebitoreEmail() {
        return debitoreEmail;
    }

    public void setDebitoreEmail(String debitoreEmail) {
        this.debitoreEmail = debitoreEmail;
    }

    public String getDebitoreTelefono() {
        return debitoreTelefono;
    }

    public void setDebitoreTelefono(String debitoreTelefono) {
        this.debitoreTelefono = debitoreTelefono;
    }

    public String getDebitoreCellulare() {
        return debitoreCellulare;
    }

    public void setDebitoreCellulare(String debitoreCellulare) {
        this.debitoreCellulare = debitoreCellulare;
    }

    public String getDebitoreFax() {
        return debitoreFax;
    }

    public void setDebitoreFax(String debitoreFax) {
        this.debitoreFax = debitoreFax;
    }

    public StatoVersamento getStatoVersamento() {
        return statoVersamento;
    }

    public void setStatoVersamento(StatoVersamento statoVersamento) {
        this.statoVersamento = statoVersamento;
    }

    public String getDescrizioneStato() {
        return descrizioneStato;
    }

    public void setDescrizioneStato(String descrizioneStato) {
        this.descrizioneStato = descrizioneStato;
    }

    public boolean isAnomalo() {
        return anomalo;
    }

    public void setAnomalo(boolean anomalo) {
        this.anomalo = anomalo;
    }

    public String getAnomalie() {
        return anomalie;
    }

    public void setAnomalie(String anomalie) {
        this.anomalie = anomalie;
    }

    public boolean isAck() {
        return ack;
    }

    public void setAck(boolean ack) {
        this.ack = ack;
    }

    public boolean isAggiornabile() {
        return aggiornabile;
    }

    public void setAggiornabile(boolean aggiornabile) {
        this.aggiornabile = aggiornabile;
    }

    public String getIuvVersamento() {
        return iuvVersamento;
    }

    public void setIuvVersamento(String iuvVersamento) {
        this.iuvVersamento = iuvVersamento;
    }

    public String getNumeroAvviso() {
        return numeroAvviso;
    }

    public void setNumeroAvviso(String numeroAvviso) {
        this.numeroAvviso = numeroAvviso;
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

    public String getSrcDebitoreIdentificativo() {
        return srcDebitoreIdentificativo;
    }

    public void setSrcDebitoreIdentificativo(String srcDebitoreIdentificativo) {
        this.srcDebitoreIdentificativo = srcDebitoreIdentificativo;
    }

    public StatoPagamento getStatoPagamento() {
        return statoPagamento;
    }

    public void setStatoPagamento(StatoPagamento statoPagamento) {
        this.statoPagamento = statoPagamento;
    }

    public BigDecimal getImportoPagato() {
        return importoPagato;
    }

    public void setImportoPagato(BigDecimal importoPagato) {
        this.importoPagato = importoPagato;
    }

    public BigDecimal getImportoIncassato() {
        return importoIncassato;
    }

    public void setImportoIncassato(BigDecimal importoIncassato) {
        this.importoIncassato = importoIncassato;
    }

    public OffsetDateTime getDataPagamento() {
        return dataPagamento;
    }

    public void setDataPagamento(OffsetDateTime dataPagamento) {
        this.dataPagamento = dataPagamento;
    }

    public Boolean getIncasso() {
        return incasso;
    }

    public void setIncasso(Boolean incasso) {
        this.incasso = incasso;
    }

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }

    public OffsetDateTime getDataValidita() {
        return dataValidita;
    }

    public void setDataValidita(OffsetDateTime dataValidita) {
        this.dataValidita = dataValidita;
    }

    public OffsetDateTime getDataScadenza() {
        return dataScadenza;
    }

    public void setDataScadenza(OffsetDateTime dataScadenza) {
        this.dataScadenza = dataScadenza;
    }

    public OffsetDateTime getDataOraUltimoAggiornamento() {
        return dataOraUltimoAggiornamento;
    }

    public void setDataOraUltimoAggiornamento(OffsetDateTime dataOraUltimoAggiornamento) {
        this.dataOraUltimoAggiornamento = dataOraUltimoAggiornamento;
    }

    public OffsetDateTime getDataNotificaAvviso() {
        return dataNotificaAvviso;
    }

    public void setDataNotificaAvviso(OffsetDateTime dataNotificaAvviso) {
        this.dataNotificaAvviso = dataNotificaAvviso;
    }

    public Boolean getAvvisoNotificato() {
        return avvisoNotificato;
    }

    public void setAvvisoNotificato(Boolean avvisoNotificato) {
        this.avvisoNotificato = avvisoNotificato;
    }

    public OffsetDateTime getAvvMailDataPromemoriaScadenza() {
        return avvMailDataPromemoriaScadenza;
    }

    public void setAvvMailDataPromemoriaScadenza(OffsetDateTime avvMailDataPromemoriaScadenza) {
        this.avvMailDataPromemoriaScadenza = avvMailDataPromemoriaScadenza;
    }

    public Boolean getAvvMailPromemoriaScadenzaNotificato() {
        return avvMailPromemoriaScadenzaNotificato;
    }

    public void setAvvMailPromemoriaScadenzaNotificato(Boolean avvMailPromemoriaScadenzaNotificato) {
        this.avvMailPromemoriaScadenzaNotificato = avvMailPromemoriaScadenzaNotificato;
    }

    public OffsetDateTime getAvvAppIoDataPromemoriaScadenza() {
        return avvAppIoDataPromemoriaScadenza;
    }

    public void setAvvAppIoDataPromemoriaScadenza(OffsetDateTime avvAppIoDataPromemoriaScadenza) {
        this.avvAppIoDataPromemoriaScadenza = avvAppIoDataPromemoriaScadenza;
    }

    public Boolean getAvvAppIoPromemoriaScadenzaNotificato() {
        return avvAppIoPromemoriaScadenzaNotificato;
    }

    public void setAvvAppIoPromemoriaScadenzaNotificato(Boolean avvAppIoPromemoriaScadenzaNotificato) {
        this.avvAppIoPromemoriaScadenzaNotificato = avvAppIoPromemoriaScadenzaNotificato;
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

    public String getIdSessione() {
        return idSessione;
    }

    public void setIdSessione(String idSessione) {
        this.idSessione = idSessione;
    }

    public Documento getDocumento() {
        return documento;
    }

    public void setDocumento(Documento documento) {
        this.documento = documento;
    }

    public List<SingoloVersamento> getSingoliVersamenti() {
        return singoliVersamenti;
    }

    public void setSingoliVersamenti(List<SingoloVersamento> singoliVersamenti) {
        this.singoliVersamenti = singoliVersamenti;
    }

    /**
     * Uguaglianza per identita' persistente: due istanze sono la stessa pendenza solo se
     * hanno lo stesso {@code id}. Un'entita' non ancora persistita ({@code id} nullo) e'
     * uguale solo a se stessa.
     */
    @Override
    public boolean equals(Object altro) {
        if (this == altro) {
            return true;
        }
        if (!(altro instanceof Versamento that) || id == null || that.id == null) {
            return false;
        }
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : Objects.hash(id);
    }

    /** Volutamente senza relazioni LAZY, per non innescare caricamenti. */
    @Override
    public String toString() {
        return "Versamento[id=" + id
                + ", codVersamentoEnte=" + codVersamentoEnte
                + ", idApplicazione=" + idApplicazione
                + ", stato=" + statoVersamento + "]";
    }
}
