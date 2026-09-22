package it.govpay.pendenze.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Posizione debitoria: radice dell'aggregato pendenza nel modello nativo v3, mappata
 * sulla tabella {@code posizioni_debitorie}.
 *
 * <p><b>Confine dell'aggregato.</b> Come nel disegno precedente (§4.8 di
 * {@code proposta-libreria-pendenze.md}), le chiavi esterne verso l'anagrafica
 * ({@code idDominio}, {@code idUnitaOperativa}) sono mappate come semplici {@code Long},
 * non come relazioni JPA, per non accoppiare il grafo delle entita' e la persistence
 * unit di ogni consumatore. Le relazioni verso {@link SoggettoDebitore} e
 * {@link OpzionePagamento} sono invece relazioni JPA vere: appartengono allo stesso
 * aggregato e alla stessa unita' transazionale.</p>
 *
 * <p><b>{@code iupd} non e' una colonna.</b> Verificato lo spec pagoPA
 * {@code gpd-4-aca.json}: l'identificativo usato nell'interfaccia ACA/GPD non ha alcun
 * vincolo di formato, l'unicita' e' responsabilita' dell'Ente Creditore. Si deriva al
 * volo da {@code idA2A}+{@code idPosizioneDebitoria} (gia' garantiti univoci insieme),
 * senza persistere nulla di nuovo.</p>
 *
 * <p><b>Soggetto pagatore: nessuno snapshot.</b> A differenza di una prima proposta, per
 * decisione esplicita del lead si usa {@code soggettiDebitori} ordinato per
 * {@link SoggettoDebitore#getOrdine()}, primo elemento per convenzione (cosi' come indica
 * lo YAML v3), in attesa di un'evoluzione dell'interfaccia pagoPA che porti l'identita'
 * del soggetto pagatore esplicitamente nelle operazioni.</p>
 */
@Entity
@Table(name = "posizioni_debitorie", uniqueConstraints = @UniqueConstraint(
        name = "unique_posizioni_debitorie_1", columnNames = {"id_a2a", "id_posizione_debitoria"}))
@SequenceGenerator(name = "seq_posizioni_debitorie", sequenceName = "seq_posizioni_debitorie", allocationSize = 1)
public class PosizioneDebitoria {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_posizioni_debitorie")
    @Column(name = "id")
    private Long id;

    @Column(name = "id_a2a", nullable = false, length = 35)
    private String idA2A;

    @Column(name = "id_posizione_debitoria", nullable = false, length = 35)
    private String idPosizioneDebitoria;

    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    @Column(name = "id_unita_operativa")
    private Long idUnitaOperativa;

    @Column(name = "descrizione", nullable = false, length = 140)
    private String descrizione;

    /** {@code null} = pubblicata immediatamente (semantica dello YAML v3). */
    @Column(name = "data_pubblicazione")
    private LocalDate dataPubblicazione;

    @Column(name = "notifica_send", nullable = false)
    private boolean notificaSend;

    /** Deve corrispondere al {@code numeroAvviso} di una pendenza della posizione. */
    @Column(name = "nav_notifica", length = 18)
    private String navNotifica;

    /** Valorizzata da questa libreria: fa prendere in carico la posizione dal batch ACA. */
    @Column(name = "data_ultima_modifica_aca")
    private OffsetDateTime dataUltimaModificaAca;

    /** Scritta esclusivamente dal batch ACA, mai da questa libreria. */
    @Column(name = "data_ultima_comunicazione_aca")
    private OffsetDateTime dataUltimaComunicazioneAca;

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "data_ultimo_aggiornamento", nullable = false)
    private OffsetDateTime dataUltimoAggiornamento;

    /**
     * Elenco dei soggetti obbligati al pagamento, in ordine: il primo e' per convenzione
     * il soggetto pagatore usato in RPT e nelle comunicazioni che richiedono un unico
     * destinatario (Nodo dei Pagamenti, ACA/GPD).
     */
    @OneToMany(mappedBy = "posizioneDebitoria", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordine ASC")
    private List<SoggettoDebitore> soggettiDebitori = new ArrayList<>();

    @OneToMany(mappedBy = "posizioneDebitoria", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<OpzionePagamento> opzioniPagamento = new ArrayList<>();

    /**
     * Aggiunge un soggetto debitore mantenendo coerente il lato inverso della relazione.
     * L'assegnazione di {@code ordine} resta a carico del chiamante (servizio di
     * caricamento): l'entita' non decide da sola la posizione nell'elenco.
     *
     * @param soggetto soggetto da aggiungere, non nullo
     */
    public void addSoggettoDebitore(SoggettoDebitore soggetto) {
        Objects.requireNonNull(soggetto, "il soggetto da aggiungere non puo' essere nullo");
        soggettiDebitori.add(soggetto);
        soggetto.setPosizioneDebitoria(this);
    }

    /**
     * Aggiunge un'opzione di pagamento mantenendo coerente il lato inverso della
     * relazione.
     *
     * @param opzione opzione da aggiungere, non nulla
     */
    public void addOpzionePagamento(OpzionePagamento opzione) {
        Objects.requireNonNull(opzione, "l'opzione da aggiungere non puo' essere nulla");
        opzioniPagamento.add(opzione);
        opzione.setPosizioneDebitoria(this);
    }

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdA2A() {
        return idA2A;
    }

    public void setIdA2A(String idA2A) {
        this.idA2A = idA2A;
    }

    public String getIdPosizioneDebitoria() {
        return idPosizioneDebitoria;
    }

    public void setIdPosizioneDebitoria(String idPosizioneDebitoria) {
        this.idPosizioneDebitoria = idPosizioneDebitoria;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public Long getIdUnitaOperativa() {
        return idUnitaOperativa;
    }

    public void setIdUnitaOperativa(Long idUnitaOperativa) {
        this.idUnitaOperativa = idUnitaOperativa;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public LocalDate getDataPubblicazione() {
        return dataPubblicazione;
    }

    public void setDataPubblicazione(LocalDate dataPubblicazione) {
        this.dataPubblicazione = dataPubblicazione;
    }

    public boolean isNotificaSend() {
        return notificaSend;
    }

    public void setNotificaSend(boolean notificaSend) {
        this.notificaSend = notificaSend;
    }

    public String getNavNotifica() {
        return navNotifica;
    }

    public void setNavNotifica(String navNotifica) {
        this.navNotifica = navNotifica;
    }

    public OffsetDateTime getDataUltimaModificaAca() {
        return dataUltimaModificaAca;
    }

    public void setDataUltimaModificaAca(OffsetDateTime dataUltimaModificaAca) {
        this.dataUltimaModificaAca = dataUltimaModificaAca;
    }

    public OffsetDateTime getDataUltimaComunicazioneAca() {
        return dataUltimaComunicazioneAca;
    }

    public void setDataUltimaComunicazioneAca(OffsetDateTime dataUltimaComunicazioneAca) {
        this.dataUltimaComunicazioneAca = dataUltimaComunicazioneAca;
    }

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }

    public OffsetDateTime getDataUltimoAggiornamento() {
        return dataUltimoAggiornamento;
    }

    public void setDataUltimoAggiornamento(OffsetDateTime dataUltimoAggiornamento) {
        this.dataUltimoAggiornamento = dataUltimoAggiornamento;
    }

    public List<SoggettoDebitore> getSoggettiDebitori() {
        return soggettiDebitori;
    }

    public List<OpzionePagamento> getOpzioniPagamento() {
        return opzioniPagamento;
    }
}
