package it.govpay.pendenze.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Chiave composta di {@link ProgressivoIuv}: {@code (protocollo, infoAssociata)}, esattamente
 * la struttura della tabella legacy {@code ID_MESSAGGIO_RELATIVO} (vedi {@link ProgressivoIuv}).
 */
@Embeddable
public class ProgressivoIuvId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "protocollo", length = 255, nullable = false)
    private String protocollo;

    @Column(name = "info_associata", length = 255, nullable = false)
    private String infoAssociata;

    protected ProgressivoIuvId() {
        // richiesto da JPA
    }

    public ProgressivoIuvId(String protocollo, String infoAssociata) {
        this.protocollo = protocollo;
        this.infoAssociata = infoAssociata;
    }

    public String getProtocollo() {
        return protocollo;
    }

    public String getInfoAssociata() {
        return infoAssociata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProgressivoIuvId other)) {
            return false;
        }
        return Objects.equals(protocollo, other.protocollo) && Objects.equals(infoAssociata, other.infoAssociata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocollo, infoAssociata);
    }

    @Override
    public String toString() {
        return protocollo + "/" + infoAssociata;
    }
}
