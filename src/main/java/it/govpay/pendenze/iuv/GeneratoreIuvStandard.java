package it.govpay.pendenze.iuv;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.spi.GeneratoreIuv;
import it.govpay.pendenze.spi.IdentificativiPagamento;

/**
 * Implementazione standard di {@link GeneratoreIuv}, condivisa fra tutti i consumatori invece
 * di essere reimplementata da ciascuno: legge la configurazione del dominio da
 * {@code govpay-common} (dipendenza di libreria, non relazione JPA — M4 di
 * {@code proposta-modello-nativo-v3.md} vieta solo la seconda), consuma un progressivo da
 * {@link GeneratoreProgressivoIuv} e applica {@link CostruttoreIdentificativiPagamento}.
 *
 * <p>Registrata da {@code PendenzeAutoConfiguration} come bean {@code @ConditionalOnMissingBean}
 * solo se un {@link DominioRepository} e' gia' presente nel contesto (il consumatore lo
 * fornisce usando govpay-common per le altre proprie esigenze): se assente, questa libreria
 * non prova a fornire generazione IUV, esattamente come prima di questa classe.</p>
 *
 * <p><b>Prefisso dinamico</b> (porting di {@code CustomIuv.buildPrefix}/{@code Iuv.generaIUV}):
 * il prefisso configurato sul dominio puo' contenere i placeholder {@code %(Y)}/{@code %(y)}
 * (anno a 4/2 cifre), {@code %(a)} ({@code Applicazione.codApplicazioneIuv}, cercata per
 * {@code idA2A} — assunto qui corrispondere a {@code Applicazione.codApplicazione}, come nelle
 * altre API GovPay) e {@code %(p)}/{@code %(t)} ({@code TipoVersamento.codificaIuv} nel legacy —
 * stesso valore per entrambe le chiavi, alias storici). Quest'ultimo non e' risolvibile da
 * questa libreria da sola: {@code govpay-common} non espone un'anagrafica tipo-versamento (M4),
 * quindi il valore va fornito dal chiamante tramite il parametro {@code codificaIuvTipoPendenza}
 * di {@link #genera}. Se il prefisso lo richiede e non e' stato fornito,
 * {@link RisolutorePrefissoIuv#risolvi} fallisce esplicitamente invece di propagare un
 * placeholder non sostituito fino a un {@link NumberFormatException} nel check digit.</p>
 */
public class GeneratoreIuvStandard implements GeneratoreIuv {

    private static final String TIPO_NUMERICO = "NUMERICO";
    private static final String CHIAVE_ANNO_4 = "Y";
    private static final String CHIAVE_ANNO_2 = "y";
    private static final String CHIAVE_APPLICAZIONE = "a";
    private static final String CHIAVE_TIPO_PENDENZA = "p";
    private static final String CHIAVE_TIPO_PENDENZA_ALIAS = "t";

    private final DominioRepository dominioRepository;
    private final ApplicazioneRepository applicazioneRepository;
    private final GeneratoreProgressivoIuv generatoreProgressivo;
    private final Clock clock;

    public GeneratoreIuvStandard(DominioRepository dominioRepository, ApplicazioneRepository applicazioneRepository,
            GeneratoreProgressivoIuv generatoreProgressivo, Clock clock) {
        this.dominioRepository = dominioRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.generatoreProgressivo = generatoreProgressivo;
        this.clock = clock;
    }

    @Override
    public IdentificativiPagamento genera(Long idDominio, String idA2A, String idPendenza,
            String codificaIuvTipoPendenza) {
        DominioEntity dominio = trovaDominio(idDominio);
        String prefix = risolviPrefix(dominio, idA2A, codificaIuvTipoPendenza);
        long progressivo = generatoreProgressivo.prossimoValore(chiaveProgressivo(dominio, prefix));
        return CostruttoreIdentificativiPagamento.genera(
                dominio.getAuxDigit(), prefix, dominio.getSegregationCode(), applicationCode(dominio), progressivo);
    }

    @Override
    public IdentificativiPagamento convertiDaNumeroAvviso(Long idDominio, String numeroAvviso) {
        DominioEntity dominio = trovaDominio(idDominio);
        String iuv = CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(
                numeroAvviso, dominio.getAuxDigit(), dominio.getSegregationCode(), applicationCode(dominio));
        return new IdentificativiPagamento(iuv, numeroAvviso);
    }

    private DominioEntity trovaDominio(Long idDominio) {
        return dominioRepository.findById(idDominio)
                .orElseThrow(() -> new IllegalStateException("dominio [" + idDominio + "] non censito"));
    }

    /**
     * Risolve i placeholder del prefisso configurato sul dominio e valida che il risultato sia
     * numerico (stesso controllo esplicito di {@code Iuv.generaIUV}, per fallire con un errore
     * chiaro invece che dentro il calcolo del check digit).
     */
    private String risolviPrefix(DominioEntity dominio, String idA2A, String codificaIuvTipoPendenza) {
        String prefixConfigurato = dominio.getIuvPrefix();
        if (prefixConfigurato == null || prefixConfigurato.isEmpty()) {
            return "";
        }

        Map<String, String> valori = new HashMap<>();
        LocalDate oggi = LocalDate.now(clock);
        valori.put(CHIAVE_ANNO_4, String.valueOf(oggi.getYear()));
        valori.put(CHIAVE_ANNO_2, String.format("%02d", oggi.getYear() % 100));
        applicazioneRepository.findByCodApplicazione(idA2A)
                .map(ApplicazioneEntity::getCodApplicazioneIuv)
                .ifPresent(codice -> valori.put(CHIAVE_APPLICAZIONE, codice));
        if (codificaIuvTipoPendenza != null) {
            // %(p) e %(t) sono alias storici dello stesso valore (CustomIuv/PagamentoContext
            // li mappano entrambi a versamentoCtx.getCodificaIuv()).
            valori.put(CHIAVE_TIPO_PENDENZA, codificaIuvTipoPendenza);
            valori.put(CHIAVE_TIPO_PENDENZA_ALIAS, codificaIuvTipoPendenza);
        }

        String prefixRisolto = RisolutorePrefissoIuv.risolvi(prefixConfigurato, valori);
        validaNumerico(prefixConfigurato, prefixRisolto);
        return prefixRisolto;
    }

    private void validaNumerico(String prefixConfigurato, String prefixRisolto) {
        if (prefixRisolto.isEmpty()) {
            return;
        }
        try {
            Long.parseLong(prefixRisolto);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "il prefisso IUV del dominio [" + prefixConfigurato + "], risolto in [" + prefixRisolto
                            + "], non e' numerico");
        }
    }

    private Integer applicationCode(DominioEntity dominio) {
        return dominio.getStazione() != null ? dominio.getStazione().getApplicationCode() : null;
    }

    /**
     * Stessa composizione di {@code codDominio+iuvPrefix+type.toString()} usata da
     * {@code IuvBD.getNextPrgIuv} nel legacy (il {@code toString()} di {@code TipoIUV.NUMERICO}
     * e' letteralmente {@code "NUMERICO"}): chiavi identiche, cosi' si continua lo stesso
     * progressivo sulla stessa riga di {@code ID_MESSAGGIO_RELATIVO}. Il prefisso qui e' gia'
     * risolto (non quello letterale con i placeholder): un prefisso con {@code %(Y)} deve
     * produrre chiavi diverse anno per anno, come nel legacy (il prefisso risolto passato a
     * {@code IuvBD.getNextPrgIuv} e' lo stesso usato per costruire lo IUV).
     */
    private String chiaveProgressivo(DominioEntity dominio, String prefixRisolto) {
        return dominio.getCodDominio() + prefixRisolto + TIPO_NUMERICO;
    }
}
