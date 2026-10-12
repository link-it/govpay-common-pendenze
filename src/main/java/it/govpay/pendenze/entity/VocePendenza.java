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
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import it.govpay.common.entity.TipoContabilita;
import it.govpay.common.entity.TipoContabilitaConverter;
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.DettaglioContabileConverter;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;

/**
 * Voce di pendenza: mappata sulla tabella legacy {@code singoli_versamenti} — riuso
 * possibile perche' {@link Pendenza} e' ora {@code versamenti}:
 * {@code id_versamento} punta sempre a una riga vera, non serve piu' una tabella nuova.
 *
 * <p><b>Stato gia' allineato</b>: {@link StatoVocePendenza#NON_ESEGUITO}/{@code ESEGUITO}
 * coincidono per stringa con {@code StatoSingoloVersamento} legacy (2 soli valori) — nessun
 * problema di grafia qui, a differenza di {@link Pendenza#getStato()}.
 * {@link StatoVocePendenza#ANOMALO} e' solo v3, stesso residuo accettato di
 * {@code StatoPendenza.ESEGUITO_ALTRO_CANALE}.</p>
 *
 * <p><b>Nessuna colonna propria aggiunta per RIFERIMENTO_ENTRATA/ENTRATA/tassonomia</b>:
 * {@code codEntrata} corrisponde a {@code tipi_tributo.cod_tributo}, e gli IBAN di
 * {@code ENTRATA} sono IBAN censiti in anagrafica (v2 li referenzia gia' cosi') — si riusano
 * quindi le FK piatte legacy reali {@link #idTributo}/{@link #idIbanAccredito}/
 * {@link #idIbanAppoggio} (verso {@code tributi}/{@code iban_accredito} di govpay-common,
 * M4: nessuna relazione JPA), risolte dal chiamante (mapper di {@code govpay-pendenze-api})
 * a partire dai codici testuali della richiesta. {@link #tipoBollo}/{@link #hashDocumento}/
 * {@link #provinciaResidenza} coincidono esattamente con colonne legacy reali.</p>
 *
 * <p><b>{@link #getTipoRiferimento()}</b> (RIFERIMENTO_ENTRATA/ENTRATA/BOLLO) e
 * <b>{@link #getTassonomia()}</b> non sono colonne: si derivano da quali altre colonne sono
 * valorizzate — stesso comportamento di v2, che non ha mai avuto ne' un discriminatore ne'
 * una colonna tassonomia propria (v2 combina {@code tipo_contabilita}/{@code codice_contabilita}
 * a runtime). Vedi Javadoc dei rispettivi metodi.</p>
 *
 * <p>{@link #dettaglioContabile} riusa {@code contabilita} (colonna legacy, gia' JSON —
 * vedi {@code ContabilitaConverter} legacy): il formato non si sovrappone su nessuna
 * chiave con quello vecchio (Contabilita/QuotaContabilita ha {@code quote}/
 * {@code proprietaCustom}, {@code DettaglioContabile} ha {@code tipo}) — la
 * compatibilita' si gestisce a livello applicativo (ragioneria v3),
 * non con una colonna separata.</p>
 *
 * <p>{@link #idDominio} e' invece una colonna legacy reale rimasta fuori dalla mappatura
 * iniziale (multi-beneficiario pagoPA) — vedi Javadoc del
 * campo.</p>
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

    /**
     * Dominio creditore di questa voce, se diverso da quello della pendenza/posizione (caso
     * multi-beneficiario pagoPA: un avviso con voci destinate a enti creditori diversi).
     * Colonna legacy reale ({@code singoli_versamenti.id_dominio}, FK verso {@code domini}),
     * mai mappata finora — stessa causa di omissione gia' vista per
     * {@code PosizioneDebitoria.dataPubblicazione}. A differenza di li', qui {@code NULL} non basta a significare "eredita
     * dal padre": {@link it.govpay.pendenze.service.PosizioneDebitoriaService#crea} la
     * materializza sempre esplicitamente al valore della posizione se il chiamante non
     * indica un override — stesso principio gia' in uso per {@link Pendenza#getIdDominio()}
     * (mai lasciato implicito rispetto alla posizione), confermato anche dal comportamento
     * del legacy ({@code VersamentoUtils.toSingoloVersamentoModel}: se {@code codDominio} e'
     * assente sulla voce, usa comunque quello del versamento, non lo lascia indefinito).
     */
    @Column(name = "id_dominio")
    private Long idDominio;

    @Column(name = "stato_singolo_versamento", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoVocePendenza stato;

    /**
     * FK piatta legacy reale (M4) verso {@code tributi.id} di govpay-common — non verso
     * {@code tipi_tributo.id}: {@code codEntrata} della richiesta REST (stesso namespace di
     * {@code tipi_tributo.cod_tributo}, catalogo globale) va risolto dal chiamante prima
     * verso il {@code TipoTributoEntity} globale, poi verso il {@code TributoEntity} di
     * QUESTO dominio (override/configurazione IBAN e contabilita' per dominio) — stesso
     * schema a due livelli gia' usato per {@code idTipoPendenza}/{@code idTipoVersamento}
     * di {@link Pendenza}. Solo {@code RIFERIMENTO_ENTRATA}.
     */
    @Column(name = "id_tributo")
    private Long idTributo;

    /**
     * FK piatta legacy reale (M4) verso {@code iban_accredito.id} di govpay-common: l'IBAN
     * di {@code ENTRATA} e' sempre un IBAN censito in anagrafica (v2 lo referenzia gia'
     * cosi', mai come stringa libera) — risolto dal chiamante a partire dal codice IBAN
     * della richiesta. Solo {@code ENTRATA}.
     */
    @Column(name = "id_iban_accredito")
    private Long idIbanAccredito;

    /** Come {@link #idIbanAccredito}, per l'IBAN di appoggio. Solo {@code ENTRATA}. */
    @Column(name = "id_iban_appoggio")
    private Long idIbanAppoggio;

    /**
     * Colonna legacy reale, stesso nome (nullable: nessun valore sensato da retro-assegnare
     * alle voci storiche v2, stesso principio gia' visto per altri campi opzionali di
     * quest'entita'). {@code ENTRATA} e {@code BOLLO} (non {@code RIFERIMENTO_ENTRATA}) —
     * componente numerica di {@link #getTassonomia()}/{@link #setTassonomia(String)}, mai
     * letta/scritta direttamente da chi consuma questa entita' dall'esterno.
     */
    @Convert(converter = TipoContabilitaConverter.class)
    @Column(name = "tipo_contabilita", length = 1)
    private TipoContabilita tipoContabilita;

    /**
     * Colonna legacy reale, stesso nome. Componente testuale libera di
     * {@link #getTassonomia()}/{@link #setTassonomia(String)} — puo' contenere a sua volta
     * il carattere {@code /}, per questo lo split in {@link #setTassonomia(String)} avviene
     * solo sulla prima occorrenza.
     */
    @Column(name = "codice_contabilita", length = 255)
    private String codiceContabilita;

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

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public StatoVocePendenza getStato() {
        return stato;
    }

    public void setStato(StatoVocePendenza stato) {
        this.stato = stato;
    }

    /**
     * Derivato, non una colonna (v2 non ha mai avuto questo
     * discriminatore, lo deduce da quali colonne sono valorizzate — vedi nota di classe):
     * {@code BOLLO} se {@link #tipoBollo} e' valorizzato, altrimenti {@code RIFERIMENTO_ENTRATA}
     * se {@link #idTributo} e' valorizzato, altrimenti {@code ENTRATA} se
     * {@link #idIbanAccredito} e' valorizzato, altrimenti {@code null} (nessuna delle tre
     * forme riconoscibile — dato storico incompleto).
     */
    @Transient
    public TipoRiferimentoVocePendenza getTipoRiferimento() {
        if (tipoBollo != null) {
            return TipoRiferimentoVocePendenza.BOLLO;
        }
        if (idTributo != null) {
            return TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA;
        }
        if (idIbanAccredito != null) {
            return TipoRiferimentoVocePendenza.ENTRATA;
        }
        return null;
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

    /**
     * Derivato, non una colonna (v2 concatena
     * {@code tipo_contabilita}/{@code codice_contabilita} a runtime, non ha mai avuto una
     * colonna tassonomia propria — vedi nota di classe): {@code null} se
     * {@link #tipoContabilita}/{@link #codiceContabilita} non sono entrambi valorizzati,
     * altrimenti {@code tipoContabilita.getCodifica() + "/" + codiceContabilita}.
     */
    @Transient
    public String getTassonomia() {
        if (tipoContabilita == null || codiceContabilita == null) {
            return null;
        }
        return tipoContabilita.getCodifica() + "/" + codiceContabilita;
    }

    /**
     * Spacchetta {@code tassonomia} nelle due colonne legacy che la compongono davvero —
     * vedi {@link #getTassonomia()}. Lo split avviene sulla <b>prima</b> occorrenza di
     * {@code /}: {@link #tipoContabilita} e' sempre una singola cifra numerica, mentre
     * {@link #codiceContabilita} e' testo libero che puo' contenere a sua volta {@code /}
     * — splittare sull'ultima occorrenza, o senza limite,
     * tronca erroneamente {@code codiceContabilita} sul primo {@code /} che contiene.
     *
     * @param tassonomia {@code null} azzera entrambi i campi
     * @throws IllegalArgumentException se {@code tassonomia} non contiene {@code /}
     */
    public void setTassonomia(String tassonomia) {
        if (tassonomia == null) {
            this.tipoContabilita = null;
            this.codiceContabilita = null;
            return;
        }
        int separatore = tassonomia.indexOf('/');
        if (separatore < 0) {
            throw new IllegalArgumentException(
                    "tassonomia [" + tassonomia + "] non e' nel formato atteso tipoContabilita/codiceContabilita");
        }
        String codificaTipoContabilita = tassonomia.substring(0, separatore);
        this.tipoContabilita = TipoContabilita.daCodifica(codificaTipoContabilita);
        this.codiceContabilita = tassonomia.substring(separatore + 1);
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
