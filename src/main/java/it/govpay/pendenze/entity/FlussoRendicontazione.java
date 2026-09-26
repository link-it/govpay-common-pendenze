package it.govpay.pendenze.entity;

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
 * dello YAML v3), mappata sulla tabella legacy {@code fr} (decisione del lead,
 * 2026-09-25, fase 2: riuso diretto, stesso principio gia' applicato al resto
 * dell'aggregato).
 *
 * <p>{@link #idDominio} e' una FK piatta verso l'anagrafica di govpay-common (M4:
 * nessuna relazione JPA verso l'esterno dell'aggregato) — in produzione {@code fr.id_dominio}
 * ha un vincolo FK reale verso {@code domini(id)}, qui omesso per coerenza con
 * {@code documenti}/{@code versamenti}.</p>
 *
 * <p><b>{@link #codDominio}</b> (decisione del lead, 2026-09-26): verificato che tutta la
 * ricerca applicativa legacy su {@code fr}/{@code rpt}/{@code pagamenti} (business layer,
 * {@code FrBD}/{@code RptBD}/{@code PagamentiBD}) avviene sempre per {@code cod_dominio},
 * mai per {@code id_dominio} (colonna presente sul bean legacy ma mai usata come parametro
 * di ricerca) — e {@code rpt}/{@code pagamenti} non hanno nemmeno una colonna
 * {@code id_dominio}. Per coerenza con {@link Rpt#getCodDominio()}/
 * {@link Pagamento#getCodDominio()} questa entita' porta entrambi i campi: il chiamante
 * fornisce sia {@code idDominio} sia {@code codDominio} (stesso principio gia' applicato a
 * {@link Pendenza#getIdTipoPendenza()}/{@link Pendenza#getIdTipoVersamento()} — nessuna
 * risoluzione fatta da questa libreria).</p>
 *
 * <p><b>{@link #importoTotale}/{@link #numeroPagamenti}/{@link #revisione}/
 * {@link #dataRegolamento} sono nullable</b>, fedeli alla colonna legacy reale (a
 * differenza di una prima ipotesi che li dava tutti {@code NOT NULL}): {@code importo_totale_pagamenti}
 * e' {@code DOUBLE PRECISION} (non {@code NUMERIC(19,2)} — stesso principio gia'
 * applicato a {@code Pendenza}/{@code VocePendenza}, per evitare conversioni in
 * migrazione).</p>
 *
 * <p><b>{@link #revisione}/{@link #obsoleto} preservano le versioni del flusso</b>, come
 * nel legacy (bug del lead, 2026-09-24: la prima versione con solo {@code id_dominio}/
 * {@code codFlusso} univoci impediva di conservare piu' revisioni dello stesso flusso).
 * Questa libreria non calcola essa stessa la prossima revisione ne' decide quale riga
 * marcare obsoleta (nessuna logica di acquisizione qui, vedi Javadoc di
 * {@link it.govpay.pendenze.service.RicevutaRendicontazioneService}): si limita a
 * offrire uno schema che possa conservarle tutte.</p>
 */
@Entity
@Table(name = "fr", uniqueConstraints = {
        @UniqueConstraint(name = "unique_fr_1", columnNames = {"id_dominio", "cod_flusso", "data_ora_flusso"}),
        @UniqueConstraint(name = "unique_fr_2", columnNames = {"id_dominio", "cod_flusso", "cod_psp", "revisione"})
})
@SequenceGenerator(name = "seq_fr", sequenceName = "seq_fr", allocationSize = 1)
public class FlussoRendicontazione {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_fr")
    @Column(name = "id")
    private Long id;

    /** Dominio creditore del flusso — FK piatta verso l'anagrafica di govpay-common (M4). */
    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    /** Codice del dominio creditore — vedi nota di classe: chiave usata per la ricerca nel legacy. */
    @Column(name = "cod_dominio", nullable = false, length = 35)
    private String codDominio;

    @Column(name = "cod_flusso", nullable = false, length = 35)
    private String codFlusso;

    @Column(name = "data_ora_flusso", nullable = false)
    private OffsetDateTime dataOraFlusso;

    @Column(name = "iur", nullable = false, length = 35)
    private String iur;

    /** Data di acquisizione del flusso, distinta da {@link #dataOraFlusso} (data dichiarata dal PSP nel flusso). */
    @Column(name = "data_acquisizione", nullable = false)
    private OffsetDateTime dataAcquisizione;

    @Column(name = "data_regolamento")
    private OffsetDateTime dataRegolamento;

    @Column(name = "cod_psp", nullable = false, length = 35)
    private String codPsp;

    @Column(name = "cod_bic_riversamento", length = 35)
    private String bicRiversamento;

    @Column(name = "numero_pagamenti")
    private Long numeroPagamenti;

    @Column(name = "importo_totale_pagamenti")
    private Double importoTotale;

    @Column(name = "stato", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoFlussoRendicontazione stato;

    /** Numero di revisione del flusso, univoco per dominio+identificativo+psp (vedi nota di classe). */
    @Column(name = "revisione")
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

    public String getCodDominio() {
        return codDominio;
    }

    public void setCodDominio(String codDominio) {
        this.codDominio = codDominio;
    }

    public String getCodFlusso() {
        return codFlusso;
    }

    public void setCodFlusso(String codFlusso) {
        this.codFlusso = codFlusso;
    }

    public OffsetDateTime getDataOraFlusso() {
        return dataOraFlusso;
    }

    public void setDataOraFlusso(OffsetDateTime dataOraFlusso) {
        this.dataOraFlusso = dataOraFlusso;
    }

    public String getIur() {
        return iur;
    }

    public void setIur(String iur) {
        this.iur = iur;
    }

    public OffsetDateTime getDataAcquisizione() {
        return dataAcquisizione;
    }

    public void setDataAcquisizione(OffsetDateTime dataAcquisizione) {
        this.dataAcquisizione = dataAcquisizione;
    }

    public OffsetDateTime getDataRegolamento() {
        return dataRegolamento;
    }

    public void setDataRegolamento(OffsetDateTime dataRegolamento) {
        this.dataRegolamento = dataRegolamento;
    }

    public String getCodPsp() {
        return codPsp;
    }

    public void setCodPsp(String codPsp) {
        this.codPsp = codPsp;
    }

    public String getBicRiversamento() {
        return bicRiversamento;
    }

    public void setBicRiversamento(String bicRiversamento) {
        this.bicRiversamento = bicRiversamento;
    }

    public Long getNumeroPagamenti() {
        return numeroPagamenti;
    }

    public void setNumeroPagamenti(Long numeroPagamenti) {
        this.numeroPagamenti = numeroPagamenti;
    }

    public Double getImportoTotale() {
        return importoTotale;
    }

    public void setImportoTotale(Double importoTotale) {
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
