package it.govpay.pendenze.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import it.govpay.pendenze.model.StatoFlussoRendicontazione;

/**
 * Dati di testata di un flusso di rendicontazione pagoPA (schema {@code FlussoRendicontazione}
 * dello YAML v3), mappata sulla tabella {@code flussi_rendicontazione}.
 *
 * <p><b>Tabella separata, non incorporata in {@link Rendicontazione}</b> (a differenza di una
 * prima ipotesi): un flusso raggruppa più rendicontazioni (`{@link Rendicontazione#getFlusso()}`
 * verso questa entità), come nel legacy (`fr`/`rendicontazioni`, FK `rendicontazioni.id_fr`) —
 * incorporarlo per riga avrebbe duplicato inutilmente gli stessi dati di testata.</p>
 *
 * <p>{@link #idDominio} e' una FK piatta verso l'anagrafica di govpay-common (M4: nessuna
 * relazione JPA verso l'esterno dell'aggregato).</p>
 *
 * <p><b>{@link #importoTotale} è {@code NUMERIC(19,2)}, non {@code DOUBLE PRECISION}</b> come
 * la colonna legacy equivalente (`fr.importo_totale_pagamenti`): stesso principio già
 * applicato a {@code Pendenza}/{@code VocePendenza}, per evitare la classe di bug di
 * precisione nota — la conversione in migrazione è un cast, non una trasformazione
 * strutturale.</p>
 *
 * <p><b>{@link #revisione}/{@link #obsoleto} preservano le versioni del flusso</b>, come nel
 * legacy (bug del lead, 2026-09-24: la prima versione con solo {@code id_dominio}/
 * {@code id_flusso} univoci impediva di conservare più revisioni dello stesso flusso). Nel
 * legacy (`Rendicontazioni.java`, business layer) una nuova acquisizione dello stesso
 * {@code cod_dominio}+{@code cod_flusso} non sovrascrive la riga esistente: inserisce una
 * nuova riga con {@code revisione} incrementata, marcando obsoleta quella con la
 * {@code data_ora_flusso} più vecchia (`fr.obsoleto`/`fr.revisione`, vincoli
 * {@code unique_fr_1}/{@code unique_fr_2}) — ogni {@link Rendicontazione} resta collegata alla
 * specifica revisione del flusso con cui è stata acquisita, non all'ultima. Questa libreria non
 * calcola essa stessa la prossima revisione né decide quale riga marcare obsoleta (nessuna
 * logica di acquisizione qui, vedi Javadoc di {@link it.govpay.pendenze.service.RicevutaRendicontazioneService}):
 * si limita a offrire uno schema che possa conservarle tutte.</p>
 */
@Entity
@Table(name = "flussi_rendicontazione", uniqueConstraints = {
        @UniqueConstraint(name = "unique_flussi_rendicontazione_1",
                columnNames = {"id_dominio", "id_flusso", "data_flusso"}),
        @UniqueConstraint(name = "unique_flussi_rendicontazione_2",
                columnNames = {"id_dominio", "id_flusso", "id_psp", "revisione"})
})
@SequenceGenerator(name = "seq_flussi_rendicontazione", sequenceName = "seq_flussi_rendicontazione", allocationSize = 1)
public class FlussoRendicontazione {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_flussi_rendicontazione")
    @Column(name = "id")
    private Long id;

    /** Dominio creditore del flusso — FK piatta verso l'anagrafica di govpay-common (M4). */
    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    @Column(name = "id_flusso", nullable = false, length = 35)
    private String idFlusso;

    @Column(name = "data_flusso", nullable = false)
    private OffsetDateTime dataFlusso;

    @Column(name = "trn", nullable = false, length = 35)
    private String trn;

    @Column(name = "data_regolamento", nullable = false)
    private OffsetDateTime dataRegolamento;

    @Column(name = "id_psp", nullable = false, length = 35)
    private String idPsp;

    @Column(name = "bic_riversamento", length = 35)
    private String bicRiversamento;

    @Column(name = "numero_pagamenti", nullable = false)
    private int numeroPagamenti;

    @Column(name = "importo_totale", nullable = false, precision = 19, scale = 2)
    private BigDecimal importoTotale;

    @Column(name = "stato", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoFlussoRendicontazione stato;

    /** Numero di revisione del flusso, univoco per dominio+identificativo+psp (vedi nota di classe). */
    @Column(name = "revisione", nullable = false)
    private Long revisione;

    /** {@code true} se questa riga è stata soppiantata da una revisione più recente (vedi nota di classe). */
    @Column(name = "obsoleto", nullable = false)
    private boolean obsoleto;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public String getIdFlusso() {
        return idFlusso;
    }

    public void setIdFlusso(String idFlusso) {
        this.idFlusso = idFlusso;
    }

    public OffsetDateTime getDataFlusso() {
        return dataFlusso;
    }

    public void setDataFlusso(OffsetDateTime dataFlusso) {
        this.dataFlusso = dataFlusso;
    }

    public String getTrn() {
        return trn;
    }

    public void setTrn(String trn) {
        this.trn = trn;
    }

    public OffsetDateTime getDataRegolamento() {
        return dataRegolamento;
    }

    public void setDataRegolamento(OffsetDateTime dataRegolamento) {
        this.dataRegolamento = dataRegolamento;
    }

    public String getIdPsp() {
        return idPsp;
    }

    public void setIdPsp(String idPsp) {
        this.idPsp = idPsp;
    }

    public String getBicRiversamento() {
        return bicRiversamento;
    }

    public void setBicRiversamento(String bicRiversamento) {
        this.bicRiversamento = bicRiversamento;
    }

    public int getNumeroPagamenti() {
        return numeroPagamenti;
    }

    public void setNumeroPagamenti(int numeroPagamenti) {
        this.numeroPagamenti = numeroPagamenti;
    }

    public BigDecimal getImportoTotale() {
        return importoTotale;
    }

    public void setImportoTotale(BigDecimal importoTotale) {
        this.importoTotale = importoTotale;
    }

    public StatoFlussoRendicontazione getStato() {
        return stato;
    }

    public void setStato(StatoFlussoRendicontazione stato) {
        this.stato = stato;
    }

    public Long getRevisione() {
        return revisione;
    }

    public void setRevisione(Long revisione) {
        this.revisione = revisione;
    }

    public boolean isObsoleto() {
        return obsoleto;
    }

    public void setObsoleto(boolean obsoleto) {
        this.obsoleto = obsoleto;
    }
}
