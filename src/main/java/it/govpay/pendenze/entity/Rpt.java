package it.govpay.pendenze.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Ricevuta di pagamento telematica (RPT/RT) pagoPA, mappata sulla tabella legacy
 * {@code rpt} (decisione del lead, 2026-09-25: riuso diretto, stesso principio gia'
 * applicato al resto dell'aggregato).
 *
 * <p><b>{@link #iur} e' fisicamente la colonna {@code ccp}</b> (correzione del lead,
 * 2026-09-26, verificata nel business layer legacy): {@code ccp} ("codice contesto
 * pagamento") e' la nomenclatura SANP storica dello stesso identificativo che le
 * specifiche piu' recenti del Nodo dei Pagamenti chiamano {@code receiptId} — nel
 * legacy i due nomi convivono sulla stessa colonna fisica (`RicevuteConverter.setIdRicevuta(rpt.getCcp())`,
 * `QuietanzaPagamento.setCcp(pagamento.getIur())`, `CtReceiptUtils.setCcp(receiptId)`; il
 * bean v2 {@code Ricevuta.idRicevuta} e' documentato esplicitamente come "Corrisponde al
 * `receiptId` oppure al `ccp` a seconda del modello di pagamento"). Quindi
 * {@code (iuv, ccp, cod_dominio)} — il vincolo naturale reale della tabella — e'
 * esattamente {@code (iuv, iur, dominio)}: questa entita' basta da sola per rispondere a
 * {@code GET .../ricevute/{iur}} dello YAML v3, senza passare da {@link Pagamento}
 * (ipotesi precedente, errata, abbandonata) — vedi
 * {@link it.govpay.pendenze.service.RicevutaRendicontazioneService}.</p>
 *
 * <p><b>Fuori dall'aggregato {@link PosizioneDebitoria}</b> (decisione del lead,
 * 2026-09-24): {@link #idVersamento} e' una FK piatta, non una relazione JPA verso
 * {@link Pendenza} — anche se in produzione {@code rpt.id_versamento} ha un vincolo FK
 * reale, per non ripetere il problema del vecchio "dettaglio pendenza" (centinaia di
 * query per una singola lettura).</p>
 *
 * <p><b>{@code StatoRpt} legacy (~11 valori, stati di trasporto RPT/nodo) non
 * mappato</b>: riguarda la consegna della RPT al Nodo dei Pagamenti, non il contenuto
 * della ricevuta — fuori dallo scopo dello YAML {@code Ricevuta} (colonna non mappata,
 * innocua con {@code ddl-auto=validate}).</p>
 *
 * <p><b>{@link #versione}</b> conserva il valore legacy grezzo di {@code VersioneRPT}
 * (es. {@code SANP_240}, {@code RPTV2_RTV1}) — la mappatura sui valori dello schema
 * {@code TipoRicevuta} dello YAML v3 ({@code ctRicevutaTelematica}/{@code ctReceipt}/
 * {@code ctReceiptV2}) e' responsabilita' del livello che espone i bean API, non di
 * questa libreria (stesso principio del vecchio model {@code TipoRicevuta}, rimosso
 * perche' senza piu' alcun campo tipizzato a cui applicarlo).</p>
 */
@Entity
@Table(name = "rpt", uniqueConstraints = @UniqueConstraint(
        name = "unique_rpt_id_transazione", columnNames = {"iuv", "ccp", "cod_dominio"}))
@SequenceGenerator(name = "seq_rpt", sequenceName = "seq_rpt", allocationSize = 1)
public class Rpt {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_rpt")
    @Column(name = "id")
    private Long id;

    /** FK piatta verso {@link Pendenza}: vedi nota di classe sul perimetro dell'aggregato. */
    @Column(name = "id_versamento", nullable = false)
    private Long idVersamento;

    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    /** Colonna fisica {@code ccp} (nomenclatura storica) — vedi nota di classe. */
    @Column(name = "ccp", nullable = false, length = 35)
    private String iur;

    @Column(name = "cod_dominio", nullable = false, length = 35)
    private String codDominio;

    /** Corpo della ricevuta (RT), XML grezzo esattamente come prodotto da pagoPA. */
    @Column(name = "xml_rt")
    private byte[] xmlRt;

    /** Data di acquisizione della ricevuta. */
    @Column(name = "data_msg_ricevuta")
    private OffsetDateTime dataMsgRicevuta;

    /** Esito del pagamento secondo la codifica pagoPA (0/1/2/3/4 — non interpretato qui). */
    @Column(name = "cod_esito_pagamento")
    private Integer codEsitoPagamento;

    /** Valore legacy grezzo di {@code VersioneRPT} — vedi nota di classe. */
    @Column(name = "versione", nullable = false, length = 35)
    private String versione;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdVersamento() {
        return idVersamento;
    }

    public void setIdVersamento(Long idVersamento) {
        this.idVersamento = idVersamento;
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

    public String getCodDominio() {
        return codDominio;
    }

    public void setCodDominio(String codDominio) {
        this.codDominio = codDominio;
    }

    public byte[] getXmlRt() {
        return xmlRt;
    }

    public void setXmlRt(byte[] xmlRt) {
        this.xmlRt = xmlRt;
    }

    public OffsetDateTime getDataMsgRicevuta() {
        return dataMsgRicevuta;
    }

    public void setDataMsgRicevuta(OffsetDateTime dataMsgRicevuta) {
        this.dataMsgRicevuta = dataMsgRicevuta;
    }

    public Integer getCodEsitoPagamento() {
        return codEsitoPagamento;
    }

    public void setCodEsitoPagamento(Integer codEsitoPagamento) {
        this.codEsitoPagamento = codEsitoPagamento;
    }

    public String getVersione() {
        return versione;
    }

    public void setVersione(String versione) {
        this.versione = versione;
    }
}
