package it.govpay.pendenze.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Debitore di una {@link PosizioneDebitoria}, mappato sulla tabella nuova
 * {@code pd_debitori}. Le {@link OpzionePagamento} (e quindi pendenze e NAV) si agganciano
 * a questa coppia posizione-debitore, non alla posizione: ogni debitore ha il proprio
 * piano di pagamento.
 *
 * <p>La mutua esclusione tra opzioni alternative resta invece a livello di intera posizione,
 * tra le opzioni di tutti i suoi debitori.</p>
 */
@Entity
@Table(name = "pd_debitori", uniqueConstraints = @UniqueConstraint(
        name = "unique_pd_debitori_1", columnNames = {"id_documento", "id_debitore"}))
public class PdDebitore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_documento", nullable = false)
    private PosizioneDebitoria posizioneDebitoria;

    /** Una riga {@code debitori} per ogni legame: nessun riuso dell'anagrafica tra posizioni. */
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "id_debitore", nullable = false)
    private Debitore debitore;

    /** Ordine di presentazione dei debitori nella posizione, 0-based. */
    @Column(name = "posizione", nullable = false)
    private int posizione;

    @Column(name = "notifica_send_payed", nullable = false)
    private boolean notificaSendPayed;

    @Column(name = "notifica_send_nav", length = 18)
    private String notificaSendNav;

    @OneToMany(mappedBy = "pdDebitore", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<OpzionePagamento> opzioniPagamento = new ArrayList<>();

    /**
     * Aggiunge un'opzione di pagamento mantenendo coerente il lato inverso della relazione.
     *
     * @param opzione opzione da aggiungere, non nulla
     */
    public void addOpzionePagamento(OpzionePagamento opzione) {
        Objects.requireNonNull(opzione, "l'opzione da aggiungere non puo' essere nulla");
        opzioniPagamento.add(opzione);
        opzione.setPdDebitore(this);
    }

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PosizioneDebitoria getPosizioneDebitoria() {
        return posizioneDebitoria;
    }

    public void setPosizioneDebitoria(PosizioneDebitoria posizioneDebitoria) {
        this.posizioneDebitoria = posizioneDebitoria;
    }

    public Debitore getDebitore() {
        return debitore;
    }

    public void setDebitore(Debitore debitore) {
        this.debitore = debitore;
    }

    public int getPosizione() {
        return posizione;
    }

    public void setPosizione(int posizione) {
        this.posizione = posizione;
    }

    public boolean isNotificaSendPayed() {
        return notificaSendPayed;
    }

    public void setNotificaSendPayed(boolean notificaSendPayed) {
        this.notificaSendPayed = notificaSendPayed;
    }

    public String getNotificaSendNav() {
        return notificaSendNav;
    }

    public void setNotificaSendNav(String notificaSendNav) {
        this.notificaSendNav = notificaSendNav;
    }

    public List<OpzionePagamento> getOpzioniPagamento() {
        return opzioniPagamento;
    }
}
