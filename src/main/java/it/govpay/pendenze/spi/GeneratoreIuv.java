package it.govpay.pendenze.spi;

/**
 * Genera un nuovo IUV/numero avviso (NAV), o ricava l'IUV da un NAV fornito dal chiamante:
 * due operazioni distinte, non intercambiabili (vedi {@code proposta-modello-nativo-v3.md}).
 * La generazione consuma un progressivo dedicato al dominio; la conversione e' una pura
 * decodifica di formato che non ne consuma alcuno — esattamente come nel legacy, dove
 * {@code IuvBD.generaIuv} e {@code VersamentoUtils.getIuvFromNumeroAvviso} sono percorsi di
 * codice separati.
 *
 * <p>{@link it.govpay.pendenze.iuv.GeneratoreIuvStandard} fornisce un'implementazione
 * condivisa (registrata da {@code PendenzeAutoConfiguration} come bean
 * {@code @ConditionalOnMissingBean}, se un {@code DominioRepository} di
 * {@code govpay-common} e' disponibile nel contesto), cosi' l'algoritmo standard
 * pagoPA non va reimplementato da ogni consumatore. Un consumatore puo' comunque
 * sostituirla con una propria implementazione come bean Spring; se nessuna e'
 * disponibile, {@link it.govpay.pendenze.service.PosizioneDebitoriaService#crea} rifiuta
 * la creazione di pendenze prive di IUV/numero avviso invece di inserire colonne
 * {@code NOT NULL} vuote.</p>
 */
public interface GeneratoreIuv {

    /**
     * @param idDominio              dominio creditore della posizione debitoria
     * @param idA2A                  identificativo del gestionale responsabile
     * @param idPendenza             identificativo della pendenza nel gestionale
     * @param codificaIuvTipoPendenza codifica IUV del tipo pendenza (equivalente di
     *                               {@code TipoVersamento.codificaIuv} del legacy), o
     *                               {@code null} se non disponibile/non richiesta. Serve solo
     *                               a risolvere i placeholder {@code %(p)}/{@code %(t)} del
     *                               prefisso IUV di dominio: questa libreria non ha accesso
     *                               all'anagrafica tipo-versamento (M4), quindi non puo'
     *                               risolvere da sola {@code idTipoPendenza} in una codifica —
     *                               il chiamante che la conosce la fornisce qui (vedi
     *                               {@link it.govpay.pendenze.entity.Pendenza#getCodificaIuvTipoPendenza()}).
     * @return la coppia IUV/numero avviso da assegnare
     */
    IdentificativiPagamento genera(Long idDominio, String idA2A, String idPendenza, String codificaIuvTipoPendenza);

    /**
     * Ricava e valida l'IUV da un numero avviso fornito dal chiamante, senza consumare alcun
     * progressivo. L'implementazione di default solleva {@link UnsupportedOperationException}:
     * un consumatore che sostituisce {@link GeneratoreIuv} con una propria implementazione
     * puramente generativa non e' obbligato a supportare anche la conversione.
     *
     * @param idDominio    dominio creditore della posizione debitoria
     * @param numeroAvviso numero avviso fornito dal chiamante
     * @return la coppia IUV/numero avviso, con lo stesso {@code numeroAvviso} fornito
     */
    default IdentificativiPagamento convertiDaNumeroAvviso(Long idDominio, String numeroAvviso) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " non supporta la conversione da numero avviso a IUV");
    }
}
