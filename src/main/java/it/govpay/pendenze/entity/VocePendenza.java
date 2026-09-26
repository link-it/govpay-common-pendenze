package it.govpay.pendenze.entity;

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
 * Voce di pendenza: mappata sulla tabella legacy {@code singoli_versamenti} (decisione
 * del lead, 2026-09-25 — riuso possibile perche' {@link Pendenza} e' ora {@code versamenti}:
 * {@code id_versamento} punta sempre a una riga vera, non serve piu' una tabella nuova).
 *
 * <p><b>Stato gia' allineato</b>: {@link StatoVocePendenza#NON_ESEGUITO}/{@code ESEGUITO}
 * coincidono per stringa con {@code StatoSingoloVersamento} legacy (2 soli valori) — nessun
 * problema di grafia qui, a differenza di {@link Pendenza#getStato()}.
 * {@link StatoVocePendenza#ANOMALO} e' solo v3, stesso residuo accettato di
 * {@code StatoPendenza.ESEGUITO_ALTRO_CANALE}.</p>
 *
 * <p><b>Colonne aggiunte</b> (concetti assenti nel legacy, che usa FK verso anagrafiche
 * separate — {@code id_tributo}/{@code id_iban_accredito}/{@code id_iban_appoggio} — invece
 * di codici inline): {@link #tipoRiferimento} (il discriminatore RIFERIMENTO_ENTRATA/
 * ENTRATA/BOLLO non esiste affatto nel legacy), {@link #codEntrata}, {@link #ibanAccredito},
 * {@link #ibanAppoggio}, {@link #tassonomia}. {@link #tipoBollo}/{@link #hashDocumento}/
 * {@link #provinciaResidenza} invece coincidono esattamente con colonne legacy reali.</p>
 *
 * <p>{@link #dettaglioContabile} riusa {@code contabilita} (colonna legacy, gia' JSON —
 * vedi {@code ContabilitaConverter} legacy): il formato non si sovrappone su nessuna
 * chiave con quello vecchio (Contabilita/QuotaContabilita ha {@code quote}/
 * {@code proprietaCustom}, {@code DettaglioContabile} ha {@code tipo}) — decisione del
 * lead, 2026-09-25, la compatibilita' si gestisce a livello applicativo (ragioneria v3),
 * non con una colonna separata.</p>
 */
@Entity
@Table(name = "singoli_versamenti", uniqueConstraints = @UniqueConstraint(
        name = "unique_sng_id_voce", columnNames = {"id_versamento", "indice_dati"}))
@SequenceGenerator(name = "seq_singoli_versamenti", sequenceName = "seq_singoli_versamenti", allocationSize = 1)
public class VocePendenza {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_singoli_versamenti")
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_versamento", nullable = false)
    private Pendenza pendenza;

    @Column(name = "cod_singolo_versamento_ente", nullable = false, length = 70)
    private String idVocePendenza;

    @Column(name = "importo_singolo_versamento", nullable = false)
    private double importo;

    @Column(name = "descrizione", length = 256)
    private String descrizione;

    /** Ordine (1-5) della voce all'interno della pendenza. */
    @Column(name = "indice_dati", nullable = false)
    private int indice;

    @Column(name = "stato_singolo_versamento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoVocePendenza stato;

    /**
     * Colonna aggiunta: il discriminatore RIFERIMENTO_ENTRATA/ENTRATA/BOLLO non esiste nel
     * legacy. Nullable sul DB (decisione del lead, 2026-09-26, in vista della migrazione
     * di un DB v2 esistente): le voci storiche non hanno un valore sensato da retro-
     * assegnare — l'obbligatorietà per le voci create da v3 resta una validazione
     * puramente applicativa (campo obbligatorio dello YAML v3), non un vincolo DB, stesso
     * principio gia' dichiarato nel Javadoc di {@link it.govpay.pendenze.validazione.ValidatorePosizioneDebitoria}
     * per i campi strutturali.
     */
    @Column(name = "tipo_riferimento", length = 35)
    @Enumerated(EnumType.STRING)
    private TipoRiferimentoVocePendenza tipoRiferimento;

    /** Colonna aggiunta. Solo {@code RIFERIMENTO_ENTRATA}. */
    @Column(name = "cod_entrata", length = 35)
    private String codEntrata;

    /** Colonna aggiunta. Solo {@code ENTRATA}. */
    @Column(name = "iban_accredito_v3", length = 35)
    private String ibanAccredito;

    /** Colonna aggiunta. Solo {@code ENTRATA}. */
    @Column(name = "iban_appoggio_v3", length = 35)
    private String ibanAppoggio;

    /** Colonna aggiunta. {@code ENTRATA} e {@code BOLLO} (non {@code RIFERIMENTO_ENTRATA}). */
    @Column(name = "tassonomia_v3", length = 35)
    private String tassonomia;

    /** Colonna legacy reale, stesso nome. Solo {@code BOLLO}. */
    @Column(name = "tipo_bollo", length = 2)
    private String tipoBollo;

    /** Colonna legacy reale, stesso nome. Solo {@code BOLLO}: digest in base64 del documento informatico. */
    @Column(name = "hash_documento", length = 70)
    private String hashDocumento;

    /** Colonna legacy reale, stesso nome. Solo {@code BOLLO}: sigla automobilistica della provincia di residenza. */
    @Column(name = "provincia_residenza", length = 2)
    private String provinciaResidenza;

    /**
     * Riconciliazione contabile pagoPA (mai per {@code BOLLO}), su colonna legacy riusata
     * {@code contabilita} — vedi nota di classe. Vuota, non {@code null}, quando assente.
     */
    @Convert(converter = DettaglioContabileConverter.class)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "contabilita")
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

    public double getImporto() {
        return importo;
    }

    public void setImporto(double importo) {
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
