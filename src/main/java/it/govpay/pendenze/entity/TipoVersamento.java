package it.govpay.pendenze.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Catalogo astratto dei tipi di versamento (anagrafica legacy reale, tabella
 * {@code tipi_versamento}), non ancora portata su {@code govpay-common} — stessa situazione
 * di {@link UnitaOperativa} (decisione del lead, 2026-09-26): nasce qui perché serve subito a
 * risolvere {@code idTipoPendenza} dello YAML v3 (codice testuale, es. {@code IMU} — stessa
 * convenzione di {@code idA2A}/{@code idDominio}, verificata nel legacy:
 * {@code Versamento.SingoloVersamento} usa {@code codDominio} String, e
 * {@code AnagraficaManager.getTipoVersamento(configWrapper, codTipoVersamento)} risolve per
 * codice) negli ID numerici {@link Pendenza#getIdTipoVersamento()}/
 * {@link Pendenza#getIdTipoPendenza()} richiesti dalla libreria.
 *
 * <p><b>Proiezione minimale, non piena fedeltà</b> (decisione del lead, 2026-09-26, dopo aver
 * verificato la DDL reale): {@code tipi_versamento} ha ~55 colonne, quasi tutte
 * configurazione di stampa/notifica del BackOffice legacy (form BO/PagOffice, template
 * email/AppIO di promemoria, tracciati CSV) — nessun concetto che la v3 usi (ha il proprio
 * {@code notificaSend}/{@code dataPubblicazione}). Mappate solo {@link #codTipoVersamento}
 * (la chiave di risoluzione), {@link #descrizione} e {@link #abilitato}: le uniche
 * potenzialmente utili anche a un futuro endpoint di sola lettura di questa anagrafica.</p>
 */
@Entity
@Table(name = "tipi_versamento", uniqueConstraints = @UniqueConstraint(
        name = "unique_tipi_versamento_1", columnNames = {"cod_tipo_versamento"}))
@SequenceGenerator(name = "seq_tipi_versamento", sequenceName = "seq_tipi_versamento", allocationSize = 1)
public class TipoVersamento {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_tipi_versamento")
    @Column(name = "id")
    private Long id;

    @Column(name = "cod_tipo_versamento", nullable = false, length = 35)
    private String codTipoVersamento;

    @Column(name = "descrizione", nullable = false, length = 255)
    private String descrizione;

    @Column(name = "abilitato", nullable = false)
    private boolean abilitato;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCodTipoVersamento() {
        return codTipoVersamento;
    }

    public void setCodTipoVersamento(String codTipoVersamento) {
        this.codTipoVersamento = codTipoVersamento;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public boolean isAbilitato() {
        return abilitato;
    }

    public void setAbilitato(boolean abilitato) {
        this.abilitato = abilitato;
    }
}
