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

import it.govpay.pendenze.model.StatoRendicontazione;

/**
 * Occorrenza di pagamento di una {@link Pendenza} all'interno di un flusso di
 * rendicontazione pagoPA (schema {@code Rendicontazione} dello YAML v3), mappata sulla
 * tabella legacy {@code rendicontazioni} (decisione del lead, 2026-09-25, fase 2: riuso
 * diretto).
 *
 * <p><b>Non ha una colonna {@code id_pendenza}</b> (correzione rispetto a una prima
 * ipotesi): il legacy correla la rendicontazione alla pendenza solo tramite
 * {@link #iuv} (stringa) — {@code id_singolo_versamento}/{@code id_pagamento} esistono
 * ma sono entrambe nullable e non sono mappate qui (fuori scopo per ora). La
 * risoluzione verso una {@link Pendenza} specifica resta a carico del chiamante, vedi
 * {@link it.govpay.pendenze.service.RicevutaRendicontazioneService}.</p>
 *
 * <p><b>Fuori dall'aggregato {@link PosizioneDebitoria}</b> (decisione del lead,
 * 2026-09-24): stesso principio del vecchio "dettaglio pendenza" da evitare (centinaia
 * di query per una singola lettura) — risorsa indipendente, interrogata solo su
 * richiesta esplicita.</p>
 *
 * <p><b>{@link #importoPagato}/{@link #esito}/{@link #data} sono nullable</b>, fedeli
 * alla colonna legacy reale (a differenza di una prima ipotesi che li dava tutti
 * {@code NOT NULL}); {@link #importoPagato} e' {@code DOUBLE PRECISION} (non
 * {@code NUMERIC(19,2)}), stesso principio di {@link FlussoRendicontazione#getImportoTotale()}.
 * {@link #esito} e' il codice di esito pagoPA (0/3/9 — non validato qui, stesso
 * principio del vecchio {@code Ricevuta}: dato prodotto integralmente da pagoPA, questa
 * libreria lo conserva senza interpretarlo).</p>
 */
@Entity
@Table(name = "rendicontazioni")
@SequenceGenerator(name = "seq_rendicontazioni", sequenceName = "seq_rendicontazioni", allocationSize = 1)
public class Rendicontazione {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_rendicontazioni")
    @Column(name = "id")
    private Long id;

    /** Correla alla {@link Pendenza}: vedi nota di classe — nessuna FK reale su {@code rendicontazioni}. */
    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_fr", nullable = false)
    private FlussoRendicontazione flusso;

    @Column(name = "iur", nullable = false, length = 35)
    private String iur;

    /** Indice dell'occorrenza nella struttura {@code datiSingoloPagamento} della ricevuta. */
    @Column(name = "indice_dati")
    private Integer indiceDati;

    @Column(name = "importo_pagato")
    private Double importoPagato;

    /** Codice di esito pagoPA — vedi nota di classe. */
    @Column(name = "esito")
    private Integer esito;

    @Column(name = "data")
    private OffsetDateTime data;

    @Column(name = "stato", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoRendicontazione stato;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIuv() {
        return iuv;
    }

    public void setIuv(String iuv) {
        this.iuv = iuv;
    }

    public FlussoRendicontazione getFlusso() {
        return flusso;
    }

    public void setFlusso(FlussoRendicontazione flusso) {
        this.flusso = flusso;
    }

    public String getIur() {
        return iur;
    }

    public void setIur(String iur) {
        this.iur = iur;
    }

    public Integer getIndiceDati() {
        return indiceDati;
    }

    public void setIndiceDati(Integer indiceDati) {
        this.indiceDati = indiceDati;
    }

    public Double getImportoPagato() {
        return importoPagato;
    }

    public void setImportoPagato(Double importoPagato) {
        this.importoPagato = importoPagato;
    }

    public Integer getEsito() {
        return esito;
    }

    public void setEsito(Integer esito) {
        this.esito = esito;
    }

    public OffsetDateTime getData() {
        return data;
    }

    public void setData(OffsetDateTime data) {
        this.data = data;
    }

    public StatoRendicontazione getStato() {
        return stato;
    }

    public void setStato(StatoRendicontazione stato) {
        this.stato = stato;
    }
}
