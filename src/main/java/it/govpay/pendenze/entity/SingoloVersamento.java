package it.govpay.pendenze.entity;

import java.math.BigDecimal;

import it.govpay.pendenze.entity.converter.ImportoConverter;
import it.govpay.pendenze.entity.converter.TipoBolloConverter;
import it.govpay.pendenze.entity.converter.TipoContabilitaConverter;
import it.govpay.pendenze.model.StatoSingoloVersamento;
import it.govpay.pendenze.model.TipoBollo;
import it.govpay.pendenze.model.TipoContabilita;
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

/**
 * Voce di una pendenza: entita' mappata sulla tabella {@code singoli_versamenti}.
 *
 * <p><b>Nota sulle date.</b> La tabella non ha alcuna colonna temporale: non esiste un
 * "ultimo aggiornamento" della voce. Per questo l'aggiornamento dello stato di una voce
 * deve marcare la pendenza padre, che e' l'unico posto dove quell'informazione puo' essere
 * registrata.</p>
 *
 * <p>Come per la pendenza, le chiavi esterne verso l'anagrafica ({@code id_tributo},
 * {@code id_iban_accredito}, {@code id_iban_appoggio}, {@code id_dominio}) sono mappate
 * come {@code Long}, non come relazioni.</p>
 */
@Entity
@Table(name = "singoli_versamenti")
@SequenceGenerator(name = "seq_singoli_versamenti", sequenceName = "seq_singoli_versamenti",
        allocationSize = 1)
public class SingoloVersamento {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_singoli_versamenti")
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_versamento", nullable = false)
    private Versamento versamento;

    @Column(name = "cod_singolo_versamento_ente", nullable = false, length = 70)
    private String codSingoloVersamentoEnte;

    @Column(name = "stato_singolo_versamento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoSingoloVersamento statoSingoloVersamento;

    @Column(name = "importo_singolo_versamento", nullable = false)
    @Convert(converter = ImportoConverter.class)
    private BigDecimal importoSingoloVersamento;

    @Column(name = "descrizione", length = 256)
    private String descrizione;

    @Column(name = "descrizione_causale_rpt", length = 140)
    private String descrizioneCausaleRpt;

    /** JSON opaco: la libreria non lo interpreta. */
    @Column(name = "dati_allegati", columnDefinition = "TEXT")
    private String datiAllegati;

    /**
     * Posizione della voce dentro la pendenza. E' assegnato dal motore di caricamento e
     * non cambia per le voci esistenti.
     */
    @Column(name = "indice_dati", nullable = false)
    private Integer indiceDati;

    /** JSON opaco: la libreria non lo interpreta. */
    @Column(name = "contabilita", columnDefinition = "TEXT")
    private String contabilita;

    /** JSON opaco: la libreria non lo interpreta. */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "tipo_bollo", length = 2)
    @Convert(converter = TipoBolloConverter.class)
    private TipoBollo tipoBollo;

    @Column(name = "hash_documento", length = 70)
    private String hashDocumento;

    @Column(name = "provincia_residenza", length = 2)
    private String provinciaResidenza;

    @Column(name = "tipo_contabilita", length = 1)
    @Convert(converter = TipoContabilitaConverter.class)
    private TipoContabilita tipoContabilita;

    @Column(name = "codice_contabilita", length = 255)
    private String codiceContabilita;

    // ── Riferimenti all'anagrafica: FK, non relazioni ────────────────────────

    @Column(name = "id_tributo")
    private Long idTributo;

    @Column(name = "id_iban_accredito")
    private Long idIbanAccredito;

    @Column(name = "id_iban_appoggio")
    private Long idIbanAppoggio;

    /** Dominio della voce: valorizzato nelle pendenze multibeneficiario. */
    @Column(name = "id_dominio")
    private Long idDominio;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Versamento getVersamento() {
        return versamento;
    }

    public void setVersamento(Versamento versamento) {
        this.versamento = versamento;
    }

    public String getCodSingoloVersamentoEnte() {
        return codSingoloVersamentoEnte;
    }

    public void setCodSingoloVersamentoEnte(String codSingoloVersamentoEnte) {
        this.codSingoloVersamentoEnte = codSingoloVersamentoEnte;
    }

    public StatoSingoloVersamento getStatoSingoloVersamento() {
        return statoSingoloVersamento;
    }

    public void setStatoSingoloVersamento(StatoSingoloVersamento statoSingoloVersamento) {
        this.statoSingoloVersamento = statoSingoloVersamento;
    }

    public BigDecimal getImportoSingoloVersamento() {
        return importoSingoloVersamento;
    }

    public void setImportoSingoloVersamento(BigDecimal importoSingoloVersamento) {
        this.importoSingoloVersamento = importoSingoloVersamento;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public String getDescrizioneCausaleRpt() {
        return descrizioneCausaleRpt;
    }

    public void setDescrizioneCausaleRpt(String descrizioneCausaleRpt) {
        this.descrizioneCausaleRpt = descrizioneCausaleRpt;
    }

    public String getDatiAllegati() {
        return datiAllegati;
    }

    public void setDatiAllegati(String datiAllegati) {
        this.datiAllegati = datiAllegati;
    }

    public Integer getIndiceDati() {
        return indiceDati;
    }

    public void setIndiceDati(Integer indiceDati) {
        this.indiceDati = indiceDati;
    }

    public String getContabilita() {
        return contabilita;
    }

    public void setContabilita(String contabilita) {
        this.contabilita = contabilita;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public TipoBollo getTipoBollo() {
        return tipoBollo;
    }

    public void setTipoBollo(TipoBollo tipoBollo) {
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

    public TipoContabilita getTipoContabilita() {
        return tipoContabilita;
    }

    public void setTipoContabilita(TipoContabilita tipoContabilita) {
        this.tipoContabilita = tipoContabilita;
    }

    public String getCodiceContabilita() {
        return codiceContabilita;
    }

    public void setCodiceContabilita(String codiceContabilita) {
        this.codiceContabilita = codiceContabilita;
    }

    public Long getIdTributo() {
        return idTributo;
    }

    public void setIdTributo(Long idTributo) {
        this.idTributo = idTributo;
    }

    public Long getIdIbanAccredito() {
        return idIbanAccredito;
    }

    public void setIdIbanAccredito(Long idIbanAccredito) {
        this.idIbanAccredito = idIbanAccredito;
    }

    public Long getIdIbanAppoggio() {
        return idIbanAppoggio;
    }

    public void setIdIbanAppoggio(Long idIbanAppoggio) {
        this.idIbanAppoggio = idIbanAppoggio;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    @Override
    public boolean equals(Object altro) {
        if (this == altro) {
            return true;
        }
        if (!(altro instanceof SingoloVersamento that) || id == null || that.id == null) {
            return false;
        }
        return id.equals(that.id);
    }

    /** Costante, per la stessa ragione documentata su {@code Versamento.hashCode()}. */
    @Override
    public int hashCode() {
        return SingoloVersamento.class.hashCode();
    }

    /** Volutamente senza la relazione verso la pendenza, che e' LAZY. */
    @Override
    public String toString() {
        return "SingoloVersamento[id=" + id
                + ", codSingoloVersamentoEnte=" + codSingoloVersamentoEnte
                + ", indiceDati=" + indiceDati
                + ", stato=" + statoSingoloVersamento + "]";
    }
}
