package it.govpay.pendenze.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

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
 * tabella {@code rendicontazioni}.
 *
 * <p><b>Fuori dall'aggregato {@link PosizioneDebitoria}</b> (decisione del lead, 2026-09-24):
 * {@link #idPendenza} e' una FK piatta, non una relazione JPA — {@code Pendenza} non ha una
 * collezione {@code @OneToMany} verso questa entità. Il vecchio "dettaglio pendenza" caricava
 * ricevute/rendicontazioni insieme al resto producendo centinaia di query per una singola
 * lettura; qui restano risorse indipendenti, interrogate solo su richiesta esplicita (stesso
 * principio della decisione A1 del disegno abbandonato: "RPT e pagamenti non nel dettaglio").</p>
 *
 * <p>{@link #importo} è {@code NUMERIC(19,2)}, non {@code DOUBLE PRECISION} come la colonna
 * legacy equivalente (`rendicontazioni.importo_pagato`) — stesso principio di
 * {@link FlussoRendicontazione#getImportoTotale()}.</p>
 */
@Entity
@Table(name = "rendicontazioni")
@SequenceGenerator(name = "seq_rendicontazioni", sequenceName = "seq_rendicontazioni", allocationSize = 1)
public class Rendicontazione {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_rendicontazioni")
    @Column(name = "id")
    private Long id;

    /** FK piatta verso {@link Pendenza}: vedi nota di classe sul perimetro dell'aggregato. */
    @Column(name = "id_pendenza", nullable = false)
    private Long idPendenza;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_flusso_rendicontazione", nullable = false)
    private FlussoRendicontazione flusso;

    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    @Column(name = "iur", nullable = false, length = 35)
    private String iur;

    /** Indice dell'occorrenza nella struttura {@code datiSingoloPagamento} della ricevuta. */
    @Column(name = "indice")
    private Integer indice;

    @Column(name = "importo", nullable = false, precision = 19, scale = 2)
    private BigDecimal importo;

    /**
     * Codice di esito pagoPA (0/3/9 — non validato qui, stesso principio di {@link Ricevuta}:
     * dato prodotto integralmente da pagoPA, questa libreria lo conserva senza interpretarlo).
     */
    @Column(name = "esito", nullable = false)
    private int esito;

    @Column(name = "data", nullable = false)
    private LocalDate data;

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

    public Long getIdPendenza() {
        return idPendenza;
    }

    public void setIdPendenza(Long idPendenza) {
        this.idPendenza = idPendenza;
    }

    public FlussoRendicontazione getFlusso() {
        return flusso;
    }

    public void setFlusso(FlussoRendicontazione flusso) {
        this.flusso = flusso;
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

    public Integer getIndice() {
        return indice;
    }

    public void setIndice(Integer indice) {
        this.indice = indice;
    }

    public BigDecimal getImporto() {
        return importo;
    }

    public void setImporto(BigDecimal importo) {
        this.importo = importo;
    }

    public int getEsito() {
        return esito;
    }

    public void setEsito(int esito) {
        this.esito = esito;
    }

    public LocalDate getData() {
        return data;
    }

    public void setData(LocalDate data) {
        this.data = data;
    }

    public StatoRendicontazione getStato() {
        return stato;
    }

    public void setStato(StatoRendicontazione stato) {
        this.stato = stato;
    }
}
