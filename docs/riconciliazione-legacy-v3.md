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
- **Fonte v3**: `govpay-api-pendenze-v3.yaml` (repo `govpay-381/claude/api-pendenze-v3`),
  verificato con `grep` mirato, non a memoria — dove non trovato è segnalato esplicitamente

## 1. Le 20 funzionalità legacy → dove vivono in v3

| # | Funzionalità (analisi §5) | In v3? | Dove / nota |
|---|---|---|---|
| 1 | Caricamento/aggiornamento pendenza (motore) | Sì, ridistribuito | `addPosizioneDebitoria` (creazione, sempre con ≥1 opzione) + `addOpzionePagamento` (nuova opzione su posizione esistente) + `updatePendenza`/`updatePosizioneDebitoria`. Cambia la forma: nessuna pendenza isolata, sempre dentro una posizione |
| 2 | Validazione semantica e di aggiornamento | Sì, da riprogettare | Sul nuovo aggregato (posizione + opzioni + pendenze + soggetti); regole come "somma voci = importo" restano valide come principio |
| 3 | Conversione DTO → modello | Sì | Da riscrivere per i bean v3, stesso principio (bean pivot prima di diventare entità) |
| 4-5 | Servizio PUT pendenza / POST modello 4 (DOVUTO backoffice vs SPONTANEO portale pagamenti, trasformazione/inoltro configurabili) | **Non trovato** | Nessuna distinzione DOVUTO/SPONTANEO né pipeline di trasformazione custom nello YAML v3. **Domanda aperta**: chi userà v3 per pendenze "spontanee" (generate al volo su richiesta di pagamento) lo farà con lo stesso `addPosizioneDebitoria`, o questo flusso resta fuori da v3? |
| 6 | Validazione input pendenza | Sì, da riscrivere | Importi/date restano; rata e soglie diventano regole di `OpzionePagamento` |
| 7 | Lettura pendenza | Sì | `getPendenza`, `getPosizioneDebitoria` |
| 8 | Lista/conteggio | Sì, con perdita nota | `findPendenze`, `findPosizioniDebitorie` — persa la ricerca ristretta rispetto a `/rpp` v2 (esito pagamento, range date RPT/RT), già segnalata come perdita deliberata nello studio v3 (punto B.5) |
| 9 | PATCH pendenza | Sì | `updatePendenza` |
| 10 | Annullamento | Sì, spostato di livello | Non si annulla più la pendenza: si annulla l'**opzione di pagamento** (`updateOpzionePagamento`, PATCH `/stato` → `ANNULLATA`), coerente con la macchina a stati |
| 11 | Verifica/acquisizione da Ente Creditore (fetch on-demand se non presente localmente) | **Non trovato** | Nessun endpoint di verifica dinamica verso un EC esterno. **Ipotesi da confermare**: v3 assume che sia l'EC stesso a scrivere la posizione (`POST /posizioni-debitorie`), quindi il fetch-on-demand (`chiediVersamento`, `VerificaClient`) non serve più. Se l'ipotesi è sbagliata, è un gap |
| 12 | Inoltro pendenza a EC ("modello 4") | **Non trovato** | Stesso discorso del punto 4-5 |
| 13 | Caricamento massivo (tracciati CSV/JSON) | **Non trovato** | Nessun endpoint bulk/tracciato nello YAML. **Domanda aperta concreta**: i grandi volumi (es. multe CdS, bollette) richiederanno N chiamate `POST /posizioni-debitorie`, o serve un canale batch equivalente non ancora specificato? |
| 14 | Aggiornamento da ricevuta pagoPA (RT) | Sì concettualmente | `OpzionePagamento` passa automaticamente ad ATTIVATA al pagamento (spec YAML). Il **meccanismo** (chi processa RPT/RT, dove) resta la domanda aperta E.3 di `studio-propedeutico-v3.md` |
| 15 | Aggiornamento da incasso/riconciliazione | Non modellato come operazione REST | Presumibilmente evento interno nello stesso componente del punto 14, non un'azione esposta all'EC |
| 16 | Avviso di pagamento (dati + PDF) | Sì, con gap noto | `getStampaPendenza` / schema `Avviso` — gap sulla stampa con più co-obbligati già in `studio-propedeutico-v3.md` §E.8 |
| 17 | Avvisatura e promemoria (mail/AppIO, giorni di preavviso per tipo pendenza) | **Parziale** | Trovato solo `notificaAppIO` (booleano). Nessuna traccia di date/giorni di preavviso configurabili separatamente per mail e AppIO come oggi (`TipoVersamentoDominio`). **Domanda aperta**: questa granularità si perde, o si sposta in una configurazione esterna non esposta da questa API? |
| 18 | Sincronizzazione ACA | **Non trovato**, atteso | Coerente con la vecchia decisione D1: l'integrazione ACA non è un concetto API, resta un dettaglio di persistenza interna (colonna tipo `data_ultima_modifica_aca`) da preservare nel nuovo schema se il batch ACA esterno continua a esistere per v3 — **da confermare che `govpay-aca-batch` debba coprire anche le posizioni create via v3** |
| 19 | Rappresentazione API (converter DTO↔bean) | Sì | Da riscrivere per i bean v3, stesso principio |
| 20 | Configurazione tipo pendenza | Sì | `findTipiPendenza`/`getTipoPendenza` |

**Sintesi**: 12 funzionalità su 20 hanno un equivalente diretto o quasi in v3 (con
adattamento di forma). **5 non hanno alcun riscontro nello YAML** (righe 4-5, 11,
12, 13) e vanno chiarite prima di considerare il disegno completo — non è detto
siano tutte "perse": alcune potrebbero essere deliberatamente fuori scope perché
il modello v3 cambia chi crea la pendenza (l'EC direttamente, non più un
fetch/inoltro). Altre due (17, forse 18) sono parziali/da confermare.

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
| `versamenti.debitore_*` | `soggettiDebitori[0]` sulla `PosizioneDebitoria` | Assume coerenza tra i versamenti dello stesso documento — **rischio già noto** (sezione F di `studio-propedeutico-v3.md`): se incoerente, serve bonifica prima o durante la migrazione, non dopo |
| `versamenti.cod_rata` numerico | `OpzionePagamento` RATEALE, pendenze raggruppate per numero di rata | |
| `versamenti.cod_rata` = `ENTRO<gg>`/`OLTRE<gg>` | `OpzionePagamento` SOLUZIONE_UNICA_ENTRO/OLTRE | |
| `versamenti.cod_rata` = `RIDOTTO`/`SCONTATO` | **Nessun equivalente v3** | Bloccante per la migrazione dei dati storici (es. multe CdS) finché non si decide (domanda E.6 di `studio-propedeutico-v3.md`) |
| `versamenti.cod_rata` = `null` | `OpzionePagamento` SOLUZIONE_UNICA semplice | |

## 4. Domande da chiudere prima di disegnare le entità

Raccolte qui perché condizionano direttamente lo schema, non solo l'implementazione:

1. **Flussi 4-5-11-12-13** (§1): fuori scope v3 per decisione esplicita, o gap da
   colmare? In particolare il caricamento massivo (13) è una perdita di
   funzionalità concreta per chi oggi carica migliaia di pendenze via tracciato.
2. **Avvisatura granulare** (17): dove vive la configurazione di giorni di
   preavviso mail/AppIO per tipo pendenza, se non nell'API v3?
3. **RIDOTTO/SCONTATO** (già E.6): blocca la migrazione dei dati storici che li
   usano.
4. **Bonifica debitori incoerenti** tra versamenti dello stesso documento:
   prerequisito separato prima della migrazione, o gestita dentro lo stesso
   script di migrazione (es. con una regola esplicita tipo "vince il debitore più
   frequente" o "segnala e blocca")?
5. **ACA** (18): conferma che il batch esterno `govpay-aca-batch` debba coprire
   anche le posizioni create via v3, e quali colonne del nuovo schema gli servono.
