# Analisi del codice legacy: gestione pendenze in GovPay 3.10.x

Documento di lavoro per definire il perimetro funzionale della libreria
`govpay-common-pendenze`.

- **Sorgente analizzata:** `../govpay` — branch `3.10.x`, commit `305365385` (2026-07-15)
- **Data analisi:** 2026-07-27

## 1. Perimetro e metodo

Analizzati i percorsi di **caricamento**, **lettura** e **aggiornamento** di una
pendenza: modello dati, accesso alla base dati, logica di business, servizi
applicativi (DAO), caricamento massivo (tracciati), acquisizione dall'Ente Creditore
e tutti i chiamanti (API REST, SOAP pagoPA, batch).

**Escluso dall'analisi di dettaglio** (citato solo dove interagisce con le pendenze):
rendicontazione, incassi, RPT/RT, stampe avvisi, promemoria, notifiche AppIO,
anagrafica domini/applicazioni.

Il livello ORM (`jars/orm`, ~57.600 righe su 123 file per il solo aggregato
`Versamento`/`SingoloVersamento`/`TipoVersamento`/viste) è **codice generato**
(openspcoop2 `JDBC*Service*`, `*FieldConverter`, `*Fetch`, `*Model`): non contiene
logica di dominio e non va riportato nella nuova libreria, va sostituito.

### 1.1 Nota terminologica

Il dominio ha **due vocabolari sovrapposti**, ed è la prima ambiguità da risolvere
nella nuova libreria:

| Concetto API / funzionale | Nome interno / DB |
|---|---|
| Pendenza | `Versamento` |
| Voce di pendenza | `SingoloVersamento` |
| Tipo pendenza | `TipoVersamento`, `TipoVersamentoDominio` |
| Stato pendenza (`NON_ESEGUITA`, …) | `StatoVersamento` (`NON_ESEGUITO`, …) |
| Documento (raggruppamento pendenze) | `Documento` |

## 2. Mappa a strati

| Modulo | Ruolo | Classi rilevanti | Righe |
|---|---|---|---|
| `jars/orm-beans` | POJO di modello | `model.Versamento`, `model.SingoloVersamento`, `model.TipoVersamento`, `model.TipoVersamentoDominio`, `model.StatoPendenza` | 2.010 |
| `jars/orm` | Accesso dati + modello arricchito | `bd.model.Versamento`, `bd.model.SingoloVersamento`, `bd.pagamento.VersamentiBD`, `bd.viste.VersamentiBD`, i due `VersamentoFilter` | 4.152 (+57.6k generato) |
| `jars/core-beans` | DTO di scambio + eccezioni | `core.beans.commons.Versamento`, `core.beans.tracciati.*`, `core.exceptions.Versamento*Exception` | 6.440 |
| `jars/core` | Logica di business e servizi | `core.business.Versamento`, `core.utils.VersamentoUtils`, `core.dao.pagamenti.PendenzeDAO`, `core.business.Tracciati`, `OperazioneFactory` | 4.966 |
| `jars/client-api-ente` | Beans client verso EC | `ec.v1.beans.*`, `ec.v2.beans.*` (`PendenzaVerificata`, `NuovaPendenza`, …) | — |
| `wars/api-pendenze` | API Pendenze v1/v2 | `PendenzeController` ×2, `PendenzeConverter` ×2, beans | 1.981 + beans |
| `wars/api-backoffice` | API Backoffice v1 | `PendenzeController` (1.152 righe), `PendenzeConverter` (978), `TipiPendenzaController` | 2.130 + beans |
| `wars/api-ragioneria` | API Ragioneria v1/v2/v3 | `PendenzeApiServiceImpl` (v3), `PendenzeConverter` ×3 | 940 |
| `wars/api-pagopa` | SOAP pagoPA (verifica/attiva) | `PagamentiTelematiciCCPImpl` | — |

**Volume della logica di dominio da riprogettare:** ~10.800 righe distribuite su 16
file tra `orm-beans`, `orm`, `core-beans` e `core` (modello, BD, filtri, business,
DAO, tracciati, validatori), a cui si aggiungono 3.305 righe dei soli 7
`PendenzeConverter` delle API. Esclusi da questo conteggio l'ORM generato e i beans
delle singole versioni di API.

## 3. Modello dati

### 3.1 `it.govpay.model.Versamento` (67 campi)

Raggruppabili in:

- **Identità:** `id`, `codVersamentoEnte`, `idApplicazione`, `idDominio`, `idUo`,
  `idTipoVersamento`, `idTipoVersamentoDominio`, `codBundlekey`, `idSessione`
- **Avviso/IUV:** `iuvVersamento`, `numeroAvviso`, `iuvProposto`, `tassonomiaAvviso`
- **Importi e stato:** `importoTotale`, `statoVersamento`, `descrizioneStato`,
  `anomalo`, `anomalie`, `ack`, `aggiornabile`
- **Pagamento:** `statoPagamento`, `importoPagato`, `importoIncassato`,
  `dataPagamento`, `iuvPagamento`, `incasso`
- **Date:** `dataCreazione`, `dataValidita`, `dataScadenza`, `dataUltimoAggiornamento`
- **Debitore e causale:** `anagraficaDebitore`, `causaleVersamento` (`Causale`,
  `CausaleSemplice`, `SpezzoneCausaleStrutturata`)
- **Classificazione:** `tipo` (`DOVUTO`/`SPONTANEO`), `divisione`, `direzione`,
  `tassonomia`, `codLotto`, `codVersamentoLotto`, `codAnnoTributario`, `numeroRata`,
  `codDocumento`, `idDocumento`, `datiAllegati`, `proprieta`
- **Soglia (pagamento parziale):** `tipoSoglia` (`TipoSogliaVersamento`), `giorniSoglia`
- **Avvisatura:** `dataNotificaAvviso`, `avvisoNotificato`,
  `avvMailDataPromemoriaScadenza`, `avvMailPromemoriaScadenzaNotificato`,
  `avvAppIODataPromemoriaScadenza`, `avvAppIOPromemoriaScadenzaNotificato`
- **Sincronizzazione ACA:** `dataUltimaModificaAca`, `dataUltimaComunicazioneAca`

Enum interne: `StatoVersamento`, `StatoPagamento`, `TipologiaTipoVersamento`,
`AvvisaturaOperazione`, `TipoSogliaVersamento`.

### 3.2 Stati

```
StatoVersamento:  NON_ESEGUITO | ESEGUITO | PARZIALMENTE_ESEGUITO | ANNULLATO
                  ESEGUITO_ALTRO_CANALE | ANOMALO | ESEGUITO_SENZA_RPT | INCASSATO
StatoPagamento:   NON_PAGATO | PAGATO | INCASSATO
StatoSingoloVersamento: NON_ESEGUITO | ESEGUITO
```

Transizioni consentite (dedotte dal codice, **non** centralizzate in una macchina a stati):

| Da | A | Dove |
|---|---|---|
| `NON_ESEGUITO` | `ANNULLATO` | `business.Versamento.annullaVersamento`, `PendenzeDAO.patchStato` |
| `ANNULLATO` | `NON_ESEGUITO` | `PendenzeDAO.patchStato` (ripristino) |
| `NON_ESEGUITO` | `ESEGUITO` / `PARZIALMENTE_ESEGUITO` / `ANOMALO` | `RtUtils`, `CtReceiptUtils`, `CtReceiptV2Utils` (da ricevuta pagoPA) |
| `NON_ESEGUITO` | `ESEGUITO` ("senza RPT") | `business.Incassi` |
| qualsiasi | `INCASSATO` | `business.Incassi` |

Aggiornamento consentito solo se lo stato è `NON_ESEGUITO` o `ANNULLATO`
(`VersamentoUtils.validazioneSemanticaAggiornamento:154`).

### 3.3 Modello arricchito: `bd.model.Versamento`

Estende il POJO con **lazy loading dalle relazioni** (`getSingoliVersamenti`,
`getApplicazione`, `getDominio`, `getUo`, `getTipoVersamento`,
`getTipoVersamentoDominio`, `getDocumento`, `getAllegati`, `getRpt`) — ognuno in tre
varianti (`()`, `(BDConfigWrapper)`, `(BasicBD)`), più il flag transiente `created`
e la serializzazione JSON di `proprieta` ↔ `ProprietaPendenza`.

> **Punto critico per il redesign:** il modello di dominio conosce il layer di
> persistenza (`BasicBD`/`BDConfigWrapper` passati come parametri ai getter). È la
> ragione principale per cui la logica di pendenza non è oggi estraibile.

### 3.4 DTO di scambio: `core.beans.commons.Versamento` (683 righe)

Bean pivot **indipendente dalle versioni API**, con classi innestate
`SingoloVersamento` (+ `TipoContabilita`, `BolloTelematico`, `Tributo`),
`SpezzoneCausaleStrutturata`, `Documento`, `AllegatoPendenza`. Tutti i flussi di
ingresso convergono qui prima di diventare `bd.model.Versamento` via
`VersamentoUtils.toVersamentoModel`.

## 4. Funzionalità

### A. Caricamento (creazione / aggiornamento sincrono)

#### A.1 Motore di caricamento — `caricaVersamento`

`jars/core/.../core/business/Versamento.java:97` (le 3 overload sono `@Deprecated`
ma sono **l'unico** punto di ingresso usato). Sequenza:

1. Validazione semantica (`VersamentoUtils.validazioneSemantica`): unicità dei
   `codSingoloVersamentoEnte`, somma importi voci == `importoTotale`
2. Ricerca per `(idApplicazione, codVersamentoEnte)` → discrimina insert/update
3. **Ramo update:** errore `VER_015` se `aggiornaSeEsiste=false`; copia dei campi non
   modificabili (`copiaPropertiesNonModificabiliVersamento:341` — 20 campi:
   tipo, ack, date di creazione, stato/importi di pagamento, tutti i flag di
   avvisatura, `proprietaPendenza`, iuv/numeroAvviso, `idDocumento`, campi ACA);
   generazione IUV se assente; `validazioneSemanticaAggiornamento`; ricalcolo
   `dataUltimaModificaAca`; `updateVersamento(deep=true)`
4. **Ramo insert:** validazione del numero avviso rispetto a dominio/stazione
   (opzionale), controllo di unicità `(dominio, iuv)` → `VER_025`, generazione IUV +
   numero avviso, calcolo delle date di avvisatura (avviso, promemoria scadenza
   mail e AppIO, con giorni di preavviso da `TipoVersamentoDominio` o
   configurazione globale), `idSessione` UUID, stato pagamento iniziale,
   `dataUltimaModificaAca`, `insertVersamento`, risveglio del batch promemoria
5. Gestione manuale di connessione/transazione (`setupConnection`, `setAutoCommit`,
   `commit`/`rollback`, `setAtomica`) con doppio flusso a seconda che il chiamante
   passi o no un `BasicBD`
6. Flag `salvataggioSuDB=false` per il caricamento "in memoria" (usato dalle
   verifiche/anteprime)

Validazione dell'aggiornamento (`VersamentoUtils.validazioneSemanticaAggiornamento:151`):
stato aggiornabile, beneficiario (UO) immutabile, numero avviso immutabile, le voci
esistenti non possono diminuire né cambiare `idTributo`/`ibanAccredito`,
**riassegnazione dell'`indiceDati`** alle voci (le nuove ricevono indici incrementali).

**Chi lo usa:** `PendenzeDAO.createOrUpdate:754`, `PendenzeDAO.createOrUpdateCustom:923`,
`OperazioneFactory.caricaVersamento:88` e `caricaVersamentoCSV` (tracciati),
`business.Incassi`, `business.Operazioni`, `business.Tracciati`, `AvvisiDAO`.

#### A.2 Servizio applicativo — `PendenzeDAO.createOrUpdate`

`jars/core/.../dao/pagamenti/PendenzeDAO.java:735`. Converte il DTO commons in
modello, imposta `tipo`, autorizza l'applicazione chiamante
(`Applicazione.autorizzaApplicazione`), decide `generaIuv`
(`VersamentoUtils.generaIUV`), chiama `caricaVersamento`, calcola bar/QR code
(`IuvUtils.toIuv`) e opzionalmente genera il PDF dell'avviso (salvo pendenze
multibeneficiario, `isPendenzaMBT`) oppure invalida la stampa esistente.

#### A.3 Caricamento "modello 4" — `PendenzeDAO.createOrUpdateCustom`

`PendenzeDAO.java:795`. Caricamento da payload custom con pipeline configurata sul
`TipoVersamentoDominio`, differenziata per `DOVUTO` (portale backoffice) e
`SPONTANEO` (portale pagamenti):

1. Risoluzione dominio / UO / tipo pendenza (eccezioni dedicate se assenti)
2. **Validazione** del JSON custom (`VersamentoUtils.validazioneInputVersamentoModello4`)
3. **Trasformazione** del payload (`trasformazioneInputVersamentoModello4`, con
   accesso a query/path parameters e header della richiesta)
4. **Inoltro** all'applicazione configurata (`inoltroInputVersamentoModello4` →
   `VersamentoUtils.inoltroPendenza` → `VerificaClient.inoltroPendenza`) **oppure**
   parsing locale `PendenzaPost` + `PendenzaPostValidator` + `TracciatiConverter`
5. Autorizzazione, discriminazione created/updated, `caricaVersamento`, avviso

#### A.4 Validazione dell'input pendenza

`core/utils/tracciati/validator/PendenzaPostValidator.java` (366 righe) — validatore
condiviso tra tracciati e modello 4; usa `ValidatoreUtils`/`ValidatoreIdentificativo`
per: importi, date, anno di riferimento, numero avviso, rata, soglie
(tipo/giorni), causale, descrizione, tipo contabilità, codice contabilità, tipo
bollo, hash documento, cartella di pagamento, provincia di residenza, nome pendenza.
Analoghi per l'input EC: `core/ec/v1/validator/PendenzaVerificataValidator`,
`core/ec/v2/validator/PendenzaVerificataValidator`, `VocePendenzaValidator` (v1 e v2).

### B. Caricamento massivo (tracciati)

| Funzione | Classe |
|---|---|
| Orchestrazione elaborazione tracciato JSON | `core/business/Tracciati.java:197` `_elaboraTracciatoJSON` |
| Orchestrazione elaborazione tracciato CSV | `core/business/Tracciati.java:644` `_elaboraTracciatoCSV` |
| Esecuzione singola operazione | `business/model/tracciati/operazioni/OperazioneFactory.java` — `caricaVersamento:71`, `caricaVersamentoCSV:168`, `annullaVersamento:316`, `elaboraLineaCSV:376` |
| Concorrenza e deduplica | `core/utils/tracciati/TracciatiPendenzeManager.java` — set delle pendenze già viste, **lock per documento** (`getDocumento`/`releaseDocumento` con `wait`/`notifyAll`), lista dei numeri avviso generati |
| Utility di esito | `core/utils/tracciati/TracciatiUtils.java` (conteggi, descrizione esito, applicazione) |
| Esito e reportistica | `Tracciati.getEsitoElaborazioneTracciato:1119`, `getEsitoElaborazioneTracciatoCSV:1165`, `fillOperazione:1256` |
| Stato per riga | `OperazioniBD` + `Operazione` (`TipoOperazioneType.ADD`/`DEL`, `StatoOperazioneType`) |
| Persistenza tracciato | `TracciatiBD`, `bd.model.Tracciato`, `StatoTracciatoPendenza` |
| Beans di tracciato | `core.beans.tracciati.*`: `TracciatoPendenzePost`, `TracciatoPendenza`, `PendenzaPost`, `AnnullamentoPendenza`, `EsitoOperazionePendenza`, `StatoOperazionePendenza`, `TipoOperazionePendenza`, `DettaglioTracciatoPendenzeEsito`, `VocePendenza`, `NuovoAllegatoPendenza`, `ProprietaPendenza` |
| Schedulazione | `core/utils/tasks/ElaborazioneTracciatiPendenze.java` + `…Check.java`, `Operazioni.elaborazioneTracciatiPendenze:661` |
| Riprendibilità | ripresa dalla linea già elaborata (`numLinea`), aggiornamento incrementale di `beanDati` |

Il tracciato produce anche lo **ZIP delle stampe** degli avvisi
(`Tracciati.salvaZipStampeTracciato:1102`).

**Chi lo usa:** API Backoffice v1 (`PendenzeController.addTracciatoPendenze` ×3
overload, `findTracciatiPendenze`, `getTracciatoPendenze`,
`getEsitoTracciatoPendenze`, `findOperazioniTracciatoPendenze`,
`getRichiestaTracciatoPendenze`, `getStampeTracciatoPendenze`) e il batch.

### C. Lettura

| Funzione | Implementazione | Note |
|---|---|---|
| Lettura per `(idA2A, idPendenza)` | `PendenzeDAO.leggiPendenza:315` → `leggiPendenzaEngine:337` | popola voci, pagamenti, RPT, allegati, documento |
| Lettura per avviso `(idDominio, numeroAvviso)` | `PendenzeDAO.leggiPendenzaByRiferimentoAvviso:386` | se non presente localmente **acquisisce dall'EC** (vedi D) |
| Lista + conteggio | `PendenzeDAO.listaPendenze:203/215`, `countPendenze:119` | usa `bd.viste.VersamentiBD` (**vista DB** `VistaVersamento`), non la tabella |
| Lettura avviso di pagamento | `PendenzeDAO.leggiAvvisoPagamento:966`, `AvvisiDAO.getAvviso`, `checkDisponibilitaAvviso` | |
| Accesso per chiave tecnica | `bd.pagamento.VersamentiBD`: `getVersamento(id[,deep])`, `getVersamento(idApplicazione, codVersamentoEnte[,deep])`, `getVersamentoByDominioIuv`, `getVersamentoByBundlekey` | |
| Voci | `VersamentiBD.getSingoloVersamento`, `getSingoliVersamenti` | |
| Ricerche per batch | `findVersamentiConAvvisoDiPagamentoDaSpedire`, `findVersamentiConAvvisoDiScadenzaDaSpedireViaMail`, `…ViaAppIO`, `findVersamentiDiUnTracciato` (+ i rispettivi `count`) | |
| Criteri di ricerca | `bd.pagamento.filters.VersamentoFilter` (1.070 righe) e `bd.viste.filters.VersamentoFilter` (1.071 righe) | stati, debitore, domini, UO, id pendenza, intervallo date, applicazione, dominio, tracciato, CF cittadino, tipi pendenza, divisione, direzione, `idSessione`, iuv, documento, tipo, filtri scaduto/non scaduto/cittadino, `mostraSpontaneiNonPagati` |

> **Duplicazione:** i due `VersamentoFilter` differiscono per ~130 righe su ~1070
> (≈88% identico); `count` esiste in due varianti (`countSenzaLimitEngine`,
> `countConLimitEngine`) per gestire il limite sui risultati.

### D. Acquisizione e verifica presso l'Ente Creditore

| Funzione | Implementazione |
|---|---|
| Risoluzione pendenza da più chiavi con fallback all'EC | `business.Versamento.chiediVersamento:487` (per `codApplicazione`+`codVersamentoEnte`, per `codDominio`+`iuv`, per `bundlekey`) e `chiediVersamentoRifAvviso:473` |
| Verifica presso l'EC | `VersamentoUtils.acquisisciVersamento:351` → `VerificaClient.verificaPendenza` |
| Inoltro pendenza all'EC | `VersamentoUtils.inoltroPendenza:462` → `VerificaClient.inoltroPendenza` |
| Aggiornamento su scadenza/validità decorsa | `VersamentoUtils.aggiornaVersamento:256` |
| Contratto client | `core-beans/.../utils/client/IVerificaClient`, impl. `core/utils/client/VerificaClient` |
| Conversione risposta EC | `core/ec/v1/converter/VerificaConverter`, `core/ec/v2/converter/VerificaConverter`, `core/ec/v2/converter/PendenzeConverter`, `PendenzePagateConverter` |
| Validazione risposta EC | `PendenzaVerificataValidator` v1/v2, `VocePendenzaValidator` v1/v2 |
| Beans EC | `jars/client-api-ente` v1/v2 (`PendenzaVerificata`, `NuovaPendenza`, `VocePendenza`, `AllegatoPendenza`, `ProprietaPendenza`, `StatoPendenzaVerificata`, …) |
| Esiti / eccezioni | `VersamentoScadutoException`, `VersamentoAnnullatoException`, `VersamentoDuplicatoException`, `VersamentoSconosciutoException`, `VersamentoNonValidoException`, `VersamentoException` → mappate sui fault pagoPA (`PAA_PAGAMENTO_SCADUTO`, `_ANNULLATO`, `_DUPLICATO`, `_SCONOSCIUTO`) |
| Politica di fallback | `GovpayConfig.isAggiornamentoValiditaMandatorio()`: se `true` l'errore di verifica blocca, se `false` si usa la pendenza locale |
| Tracciamento | `EventoContext(Componente.API_ENTE)` + `EventiBD` per ogni interazione |

**Chi lo usa:** `api-pagopa/PagamentiTelematiciCCPImpl` (verifica e attivazione RPT),
`PendenzeDAO.leggiPendenzaByRiferimentoAvviso`, `PendenzeDAO.createOrUpdateCustom`.

### E. Aggiornamento

#### E.1 Aggiornamento parziale (PATCH) — `PendenzeDAO.patch:580`

Autorizza su dominio + tipo pendenza (`AuthorizationManager.isTipoVersamentoDominioAuthorized`),
poi applica in sequenza le operazioni JSON-Patch sui path ammessi:

| Path | Op | Effetto |
|---|---|---|
| `/stato` | `replace` | `patchStato:666` — solo `ANNULLATO` ⇄ `NON_ESEGUITO`, azzera/ripristina i flag di avvisatura |
| `/descrizioneStato` | `replace` | `patchDescrizioneStato:653` |
| `/ack` | `replace` | `patchAck:709` |
| `/nota` | `add` | scrive in `descrizioneStato` |

Aggiorna `dataUltimoAggiornamento` e rilegge la pendenza per la risposta.

#### E.2 Annullamento — `business.Versamento.annullaVersamento:373`

Autorizzazione (l'applicazione chiamante deve essere proprietaria), lock pessimistico
(`enableSelectForUpdate`), idempotente se già `ANNULLATO`, consentito solo da
`NON_ESEGUITO` (altrimenti `VER_009`), azzeramento dei flag di avvisatura,
diagnostici dedicati. Usato dai tracciati (`OperazioneFactory.annullaVersamento`,
operazione `DEL`) e dal PATCH.

#### E.3 Aggiornamenti puntuali su base dati — `bd.pagamento.VersamentiBD`

| Metodo | Chiamanti |
|---|---|
| `updateVersamento([deep])`, `_updateVersamento` | `business.Versamento`, `PendenzeDAO.patch` |
| `updateStatoVersamento`, `updateStatoVersamentoAnomalo` | `business.Incassi` |
| `updateStatoSingoloVersamento` | `RtUtils`, `CtReceiptUtils`, `CtReceiptV2Utils`, `business.Incassi` |
| `updateVersamentoInformazioniPagamento` | `RtUtils` (esito pagamento pagoPA) |
| `aggiornaIncassoVersamento` | `business.Incassi` |
| `updateVersamentoInformazioniAvvisatura` | `business.Incassi` |
| `updateStatoPromemoriaAvvisoVersamento`, `updateStatoPromemoriaScadenzaMailVersamento`, `updateStatoPromemoriaScadenzaAppIOVersamento` | `business.Promemoria`, `business.NotificaAppIo`, `Operazioni` |
| `updateVersamentoIuvNav` | assegnazione tardiva di IUV/NAV |
| `updateUltimaModificaAca` | `business.Incassi` (marcatura per sincronizzazione ACA) |

> L'aggiornamento della pendenza a seguito di **pagamento** non passa da
> `PendenzeDAO` né da `business.Versamento`: è dentro `RtUtils`,
> `CtReceiptUtils`, `CtReceiptV2Utils` (ricevute pagoPA) e `business.Incassi`
> (incassi/riconciliazione).

### F. Funzioni collaterali attivate dal ciclo di vita

| Funzione | Implementazione | Innesco |
|---|---|---|
| Generazione IUV e numero avviso | `business.Iuv.generaIUV`, `IuvUtils.toIuv`, `VersamentoUtils.getIuvFromNumeroAvviso`, `verifyNumeroAvviso` | caricamento |
| Conformità numero avviso a dominio/stazione | `VersamentoUtils.checkNumeroAvvisoConformeAConfigurazioneDominioEStazione:828` | inserimento |
| Bar code / QR code | `IuvUtils` | caricamento, lettura avviso |
| Stampa avviso PDF | `business.AvvisoPagamento.printAvvisoVersamento`, `cancellaAvviso`, `utils/stampe/AvvisoPagamentoUtils`, `AvvisoPagamentoV2Utils` | caricamento (`stampaAvviso`), tracciati |
| Promemoria avviso / scadenza (mail, AppIO) | `business.Versamento.inserisciPromemoriaAvviso:715`, `inserisciPromemoriaScadenzaMail:593`, `inserisciPromemoriaScadenzaAppIO:661`; `PromemoriaBD`, `NotificheAppIoBD` | inserimento + batch `gestionePromemoria` |
| Schedulazione batch promemoria | `Operazioni.setEseguiGestionePromemoria`, `Operazioni.gestionePromemoria:761`, `spedizionePromemoria:712`, `spedizioneNotificheAppIO:376` | inserimento pendenza |
| Marcatura per ACA/GPD | campi `dataUltimaModificaAca` / `dataUltimaComunicazioneAca`, `VersamentoUtils.comunicaAggiornamentoPendenzaAllArchivioCentralizzato:1259` | caricamento, incasso |
| Documento (raggruppamento) e allegati | `DocumentiBD`, `AllegatiBD`, `AllegatiDAO`, `bd.model.Documento`, `bd.model.Allegato`, `VersamentoUtils.toAllegatiModel:834` | caricamento |
| Multibeneficiario / MBT / IBAN postali | `VersamentoUtils.isPendenzaMultibeneficiario:1163`, `isPendenzaMBT:1238`, `isAllIBANPostali:1187`, `getDominioSingoloVersamento:1220` | caricamento, stampa, RPT |
| Soglie di pagamento | `VersamentoUtils.getTipoSogliaPagamento:1134`, `getGiorniSogliaPagamento:1145`, `isNumeroRata:1122` | conversione voci |
| Contesto diagnostico | `core/utils/VersamentoContext.java`, `GpContext.getPagamentoCtx().loadVersamentoContext`, `MessaggioDiagnosticoUtils` | tutti i flussi |

### G. Anagrafica tipo pendenza (adiacente, non nel perimetro stretto)

`TipoPendenzaDAO` (`createOrUpdateTipoPendenza`, `findTipiPendenza`,
`getTipoPendenza`), `TipiVersamentoBD`, `TipiVersamentoDominiBD` + cache JMX
(`TipiVersamentoBDCacheWrapper`, `TipiVersamentoDominiBDCacheWrapper`),
`model.TipoVersamento`, `model.TipoVersamentoDominio`, `TipoVersamentoFilter`,
`TipoVersamentoDominioFilter`.

Rilevante perché **`TipoVersamentoDominio` contiene la configurazione che pilota il
caricamento**: definizioni di validazione/trasformazione/inoltro per portale
backoffice e portale pagamenti, abilitazioni e preavvisi di avvisatura
(mail/AppIO), tipologia, trasformazioni.

## 5. Riepilogo: funzionalità → implementazione → utilizzatori

| # | Funzionalità | Implementata in | Usata da |
|---|---|---|---|
| 1 | Caricamento/aggiornamento pendenza (motore) | `core.business.Versamento.caricaVersamento` | `PendenzeDAO` (×2), `OperazioneFactory` (×2), `Incassi`, `Operazioni`, `Tracciati`, `AvvisiDAO` |
| 2 | Validazione semantica e di aggiornamento | `VersamentoUtils.validazioneSemantica*` | `business.Versamento` |
| 3 | Conversione DTO → modello | `VersamentoUtils.toVersamentoModel`, `toSingoloVersamentoModel`, `toAnagraficaModel`, `TracciatiConverter` | `business.Versamento`, `PendenzeDAO`, `OperazioneFactory` |
| 4 | Servizio PUT pendenza | `PendenzeDAO.createOrUpdate` | api-pendenze v1/v2, api-backoffice v1 |
| 5 | Servizio POST pendenza modello 4 | `PendenzeDAO.createOrUpdateCustom` | api-backoffice v1 (`addPendenzaPOST`) |
| 6 | Validazione input pendenza | `PendenzaPostValidator`, `ValidatoreUtils` | tracciati, modello 4 |
| 7 | Lettura pendenza | `PendenzeDAO.leggiPendenza`, `leggiPendenzaByRiferimentoAvviso` | api-pendenze v1/v2, api-backoffice v1, api-ragioneria v3 |
| 8 | Lista/conteggio pendenze | `PendenzeDAO.listaPendenze`, `countPendenze` + `bd.viste.VersamentiBD` | api-pendenze v1/v2, api-backoffice v1 |
| 9 | PATCH pendenza | `PendenzeDAO.patch` | api-pendenze v1/v2, api-backoffice v1 |
| 10 | Annullamento | `business.Versamento.annullaVersamento` | tracciati (`DEL`), PATCH |
| 11 | Verifica/acquisizione da EC | `VersamentoUtils.acquisisciVersamento`, `aggiornaVersamento`, `VerificaClient` | api-pagopa (`PagamentiTelematiciCCPImpl`), `PendenzeDAO` |
| 12 | Inoltro pendenza a EC | `VersamentoUtils.inoltroPendenza`, `inoltroInputVersamentoModello4` | `PendenzeDAO.createOrUpdateCustom` |
| 13 | Caricamento massivo tracciati | `business.Tracciati`, `OperazioneFactory`, `TracciatiPendenzeManager` | api-backoffice v1, batch `ElaborazioneTracciatiPendenze` |
| 14 | Aggiornamento da ricevuta pagoPA | `RtUtils`, `CtReceiptUtils`, `CtReceiptV2Utils` | flusso RT |
| 15 | Aggiornamento da incasso | `business.Incassi` | api-ragioneria, batch riconciliazioni |
| 16 | Avviso di pagamento (dati + PDF) | `AvvisiDAO`, `business.AvvisoPagamento`, `IuvUtils` | api-pendenze, api-backoffice, tracciati |
| 17 | Avvisatura e promemoria | `business.Versamento.inserisciPromemoria*`, `Promemoria`, `NotificaAppIo`, `Operazioni` | batch |
| 18 | Sincronizzazione ACA | campi ACA + `comunicaAggiornamentoPendenzaAllArchivioCentralizzato` | batch esterno (repo `govpay-aca-batch`) |
| 19 | Rappresentazione API | 7 × `PendenzeConverter` (~3.300 righe) + beans per versione | tutte le API |
| 20 | Configurazione tipo pendenza | `TipoPendenzaDAO`, `TipiVersamento*BD` | api-backoffice, caricamento modello 4 |

## 6. Utilizzatori per modulo

**API Pendenze v1** (`pendenze/v1/controller/PendenzeController`): GET pendenza,
GET lista, PATCH, PUT.

**API Pendenze v2** (`pendenze/v2/controller/PendenzeController`): GET pendenza,
GET per avviso, GET lista (con `direzione`, `divisione`, `mostraSpontaneiNonPagati`),
PATCH, PUT (con `dataAvvisatura`).

**API Backoffice v1** (`backoffice/v1/controllers/PendenzeController`, 1.152 righe):
GET per avviso, GET pendenza (con info incasso), find pendenze (filtri estesi: tipo
pendenza, iuv, direzione, divisione), update, add (2 overload), add modello 4
(`addPendenzaPOST`), + 7 operazioni sui tracciati.

**API Ragioneria v3** (`ragioneria/v3/api/impl/PendenzeApiServiceImpl`):
GET pendenza per avviso, GET allegato. v1/v2 espongono solo conversioni
(`PendenzaIndex`, `VocePendenza`) usate dalle riscossioni.

**API pagoPA** (`api-pagopa/…/PagamentiTelematiciCCPImpl`): verifica e attivazione,
con acquisizione/aggiornamento della pendenza dall'EC e mappatura degli esiti sui
fault pagoPA.

**Batch** (`core/utils/tasks/*` + `business.Operazioni`):
`elaborazioneTracciatiPendenze`, `gestionePromemoria`, `spedizionePromemoria`,
`spedizioneNotifiche`, `spedizioneNotificheAppIO`, `elaborazioneRiconciliazioni`,
`chiusuraRptScadute`, `acquisizioneRendicontazioni`.

**Sonde** (`backoffice/v1/controllers/SondeController`): accesso diretto a
`VersamentiBD` per il monitoraggio.

**Progetti esterni:** il batch di sincronizzazione ACA vive nel repository separato
`link-it/govpay-aca-batch` e lavora sui campi `dataUltimaModificaAca` /
`dataUltimaComunicazioneAca` scritti da questo codice — da verificare come contratto
verso la nuova libreria.

## 7. Criticità del codice attuale (input per il redesign)

1. **Modello accoppiato alla persistenza:** `bd.model.Versamento` riceve
   `BasicBD`/`BDConfigWrapper` nei getter per il lazy loading, in 3 varianti per
   relazione. Impedisce di usare il modello fuori dal contesto di una connessione.
2. **Transazioni gestite a mano:** `setupConnection`, `setAutoCommit(false)`,
   `setAtomica`, `commit`/`rollback`, `enableSelectForUpdate` sparsi nella logica di
   business, con doppio percorso "connessione mia / connessione del chiamante".
3. **Ingresso unico ma deprecato:** le 3 overload di `caricaVersamento` sono
   `@Deprecated` e ancora l'unica via di scrittura; la firma ha 8 parametri, 5 dei
   quali flag booleani/nullable (`generaIuv`, `aggiornaSeEsiste`, `avvisatura`,
   `salvataggioSuDB`, `controlloNumeroAvvisoDominioApplicazione`).
4. **Nessuna macchina a stati:** le transizioni di `StatoVersamento` sono replicate
   in almeno 5 punti (`annullaVersamento`, `patchStato`, `RtUtils`,
   `CtReceipt*Utils`, `Incassi`) con controlli non uniformi.
5. **Regola "campi non modificabili" implicita:** `copiaPropertiesNonModificabiliVersamento`
   elenca 20 campi in codice; nessuna dichiarazione unica di cosa sia aggiornabile.
6. **Duplicazione dei filtri:** due `VersamentoFilter` (tabella e vista) identici
   all'88%, ~2.100 righe complessive.
7. **Duplicazione dei converter:** 7 `PendenzeConverter` (~3.300 righe) per 6
   versioni di API + EC, con logica di mappatura ripetuta.
8. **Lettura su vista DB:** liste e conteggi passano da `VistaVersamento`, i dettagli
   dalla tabella: due modelli di lettura da riconciliare.
9. **Side-effect nel caricamento:** calcolo delle date di avvisatura, generazione
   IUV, marcatura ACA, invalidazione stampa avviso e risveglio dei batch sono
   dentro il metodo di scrittura.
10. **Concorrenza artigianale:** `TracciatiPendenzeManager` implementa lock e
    deduplica con `synchronized`/`wait`/`notifyAll` su strutture in memoria: non
    funziona in multi-nodo se non con l'attuale schema di lock a livello di tracciato.
11. **Contesto globale:** dipendenza pervasiva da `ContextThreadLocal`,
    `GpContext`, `MessaggioDiagnosticoUtils`, `EventoContext`.
12. **Aggiornamento post-pagamento fuori dal dominio pendenza:** la logica che porta
    la pendenza a `ESEGUITO`/`INCASSATO` sta in `RtUtils`/`CtReceipt*Utils`/`Incassi`,
    non nel componente che governa la pendenza.

## 8. Decisioni acquisite

| # | Decisione | Conseguenza sul disegno |
|---|---|---|
| D1 | **ACA:** è sufficiente valorizzare le colonne previste perché il batch ACA prenda in carico le pendenze nuove/modificate | Nessuna integrazione ACA nella libreria: serve solo un'operazione di scrittura su `data_ultima_modifica_aca` (e la regola che decide quando toccarla). Vedi §9.2 U6 e §11.2 |
| D2 | **Motore di caricamento:** va riportato e ammodernato | `caricaVersamento` diventa il servizio di riferimento, riscritto (firma, transazioni, side-effect); la semantica attuale (§4.A.1) è il contratto da preservare |
| D3 | **Update:** gestito da questa libreria, con **metodi precisi per tipo di update** | Niente metodo generico "salva": un'operazione per evento di dominio. Proposta in §11.2 |
| D4 | **Metodi ibridi/misti:** vanno riportati e **documentati**, perché saranno richiamati quando si migreranno le utility di gestione pendenza | Sono censiti uno per uno in §9.3, con responsabilità mescolate e contratto atteso |
| D5 | **Ricerche:** la libreria deve fornire i metodi di ricerca di **lista** e di **dettaglio** | Proposta in §11.3 e §11.4 |

## 9. Approfondimento: `bd.pagamento.VersamentiBD` (1.262 righe)

Classe unica di accesso in scrittura e lettura sulla tabella `versamenti` (+
`singoli_versamenti`). Estende `BasicBD`: ogni metodo apre/chiude la connessione se
`isAtomica()`, altrimenti riusa quella del chiamante.

### 9.1 Inventario completo

| # | Metodo | Tipo | Tabelle toccate |
|---|---|---|---|
| 1 | `getVersamento(id[, deep])` | lettura per chiave fisica | `versamenti` (+`singoli_versamenti` se `deep`) |
| 2 | `getVersamento(idApplicazione, codVersamentoEnte[, deep])` | lettura per chiave logica | idem |
| 3 | `getVersamentoByDominioIuv(idDominio, iuv[, deep])` | lettura per avviso | idem |
| 4 | `getVersamentoByBundlekey(idApplicazione, bundleKey, codDominio, codUnivocoDebitore)` | lettura per bundlekey | `versamenti` + join `uo`/`domini` |
| 5 | `getSingoloVersamento(id)` / `getSingoliVersamenti(idVersamento)` / `_getSingoliVersamenti` | lettura voci | `singoli_versamenti` |
| 6 | `insertVersamento` → `insertVersamentoEngine` | **scrittura mista** | `versamenti`, `singoli_versamenti`, `documenti`, `promemoria`, `notifiche_appio`, `allegati` |
| 7 | `updateVersamento(v)` → `_updateVersamento` | update completo | `versamenti` (+ `audit`) |
| 8 | `updateVersamento(v, deep)` | **update misto** | `versamenti`, `singoli_versamenti`, `documenti`, `allegati` |
| 9 | `updateStatoVersamento` ×2 + `updateStatoVersamentoAnomalo` → `updateStatoVersamentoEngine` | update puntuale | `versamenti` |
| 10 | `updateStatoPromemoriaAvvisoVersamento` | update puntuale | `versamenti` |
| 11 | `updateStatoPromemoriaScadenzaMailVersamento` | update puntuale | `versamenti` |
| 12 | `updateStatoPromemoriaScadenzaAppIOVersamento` | update puntuale | `versamenti` |
| 13 | `updateVersamentoInformazioniAvvisatura` | update puntuale (3 flag) | `versamenti` |
| 14 | `updateUltimaModificaAca` | update puntuale | `versamenti` |
| 15 | `updateVersamentoIuvNav` | update puntuale | `versamenti` |
| 16 | `updateStatoSingoloVersamento` | update puntuale su voce | `singoli_versamenti` |
| 17 | `updateVersamentoInformazioniPagamento` (16 parametri) | **update puntuale multi-concern** | `versamenti` |
| 18 | `aggiornaIncassoVersamento(Pagamento)` | **update con logica di business** | `versamenti` (legge `singoli_versamenti`, `pagamenti`) |
| 19 | `newFilter([simpleSearch])`, `count`, `countSenzaLimitEngine`, `countConLimitEngine`, `findAll` | ricerca | `versamenti` |
| 20 | `findVersamentiConAvvisoDiPagamentoDaSpedire` + `count…` | query di batch | `versamenti` |
| 21 | `findVersamentiConAvvisoDiScadenzaDaSpedireViaMail` + `count…` | query di batch | `versamenti` |
| 22 | `findVersamentiConAvvisoDiScadenzaDaSpedireViaAppIO` + `count…` | query di batch | `versamenti` |
| 23 | `findVersamentiDiUnTracciato(idTracciato, offset, limit)` | query di batch | `versamenti` + join `operazioni` |

### 9.2 Gli aggiornamenti puntuali — confermati e mappati sulle colonne

Sì: nel package `bd` gli aggiornamenti puntuali esistono e sono realizzati con
`updateFields(idVO, UpdateField[])` (UPDATE su singole colonne per chiave primaria,
senza rilettura né optimistic locking). Distribuzione nel package:

| Classe BD | n° `updateFields` |
|---|---|
| `pagamento/VersamentiBD` | **10** |
| `pagamento/TracciatiBD` | 4 |
| `pagamento/TracciatiNotificaPagamentiBD` | 3 |
| `pagamento/FrBD` | 3 |
| `pagamento/RptBD` | 2 |
| `pagamento/PromemoriaBD` | 2 |
| `pagamento/StampeBD`, `NotificheBD`, `NotificheAppIoBD`, `anagrafica/ApplicazioniBD` | 1 ciascuna |

Dettaglio dei 10 di `VersamentiBD` (`[…]` = colonna scritta solo se il flag
booleano corrispondente è `true`):

| ID | Metodo | Colonne scritte | `data_ora_ultimo_aggiornamento` | Chiamanti |
|---|---|---|---|---|
| U1 | `updateStatoVersamentoEngine` (via `updateStatoVersamento` ×2, `updateStatoVersamentoAnomalo`) | `stato_versamento`, `descrizione_stato`, `[anomalo]` | ✅ | `business.Incassi` |
| U2 | `updateStatoPromemoriaAvvisoVersamento` | `[avviso_notificato]` | ✅ | `business.Promemoria`, `Operazioni` |
| U3 | `updateStatoPromemoriaScadenzaMailVersamento` | `[avv_mail_prom_scad_notificato]` | ✅ | `business.Promemoria`, `Operazioni` |
| U4 | `updateStatoPromemoriaScadenzaAppIOVersamento` | `[avv_app_io_prom_scad_notificato]` | ✅ | `business.NotificaAppIo`, `Operazioni` |
| U5 | `updateVersamentoInformazioniAvvisatura` | `[avviso_notificato]`, `[avv_app_io_prom_scad_notificato]`, `[avv_mail_prom_scad_notificato]` | ✅ | `business.Incassi` |
| U6 | `updateUltimaModificaAca` | `data_ultima_modifica_aca` | ✅ | `business.Incassi` |
| U7 | `updateVersamentoIuvNav` | `[iuv_versamento` + `src_iuv]`, `[numero_avviso]` | ✅ | assegnazione tardiva IUV/NAV |
| U8 | `updateStatoSingoloVersamento` | `singoli_versamenti.stato_singolo_versamento` | ❌ | `RtUtils`, `CtReceiptUtils`, `CtReceiptV2Utils`, `Incassi` |
| U9 | `updateVersamentoInformazioniPagamento` | `data_pagamento`, `importo_pagato`, `importo_incassato`, `iuv_pagamento` + `src_iuv`, `stato_pagamento`, `[avviso_notificato]`, `[avv_app_io_prom_scad_notificato]`, `[avv_mail_prom_scad_notificato]`, `stato_versamento`, `descrizione_stato`, `[anomalo]` | ❌ | `RtUtils` |
| U10 | `aggiornaIncassoVersamento` | `importo_incassato`, `[stato_pagamento=INCASSATO]` | ❌ | `business.Incassi` |

**Anomalie rilevate, da non replicare:**

1. **`data_ora_ultimo_aggiornamento` non uniforme:** 7 update su 10 la scrivono, i 3
   della catena di pagamento/incasso (U8, U9, U10) no. Una pendenza pagata non
   risulta "aggiornata".
2. **Audit asimmetrico:** `emitAudit` (`BasicBD:822`, scrive su `audit` solo se
   `idOperatore` è valorizzato) è invocato **solo** da `_updateVersamento` (update
   completo). Nessuno dei 10 update puntuali produce audit.
3. **`src_iuv` incoerente:** U7 lo scrive con il valore grezzo
   (`VersamentiBD:744`), U9 con `iuvPagamento.toUpperCase()` (`:966`). È una
   colonna denormalizzata per la ricerca: la normalizzazione va decisa una volta.
4. **Parametro mal chiamato:** `updateStatoSingoloVersamento(long idVersamento, …)`
   riceve in realtà l'**id della voce** (`IdSingoloVersamento.setId(idVersamento)`,
   `:927`); i chiamanti passano correttamente `singoloVersamento.getId()`.
   Comportamento giusto, firma fuorviante.
5. **U9 con 16 parametri** di cui 8 in coppie `boolean updateX, Boolean x`: è il
   pattern "flag + valore" usato per distinguere "non toccare" da "imposta a null".
   Da sostituire con un tipo esplicito (`Optional`/oggetto di comando/patch tipizzata).
6. **Nessun controllo di stato:** gli update puntuali non verificano lo stato di
   partenza (a differenza di `annullaVersamento`, che filtra su `NON_ESEGUITO`). Le
   transizioni sono garantite solo dal chiamante.
7. **Nessun locking:** nessuno dei 10 usa `select for update` né versioning; il lock
   pessimistico è usato solo in `annullaVersamento` (business) e nell'upsert del
   documento.

### 9.3 Metodi ibridi/misti — inventario e contratto (D4)

Questi metodi mescolano responsabilità e verranno richiamati dalle utility migrate:
vanno riportati preservando il comportamento, con le responsabilità separate ma il
contratto complessivo invariato.

**H1 — `insertVersamentoEngine(Versamento, Promemoria, NotificaAppIo)` (`:255`)**
Responsabilità mescolate: (a) upsert del **documento** con gestione della corsa fra
thread — `exists` → `get` con `select for update` → `update`, oppure `create` con
fallback su conflitto (`:280-321`); (b) insert della pendenza; (c) insert delle voci
con assegnazione `idVersamento`; (d) insert opzionale del **promemoria mail**
(agganciato al documento se presente, altrimenti alla pendenza — `:340-348`);
(e) insert opzionale della **notifica AppIO**; (f) insert degli **allegati** via
`AllegatiBD`. Vincolo: `throw` se `autoCommit` è attivo (`:261`).
Nota: la variante pubblica `insertVersamento` passa sempre `null, null`, quindi
promemoria e notifica AppIO **non** vengono mai scritti da questo percorso — sono
creati altrove (`business.Versamento.inserisciPromemoria*`).

**H2 — `updateVersamento(Versamento, deep=true)` (`:399`)**
(a) Per ogni voce: `findTableId` per decidere `update` o `create` (upsert per
`idVersamento`+`indiceDati`, `:421-427`); (b) upsert del documento, identico a H1;
(c) **allegati: cancella tutti quelli esistenti e reinserisce quelli passati**
(`:495-516`) — perdita di identità e di eventuali metadati; (d) update della
pendenza (spostato in fondo "perche' posso sostituire il documento", `:518`).
Vincolo: `throw` se `autoCommit` è attivo.

**H3 — `aggiornaIncassoVersamento(Pagamento)` (`:995`)**
Logica di business dentro il layer di persistenza: risale da `Pagamento` a
`SingoloVersamento` a `Versamento`, somma `importoPagato` all'`importoIncassato`
corrente, poi **conta le voci incassate** iterando tutte le voci e tutti i loro
pagamenti con la regola: conta se (`ENTRATA` e stato `INCASSATO`) oppure tipo ≠
`ENTRATA` (MBT / altri intermediari) (`:1011-1024`); se tutte incassate imposta
`stato_pagamento=INCASSATO`. Da riportare come regola di dominio esplicita.

**H4 — `updateVersamentoInformazioniPagamento(…)` (`:942`)**
Un solo UPDATE che copre tre concern: esito pagamento (data, importi, iuv, stato
pagamento), stato pendenza (`stato_versamento`, `descrizione_stato`, `anomalo`) e
azzeramento avvisatura. È il punto in cui la pendenza diventa `ESEGUITO`.

**H5 — `_updateVersamento(Versamento)` (`:531`)**
Update completo + `emitAudit` (side-effect di audit dentro l'update).

**H6 — `countConLimitEngine(filter)` (`:792`)**
SQL nativo costruito a mano: sottoquery `SELECT id FROM versamenti WHERE … LIMIT
maxRisultati` avvolta da `SELECT count(distinct id)`, con parametri estratti dal
filtro. Serve a limitare il costo del conteggio sulle liste grandi
(`GovpayConfig.getMaxRisultati()`).

**H7 — `getVersamento…(…, deep)`**
La stessa firma restituisce l'aggregato con o senza voci: da sostituire con profili
di fetch espliciti.

**H8 — `bd.model.Versamento` getter con `BasicBD`/`BDConfigWrapper`**
Lazy loading nel modello (§3.3): `getSingoliVersamenti`, `getDocumento`,
`getAllegati`, `getRpt`, `getApplicazione`, `getDominio`, `getUo`,
`getTipoVersamento`, `getTipoVersamentoDominio`.

**H9 — `VersamentoUtils.toVersamentoModel` (`:626`)**
Conversione DTO→modello che dentro fa lookup di anagrafica, risoluzione di tributi e
IBAN, validazioni e logica su IUV/numero avviso.

**H10 — `business.Versamento.caricaVersamento` (`:97`)**
Il motore descritto in §4.A.1: validazione + insert/update + IUV + avvisatura +
ACA + risveglio batch + gestione transazione.

**H11 — `PendenzeDAO.createOrUpdate` / `createOrUpdateCustom`**
Servizio applicativo che include autorizzazione, generazione bar/QR code e
generazione o invalidazione del **PDF dell'avviso**.

### 9.4 Query di batch (criteri esatti)

| Metodo | Criteri | Ordinamento |
|---|---|---|
| `findVersamentiConAvvisoDiPagamentoDaSpedire` / `count…` | `avviso_notificato = false` AND `data_notifica_avviso IS NOT NULL` AND `data_notifica_avviso <= now` AND `stato_versamento = NON_ESEGUITO` | `data_creazione DESC` |
| `findVersamentiConAvvisoDiScadenzaDaSpedireViaMail` / `count…` | `avv_mail_prom_scad_notificato = false` AND `avv_mail_data_prom_scadenza IS NOT NULL` AND `<= now` AND `stato_versamento = NON_ESEGUITO` | `data_creazione DESC` |
| `findVersamentiConAvvisoDiScadenzaDaSpedireViaAppIO` / `count…` | `avv_app_io_prom_scad_notificato = false` AND `avv_app_io_data_prom_scadenza IS NOT NULL` AND `<= now` AND `stato_versamento = NON_ESEGUITO` | `data_creazione DESC` |
| `findVersamentiDiUnTracciato(idTracciato, …)` | join `operazioni`: `operazioni.stato = ESEGUITO_OK` AND `operazioni.id_tracciato = ?` | `numero_avviso ASC` |

Tutte accettano `offset`/`limit`. I tre `count…` sono usati anche dalle **sonde**
(`SondeController:234-236`).

## 10. Vista vs tabella nelle ricerche — verifica

Esistono due percorsi di ricerca paralleli:

| | `bd.pagamento.VersamentiBD` | `bd.viste.VersamentiBD` |
|---|---|---|
| Sorgente | tabella `versamenti` | **vista `v_versamenti`** |
| Servizio ORM | `VersamentoService` | `VistaVersamentoServiceSearch` (solo lettura) |
| Filtro | `bd.pagamento.filters.VersamentoFilter` (1.070 righe) | `bd.viste.filters.VersamentoFilter` (1.071 righe) |
| Metodi | `count`, `countConLimit`, `findAll` + tutte le letture per chiave + tutte le scritture | solo `newFilter`, `count`, `findAll` |

**Differenza reale fra vista e tabella:** la vista espone **60 campi** contro i 58
della tabella; gli unici due in più sono `COD_DOCUMENTO` e `DOC_DESCRIZIONE`, cioè i
dati del documento denormalizzati (evitano il join su `documenti` in lista).

**Differenza reale fra i due filtri:** nessuna, a livello semantico. Il diff
(~130 righe su 1.070, ≈88% identico) è **solo** la sostituzione di
`Versamento.model()`/`VersamentoFieldConverter` con
`VistaVersamento.model()`/`VistaVersamentoFieldConverter`. Stessi criteri, stessi
campi di ordinamento, stessa logica di autorizzazione. È duplicazione pura indotta
dai tipi generati.

**Chi usa cosa (verificato):**

| Chiamante | Sorgente | Operazione |
|---|---|---|
| `PendenzeDAO.listaPendenze` (`:215`) | **vista** | `count` (`:289`) + `findAll` (`:295`) |
| `PendenzeDAO.countPendenze` (`:119`) | **tabella** | `count` (`:194`) |
| `AvvisiDAO.checkDisponibilitaAvviso` (`:289`) | **tabella** | `count` per `codDominio`+`iuv`+`idSessione` |
| `bd.model.Documento.getVersamenti` (`:51`) | **tabella** | `findAll` per `idDocumento` |
| `SondeController` (`:234`) | **tabella** | i 3 `count` di avvisatura |
| API list (`api-pendenze` v1 `:215`, v2 `:248`, `api-backoffice` `:300`) | via DAO → **vista** | lista + metadati paginazione |
| `EventiController` (`:229`, `:349`) | via DAO → **tabella** | `countPendenze` usato come **check di autorizzazione** (se `totalResults == 0` → non autorizzato) |

> **Incoerenza:** la stessa ricerca logica è implementata su due sorgenti diverse.
> `listaPendenze` conta e lista sulla vista; `countPendenze` conta sulla tabella con
> l'altro filtro. Non è un ottimizzazione documentata: è il risultato
> dell'introduzione della vista solo sul percorso di lista.
>
> Campi di ordinamento ammessi (identici nei due filtri): `data_ora_ultimo_aggiornamento`,
> `data_creazione`, `data_scadenza`, `stato_versamento` (ASC/DESC).

## 11. Proposta di API per la nuova libreria

Nomenclatura proposta: `Pendenza` / `VocePendenza` sul modello di dominio, mapping
verso `versamenti` / `singoli_versamenti` (da confermare — vedi §12 Q1).

### 11.1 Caricamento (D2)

```
PendenzaCaricata carica(ComandoCaricamentoPendenza comando)
```
Un solo ingresso, con oggetto di comando tipizzato al posto degli 8 parametri
attuali: `pendenza`, `generaIuv`, `aggiornaSeEsiste`, `avvisatura` (tri-stato),
`dataAvvisatura`, `soloInMemoria`, `verificaNumeroAvvisoSuDominio`. Restituisce
l'esito con `created`/`updated` e gli identificativi assegnati (IUV, numero avviso).
Comportamento da preservare: validazione semantica, campi non modificabili,
riassegnazione `indiceDati`, controllo unicità `(dominio, iuv)`, calcolo date di
avvisatura, marcatura ACA.

### 11.2 Aggiornamenti precisi per tipo (D3)

| Operazione proposta | Sostituisce | Colonne / effetto |
|---|---|---|
| `aggiornaDatiPendenza(Pendenza, opzioni)` | `updateVersamento(v, deep)` (H2) | pendenza + voci (upsert) + documento + allegati |
| `annulla(idPendenza, motivo)` | `business.Versamento.annullaVersamento` | `stato_versamento=ANNULLATO`, `descrizione_stato`, azzera i 3 flag di avvisatura; solo da `NON_ESEGUITO`, idempotente se già annullata |
| `ripristina(idPendenza)` | `PendenzeDAO.patchStato` (ramo `NON_ESEGUITO`) | `stato_versamento=NON_ESEGUITO`, ricalcolo dei flag di avvisatura in base alle date presenti |
| `aggiornaDescrizioneStato(idPendenza, testo)` | `patchDescrizioneStato` | `descrizione_stato` |
| `aggiornaPresaInCarico(idPendenza, ack)` | `patchAck` | `ack` |
| `registraEsitoPagamento(idPendenza, EsitoPagamento)` | U9 / H4 | `data_pagamento`, `importo_pagato`, `importo_incassato`, `iuv_pagamento`, `src_iuv`, `stato_pagamento`, `stato_versamento`, `descrizione_stato`, `anomalo`, azzeramento avvisatura |
| `registraIncasso(idPendenza, Incasso)` | U10 / H3 | `importo_incassato` e, se tutte le voci risultano incassate, `stato_pagamento=INCASSATO` (regola da esplicitare) |
| `aggiornaStatoVoce(idVoce, stato)` | U8 | `singoli_versamenti.stato_singolo_versamento` (+ `data_ora_ultimo_aggiornamento` della pendenza, vedi Q3) |
| `marcaAvvisoNotificato(idPendenza, esito)` | U2 | `avviso_notificato` |
| `marcaPromemoriaScadenzaMailNotificato(idPendenza, esito)` | U3 | `avv_mail_prom_scad_notificato` |
| `marcaPromemoriaScadenzaAppIoNotificato(idPendenza, esito)` | U4 | `avv_app_io_prom_scad_notificato` |
| `azzeraAvvisatura(idPendenza, quali)` | U5 | i 3 flag, selettivamente |
| `assegnaIuvENumeroAvviso(idPendenza, iuv, numeroAvviso)` | U7 | `iuv_versamento`, `src_iuv`, `numero_avviso` |
| `marcaDaSincronizzareAca(idPendenza)` | U6 | `data_ultima_modifica_aca` — **unica integrazione ACA richiesta (D1)** |
| `aggiornaStato(idPendenza, stato, descrizione[, anomalo])` | U1 | `stato_versamento`, `descrizione_stato`, `anomalo` |

Regole trasversali da fissare una volta per tutte: aggiornamento di
`data_ora_ultimo_aggiornamento`, emissione dell'audit, verifica dello stato di
partenza (§12 Q3, Q4, Q5).

### 11.3 Ricerca di dettaglio (D5)

```
Optional<Pendenza> trovaPerId(long id, ProfiloFetch fetch)
Optional<Pendenza> trovaPerIdentificativo(String idA2A, String idPendenza, ProfiloFetch fetch)
Optional<Pendenza> trovaPerAvviso(String idDominio, String numeroAvviso, ProfiloFetch fetch)
Optional<Pendenza> trovaPerDominioIuv(long idDominio, String iuv, ProfiloFetch fetch)
Optional<Pendenza> trovaPerBundlekey(long idApplicazione, String bundlekey, String codDominio, String cfDebitore)
boolean esistePerDominioIuvOSessione(String codDominio, String iuv, String idSessione)
```

`ProfiloFetch` sostituisce il flag `deep` e il lazy loading di H8, dichiarando cosa
caricare: `SOLO_TESTATA`, `CON_VOCI`, `COMPLETO` (voci + documento + allegati + RPT +
pagamenti). Il dettaglio completo di oggi (`leggiPendenzaEngine`, `:337`) carica:
pendenza, dominio, tipo pendenza, tipo pendenza dominio, documento, applicazione,
UO, voci (ognuna con pagamento e riscossione), RPT ordinate per
`data_msg_richiesta ASC`, allegati.

### 11.4 Ricerca di lista (D5)

```
PaginaPendenze cerca(CriteriRicercaPendenze criteri, Paginazione paginazione)
long conta(CriteriRicercaPendenze criteri)
long contaConLimite(CriteriRicercaPendenze criteri, int limite)
```

`CriteriRicercaPendenze` unifica i due filtri attuali (nessuna differenza semantica,
§10): stati, CF debitore/cittadino, domini, unità operative, id pendenza,
applicazione, intervallo date, tracciato, tipi pendenza, divisione, direzione,
`idSessione`, IUV, documento, tipo (`DOVUTO`/`SPONTANEO`), flag scaduto/non scaduto,
`mostraSpontaneiNonPagati`, ricerca semplice su
(`cod_versamento_ente`, `iuv_versamento`, `debitore_identificativo`).
Ordinamenti: `data_ora_ultimo_aggiornamento`, `data_creazione`, `data_scadenza`,
`stato_versamento`.

Query dedicate ai batch (§9.4), da esporre come metodi espliciti:

```
PaginaPendenze conAvvisoDaSpedire(Paginazione p)          long contaConAvvisoDaSpedire()
PaginaPendenze conPromemoriaScadenzaMailDaSpedire(Paginazione p)   long conta…()
PaginaPendenze conPromemoriaScadenzaAppIoDaSpedire(Paginazione p)  long conta…()
PaginaPendenze diUnTracciato(long idTracciato, Paginazione p)
PaginaPendenze diUnDocumento(long idDocumento)
```

## 12. Punti da decidere (aggiornato)

Risolti da §8: perimetro ACA (D1), riporto del motore (D2), update nella libreria
con metodi precisi (D3), riporto e documentazione degli ibridi (D4), ricerche di
lista e dettaglio (D5).

**Perimetro**

- **Q1 — Terminologia:** `Pendenza`/`VocePendenza` nel dominio (mapping su
  `versamenti`/`singoli_versamenti`) o si resta su `Versamento`? Impatta ogni firma
  pubblica e la migrazione dei chiamanti.
- **Q2 — Confini dell'aggregato:** documento, allegati, promemoria e notifiche AppIO
  oggi vengono scritti dentro insert/update della pendenza (H1, H2). Restano
  nell'aggregato (un solo servizio transazionale) o diventano repository separati
  orchestrati dal caricamento? Nota: l'update `deep` oggi **cancella e reinserisce**
  gli allegati.

**Semantica di scrittura**

- **Q3 — `data_ora_ultimo_aggiornamento`:** la scriviamo su **tutte** le operazioni
  (uniformando U8, U9, U10) accettando la differenza di comportamento rispetto al
  legacy, o si replica l'asimmetria attuale?
- **Q4 — Audit:** lo estendiamo agli update puntuali o resta solo sull'update
  completo? E come si porta il concetto di operatore (oggi `idOperatore` dentro
  `BDConfigWrapper`) in un'API Spring?
- **Q5 — Guardie di stato:** ogni operazione verifica lo stato di partenza (es.
  `registraEsitoPagamento` solo da `NON_ESEGUITO`) o la responsabilità resta al
  chiamante come oggi? Se sì: macchina a stati esplicita con transizioni dichiarate?
- **Q6 — `src_iuv`:** colonna denormalizzata di ricerca scritta con case diverso da
  U7 e U9. Normalizzazione unica (upper) e chi la mantiene?
- **Q7 — Concorrenza:** manteniamo il lock pessimistico (`select for update`) su
  annullamento e upsert documento, o passiamo a optimistic locking (colonna di
  versione)? Il secondo richiede modifica dello schema.
- **Q8 — Transazioni:** `@Transactional` con propagazione, eliminando
  `setAtomica`/`setupConnection`/`commit` manuali. Come si serve il caso dei batch e
  dei tracciati, che oggi passano il proprio `BasicBD` per condividere la
  transazione su molte pendenze?

**Semantica di lettura**

- **Q9 — Vista o tabella:** unifichiamo la ricerca sulla **vista `v_versamenti`**
  (che aggiunge solo `cod_documento` e `doc_descrizione`) con un solo oggetto
  criteri, oppure teniamo i due percorsi? Se unifichiamo, `countPendenze`
  (`EventiController`) e `checkDisponibilitaAvviso` (`AvvisiDAO`) cambiano sorgente.
- **Q10 — Conteggio con limite:** manteniamo il meccanismo `maxRisultati` con SQL
  nativo (H6) o lo sostituiamo (es. `count` stimato / paginazione a cursore)?
- **Q11 — Profili di fetch:** `ProfiloFetch` come enum chiusa o composizione a la
  carte? Il dettaglio completo oggi tocca 8 relazioni.

**Compatibilità e migrazione**

- **Q12 — Schema DB:** la libreria resta compatibile con lo schema 3.10.x senza
  modifiche (obbligatorio se il batch ACA e gli altri progetti separati leggono le
  stesse tabelle) o è ammessa evoluzione?
- **Q13 — Persistenza:** JPA/Hibernate (allineandosi a `govpay-common`, che usa
  `spring-boot-starter-data-jpa`) o accesso SQL/JdbcClient per mantenere il
  controllo sulle query di lista e sui conteggi con limite?
- **Q14 — Adattatore legacy:** forniamo un adattatore che espone le firme di
  `VersamentiBD`/`PendenzeDAO` sopra la nuova API, per migrare i chiamanti in modo
  incrementale, o si migra tutto in un colpo?
- **Q15 — Ordine di migrazione:** dato che le utility di gestione pendenza verranno
  migrate dopo (D4), quali sono i primi chiamanti da spostare — API Pendenze v2,
  tracciati, o la catena RT/incassi?
