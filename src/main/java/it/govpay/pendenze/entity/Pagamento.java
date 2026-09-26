package it.govpay.pendenze.entity;

import java.time.OffsetDateTime;

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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import it.govpay.pendenze.model.StatoPagamentoRicevuta;
import it.govpay.pendenze.model.TipoPagamento;

/**
 * Occorrenza di pagamento riscontrata da pagoPA per una singola voce, mappata sulla
 * tabella legacy {@code pagamenti} (decisione del lead, 2026-09-25, fase 2: riuso
 * diretto).
 *
 * <p><b>Unica fonte dello IUR per lo YAML v3 "Ricevuta"</b> (decisione del lead,
 * 2026-09-25): {@code rpt} non ha una colonna {@code iur} — la chiave naturale di
 * {@link Rpt} e' {@code (iuv, ccp, cod_dominio)}. Per ora la risoluzione
 * IUR&#8594;ricevuta di {@code GET .../ricevute/{iur}} usa direttamente
 * {@link #getIur()}, senza risolvere anche {@code cod_dominio} (vedi Javadoc di
 * {@link it.govpay.pendenze.service.RicevutaRendicontazioneService}).</p>
 *
 * <p><b>Fuori dall'aggregato {@link PosizioneDebitoria}</b>, stesso principio di
 * {@link Rpt}/{@link Rendicontazione}.</p>
 */
@Entity
@Table(name = "pagamenti", uniqueConstraints = @UniqueConstraint(
        name = "unique_pag_id_riscossione", columnNames = {"cod_dominio", "iuv", "iur", "indice_dati"}))
@SequenceGenerator(name = "seq_pagamenti", sequenceName = "seq_pagamenti", allocationSize = 1)
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_pagamenti")
    @Column(name = "id")
    private Long id;

    @Column(name = "cod_dominio", nullable = false, length = 35)
    private String codDominio;

    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    @Column(name = "iur", nullable = false, length = 35)
    private String iur;

    @Column(name = "indice_dati", nullable = false)
    private int indiceDati = 1;

    @Column(name = "importo_pagato", nullable = false)
    private double importoPagato;

    @Column(name = "data_acquisizione", nullable = false)
    private OffsetDateTime dataAcquisizione;

    @Column(name = "data_pagamento", nullable = false)
    private OffsetDateTime dataPagamento;

    @Column(name = "stato", length = 35)
    @Enumerated(EnumType.STRING)
    private StatoPagamentoRicevuta stato;

    @Column(name = "tipo", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private TipoPagamento tipo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_rpt")
    private Rpt rpt;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCodDominio() {
        return codDominio;
    }

    public void setCodDominio(String codDominio) {
        this.codDominio = codDominio;
    }

    public String getIuv() {
        return iuv;
    }

    public void setIuv(String iuv) {
        this.iuv = iuv;
    }

    public String getIur() {
        return iur;
    }

    public void setIur(String iur) {
        this.iur = iur;
    }

    public int getIndiceDati() {
        return indiceDati;
    }

    public void setIndiceDati(int indiceDati) {
        this.indiceDati = indiceDati;
    }

    public double getImportoPagato() {
        return importoPagato;
    }

    public void setImportoPagato(double importoPagato) {
        this.importoPagato = importoPagato;
    }

    public OffsetDateTime getDataAcquisizione() {
        return dataAcquisizione;
    }

    public void setDataAcquisizione(OffsetDateTime dataAcquisizione) {
        this.dataAcquisizione = dataAcquisizione;
    }

    public OffsetDateTime getDataPagamento() {
        return dataPagamento;
    }

    public void setDataPagamento(OffsetDateTime dataPagamento) {
        this.dataPagamento = dataPagamento;
    }

    public StatoPagamentoRicevuta getStato() {
        return stato;
    }

    public void setStato(StatoPagamentoRicevuta stato) {
        this.stato = stato;
    }

    public TipoPagamento getTipo() {
        return tipo;
    }

    public void setTipo(TipoPagamento tipo) {
        this.tipo = tipo;
    }

    public Rpt getRpt() {
        return rpt;
    }

    public void setRpt(Rpt rpt) {
        this.rpt = rpt;
    }
}
