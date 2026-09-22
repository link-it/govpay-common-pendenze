# Riconciliazione: funzionalità legacy → modello nativo v3, e migrazione dati

Punto di partenza del disegno del modello nativo (issue #8), dopo l'abbandono del
porting retrocompatibile (PR #9). Obiettivo: verificare, funzionalità per
funzionalità, cosa dell'esperienza raccolta in
[analisi-legacy-pendenze.md](analisi-legacy-pendenze.md) e
[proposta-libreria-pendenze.md](proposta-libreria-pendenze.md) (entrambi
archiviati ma non superati come *conoscenza*) va portato nel modello v3 — non come
codice, ma come funzionalità da non perdere — e cosa la migrazione dati una tantum
deve preservare.

- **Fonte legacy**: `analisi-legacy-pendenze.md` §5 (20 funzionalità censite)
- **Fonte v3**: `govpay-api-pendenze-v3.yaml`,
  verificato con `grep` mirato, non a memoria — dove non trovato è segnalato esplicitamente

## 1. Le 20 funzionalità legacy → dove vivono in v3

| # | Funzionalità (analisi §5) | In v3? | Dove / nota |
|---|---|---|---|
| 1 | Caricamento/aggiornamento pendenza (motore) | Sì, ridistribuito | `addPosizioneDebitoria` (creazione, sempre con ≥1 opzione) + `addOpzionePagamento` (nuova opzione su posizione esistente) + `updatePendenza`/`updatePosizioneDebitoria`. Cambia la forma: nessuna pendenza isolata, sempre dentro una posizione |
| 2 | Validazione semantica e di aggiornamento | Sì, da riprogettare | Sul nuovo aggregato (posizione + opzioni + pendenze + soggetti); regole come "somma voci = importo" restano valide come principio |
| 3 | Conversione DTO → modello | Sì | Da riscrivere per i bean v3, stesso principio (bean pivot prima di diventare entità) |
| 4-5 | Servizio PUT pendenza / POST modello 4 (DOVUTO backoffice vs SPONTANEO portale pagamenti, trasformazione/inoltro configurabili) | **Deciso (parziale) dal lead, 2026-09-22** | Per ora `api-pendenze` v3 carica **solo pendenze DOVUTO**. Il flusso SPONTANEO e le funzioni di caricamento con trasformazione ("modello 4") restano **in pausa**: non decisi, non implementati — punto di discussione futura, vedi §4 |
| 6 | Validazione input pendenza | Sì, da riscrivere | Importi/date restano; rata e soglie diventano regole di `OpzionePagamento` |
| 7 | Lettura pendenza | Sì | `getPendenza`, `getPosizioneDebitoria` |
| 8 | Lista/conteggio | Sì, con perdita nota | `findPendenze`, `findPosizioniDebitorie` — persa la ricerca ristretta rispetto a `/rpp` v2 (esito pagamento, range date RPT/RT), già segnalata come perdita deliberata nello studio v3 (punto B.5) |
| 9 | PATCH pendenza | Sì | `updatePendenza` |
| 10 | Annullamento | Sì, spostato di livello | Non si annulla più la pendenza: si annulla l'**opzione di pagamento** (`updateOpzionePagamento`, PATCH `/stato` → `ANNULLATA`), coerente con la macchina a stati |
| 11 | Verifica/acquisizione da Ente Creditore (fetch on-demand se non presente localmente) | **In pausa, come 4-5** | Coerente col caricamento solo-DOVUTO: senza flusso SPONTANEO non serve fetch-on-demand da un EC esterno per ora. Punto di discussione futura se/quando SPONTANEO verrà ripreso |
| 12 | Inoltro pendenza a EC ("modello 4") | **In pausa, come 4-5** | Stesso discorso: funzioni di caricamento con trasformazione, non decise, messe in pausa dal lead |
| 13 | Caricamento massivo (tracciati CSV/JSON) | **Non in questa API, per scelta** | Confermato dal lead (2026-09-21): il caricamento del tracciato è già stato portato in `govpay-console-api`; l'elaborazione effettiva sarà a carico di un nuovo `govpay-tracciati-batch`, presumibilmente consumatore di `govpay-common-pendenze`/`api-pendenze` per creare le posizioni. Non è un gap di questa libreria: vive deliberatamente altrove |
| 14 | Aggiornamento da ricevuta pagoPA (RT) | Sì concettualmente | `OpzionePagamento` passa automaticamente ad ATTIVATA al pagamento (spec YAML). Il **meccanismo** (chi processa RPT/RT, dove) resta la domanda aperta E.3 di `studio-propedeutico-v3.md` |
| 15 | Aggiornamento da incasso/riconciliazione | Non modellato come operazione REST | Presumibilmente evento interno nello stesso componente del punto 14, non un'azione esposta all'EC |
| 16 | Avviso di pagamento (dati + PDF) | Sì, con gap noto | `getStampaPendenza` / schema `Avviso` — gap sulla stampa con più co-obbligati (nessun campo debitore nello schema, nessun concetto di "richiedente"), non solo di rappresentazione ma di **design non ancora chiuso** — vedi `studio-propedeutico-v3.md` §E.7 |
| 17 | Avvisatura e promemoria (mail/AppIO, giorni di preavviso per tipo pendenza) | **Parziale** | Trovato solo `notificaAppIO` (booleano). Nessuna traccia di date/giorni di preavviso configurabili separatamente per mail e AppIO come oggi (`TipoVersamentoDominio`). **Domanda aperta**: questa granularità si perde, o si sposta in una configurazione esterna non esposta da questa API? |
| 18 | Sincronizzazione ACA (Archivio Centralizzato **Avvisi**) | **Continua, tramite questa libreria — granularità chiarita da fonte ufficiale pagoPA** | Confermato dal lead (2026-09-22): `govpay-common-pendenze` sarà usata da chiunque debba accedere ai dati delle pendenze, incluso `govpay-aca-batch`. Verificato lo spec ufficiale pagoPA `pagopa/pagopa-api` (`openapi/gpd-4-aca.json`, "GPD for ACA — Debt Positions service API for ACA"): il contratto è a **due livelli**, `PaymentPositionModel` (posizione, identificata da `iupd`, **un solo debitore embedded**, `status` aggregato) + `paymentOption[]` (`PaymentOptionModel`, ciascuno con proprio `nav`, marcabile pagato singolarmente via `POST /paymentoptions/paids/{nav}`). Mappa 1:1 su `PosizioneDebitoria`(`iupd`)/`OpzionePagamento`-`Pendenza`(`nav`): la marcatura/sincronizzazione ACA vive quindi su **entrambi i livelli**, non uno solo. **Nota di migrazione dal lead**: oggi `iupd` è valorizzato con una chiave a grana di **singola pendenza** (per poter risalire a quale pendenza in caso di modifica/annullamento) — va ribaltato: `iupd` deve identificare la `PosizioneDebitoria`, le singole pendenze si indirizzano col `nav` di ciascuna opzione, coerente con lo standard. **Bonus**: questo contratto conferma anche che il vincolo "un solo debitore" (punto 1 sopra) non è specifico del Nodo dei Pagamenti/RPT — è replicato identico qui, quindi è un vincolo di piattaforma pagoPA, non solo GovPay |
| 19 | Rappresentazione API (converter DTO↔bean) | Sì | Da riscrivere per i bean v3, stesso principio |
| 20 | Configurazione tipo pendenza | Sì | `findTipiPendenza`/`getTipoPendenza` |

**Sintesi**: 14 funzionalità su 20 hanno un equivalente diretto o quasi in v3, o
sono confermate come deliberatamente fuori scope/rimandate (13, portata in
`govpay-console-api`; 4-5/11/12, caricamento solo-DOVUTO per ora, SPONTANEO e
"modello 4" in pausa). Restano 2 parziali/da confermare (17, 18).

## 2. Principi di design da non perdere (dalle decisioni D1-D12/A1-A6/B1-B6)

Il modello concreto cambia, ma buona parte delle decisioni sul *come si scrive/legge*
restano valide indipendentemente dalla forma delle tabelle:

- **D10** — `data_ora_ultimo_aggiornamento` (o equivalente) e audit su **ogni**
  scrittura, senza eccezioni: si applica identica al nuovo aggregato
  `PosizioneDebitoria`/`OpzionePagamento`/`Pendenza`.
- **D11** — `@Transactional` con propagazione, niente gestione manuale di
  connessione/transazione.
- **D3 / §11.2** — un metodo esplicito per evento di dominio (mai un "salva"
  generico): si applica perfettamente alla macchina a stati di `OpzionePagamento`
  (`attiva`, `annulla`, mai un update generico dello stato).
- **A5** — eventi Spring (`ApplicationEventPublisher`) invece di dirty flag
  statici in memoria, per disaccoppiare i consumatori (batch, notifiche) dal
  motore di scrittura.
- **§4.2** — `BigDecimal` con `AttributeConverter` dedicato per gli importi: il
  problema del tipo `DOUBLE PRECISION` (3 DB su 4) esiste comunque per le nuove
  tabelle, se non si decide di cambiare tipo colonna in migrazione.
- **§4.8** — niente relazioni JPA dirette verso l'anagrafica di `govpay-common`
  (accoppiamento del grafo entità): vale anche per le nuove tabelle.
- **§7.4** — disegnare gli indici **prima** di scegliere ordinamenti/criteri di
  ricerca di default, non dopo (il vecchio disegno lo aveva scoperto a posteriori
  con `data_ora_ultimo_aggiornamento` vs `data_creazione`).

## 3. Migrazione dati: schema di massima (una tantum)

| Da (schema attuale) | A (schema v3) | Note |
|---|---|---|
| `documenti` | `PosizioneDebitoria` | 1:1 diretto |
| `versamenti` (con `id_documento`) | `Pendenza`, raggruppate sotto la `PosizioneDebitoria` corrispondente | |
| `versamenti` (senza `id_documento`) | `PosizioneDebitoria` con una sola `Pendenza` | Ogni versamento "orfano" diventa una posizione a sé |
| `singoli_versamenti` | `VocePendenza` | 1:1 diretto |
| `versamenti.debitore_*` | `soggettiDebitori[0]` sulla `PosizioneDebitoria` | Coerenza tra i versamenti dello stesso documento **attesa per prassi**, ma la policy da applicare se lo script la trova violata (eccezione + log per revisione manuale? altro?) **resta da decidere** — §4 punto 4 |
| `versamenti.cod_rata` numerico | `OpzionePagamento` RATEALE, pendenze raggruppate per numero di rata | |
| `versamenti.cod_rata` = `ENTRO<gg>`/`OLTRE<gg>` | `OpzionePagamento` SOLUZIONE_UNICA_ENTRO/OLTRE | |
| `versamenti.cod_rata` = `RIDOTTO`/`SCONTATO` | **Probabile equivalente**: coppia `OpzionePagamento` SOLUZIONE_UNICA_ENTRO/OLTRE | Verificato in `AvvisoPagamentoUtils.java` (righe 304-337, 480-504): sono sempre usati **in coppia** per il caso violazione CdS (due `versamenti` sotto lo stesso documento, stampati su un unico avviso con due importi/QR). Lo YAML v3 ha lo stesso identico caso d'uso come esempio (`SOLUZIONE_UNICA_ENTRO`, sanzione CdS ridotta del 30% entro 5 giorni). **Manca però il dato**: `cod_rata` per RIDOTTO/SCONTATO non porta i giorni (a differenza di `ENTRO<gg>`/`OLTRE<gg>`) — il termine è fisso per legge, va recuperato da dove vive oggi (`LabelAvvisiProperties` o costante), non dalla colonna, per popolare `giorni` in migrazione |
| `versamenti.cod_rata` = `null` | `OpzionePagamento` SOLUZIONE_UNICA semplice | |
| `numero_avviso` (già oggi = NAV) | `nav` di ciascuna `Pendenza`/`OpzionePagamento` verso ACA/GPD | Nessun cambiamento, il concetto esiste già identico |
| chiave usata oggi per `iupd` verso ACA | `iupd` = identificativo della `PosizioneDebitoria` | **Da ribaltare** (nota del lead, punto 5 sez. 4): oggi `iupd` è valorizzato a grana di singola pendenza per tracciabilità; va spostato a identificare la posizione, coerente con lo standard pagoPA `gpd-4-aca.json` |

## 4. Domande da chiudere prima di disegnare le entità

Raccolte qui perché condizionano direttamente lo schema, non solo l'implementazione:

1. **Flussi 4-5-11-12** (§1): **deciso (parziale), non chiuso**. Per ora
   `api-pendenze` v3 carica solo pendenze DOVUTO. SPONTANEO, verifica/fetch
   on-demand da Ente Creditore e "modello 4" (caricamento con trasformazione)
   restano **messi in pausa dal lead**, da riprendere come punto di discussione
   futura quando si deciderà se/come reintrodurli. Non bloccante per il disegno
   attuale del modello nativo, che può assumere solo-DOVUTO.
   (Il caricamento massivo, punto 13, è **risolto**: già portato in
   `govpay-console-api`, con l'elaborazione a carico del nuovo
   `govpay-tracciati-batch`; non riguarda questa libreria.)
2. **Avvisatura granulare** (17): dove vive la configurazione di giorni di
   preavviso mail/AppIO per tipo pendenza, se non nell'API v3? Verificato che lo
   YAML non ha alcun campo `giorni`/`preavviso` legato alla notifica (tutte le
   occorrenze di `giorni` trovate riguardano le soglie di `OpzionePagamento`).
3. **RIDOTTO/SCONTATO** (già E.6): **probabile mapping trovato** (coppia
   SOLUZIONE_UNICA_ENTRO/OLTRE, vedi §3) — resta da recuperare il numero di
   giorni (fisso per legge, non nella colonna `cod_rata`) da dove vive oggi nel
   codice di stampa, per popolarlo in migrazione.
4. **Debitori incoerenti tra versamenti dello stesso documento — ancora aperto,
   ma ristretto (2026-09-22).** L'assunzione è confermata dal lead: per prassi
   un `documento` raggruppa sempre pendenze dello stesso debitore, quindi
   **non serve una regola di business per risolvere l'incoerenza** (niente
   "vince il più frequente" o simili — non è un caso da interpretare, perché
   non dovrebbe esistere). Ma proprio perché non è previsto, se lo script di
   migrazione lo trova comunque (bug, dato corrotto) **va gestito
   esplicitamente**, e la policy non è ancora decisa: sollevare un'eccezione e
   loggare il caso a parte per una decisione manuale successiva? Bloccare solo
   quel documento e proseguire con gli altri? Altro? Da decidere prima di
   scrivere lo script, non lasciato improvvisato.
5. **Quale soggetto usare quando serve un solo valore, tra co-obbligati alla pari**
   (16, studio-propedeutico-v3.md §E.7) — problema di design **non chiuso**,
   non solo di rappresentazione: `soggettiDebitori[0]` per l'RPT è un workaround
   tecnico imposto dal Nodo dei Pagamenti (un solo soggetto pagatore per avviso),
   non una scelta di dominio — i co-obbligati sono "in solido", nessuna gerarchia
   reale tra loro. Si ripresenta identico per la stampa dell'avviso
   (`getStampaPendenza`): stampare solo il primo? tutti? il "richiedente" — concetto
   oggi non definito in v3 (nessun parametro per identificare chi chiede la
   stampa)? Non blocca il modello dati (`soggettiDebitori` resta una collezione
   paritaria, nessun campo "principale"), ma va deciso a livello di logica
   applicativa/protocollo prima di implementare RPT e stampa.
5. **ACA — Archivio Centralizzato Avvisi** (18): **chiarito da fonte ufficiale**,
   non più aperto sulla granularità. `govpay-aca-batch` userà questa libreria;
   verificato lo spec pagoPA `gpd-4-aca.json` ("GPD for ACA"): due livelli,
   `iupd` (posizione, un solo debitore embedded, status aggregato) + `nav` per
   ciascuna opzione di pagamento (marcabile pagata singolarmente). Mappa
   direttamente su `PosizioneDebitoria`(`iupd`)/`Pendenza`(`nav`) — la
   sincronizzazione va quindi su **entrambi i livelli**. Resta solo un lavoro di
   migrazione: oggi `iupd` è usato a grana di singola pendenza (per
   tracciabilità di modifiche/annullamenti), va ribaltato perché identifichi la
   posizione, con `nav` a indirizzare le singole pendenze (nota del lead).
