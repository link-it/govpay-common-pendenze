package it.govpay.pendenze.spi;

import java.util.Objects;

/**
 * Coppia IUV/numero avviso (NAV) assegnata a una pendenza, prodotta da
 * {@link GeneratoreIuv}.
 *
 * @param iuv          Identificativo Univoco di Versamento
 * @param numeroAvviso NAV: identificativo dell'avviso di pagamento pagoPA
 */
public record IdentificativiPagamento(String iuv, String numeroAvviso) {

    public IdentificativiPagamento {
        Objects.requireNonNull(iuv, "iuv non puo' essere nullo");
        Objects.requireNonNull(numeroAvviso, "numeroAvviso non puo' essere nullo");
    }
}
