package it.govpay.pendenze.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.DettaglioContabileConverter;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;

/**
 * Voce di pendenza: mappata sulla tabella {@code voci_pendenza}.
 *
 * <p><b>Tabella unica per le 3 varianti (M3 di {@code proposta-modello-nativo-v3.md}).</b>
 * {@code RiferimentoEntrata}/{@code Entrata}/{@code Bollo} condividono
 * {@code DatiComuniVocePendenza}; le colonne specifiche di ciascuna variante sono
 * mutuamente esclusive e nullable, selezionate da {@link #tipoRiferimento}:</p>
 * <ul>
 *   <li>{@code RIFERIMENTO_ENTRATA}: solo {@link #codEntrata}</li>
 *   <li>{@code ENTRATA}: {@link #ibanAccredito}, {@link #ibanAppoggio}, {@link #tassonomia}</li>
 *   <li>{@code BOLLO}: {@link #tipoBollo}, {@link #hashDocumento}, {@link #provinciaResidenza},
 *       {@link #tassonomia} (condivisa con {@code ENTRATA}, non con {@code RIFERIMENTO_ENTRATA})</li>
 * </ul>
 *
 * <p>{@link #dettaglioContabile} (riconciliazione contabile pagoPA) e' ammesso solo per
 * {@code ENTRATA}/{@code RIFERIMENTO_ENTRATA}, mai per {@code BOLLO} (che si classifica
 * solo tramite {@code tassonomia}) — vincolo verificato da
 * {@code ValidatorePosizioneDebitoria}, non esprimibile a livello di colonna.</p>
 */
@Entity
@Table(name = "voci_pendenza", uniqueConstraints = @UniqueConstraint(
        name = "unique_voci_pendenza_1", columnNames = {"id_pendenza", "indice"}))
@SequenceGenerator(name = "seq_voci_pendenza", sequenceName = "seq_voci_pendenza", allocationSize = 1)
public class VocePendenza {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_voci_pendenza")
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_pendenza", nullable = false)
    private Pendenza pendenza;

    @Column(name = "id_voce_pendenza", nullable = false, length = 35)
    private String idVocePendenza;

    @Column(name = "importo", nullable = false, precision = 19, scale = 2)
    private BigDecimal importo;

    @Column(name = "descrizione", nullable = false, length = 140)
    private String descrizione;

    /** Ordine (1-5) della voce all'interno della pendenza. */
    @Column(name = "indice", nullable = false)
    private int indice;

    @Column(name = "stato", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoVocePendenza stato;

    /** Diverso da quello della pendenza solo nel caso multi-beneficiario. */
    @Column(name = "id_dominio")
    private Long idDominio;

    @Column(name = "tipo_riferimento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private TipoRiferimentoVocePendenza tipoRiferimento;

    /** Solo {@code RIFERIMENTO_ENTRATA}. */
    @Column(name = "cod_entrata", length = 35)
    private String codEntrata;

    /** Solo {@code ENTRATA}. */
    @Column(name = "iban_accredito", length = 35)
    private String ibanAccredito;

    /** Solo {@code ENTRATA}. */
    @Column(name = "iban_appoggio", length = 35)
    private String ibanAppoggio;

    /** {@code ENTRATA} e {@code BOLLO} (non {@code RIFERIMENTO_ENTRATA}). */
    @Column(name = "tassonomia", length = 35)
    private String tassonomia;

    /** Solo {@code BOLLO}. */
    @Column(name = "tipo_bollo", length = 2)
    private String tipoBollo;

    /** Solo {@code BOLLO}: digest in base64 del documento informatico. */
    @Column(name = "hash_documento", length = 72)
    private String hashDocumento;

    /** Solo {@code BOLLO}: sigla automobilistica della provincia di residenza. */
    @Column(name = "provincia_residenza", length = 2)
    private String provinciaResidenza;

    /**
     * Riconciliazione contabile pagoPA (mai per {@code BOLLO}). Vuota, non {@code null},
     * quando assente: evita di dover distinguere "nessun dettaglio" da "colonna non ancora
     * letta" nel resto del codice.
     */
    @Convert(converter = DettaglioContabileConverter.class)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "dettaglio_contabile")
    private List<DettaglioContabile> dettaglioContabile = new ArrayList<>();

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Pendenza getPendenza() {
        return pendenza;
    }

    public void setPendenza(Pendenza pendenza) {
        this.pendenza = pendenza;
    }

    public String getIdVocePendenza() {
        return idVocePendenza;
    }

    public void setIdVocePendenza(String idVocePendenza) {
        this.idVocePendenza = idVocePendenza;
    }

    public BigDecimal getImporto() {
        return importo;
    }

    public void setImporto(BigDecimal importo) {
        this.importo = importo;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public int getIndice() {
        return indice;
    }

    public void setIndice(int indice) {
        this.indice = indice;
    }

    public StatoVocePendenza getStato() {
        return stato;
    }

    public void setStato(StatoVocePendenza stato) {
        this.stato = stato;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public TipoRiferimentoVocePendenza getTipoRiferimento() {
        return tipoRiferimento;
    }

    public void setTipoRiferimento(TipoRiferimentoVocePendenza tipoRiferimento) {
        this.tipoRiferimento = tipoRiferimento;
    }

    public String getCodEntrata() {
        return codEntrata;
    }

    public void setCodEntrata(String codEntrata) {
        this.codEntrata = codEntrata;
    }

    public String getIbanAccredito() {
        return ibanAccredito;
    }

    public void setIbanAccredito(String ibanAccredito) {
        this.ibanAccredito = ibanAccredito;
    }

    public String getIbanAppoggio() {
        return ibanAppoggio;
    }

    public void setIbanAppoggio(String ibanAppoggio) {
        this.ibanAppoggio = ibanAppoggio;
    }

    public String getTassonomia() {
        return tassonomia;
    }

    public void setTassonomia(String tassonomia) {
        this.tassonomia = tassonomia;
    }

    public String getTipoBollo() {
        return tipoBollo;
    }

    public void setTipoBollo(String tipoBollo) {
        this.tipoBollo = tipoBollo;
    }

    public String getHashDocumento() {
        return hashDocumento;
    }

    public void setHashDocumento(String hashDocumento) {
        this.hashDocumento = hashDocumento;
    }

    public String getProvinciaResidenza() {
        return provinciaResidenza;
    }

    public void setProvinciaResidenza(String provinciaResidenza) {
        this.provinciaResidenza = provinciaResidenza;
    }

    public List<DettaglioContabile> getDettaglioContabile() {
        return dettaglioContabile;
    }

    public void setDettaglioContabile(List<DettaglioContabile> dettaglioContabile) {
        this.dettaglioContabile = dettaglioContabile != null ? dettaglioContabile : new ArrayList<>();
    }
}
