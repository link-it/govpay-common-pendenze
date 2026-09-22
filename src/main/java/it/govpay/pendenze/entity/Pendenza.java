package it.govpay.pendenze.entity;

import java.math.BigDecimal;
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
import jakarta.persistence.UniqueConstraint;

import it.govpay.pendenze.model.StatoPendenza;

/**
 * Pendenza (rata): mappata sulla tabella {@code pendenze}.
 *
 * <p>{@link #numeroAvviso} coincide con il concetto di NAV usato nell'interfaccia
 * ACA/GPD di pagoPA (M8 di {@code proposta-modello-nativo-v3.md}): nessuna colonna
 * duplicata per quel concetto. Le coppie {@link #dataUltimaModificaAca}/
 * {@link #dataUltimaComunicazioneAca} vivono qui (oltre che su
 * {@link PosizioneDebitoria}) perche' l'oggetto che l'Archivio Centralizzato Avvisi
 * archivia e' l'avviso di pagamento, che nasce per singola pendenza.</p>
 *
 * <p>{@link StatoPendenza} non include {@code SCADUTA}: e' uno stato derivato
 * (pendenza {@code NON_ESEGUITA} con scadenza nel passato), calcolato da un livello
 * successivo, non persistito qui.</p>
 */
@Entity
@Table(name = "pendenze", uniqueConstraints = {
        @UniqueConstraint(name = "unique_pendenze_numero_avviso", columnNames = "numero_avviso"),
        @UniqueConstraint(name = "unique_pendenze_iuv", columnNames = "iuv")
})
@SequenceGenerator(name = "seq_pendenze", sequenceName = "seq_pendenze", allocationSize = 1)
public class Pendenza {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_pendenze")
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_opzione_pagamento", nullable = false)
    private OpzionePagamento opzionePagamento;

    @Column(name = "id_pendenza", nullable = false, length = 35)
    private String idPendenza;

    @Column(name = "id_tipo_pendenza", nullable = false)
    private Long idTipoPendenza;

    /**
     * Posizione (1-based) di questa pendenza nell'elenco {@code pendenze} della sua
     * opzione di pagamento. Assegnato dal servizio di caricamento, non da questa
     * entita' (vedi {@link OpzionePagamento#addPendenza(Pendenza)}).
     */
    @Column(name = "numero_rata", nullable = false)
    private int numeroRata;

    @Column(name = "importo", nullable = false, precision = 19, scale = 2)
    private BigDecimal importo;

    /** NAV: identificativo dell'avviso di pagamento pagoPA associato alla pendenza. */
    @Column(name = "numero_avviso", nullable = false, length = 18)
    private String numeroAvviso;

    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    @Column(name = "stato", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoPendenza stato = StatoPendenza.NON_ESEGUITA;

    @Column(name = "data_pagamento")
    private LocalDate dataPagamento;

    @Column(name = "data_caricamento", nullable = false)
    private LocalDate dataCaricamento;

    @Column(name = "data_validita")
    private LocalDate dataValidita;

    /** Se assente, si usa la scadenza dell'opzione di pagamento (semantica dello YAML v3). */
    @Column(name = "data_scadenza_avviso")
    private LocalDate dataScadenzaAvviso;

    /** Valorizzata da questa libreria: fa prendere in carico la pendenza dal batch ACA. */
    @Column(name = "data_ultima_modifica_aca")
    private OffsetDateTime dataUltimaModificaAca;

    /** Scritta esclusivamente dal batch ACA, mai da questa libreria. */
    @Column(name = "data_ultima_comunicazione_aca")
    private OffsetDateTime dataUltimaComunicazioneAca;

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "data_ultimo_aggiornamento", nullable = false)
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

    public int getNumeroRata() {
        return numeroRata;
    }

    public void setNumeroRata(int numeroRata) {
        this.numeroRata = numeroRata;
    }

    public BigDecimal getImporto() {
        return importo;
    }

    public void setImporto(BigDecimal importo) {
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

    public StatoPendenza getStato() {
        return stato;
    }

    public void setStato(StatoPendenza stato) {
        this.stato = stato;
    }

    public LocalDate getDataPagamento() {
        return dataPagamento;
    }

    public void setDataPagamento(LocalDate dataPagamento) {
        this.dataPagamento = dataPagamento;
    }

    public LocalDate getDataCaricamento() {
        return dataCaricamento;
    }

    public void setDataCaricamento(LocalDate dataCaricamento) {
        this.dataCaricamento = dataCaricamento;
    }

    public LocalDate getDataValidita() {
        return dataValidita;
    }

    public void setDataValidita(LocalDate dataValidita) {
        this.dataValidita = dataValidita;
    }

    public LocalDate getDataScadenzaAvviso() {
        return dataScadenzaAvviso;
    }

    public void setDataScadenzaAvviso(LocalDate dataScadenzaAvviso) {
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
