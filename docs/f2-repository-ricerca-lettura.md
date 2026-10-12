> **ARCHIVIATO** — Disegno mai implementato, superato dalla decisione di andare
> direttamente al modello nativo v3 (multi-debitore, `OpzionePagamento`), niente
> porting retrocompatibile del modello attuale. Vedi
> [issue #8](https://github.com/link-it/govpay-common-pendenze/issues/8).

# F2 — Repository, ricerca e lettura: disegno di dettaglio

Riferimenti: [proposta-libreria-pendenze.md](proposta-libreria-pendenze.md) §2, §3.2, §5, §7,
§10 — [f1-modello-e-entita.md](f1-modello-e-entita.md) — [analisi-legacy-pendenze.md](analisi-legacy-pendenze.md)
§C, §9.4, §10, §11.3, §11.4. Issue: [#5](https://github.com/link-it/govpay-common-pendenze/issues/5).

## 1. Obiettivo e Definition of Done

F1 ha dato entità e tipi di dominio. F2 li rende **interrogabili**: repository, criteri di
ricerca unificati, dettaglio per chiave e lista paginata. Nessuna scrittura.

È fatta quando:

- le sei letture di dettaglio di §7.1 della proposta esistono, ognuna appoggiata a un
  indice esistente;
- esiste un unico oggetto di criteri che copre tutto ciò che i due `VersamentoFilter` del
  legacy sanno esprimere (§7 di questo documento), senza join verso l'anagrafica;
- la lista è paginabile per offset e per cursore keyset, con lo stesso ordinamento di
  default del legacy;
- la visibilità è un predicato che entra nella query, non un filtro dopo il fetch;
- un consumatore con package base diverso da `it.govpay.pendenze` vede entità e repository
  senza configurazione aggiuntiva;
- `mvn clean install` verde, nessuna modifica allo schema del database.

### Decisioni di F2

| ID | Decisione | Perché |
|----|-----------|--------|
| F2-1 | Sorgente unica: la tabella `versamenti`. La vista `v_versamenti` non si usa | è un solo LEFT JOIN su `documenti` (proposta §2); in JPA è il `@ManyToOne documento` che l'entità ha già |
| F2-2 | I criteri e le firme prendono identificatori tecnici (`Long`), mai codici | senza relazioni JPA verso l'anagrafica (F1-6) non c'è join su cui filtrare per `cod_dominio`. Chi parte da un codice lo risolve a monte, con la query che gli serve comunque per autorizzare |
| F2-3 | L'ordinamento è un enum, non una stringa: il parsing di `?sort=` resta ai consumatori | è l'unico modo di non scegliere fra il nome pubblico di V1 (`dataCaricamento`) e quello della console (`dataCreazione`). La libreria non è uno strato HTTP |
| F2-4 | Default `data_creazione DESC`; cursore keyset su `(data_creazione DESC, id DESC)`; **la modalità cursore è disponibile solo con l'ordinamento di default** | `data_creazione` è immutabile e ha `idx_vrs_data_creaz`. Un cursore su una colonna che muta salta o duplica righe. La console oggi ignora in silenzio il `sort` quando arriva un cursore: qui la combinazione è un errore esplicito |
| F2-5 | `ProfiloFetch` su due soli assi: voci e documento | il grafo `summary` della console è fatto **solo** di anagrafica (`dominio`, `applicazione`, `tipoVersamento`, `unitaOperativa`, `tipoVersamentoDominio`): con F1-6 sparisce, e la lista diventa una query senza alcun join |
| F2-6 | La visibilità è una SPI (`VisibilitaPendenze`) realizzata già in F2, non rinviata | il predicato deve entrare nella `WHERE` della ricerca paginata: applicato dopo il fetch romperebbe dimensione di pagina e conteggi |
| F2-7 | La ricerca per stato accetta `StatoPendenzaApplicativo`, con la derivazione di F1 come sorgente unica | altrimenti il filtro e la proiezione divergono in silenzio: una riga mostrata `SCADUTA` sarebbe irraggiungibile da `stato=SCADUTA` |
| F2-8 | Ricerca per IUV su `src_iuv`, lookup per avviso su `iuv_versamento` | sono due cose diverse e il legacy le tiene distinte: `src_iuv` è la colonna di ricerca (uppercase, `idx_vrs_iuv`), `iuv_versamento` è la chiave con il dominio (`idx_vrs_iuv_dominio`) |
| F2-9 | Niente `PendenzaSortParser`, `CursorCodec` sì | il parser è HTTP (whitelist di query param, 400); il codec è pura codifica di un keyset, e serve identico a chiunque pagini |

## 2. Perimetro

**Dentro:** `repository`, `ricerca`, `lettura`, la SPI di visibilità, l'estensione
dell'autoconfigurazione.

**Fuori:** aggiornamento (F3), caricamento (F4), query di batch e adattatore legacy (F5).
Fuori anche le query per tracciato: `versamenti` non ha `id_tracciato`, la relazione è
inversa (`operazioni.id_versamento`) e `operazioni` non è un'entità di questa libreria —
è materia di F5.

Non si riportano da `govpay-console-api`: `PendenzaController`, `PendenzaLinksBuilder`,
`PendenzaExpandConverter`, `PendenzaMapper` verso i bean OpenAPI, `PendenzaSortParser`
(F2-9).

## 3. Cosa si porta davvero da `govpay-console-api`

La tabella §3.2 della proposta è stata scritta a luglio e su due righe **non è più
allineata** al codice della console. Verificato sul sorgente attuale:

| Voce della proposta | Stato reale | Conseguenza |
|---|---|---|
| «`PendenzaSortParser`: cambiare il default in `-dataCaricamento`» | la console ha già `DEFAULT_SORT_RAW = "-dataCreazione"`, cioè `data_creazione DESC` | il default non va cambiato: diverge solo il **nome pubblico** del campo, e con F2-3 la questione esce dal perimetro della libreria |
| «`CursorCodec`: cambia il significato del timestamp nel cursore» | `PendenzaService.findByCursor` ordina già per `(dataCreazione DESC, id DESC)` e il keyset è su `dataCreazione` | il codec si porta as-is; è **stantio il suo Javadoc**, che parla ancora di `dataOraUltimoAggiornamento` |

Il resto della tabella tiene. Il porting effettivo:

| Da | Adattamento |
|---|---|
| `repository/VersamentoRepository` | l'`@EntityGraph` su `findAll` sparisce (F2-5); `findDetail` diventa una famiglia di lookup per chiave (§5.1), su `id_applicazione` invece che su `applicazione.codApplicazione` |
| `pendenza/PendenzaSpecifications` | 13 predicati; **quattro vanno riscritti** perché oggi passano da un join sull'anagrafica (§7.1), gli altri si portano cambiando il tipo dell'entità |
| `common/VersamentoPredicates` | assorbito nelle specifications: esisteva per condividere i predicati con `/ricevute`, che qui non c'è |
| `pagination/CursorCodec` + `BadCursorException` | as-is, Javadoc corretto |
| `PendenzaService.findSlice` / `findByCursor` | diventano il repository custom di §5.3; il `fetchgraph` sul `summary` sparisce, resta quello del profilo richiesto |
| `PendenzaService.summaryEntityGraph` | **non si porta**: è interamente anagrafica |
| `security/VersamentoVisibilita` | diventa la SPI di §11; il predicato si semplifica (niente join: gli `id` sono colonne) |

## 4. File da creare

```
it.govpay.pendenze
├── repository/
│   ├── VersamentoRepository            JpaRepository + JpaSpecificationExecutor + lookup
│   ├── SingoloVersamentoRepository
│   ├── DocumentoRepository
│   ├── VersamentoQueryRepository       interfaccia del custom (slice, cursore, conteggi)
│   └── VersamentoQueryRepositoryImpl   Criteria API + entity graph
├── ricerca/
│   ├── CriteriRicercaPendenze          record dei criteri (§7)
│   ├── VersamentoSpecifications        criteri -> Specification (§7)
│   ├── CampoOrdinamento                enum (§8)
│   ├── Ordinamento                     record (campo, verso) (§8)
│   ├── Paginazione                     sealed: PerOffset | PerCursore (§9)
│   ├── PaginaPendenze                  record risultato (§9)
│   ├── CursorCodec + BadCursorException porting (§9)
│   └── ProfiloFetch                    enum (§6)
├── lettura/
│   ├── LetturaPendenzaService          dettaglio (§10.1)
│   └── RicercaPendenzeService          lista e conteggi (§10.2)
├── spi/
│   └── VisibilitaPendenze              porta di visibilità (§11)
└── config/
    └── PendenzeAutoConfiguration       esteso (§12)
```

`ricerca` resta senza dipendenze da Spring e JPA **eccetto `VersamentoSpecifications`**,
che è il punto di traduzione verso la Criteria API: sta lì perché è dove si legge insieme
al resto dei criteri, ma è l'unico file del package che importa JPA. Se in F3 diventasse
scomodo, si sposta in `repository` senza toccare il resto.

## 5. Repository

### 5.1 `VersamentoRepository` — lookup per chiave

```java
public interface VersamentoRepository
        extends JpaRepository<Versamento, Long>,
                JpaSpecificationExecutor<Versamento>,
                VersamentoQueryRepository {

    Optional<Versamento> findByIdApplicazioneAndCodVersamentoEnte(long idApplicazione, String codVersamentoEnte);

    Optional<Versamento> findByIdDominioAndIuvVersamento(long idDominio, String iuvVersamento);

    Optional<Versamento> findByIdDominioAndNumeroAvviso(long idDominio, String numeroAvviso);

    List<Versamento> findByIdApplicazioneAndCodBundlekey(long idApplicazione, String codBundlekey);

    boolean existsByIdDominioAndIuvVersamento(long idDominio, String iuvVersamento);

    boolean existsByIdDominioAndIdSessione(long idDominio, String idSessione);
}
```

Ogni firma copre un indice esistente (proposta §7.4):

| Metodo | Indice |
|---|---|
| `findByIdApplicazioneAndCodVersamentoEnte` | `idx_vrs_id_pendenza (cod_versamento_ente, id_applicazione)` — è anche il vincolo di unicità della chiave logica |
| `findByIdDominioAndIuvVersamento`, `findByIdDominioAndNumeroAvviso` | `idx_vrs_iuv_dominio (iuv_versamento, id_dominio)`; il numero avviso è derivabile dallo IUV ma la colonna esiste ed è quella che i chiamanti hanno in mano |
| `findByIdApplicazioneAndCodBundlekey` | nessun indice dedicato: resta una scansione su `id_applicazione`. Il bundlekey non è unico (D8 del legacy), quindi la firma restituisce una **lista**: è il chiamante a decidere cosa farne |

`findByIdApplicazioneAndCodBundlekey` è l'unico punto in cui ci si discosta dalla firma
della proposta (`trovaPerBundlekey` con `idDominio` e `cfDebitore` come argomenti
aggiuntivi): quei due criteri sono un filtro sul risultato, non parte della chiave, e
espressi come argomenti facoltativi produrrebbero quattro query diverse dietro una firma
sola. Restano nei criteri di ricerca, dove uno che cerca per bundlekey può comporli.

### 5.2 Repository delle voci e del documento

```java
public interface SingoloVersamentoRepository extends JpaRepository<SingoloVersamento, Long> {
    List<SingoloVersamento> findByVersamentoIdOrderByIndiceDatiAsc(long idVersamento);
    Optional<SingoloVersamento> findByVersamentoIdAndCodSingoloVersamentoEnte(long idVersamento, String cod);
}

public interface DocumentoRepository extends JpaRepository<Documento, Long> {
    Optional<Documento> findByIdApplicazioneAndCodDocumento(long idApplicazione, String codDocumento);
}
```

Le pendenze di un documento **non** stanno qui: sono il criterio `idDocumento` di §7,
altrimenti la stessa ricerca esisterebbe in due posti con due paginazioni diverse — che è
il difetto del legacy, dove `bd.model.Documento.getVersamenti` fa una `findAll` per conto
proprio.

Le voci si raggiungono quasi sempre dall'aggregato (`ProfiloFetch.CON_VOCI`): il
repository dedicato serve ai percorsi che aggiornano una singola voce, che sono di F3. In
F2 si crea con le due letture qui sopra e nient'altro.

### 5.3 `VersamentoQueryRepository` — il custom

Ci confluisce quello che oggi sta in `PendenzaService` (`findSlice`, `findByCursor`,
`summaryEntityGraph`): sono query Criteria che non si esprimono con i derived method.

```java
public interface VersamentoQueryRepository {

    List<Versamento> cerca(Specification<Versamento> criteri, Ordinamento ordinamento,
                           int offset, int limite, ProfiloFetch fetch);

    List<Versamento> cercaDopoIlCursore(Specification<Versamento> criteri, Cursore cursore,
                                        int limite, ProfiloFetch fetch);

    long conta(Specification<Versamento> criteri);

    long contaConLimite(Specification<Versamento> criteri, int limite);
}
```

`contaConLimite` non è un vezzo: nel legacy esiste come `countConLimitEngine` e serve a
due cose che vale la pena conservare — non pagare un `count(*)` esatto su tabelle grandi
quando alla UI basta sapere «più di N», e rispondere al caso d'uso di `EventiController`,
che usa il conteggio come **check di autorizzazione** (`totalResults == 0` ⇒ non
autorizzato) e a cui basta il limite 1.

## 6. `ProfiloFetch` ed entity graph

```java
public enum ProfiloFetch { SOLO_TESTATA, CON_VOCI, COMPLETO }
```

| Profilo | Grafo | Query |
|---|---|---|
| `SOLO_TESTATA` | nessuna relazione | 1 |
| `CON_VOCI` | `singoliVersamenti` | 1 |
| `COMPLETO` | `singoliVersamenti`, `documento` | 1 |

Tre righe, non cinque livelli: è tutto quello che l'aggregato contiene. **Nessuna
anagrafica nel grafo** (F1-6) e **RPT e pagamenti esclusi** (A1): chi li vuole li chiede
con le proprie query, sulle proprie entità.

Conseguenza pratica di F2-5, che vale la pena rendere esplicita perché è il punto in cui
questa libreria diverge di più dalla console: la lista di pendenze in profilo
`SOLO_TESTATA` è **una query su una tabella sola, senza join**. Nella console la stessa
lista ne fa cinque, tutti verso l'anagrafica, per riempire la proiezione di summary. Se un
consumatore ha bisogno di quei dati per la sua vista, li risolve con una query sua — una
volta per pagina, non una per riga.

Gli entity graph si costruiscono a mano nel repository custom
(`entityManager.createEntityGraph`) e si passano come `jakarta.persistence.fetchgraph`.
`fetchgraph` e non `loadgraph`: quello che non è nel grafo resta LAZY, che è esattamente
ciò che si vuole per `SOLO_TESTATA`.

## 7. Criteri di ricerca

Un solo oggetto, che unifica i due `VersamentoFilter` del legacy (1.070 + 1.071 righe,
88% identiche, nessuna differenza semantica — analisi §10) e i criteri della console.

```java
public record CriteriRicercaPendenze(
        Set<Long> id,
        Set<Long> idDomini,
        Set<Long> idUo,
        Long idApplicazione,
        Set<Long> idTipiVersamento,
        Long idDocumento,
        String codVersamentoEnte,
        String codVersamentoEnteContiene,
        String codBundlekey,
        String numeroAvviso,
        String iuv,
        String idSessione,
        String debitoreIdentificativo,
        String identificativoCittadino,
        Set<StatoPendenzaApplicativo> stati,
        TipologiaTipoVersamento tipo,
        Set<String> direzioni,
        Set<String> divisioni,
        OffsetDateTime creataDa,
        OffsetDateTime creataA,
        boolean soloVisibiliAlCittadino,
        boolean escludiSpontaneiNonPagati,
        String ricercaSemplice) {
}
```

Corrispondenza con il legacy, criterio per criterio:

| `VersamentoFilter` | Colonna | Nella libreria |
|---|---|---|
| `idVersamento` | `id` | `id` |
| `idDomini` | `id_dominio` | `idDomini` |
| `idUo` (`IdUnitaOperativa`) | `id_dominio` + `id_uo` | `idUo` — vedi §7.2 |
| `codApplicazione` | join `applicazioni` | `idApplicazione` (F2-2) |
| `codDominio` | join `domini` | risolto a monte, confluisce in `idDomini` |
| `idTipiVersamento`, `codTipoVersamento`, `codTipiVersamento` | `id_tipo_versamento` / join | `idTipiVersamento` (F2-2) |
| `idDocumento` | `id_documento` | `idDocumento` |
| `codVersamento` | `cod_versamento_ente` | `codVersamentoEnte` (esatto) e `codVersamentoEnteContiene` (parziale) — vedi §7.2 |
| `iuv` | `src_iuv` **uppercase** | `iuv` (F2-8) |
| `idSessione` | `id_sessione` | `idSessione` |
| `codUnivocoDebitore` | `debitore_identificativo` | `debitoreIdentificativo` |
| `cfCittadino` | `src_debitore_identificativo` **uppercase** | `identificativoCittadino` |
| `statiVersamento` | `stato_versamento` | `stati`, ma di tipo applicativo — §7.3 |
| `abilitaFiltroScaduto` / `abilitaFiltroNonScaduto` | `data_scadenza` | assorbiti da `stati` — §7.3 |
| `divisione`, `direzione` | `divisione`, `direzione` | `divisioni`, `direzioni` (insiemi: la console già filtra `IN`) |
| `dataInizio`, `dataFine` | `data_creazione` | `creataDa`, `creataA` |
| `abilitaFiltroCittadino` | `tipo`, `importo_pagato` | `soloVisibiliAlCittadino` |
| `mostraSpontaneiNonPagati` | `tipo`, `stato_versamento` | `escludiSpontaneiNonPagati` |
| `simpleSearch` | `debitore_identificativo`, `cod_versamento_ente`, `iuv_versamento` | `ricercaSemplice` |
| `idTracciato` | join `operazioni` | **fuori perimetro** (F5) |

Un criterio nullo o vuoto non produce predicato: `VersamentoSpecifications` restituisce
`null` e la composizione li scarta, come già fa la console — con l'accortezza che
`Specification.allOf` di Spring Data JPA 4 rifiuta i `null`, quindi vanno filtrati prima.

### 7.1 I quattro predicati da riscrivere

Nella console passano da un join sull'anagrafica; qui sono colonne:

| Console | Libreria |
|---|---|
| `root.get("dominio").get("codDominio")` = codice | `root.get("idDominio").in(idDomini)` |
| `root.get("applicazione").get("codApplicazione")` = codice | `root.get("idApplicazione")` = id |
| `root.get("tipoVersamento").get("codTipoVersamento").in(codici)` | `root.get("idTipoVersamento").in(idTipiVersamento)` |
| `root.get("documento").get("id")` | resta così: `documento` è dentro l'aggregato e Hibernate legge la FK senza join |

Non è solo una traduzione: è la ragione per cui `idx_vrs_auth (id_dominio,
id_tipo_versamento, id_uo)` torna utilizzabile per intero, cosa che con i join sui codici
non accadeva.

### 7.2 Criteri con semantica non ovvia

**`idUo`.** Nel legacy è una lista di coppie `(idDominio, idUnita)` messe in OR, ognuna
con i due termini in AND: «UO 3 del dominio 7 **oppure** UO 1 del dominio 9». Va
conservata così — un `id_uo IN (...)` piatto attraverserebbe i domini. Il criterio è
quindi `Set<UnitaOperativa>` con `UnitaOperativa(Long idDominio, Long idUo)`, non un
insieme di `Long`; entrambi i campi possono essere nulli, come nel legacy.

**`codVersamentoEnte` esatto o parziale.** Il legacy filtra **esatto**, la console fa
`LIKE %valore%` su `lower(cod_versamento_ente)`. Sono due criteri diversi, non due
opinioni sullo stesso: si espongono entrambi, con nomi che dicono quale è quale. Il
parziale non usa indice ed è bene che chi lo sceglie lo sappia dal nome.

**`identificativoCittadino` e `iuv` sono uppercase.** Le colonne `src_*` sono normalizzate
in maiuscolo alla scrittura (B4) e il legacy fa `.toUpperCase()` sul criterio prima di
confrontare. La libreria fa lo stesso, dentro la specification: chi cerca non deve saperlo.

**`soloVisibiliAlCittadino`** = `tipo = 'DOVUTO' OR importo_pagato > 0`. È la regola con
cui il portale del cittadino nasconde le pendenze spontanee mai pagate di altri.

**`escludiSpontaneiNonPagati`** = `NOT (tipo = 'SPONTANEO' AND stato_versamento =
'NON_ESEGUITO')`. Nel legacy è un `Boolean` a tre stati dove solo `FALSE` produce il
predicato; qui è un `boolean` che quando è `true` esclude, perché il terzo stato non
significava niente di diverso da `false`.

**`ricercaSemplice`** è un OR su `debitore_identificativo`, `cod_versamento_ente`,
`iuv_versamento`, in AND con tutto il resto. Nel legacy è un percorso separato
(`toSimpleSearchExpressionEngine`) che riapplica a mano i criteri di autorizzazione: qui è
un criterio come gli altri e la visibilità arriva da §11, che non si può dimenticare.

### 7.3 Stato: si filtra sullo stato applicativo

F1 ha già la derivazione (`StatoPendenzaApplicativo`, §10.4 del disegno di F1): `SCADUTA`
non è un valore in colonna, è `NON_ESEGUITO` + `data_scadenza` passata rispetto a un
istante di riferimento. Il filtro deve usare **quella** derivazione, non riscriverla:
altrimenti una riga che l'API mostra `SCADUTA` non sarebbe raggiungibile da
`stati=SCADUTA`, che è esattamente il difetto che la console ha corretto tenendo insieme
`StatoVersamentoMapping` e il filtro.

Traduzione di ogni stato applicativo in predicato:

| Stato | Predicato |
|---|---|
| `NON_PAGATA` | `stato_versamento IN (NON_ESEGUITO)` AND (`data_scadenza IS NULL` OR `data_scadenza >= :ora`) |
| `SCADUTA` | `stato_versamento IN (NON_ESEGUITO)` AND `data_scadenza IS NOT NULL` AND `data_scadenza < :ora` |
| `PAGATA` | `stato_versamento IN (ESEGUITO, ESEGUITO_ALTRO_CANALE, ESEGUITO_SENZA_RPT)` |
| `PAGATA_PARZIALE` | `stato_versamento IN (PARZIALMENTE_ESEGUITO)` |
| `RICONCILIATA` | `stato_versamento IN (INCASSATO)` |
| `ANNULLATA` | `stato_versamento IN (ANNULLATO)` |
| `ANOMALA` | `stato_versamento IN (ANOMALO)` OR `NOT IN (<tutti gli altri stati noti>)` |

Due dettagli che vengono dal legacy e vanno conservati:

1. `:ora` viene dal `Clock` iniettato (F1-2), mai da `OffsetDateTime.now()`. Il legacy usa
   la **mezzanotte** come riferimento, non l'istante corrente (azzera ore, minuti,
   secondi; per lo scaduto sottrae un millisecondo, ottenendo le 23:59:59.999 di ieri).
   Con l'istante corrente una pendenza in scadenza oggi risulta scaduta a metà giornata:
   la libreria tronca al giorno come il legacy.
2. `ANOMALA` è un `NOT IN` sul complemento, non un elenco chiuso: uno stato grezzo ignoto
   in colonna deve essere raggiungibile dal filtro, non solo apparire in output.

### 7.4 I valori grezzi di `stato_versamento` non sono solo gli otto canonici

`StatoVersamentoMapping` di `govpay-console-api` è la fonte unica con cui la console
traduce la colonna, e riconosce **ventitré** valori grezzi, non otto:

| Gruppo | Valori grezzi riconosciuti |
|---|---|
| pagata | `ESEGUITA`, `ESEGUITO`, `PAGATA`, `PAGATO`, `ESEGUITO_ALTRO_CANALE`, `ESEGUITO_SENZA_RPT` |
| non eseguita | `NON_ESEGUITA`, `NON_ESEGUITO`, `NON_PAGATA`, `NON_PAGATO` |
| parziale | `ESEGUITA_PARZIALE`, `ESEGUITO_PARZIALE`, `PAGATA_PARZIALE`, `PAGATO_PARZIALE`, `PARZIALMENTE_ESEGUITO` |
| riconciliata | `INCASSATA`, `INCASSATO`, `RICONCILIATA`, `RICONCILIATO` |
| annullata | `ANNULLATA`, `ANNULLATO` |
| scaduta (letterale) | `SCADUTA`, `SCADUTO` |
| anomala (letterale) | `ANOMALA`, `ANOMALO` |

Non è codice difensivo scritto a caso: il Javadoc di `PendenzaSpecifications` dichiara di
aver visto **sia `ESEGUITO` che `ESEGUITA` in dati reali**, e su questo la console ha
costruito il catch-all.

**Il problema è di F1, e si vede solo ora.** L'entità mappa la colonna con
`@Enumerated(EnumType.STRING)` su un enum di otto costanti, e F1-4 ha deciso che uno stato
ignoto è un'eccezione. Le due cose insieme dicono che una riga con `stato_versamento =
'ESEGUITA'` **non si legge affatto**: Hibernate solleva, e non fallisce quel campo, fallisce
la query. Un filtro che non trova la riga sarebbe un difetto; una lista che esplode perché
una riga su diecimila ha il genere sbagliato è peggio.

La proposta è un `StatoVersamentoConverter` che canonicalizza i sinonimi noti — la stessa
tabella qui sopra — e **continua a sollevare** su un valore fuori da tutti i gruppi, così
lo spirito di F1-4 resta (uno stato davvero ignoto è un errore, non un `null` silenzioso).
Restano da decidere due cose, in §14.

## 8. Ordinamento

```java
public enum CampoOrdinamento {
    DATA_CREAZIONE("dataCreazione"),
    DATA_VALIDITA("dataValidita"),
    DATA_SCADENZA("dataScadenza"),
    STATO("statoVersamento"),
    DATA_ULTIMO_AGGIORNAMENTO("dataOraUltimoAggiornamento");
}

public record Ordinamento(CampoOrdinamento campo, Verso verso) {
    public static final Ordinamento DEFAULT =
            new Ordinamento(CampoOrdinamento.DATA_CREAZIONE, Verso.DISCENDENTE);
}
```

I primi quattro sono i campi ordinabili del legacy (`ListaPendenzeDTO`), con lo stesso
default `data_creazione DESC` e l'indice `idx_vrs_data_creaz` che lo copre.
`DATA_ULTIMO_AGGIORNAMENTO` è in coda e **non è indicizzato**: si espone perché i
consumatori della console lo usano già, documentando che non va usato come chiave di
cursore (§9) e che su tabelle grandi ordina in memoria.

La libreria non parla di `?sort=` (F2-3): la mappa dai nomi pubblici — `dataCaricamento`
per le API v1, `dataCreazione` per la console — sta nei consumatori, che sono anche gli
unici a sapere quale contratto devono rispettare.

## 9. Paginazione

```java
public sealed interface Paginazione {
    record PerOffset(int pagina, int dimensione, Ordinamento ordinamento, boolean conTotale) implements Paginazione {}
    record PerCursore(String cursore, int dimensione) implements Paginazione {}
}

public record PaginaPendenze(List<Versamento> risultati, boolean altraPagina,
                             String cursoreSuccessivo, Long totale) {}
```

**Offset.** Ordinamento a scelta, `LIMIT dimensione + 1` per sapere se c'è una pagina
dopo senza contare, `totale` valorizzato solo se richiesto.

**Cursore.** `PerCursore` non porta un ordinamento: è implicitamente
`(data_creazione DESC, id DESC)` (F2-4). Il predicato è

```sql
data_creazione < :ts OR (data_creazione = :ts AND id < :id)
```

con cursore assente alla prima pagina. Il codec è quello della console: base64 URL-safe
senza riempimento di `"<timestamp ISO_8601>|<id>"`, non firmato — è un suggerimento di
paginazione, non un token di sicurezza, e la query resta comunque limitata dalla
visibilità (§11), quindi una manomissione al più fa saltare l'ordine.

Due cose che qui si fanno diversamente dalla console:

1. **La combinazione «cursore + ordinamento diverso dal default» è un errore**, non un
   silenzio. Oggi `listCursorMode` riceve il `sort` e lo ignora: chi chiede
   `?sort=stato&cursor=…` ottiene righe ordinate per data senza che nessuno glielo dica.
2. **L'indice non copre il tiebreak.** `idx_vrs_data_creaz` è su `data_creazione DESC` a
   colonna singola; la chiave del keyset è `(data_creazione DESC, id DESC)`. Il piano
   resta accettabile (l'indice guida, il tiebreak si risolve sulle poche righe con lo
   stesso istante), ma va scritto nel Javadoc del repository e verificato con un
   `EXPLAIN` nei test: `idx_vrs_data_creaz_id (data_creazione DESC, id DESC)` sarebbe la
   migliore, e siccome ne è il prefisso lo sostituirebbe — ma è una modifica allo schema,
   che è fuori perimetro e va concordata a parte.

## 10. Servizi di lettura

### 10.1 Dettaglio

```java
Optional<Versamento> trovaPerId(long id, ProfiloFetch fetch);
Optional<Versamento> trovaPerIdentificativo(long idApplicazione, String idPendenza, ProfiloFetch fetch);
Optional<Versamento> trovaPerDominioIuv(long idDominio, String iuv, ProfiloFetch fetch);
Optional<Versamento> trovaPerDominioNumeroAvviso(long idDominio, String numeroAvviso, ProfiloFetch fetch);
List<Versamento> trovaPerBundlekey(long idApplicazione, String bundlekey);
boolean esiste(long idDominio, String iuv);
boolean esisteSessione(long idDominio, String idSessione);
```

`Optional` vuoto, mai eccezione: il 404 e la sua semantica anti-leak sono decisioni dello
strato che espone l'API. Nessuna acquisizione dall'Ente Creditore quando la pendenza non
c'è (il legacy la fa in `leggiPendenzaByRiferimentoAvviso`): è un flusso applicativo,
fuori perimetro (proposta §11).

Il controllo di visibilità sul dettaglio **non** è dentro queste firme: torna l'entità e
il chiamante decide, con `VisibilitaPendenze.puoVedere` (§11), se rispondere 404. Metterlo
dentro avrebbe voluto dire portare qui la nozione di «operatore corrente», che è di chi
espone l'API.

### 10.2 Lista

```java
PaginaPendenze cerca(CriteriRicercaPendenze criteri, Paginazione paginazione, ProfiloFetch fetch);
long conta(CriteriRicercaPendenze criteri);
long contaConLimite(CriteriRicercaPendenze criteri, int limite);
```

Tutte e tre applicano la visibilità prima di comporre il resto dei criteri.

## 11. Visibilità (SPI)

```java
public interface VisibilitaPendenze {
    Predicate criterio(CriteriaBuilder cb, Path<?> versamento);
    boolean puoVedere(Versamento pendenza);
}
```

Porting di `VersamentoVisibilita` della console, con i tre livelli di V1: dominio intero
**OR** unità operativa visibile, **AND** tipo pendenza autorizzato. Insieme autorizzato
vuoto ⇒ predicato sempre falso, cioè risultato vuoto e mai un 403 — la stessa scelta della
console, che evita di rivelare l'esistenza di ciò che non si può vedere.

Due differenze rispetto all'originale, entrambe conseguenza di F1-6:

- il predicato **non fa più join**: `versamento.get("dominio").get("id")` diventa
  `versamento.get("idDominio")`, e le tre colonne coinvolte sono esattamente quelle di
  `idx_vrs_auth`;
- `puoVedere` non deve più guardarsi da relazioni nulle, perché legge colonne.

L'implementazione di default è «tutto visibile»: una libreria non può decidere la politica
di autorizzazione del consumatore, e un default restrittivo renderebbe la libreria
inutilizzabile finché non si configura una SPI. Il default è un bean
`@ConditionalOnMissingBean`, così chi ne fornisce una la sostituisce senza altro.

## 12. Autoconfigurazione

```java
@AutoConfiguration
@EnableConfigurationProperties(PendenzeProperties.class)
@EntityScan(basePackageClasses = Versamento.class)
@EnableJpaRepositories(basePackageClasses = VersamentoRepository.class)
public class PendenzeAutoConfiguration { … }
```

È il punto annotato in F1 §14.5: oggi le entità si trovano solo perché nei test la
configurazione sta in `it.govpay.pendenze`. Un consumatore con package base diverso non le
vedrebbe — e non se ne accorgerebbe con un errore chiaro, ma con un
`Not a managed type` all'avvio.

`basePackageClasses` e non `basePackages`: una stringa non si rinomina insieme al package.

Attenzione al mestiere: `@EntityScan` e `@EnableJpaRepositories` sul consumatore
**sostituiscono** i default di Boot invece di aggiungersi. Un consumatore che li dichiara
per i propri package smette di vedere i nostri: va documentato nel README che in quel caso
deve includere anche `it.govpay.pendenze`. È il motivo per cui il test di §13 va scritto
con un package base diverso davvero, non simulato.

## 13. Test di F2

| Area | Casi |
|---|---|
| Lookup per chiave | presenza, assenza, bundlekey con più righe, unicità della chiave logica |
| Criteri, uno per uno | ogni criterio isolato: righe che matchano e righe che non matchano |
| Criteri combinati | AND fra criteri di assi diversi; `idUo` come OR di coppie, con la verifica che una UO di un altro dominio **non** rientri |
| Uppercase | ricerca per `iuv` e `identificativoCittadino` in minuscolo che trova la riga scritta in maiuscolo |
| Stato | ogni stato applicativo; `SCADUTA` derivata con `Clock` fisso; scadenza esattamente al riferimento; stato grezzo ignoto raggiungibile da `ANOMALA` |
| Ordinamento | default; ogni campo ammesso; verso ascendente e discendente |
| Paginazione offset | continuità fra pagine, ultima pagina, `altraPagina` senza conteggio, `totale` solo se richiesto |
| Paginazione cursore | continuità, cursore malformato → `BadCursorException`, cursore + ordinamento non di default → errore esplicito, **nessun salto né duplicato** quando una riga viene aggiornata fra una pagina e l'altra |
| `ProfiloFetch` | numero di query per profilo (contatore di statement Hibernate): 1 per ognuno dei tre, nessun N+1 su una lista di 20 righe con voci |
| Visibilità | domini interi, UO, tipi pendenza, insiemi vuoti → risultato vuoto; il predicato entra nella query (verificato sul conteggio, non sui risultati già caricati) |
| Conteggi | `conta` esatto; `contaConLimite` che si ferma al limite |
| Autoconfigurazione | un contesto con package base **diverso** da `it.govpay.pendenze` vede entità e repository |

Il test sul numero di query è quello che protegge F2-5: senza, la sparizione del grafo di
summary è una scelta di disegno che nessuno verifica, e il primo che aggiunge una relazione
LAZY letta nel mapper reintroduce l'N+1 senza che il build se ne accorga.

## 14. Punti aperti di F2

- **`stato_versamento`: quanto tollerare** (§7.4). Due domande, in ordine:
  1. *I sinonimi esistono davvero in banca dati?* Va verificato con un `SELECT DISTINCT
     stato_versamento FROM versamenti` su un'installazione reale — è una query da niente e
     decide il resto. Se esistono solo gli otto valori canonici, F1 va bene com'è e questo
     punto si chiude.
  2. *Se esistono, come si legge il letterale `SCADUTA`/`SCADUTO`?* Non ha una costante
     corrispondente in `StatoVersamento`, perché in F1 «scaduta» è derivata
     (`NON_ESEGUITO` + data passata) e non un valore di colonna. Canonicalizzarlo in
     `NON_ESEGUITO` fa dipendere lo stato mostrato da `data_scadenza`, e una riga con
     `SCADUTA` in colonna e `data_scadenza` nulla risulterebbe `NON_PAGATA` — mentre la
     console la mostra `SCADUTA`. L'alternativa è aggiungere la costante all'enum, cioè
     ammettere che la colonna porta uno stato che il dominio considera derivato.

  L'impatto è su F1, che è già su `main`: cambia il mapping dell'entità e aggiunge un
  converter. Va deciso **prima** di scrivere il filtro di §7.3, perché filtro e lettura
  devono usare la stessa tabella di valori.
- **Il riferimento temporale dello scaduto è la mezzanotte** (§7.3), come nel legacy.
  Da confermare che sia ancora il comportamento voluto: è visibile all'utente, perché
  decide se una pendenza in scadenza oggi appaia scaduta prima di sera. Se si decide per
  l'istante corrente, cambia il comportamento delle API rispetto alla 3.x.
- **`ricercaSemplice` cerca su `iuv_versamento`**, mentre il criterio `iuv` cerca su
  `src_iuv` (F2-8). È così nel legacy. Vale la pena allinearli a `src_iuv`, che è la
  colonna indicizzata per la ricerca? Cambierebbe i risultati della ricerca semplice per
  gli IUV riscritti.
- **Modifica di indice per il keyset** (§9): fuori perimetro qui, ma da aprire come issue
  a sé sullo schema del core se il piano di esecuzione risultasse insoddisfacente.

## 15. Da tenere presente per F3

- Il repository custom nasce in F2 con le sole letture: gli aggiornamenti puntuali di F3
  (15 metodi, analisi §9.2) ci si appoggeranno, ma servirà decidere lì se passano da
  `@Modifying` o dal dirty checking, che è la decisione già annotata su
  `data_ora_ultimo_aggiornamento` (F1 §8).
- `VisibilitaPendenze` nasce in sola lettura: se in F3 serve anche in scrittura (chi può
  aggiornare cosa), la SPI va estesa, non duplicata.
