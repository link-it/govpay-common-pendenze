# Proposta di disegno — modello nativo v3

Documento di proposta per il modello dati/entità JPA dell'evolutiva api-pendenze
v3, dopo l'abbandono del porting del modello legacy (issue #8, PR #9). Sostituisce
concettualmente `proposta-libreria-pendenze.md`/`f1-modello-e-entita.md` (che
restano come riferimento sul comportamento 3.10.x per la migrazione dati, non
come base di questo disegno).

- **Fonte v3**: `govpay-api-pendenze-v3.yaml` (schemi `NuovaPosizioneDebitoria`,
  `NuovaOpzionePagamento*`, `NuovaPendenza`, `NuovaVocePendenza*`, `Soggetto`,
  `PosizioneDebitoria`, `OpzionePagamento*`, `Pendenza*`, `VocePendenza*`)
- **Fonte principi di scrittura/lettura**: `riconciliazione-legacy-v3.md` §2
  (D10/D11/D3/A5/§4.2/§4.8/§7.4 del vecchio disegno, ancora validi)
- **Fonte ACA/GPD**: spec ufficiale pagoPA `pagopa/pagopa-api`,
  `openapi/gpd-4-aca.json` (verificata il 2026-09-22)

> **Nota (2026-09-25/26), da leggere prima del resto**: le tabelle *nuove* di
> §§1-16 (`posizioni_debitorie`, `pendenze`, `voci_pendenza`, `ricevute`,
> `rendicontazioni`, `flussi_rendicontazione` come schema v3 separato) sono
> state **abbandonate** in favore del riuso diretto delle tabelle legacy
> (`documenti`/`versamenti`/`singoli_versamenti`/`rpt`/`pagamenti`/`fr`/
> `rendicontazioni`) — vedi §17 in poi. Le entità/decisioni di mapping restano
> valide dove non in contraddizione con §17+ (validazioni di dominio, criteri
> di ricerca, generazione IUV, `dettaglioContabile`), ma ogni riferimento a
> nomi di tabella in §§1-16 va inteso come lo schema abbandonato, non quello
> reale.

## 1. Struttura dell'aggregato

```
PosizioneDebitoria (root)
├── SoggettoDebitore[]     (ordinati, "in solido")
└── OpzionePagamento[]     (tipologia: PIANO_RATEALE | SOLUZIONE_UNICA |
    │                       SOLUZIONE_UNICA_ENTRO | SOLUZIONE_UNICA_OLTRE)
    │                       stato: DISPONIBILE → ATTIVATA | ANNULLATA
    └── Pendenza[]         (numeroRata, nav = numeroAvviso)
        └── VocePendenza[] (RiferimentoEntrata | Entrata | Bollo)
            └── DettaglioContabile[] (solo per Entrata/RiferimentoEntrata — non in questo primo disegno, vedi §6)
```

Un solo aggregato transazionale (coerente con D3/D9 del vecchio disegno):
caricare/modificare una `PosizioneDebitoria` tocca le sue opzioni, pendenze,
voci e soggetti in un'unica unità di lavoro.

## 2. Decisioni di mapping

| ID | Decisione |
|---|---|
| M1 | **Package/nomi nativi v3**, non il vocabolario legacy: `PosizioneDebitoria`, `OpzionePagamento`, `Pendenza`, `VocePendenza`, `SoggettoDebitore` — a differenza della vecchia A2 ("doppio vocabolario mantenuto"), qui non c'è un vocabolario legacy da conciliare: si parte nativi |
| M2 | **`OpzionePagamento` a tabella unica, non per-tipologia**: le 4 varianti differiscono solo per `tipologia` (enum), `giorni` (nullable, richiesto solo per ENTRO/OLTRE) e cardinalità di `pendenze` (validata in applicazione, non a DB). Una gerarchia JPA (`@Inheritance`) sarebbe sovradimensionata: colonna `tipologia` + `giorni` nullable bastano, validazione applicativa sulla cardinalità |
| M3 | **`VocePendenza` a tabella unica**, stesso ragionamento: le 3 varianti (`RiferimentoEntrata`/`Entrata`/`Bollo`) condividono `DatiComuniVocePendenza(Lettura)`, differiscono per un piccolo set di colonne mutuamente esclusive (`codEntrata` vs `ibanAccredito`/`ibanAppoggio`/`tassonomia` vs `tipoBollo`/`hashDocumento`/`provinciaResidenza`) — colonne nullable + discriminatore esplicito `tipoRiferimento`, non tre tabelle |
| M4 | **FK verso anagrafica come colonne `Long`, niente relazione JPA** (continuità con §4.8 del vecchio disegno): `idDominio`, `idUnitaOperativa`, `idTipoPendenza` risolti a `Long` al bordo dell'applicazione, mai `@ManyToOne` verso entità di `govpay-common`. **Diverso per le relazioni interne all'aggregato stesso** (`PosizioneDebitoria`→`SoggettoDebitore`/`OpzionePagamento`, `OpzionePagamento`→`Pendenza`, `Pendenza`→`VocePendenza`): qui la relazione JPA è appropriata, sono nella stessa persistence unit e nello stesso confine transazionale |
| M5 | **`SoggettoDebitore` è un'entità con identità propria**, non un `@Embeddable` nella lista: ha una PK e una FK esplicita `id_posizione_debitoria` |
| M6 | **Ordine di `soggettiDebitori` preservato** (colonna `ordine`, non `@OrderColumn` implicito): è la **sola** fonte di verità per "chi è il soggetto pagatore" — il primo per `ordine` (deciso dal lead, 2026-09-22, contro la mia proposta di uno snapshot esplicito, vedi nota sotto). Nessun campo aggiuntivo |

> **Nota su M6 (non più M7)**: avevo proposto uno snapshot esplicito (`soggetto_pagatore_id` su `OpzionePagamento`) per isolare il modello dati dal rischio noto (`updatePosizioneDebitoria` non impedisce di modificare `soggettiDebitori` dopo che un'opzione è ATTIVATA, quindi rileggere "live" l'ordine potrebbe cambiare retroattivamente chi risulta pagatore di una pendenza già chiusa). **Decisione del lead**: niente snapshot per ora — ci si attiene all'indicazione dello YAML (ordine + primo della lista), in attesa che pagoPA stessa evolva l'interfaccia includendo esplicitamente l'identità del soggetto pagatore nelle operazioni (RPT, ecc.). Il rischio resta noto e tracciato (punto 1 della riconciliazione), ma non viene ora assorbito dallo schema.
| M7 | **`iupd` non è una colonna** (superato il 2026-10-24, vedi §28 — qui per il contesto storico): verificato lo spec `gpd-4-aca.json`, è definito solo `type: string`, nessun formato imposto — "è responsabilità dell'EC garantirne l'unicità" (suggerito lì: `<codice fiscale ente + UUID>`, ma non vincolante). Dato che `idA2A`+`idPosizioneDebitoria` sono già garantiti univoci insieme (vincolo esplicito dello YAML v3), `iupd` si **deriva al volo** da questi (es. codice fiscale del dominio + `idA2A` + `idPosizioneDebitoria`) quando serve dialogare con ACA/GPD, senza persistere nulla di nuovo (correzione rispetto alla prima stesura di questo documento, su rilievo del lead). **§28 va oltre**: non è più solo `iupd` verso ACA/GPD a derivarsi così — è diventato `idPosizioneDebitoria` stesso, l'identità pubblica di *tutta* l'API |
| M8 | **`nav` non è una colonna a sé**: coincide con `Pendenza.numeroAvviso`, già previsto dallo YAML. Nessun campo duplicato |
| M9 | **`StatoPendenza.SCADUTA` derivato, non persistito** (continuità con la vecchia A6): calcolato da `stato=NON_ESEGUITA` + `dataScadenza` (dell'opzione o della pendenza) nel passato, mai scritto su colonna. Le altre 6 costanti (`ESEGUITA`, `NON_ESEGUITA`, `ESEGUITA_PARZIALE`, `ANNULLATA`, `ANOMALA`, `INCASSATA`) sono lo stato persistito reale |
| M10 | **Audit e timestamp su ogni scrittura, senza eccezioni** (continuità con D10): `dataCreazione`/`dataUltimoAggiornamento` su tutte le entità dell'aggregato, non solo sulla radice |
| M11 | **Tabelle nuove**, non riuso di `versamenti`/`documenti`/`singoli_versamenti`: `posizioni_debitorie`, `soggetti_debitori`, `opzioni_pagamento`, `pendenze`, `voci_pendenza` |
| M12 | **Lock ottimistico (`@Version`) su `OpzionePagamento`** (rilievo del lead, 2026-09-22): senza un controllo di concorrenza, `attiva`/`annulla` chiamati concorrentemente sulla stessa opzione potrebbero sovrascriversi in silenzio — un annullamento basato su una lettura antecedente potrebbe vincere su un'attivazione appena registrata (o viceversa), corrompendo la macchina a stati. Con `@Version` chi scrive per secondo su una versione superata riceve un'eccezione invece di un aggiornamento perso |
| M13 | **`idDominio` denormalizzato su `Pendenza`** (rilievo del lead, 2026-09-22): IUV e NAV sono univoci **per dominio**, non globalmente — coerente con `idx_vrs_iuv_dominio` del legacy, già composto `(iuv_versamento, id_dominio)`. Un vincolo di unicità globale (come nella prima stesura di questo documento) impedirebbe a due enti creditori diversi di generare legittimamente lo stesso IUV/numero avviso, rompendo il multi-ente e la migrazione dei dati storici |

## 3. Entità proposte

### 3.1 `PosizioneDebitoria`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `idA2A` | `String` | identificativo del gestionale, con `idPosizioneDebitoria` forma la chiave logica univoca |
| `idPosizioneDebitoria` | `String` | business key, univoca per `idA2A` (409 se duplicata) — **da qui si deriva `iupd` per ACA/GPD, nessuna colonna dedicata (M7)** |
| `idDominio` | `Long` | FK, colonna (M4) |
| `idUnitaOperativa` | `Long` | FK, colonna, nullable |
| `descrizione` | `String` | max 140 |
| `dataPubblicazione` | `LocalDate` | nullable = pubblicata subito |
| `notificaSend` | `boolean` | default `false` |
| `navNotifica` | `String` | nullable, deve corrispondere al `numeroAvviso` di una pendenza figlia (vincolo applicativo, non FK — il riferimento è a un valore, non a una riga) |
| `dataUltimaModificaAca` / `dataUltimaComunicazioneAca` | `OffsetDateTime` | **nuovo, da §5** — marcatura ACA a livello posizione |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `soggettiDebitori` | `List<SoggettoDebitore>` | ordinata per `ordine` |
| `opzioniPagamento` | `List<OpzionePagamento>` | |

### 3.2 `SoggettoDebitore`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `posizioneDebitoria` | FK (`@ManyToOne`) | |
| `ordine` | `int` | posizione nell'array originale (M6) |
| `tipo` | enum `F`/`G` | |
| `identificativo` | `String` | CF/PIVA, 2-16 char |
| `anagrafica` | `String` | nullable |
| `indirizzo`, `civico`, `cap`, `localita`, `provincia`, `nazione`, `email` | `String` | tutti nullable |

### 3.3 `OpzionePagamento`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `versione` | `long` | **nuovo, M12** — lock ottimistico (`@Version`), protegge le transizioni di stato dalla concorrenza |
| `idOpzionePagamento` | `UUID` | esposto in API, generato da GovPay, stabile |
| `posizioneDebitoria` | FK | |
| `tipologia` | enum `PIANO_RATEALE`/`SOLUZIONE_UNICA`/`SOLUZIONE_UNICA_ENTRO`/`SOLUZIONE_UNICA_OLTRE` | M2 |
| `giorni` | `Integer` | nullable, richiesto solo per ENTRO/OLTRE (validazione applicativa) |
| `stato` | enum `DISPONIBILE`/`ATTIVATA`/`ANNULLATA` | macchina a stati |
| `dataInizioValidita` / `dataScadenza` | `LocalDate` | nullable |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `pendenze` | `List<Pendenza>` | ordinata (determina `numeroRata`) |

Vincoli applicativi (non a DB, coerenti con D3 "un metodo per evento di
dominio"): `PIANO_RATEALE` e `SOLUZIONE_UNICA` richiedono almeno 1 pendenza,
senza limite massimo (per `SOLUZIONE_UNICA` da quando un dovuto con più di 5
voci richiede più di un avviso, quindi più di una pendenza — vedi
`riconciliazione-legacy-v3.md` §4 punto 6). `SOLUZIONE_UNICA_ENTRO`/
`SOLUZIONE_UNICA_OLTRE` restano a esattamente 1 pendenza — confermato dal
lead (2026-09-24): non è previsto che ammettano più pendenze come
`SOLUZIONE_UNICA`. `giorni` obbligatorio solo per ENTRO/OLTRE.

**Correzione (2026-10-20)**: "`PIANO_RATEALE` in pratica ne richiede almeno 2,
altrimenti si userebbe `SOLUZIONE_UNICA`" era stato tradotto letteralmente in
`cardinalitaPendenzeMinima()` (minimo 2 solo per `PIANO_RATEALE`) — mai una
decisione dedicata, a differenza di quella su ENTRO/OLTRE appena citata.
Rimosso: il minimo e' ora 1 per tutte le tipologie **in questa libreria**. Il
vincolo "servono almeno due rate" resta comunque vero ed e' imposto a un
client v3-nativo nel contratto REST (`NuovaOpzionePagamentoPianoRateale.pendenze`,
`minItems: 2`, verificato generare `@Size(min = 2)` sul DTO applicato da
Spring con `@Valid` sul controller — indipendente da questa libreria). Motivo
del rilassamento: questa libreria e' usata anche da chiamanti diretti in Java
che costruiscono l'aggregato un passo alla volta — il wrapper verso i client
v2 (§28), dove la prima rata di un piano va persistita da sola (per rendere
subito disponibile IUV/numero avviso al chiamante) prima che arrivi la
seconda. Nessun altro punto del codice assume "`PIANO_RATEALE` implica ≥2
pendenze" (verificato: stati, `navNotifica`, mapper di stampa — tutti
generici sulla lunghezza della lista).

### 3.4 `Pendenza`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `opzionePagamento` | FK | |
| `idDominio` | `Long` | **nuovo (M13)** — denormalizzato dalla posizione: IUV/NAV sono univoci per dominio, non globalmente (rilievo del lead, 2026-09-22) |
| `idPendenza` | `String` | business key |
| `idTipoPendenza` | `Long` | FK, colonna (M4) |
| `numeroRata` | `int` | derivato dalla posizione nell'array (assegnato da GovPay) |
| `importo` | `BigDecimal` | continuità §4.2 del vecchio disegno (conversione dedicata, mai `new BigDecimal(double)`) |
| `numeroAvviso` | `String` | = NAV (M8), generato se assente; univoco insieme a `idDominio` (M13), non da solo |
| `iuv` | `String` | generato; univoco insieme a `idDominio` (M13), non da solo |
| `stato` | enum (6 valori persistiti, M9) | |
| `dataPagamento` | `LocalDate` | nullable |
| `dataCaricamento` | `LocalDate` | = data creazione, business-facing |
| `dataValidita` | `LocalDate` | nullable |
| `dataScadenzaAvviso` | `LocalDate` | nullable, override di stampa |
| `dataUltimaModificaAca` / `dataUltimaComunicazioneAca` | `OffsetDateTime` | **nuovo, da §5** — marcatura ACA a livello pendenza/avviso |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `voci` | `List<VocePendenza>` | |

### 3.5 `VocePendenza`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `pendenza` | FK | |
| `idVocePendenza` | `String` | business key |
| `importo` | `BigDecimal` | |
| `descrizione` | `String` | max 140 |
| `indice` | `int` | 1-5, ordine nella pendenza |
| `stato` | enum `Eseguito`/`Non eseguito`/`Anomalo` | valori così come nello YAML (maiuscole non uniformi, da preservare) |
| `idDominio` | `Long` | FK, nullable — override multi-beneficiario |
| `tipoRiferimento` | enum `RIFERIMENTO_ENTRATA`/`ENTRATA`/`BOLLO` | discriminatore (M3) |
| `codEntrata` | `String` | nullable, solo `RIFERIMENTO_ENTRATA` |
| `ibanAccredito`, `ibanAppoggio`, `tassonomia` | `String` | nullable, solo `ENTRATA` (e `tassonomia` anche per `BOLLO`) |
| `tipoBollo`, `hashDocumento`, `provinciaResidenza` | `String` | nullable, solo `BOLLO` |

`dettaglioContabile` (array, solo per `ENTRATA`/`RIFERIMENTO_ENTRATA`) **non è
in questo primo disegno** — vedi §6.

## 4. Come il modello accomoda i punti ancora aperti (senza deciderli)

- **Punto 1 (soggetto per RPT/stampa avviso)**: **non assorbito dallo schema,
  per decisione esplicita del lead** — niente snapshot dedicato (vedi nota su
  M6): si usa `soggettiDebitori` ordinato per `ordine`, primo elemento per
  convenzione, esattamente come indica lo YAML. Il rischio noto
  (`updatePosizioneDebitoria` non impedisce modifiche a `soggettiDebitori` dopo
  che un'opzione è ATTIVATA) resta quindi presente e non mitigato a livello di
  schema, in attesa che pagoPA evolva l'interfaccia includendo esplicitamente
  l'identità del soggetto pagatore nelle operazioni.
- **Punto 2 (avvisatura granulare)**: **non modellato in questo disegno** —
  nessun campo per giorni di preavviso mail/AppIO su `PosizioneDebitoria`/
  `Pendenza`. Se la decisione futura sarà di reintrodurla, servirà una
  migrazione di schema successiva.
- **Punto 4 (debitori incoerenti in migrazione)**: non è un problema di
  schema — è la migrazione a doverlo gestire (vedi `riconciliazione-legacy-v3.md`
  §4 punto 4).
- **ACA (chiarito, §5 di questo documento)**: `iupd` derivato da `idA2A`+
  `idPosizioneDebitoria` (M7, nessuna colonna), `nav` = `numeroAvviso` (M8) — le
  coppie `dataUltimaModificaAca`/`dataUltimaComunicazioneAca` restano su
  **entrambi** i livelli (posizione e pendenza), coerenti con
  `PaymentPositionModel`(`iupd`)/`PaymentOptionModel`(`nav`) dello spec pagoPA
  `gpd-4-aca.json`.

## 5. Cosa NON è in questo primo disegno (rimandato)

- `dettaglioContabile` completo (4 varianti: CorrispettivoDL118/IncassoTipico/
  Civilistico/ImportoNotifica) — solo abbozzato come collezione futura su
  `VocePendenza`
- Ricevute (`Ricevuta`, 3 formati XML), Rendicontazioni — fuori perimetro
  dell'aggregato pendenza in senso stretto, come nel vecchio disegno (A1)
- Ricerca, criteri, paginazione (sarà una fase a sé, come F2 nel vecchio
  disegno) — i 3 repository Spring Data esistenti coprono solo i lookup per
  chiave (identificativo, UUID, IUV/NAV)
- Aggiornamenti oltre `attiva`/`annulla` (PATCH, annullamento pendenza, ecc.)
  — le validazioni di dominio sono invece fatte, vedi §8
- Migrazione dati vera e propria (script), oggetto di un documento a parte

**Aggiornamento (2026-09-22): implementato da quando questo elenco è stato
scritto** — `repository/` (3 interfacce), `service/PosizioneDebitoriaService`
(`crea`/`trovaPerId`/`trovaPerIdentificativo`/`attiva`/`annulla`, quest'ultime
due la macchina a stati vera e propria con lock ottimistico M12), `exception/`
(2 eccezioni), e `spi/GeneratoreIuv` (generazione IUV/numero avviso quando il
chiamante non li fornisce — l'algoritmo reale resta al consumatore, questa
libreria non ha accesso all'anagrafica del dominio, M4). 15 test, tutti verdi
(build reale, non solo compilazione).

## 6. Punti da validare prima di procedere all'implementazione

1. Conferma della struttura a tabella unica per `OpzionePagamento` (M2) e
   `VocePendenza` (M3) invece di gerarchie JPA per tipologia.
2. Conferma che le relazioni interne all'aggregato (M4, seconda parte) possano
   essere `@ManyToOne`/`@OneToMany` reali, a differenza delle FK verso
   l'anagrafica esterna.
3. Conferma di M8 (nav = numeroAvviso, senza colonna dedicata).
4. ~~`cardinalitaPendenzeMassima()` di `SOLUZIONE_UNICA` (M2)~~ — **risolto
   (2026-09-22)**: confermato dal lead che lo YAML va aggiornato (oggi impone
   ancora `maxItems: 1`) per consentire più pendenze con `SOLUZIONE_UNICA`
   (dovuto con >5 voci → >1 avviso, vedi `riconciliazione-legacy-v3.md` §4
   punto 6). Implementato: `SOLUZIONE_UNICA` senza limite massimo, come
   `PIANO_RATEALE`. `SOLUZIONE_UNICA_ENTRO`/`OLTRE` restano a 1 pendenza —
   **confermato dal lead (2026-09-24)**: non è previsto che ammettano più
   pendenze come `SOLUZIONE_UNICA`.

## 7. Dipendenza da `govpay-common` (aggiunta il 2026-09-23)

**M4 era stato applicato in modo più restrittivo di quanto scritto**: vieta solo
le relazioni JPA (`@ManyToOne`) verso le entità di `govpay-common`, non l'uso
della libreria in generale. Il vecchio disegno (`proposta-libreria-pendenze.md`
§3.1, decisione B2) prevedeva esplicitamente questa dipendenza, per riusare le
utility condivise. Corretto: aggiunta la dipendenza (stesso pattern di
`govpay-console-api`: `${govpay-common.version}`, "not in BOM").

**Revisione di cosa avremmo potuto riusare e non abbiamo riusato** (richiesta
del lead): trovata **una sola sovrapposizione reale**, minore —
`PendenzeProperties.FUSO_ORARIO_DEFAULT` duplicava come letterale
`DateTimePatterns.DEFAULT_TIME_ZONE` (stessa stringa `"Europe/Rome"`);
corretto per riferirsi a quella costante. Nessun'altra sovrapposizione trovata:
gli enum di dominio (`TipoSoggetto`, stati, tipologie) non hanno equivalente in
`govpay-common`; non esiste un converter `BigDecimal`/importi equivalente al
nostro; il bean `Clock` e il customizer del fuso orario Hibernate di
`PendenzeAutoConfiguration` sono originali, `govpay-common` non espone nulla
del genere.

**Per `GeneratoreIuv`**: `govpay-common.utils.IuvUtils` ha oggi solo funzioni di
**verifica** (`isIuvInterno`, `isNumeric` — per capire se uno IUV già esistente
è interno), non di generazione. Verificato anche `jars/core/.../business/Iuv.java`
nel monorepo: la generazione reale richiede oggetti anagrafica completi
(`Applicazione`, `Dominio` con aux digit/stazione/prefisso custom) e un
contatore persistito per l'unicità — non e' una funzione pura delegabile a
un'utility statica. La SPI resta quindi necessaria per intero: nulla da
riusare da `govpay-common` oggi. Se in futuro quella libreria guadagnasse
funzioni di conversione/generazione (come il vecchio disegno auspicava), va
rivista.

**Decisi** (non più da validare):
- **M6** — niente snapshot del soggetto pagatore, si usa `soggettiDebitori`
  ordinato + primo elemento, come da YAML (vedi nota su M6 in §2).
- **M7** — niente colonna `iupd`, derivato al volo da `idA2A`+
  `idPosizioneDebitoria` (verificato sullo spec `gpd-4-aca.json`: nessun
  vincolo di formato, l'unicità è responsabilità dell'EC).

## 8. Validazioni di dominio (aggiunto il 2026-09-23)

Nuovo package `validazione/`, classe `ValidatorePosizioneDebitoria` — senza
stato ne' dipendenze da Spring/JPA (stesso principio di `model/`), invocata da
`PosizioneDebitoriaService.crea` prima di valorizzare audit/IUV. Copre i
vincoli semantici che lo schema JSON non può esprimere da solo:

- almeno un `SoggettoDebitore` e almeno una `OpzionePagamento` sulla posizione
- cardinalità delle pendenze per tipologia, usando
  `TipologiaOpzionePagamento.cardinalitaPendenzeMinima/Massima` (scritti in
  precedenza ma non ancora cablati in nessuna validazione)
- `giorni` obbligatorio e positivo per `SOLUZIONE_UNICA_ENTRO`/`OLTRE`, non
  ammesso per le altre due tipologie
- ogni pendenza ha da 1 a 5 voci
- l'importo di ogni pendenza coincide con la somma delle sue voci
  (confronto con `compareTo`, non `equals`, per non essere sensibile alla
  scala del `BigDecimal`)

Nuova eccezione `ValidazioneNonSuperataException`. 9 nuovi test (24 totali),
tutti JUnit puro senza `@DataJpaTest` — il validatore non ha bisogno di un
database per essere testato.

## 9. Quattro bug in `crea()` trovati in revisione e corretti (2026-09-23)

Tutti scoperti dal lead con casi concreti, non dalla suite di test esistente —
`crea()` compilava e i 24 test di prima passavano comunque, perché nessuno
copriva questi scenari.

**1. Un numero avviso fornito poteva essere sostituito.** La condizione era
`if (iuv == null || numeroAvviso == null)`: se il chiamante forniva solo uno
dei due, `crea()` generava una **coppia nuova**, scartando in silenzio quello
fornito. Corretto: genera solo se **entrambi** sono assenti; se ne manca solo
uno, `ValidazioneNonSuperataException` esplicita — completare la coppia
preservando l'identificativo fornito richiederebbe l'algoritmo di conversione
IUV↔NAV (dipendente dalla configurazione del dominio, M4), che
{@link GeneratoreIuv} non espone (genera sempre coppie nuove). Non risolvibile
senza estendere la SPI: rifiutato esplicitamente, come suggerito.

**2. Gli indici delle collezioni non venivano assegnati.** `numeroRata` (su
`Pendenza`) e `ordine` (su `SoggettoDebitore`) restavano al valore di default
(`0`): più pendenze finivano tutte con `numeroRata=0`, più soggetti senza
`ordine` esplicito violavano il vincolo di unicità `(id_posizione_debitoria,
ordine)`. La Javadoc diceva "assegnato dal servizio di caricamento" ma
`crea()` non lo faceva davvero. Corretto: nuovo metodo privato
`assegnaIndici`, chiamato per primo in `crea()`, assegna `ordine` (0-based) e
`numeroRata` (1-based) dalla posizione nelle liste — **sempre**, sovrascrivendo
anche un valore che il chiamante avesse impostato, perché nessuno dei due è
scrivibile in API (per `numeroRata` lo YAML lo dice esplicitamente) e l'unica
fonte di verità è l'ordine delle liste stesse.

**3. La somma degli importi poteva diventare incoerente dopo il salvataggio.**
Due voci da `0.005` e una pendenza da `0.01` superano il confronto in memoria
(`compareTo` non distingue la scala), ma la colonna `NUMERIC(19,2)` arrotonda
**implicitamente** ciascuna voce a `0.01` in scrittura: alla rilettura la
somma è `0.02`, non più coerente. Corretto: `ValidatorePosizioneDebitoria`
rifiuta ora qualunque importo con più di 2 decimali significativi
(`importo.setScale(2, RoundingMode.UNNECESSARY)`, che solleva
`ArithmeticException` se ci sono cifre significative oltre la seconda) —
stesso principio del vecchio `ImportoConverter` (che avevo scartato insieme al
resto del porting legacy, senza notare che il problema della precisione era
indipendente dal problema `DOUBLE PRECISION` multi-DB che l'aveva originato).

**4. Mancavano controllo e assegnazione di `navNotifica`.** `crea()` non
verificava che `navNotifica` (se fornito) corrispondesse al `numeroAvviso` di
una pendenza della posizione (previsto esplicitamente dallo YAML v3), né lo
assegnava automaticamente quando `notificaSend` è attivo e `navNotifica` è
assente — nemmeno nel caso più semplice (una sola pendenza). Corretto: nuovo
metodo privato `assegnaOValidaNavNotifica`, chiamato per ultimo in `crea()`
(dopo che `numeroRata` e `numeroAvviso` sono stati assegnati, da cui dipende):
valida la corrispondenza se `navNotifica` è fornito; altrimenti, se
`notificaSend` è attivo, lo assegna alla pendenza dell'opzione
`SOLUZIONE_UNICA` se presente, altrimenti alla rata 1 dell'unico
`PIANO_RATEALE` (semantica esatta dello YAML). Se nessuno dei due casi si
applica (es. solo opzioni `ENTRO`/`OLTRE`), resta `null` — non è un caso
coperto dalla regola nota, non si tenta un'assegnazione arbitraria.

7 nuovi test di regressione (31 totali): due per la coppia IUV/NAV parziale
(solo numeroAvviso, solo iuv) più uno che conferma che una coppia fornita per
intero non viene mai toccata; uno per l'assegnazione di indici; uno per il
rifiuto di importi con troppi decimali; due per `navNotifica` (rifiuto se non
corrisponde, assegnazione automatica).

## 10. Altri due bug trovati in revisione dello stesso giro (2026-09-23)

**5. `VocePendenza.indice` dimenticato in `assegnaIndici`.** Il fix del punto 9.2
copriva `ordine` (soggetti) e `numeroRata` (pendenze) ma non l'indice delle
voci: due voci aggiunte con `addVocePendenza()` restavano entrambe a `0`,
violando `unique_voci_pendenza_1 (id_pendenza, indice)` al salvataggio.
Corretto: `assegnaIndici` ora itera anche le voci di ogni pendenza,
assegnando `indice` 1-based dalla posizione nella lista.

**6. `notificaSend` poteva essere persistito con `navNotifica` nullo.**
`assegnaOValidaNavNotifica` (punto 4 del §9) copriva solo due casi
(`SOLUZIONE_UNICA` presente, o `PIANO_RATEALE` presente) per l'assegnazione
automatica; se la posizione aveva **solo** opzioni `SOLUZIONE_UNICA_ENTRO`/
`OLTRE`, nessuno dei due si applicava e il metodo lasciava silenziosamente
`navNotifica` a `null`, permettendo di salvare `notificaSend=true` con
`navNotifica` mancante — una configurazione incompleta accettata senza
errore. Corretto: se non si trova un candidato, `crea()` rifiuta
esplicitamente con `ValidazioneNonSuperataException`, richiedendo un
`navNotifica` indicato dal chiamante. La scelta automatica per il caso
solo-`ENTRO`/`OLTRE` resta un punto da chiarire (quale pendenza scegliere,
se ce ne può essere più di una anche qui) — per ora si preferisce rifiutare
piuttosto che indovinare.

2 nuovi test di regressione (33 totali): uno riproduce esattamente lo scenario
segnalato (due voci via `addVocePendenza`, verificato `indice` 1 e 2); uno
verifica il rifiuto con sole opzioni `ENTRO`.

## 11. Generazione IUV: SPI standard, progressivi, conversione da NAV (2026-09-23)

Il lead ha messo in discussione l'architettura di `GeneratoreIuv` confrontandola
punto per punto con il generatore legacy (`IuvBD`/`VersamentoUtils`), con 4
osservazioni verificate sul codice del monorepo prima di essere accolte:

1. **NAV fornito dal chiamante → si ricava l'IUV, non si genera una coppia.**
   Confermato in `VersamentoUtils` (righe ~798-810): se `numeroAvviso` è
   presente, l'IUV si estrae per formato (`getIuvFromNumeroAvviso`) e si valida
   (`checkIUV`), senza toccare alcun progressivo. È un percorso distinto dalla
   generazione, non la stessa operazione con un ramo diverso.
2. **Algoritmo di generazione**: aux digit 0/1/2/3, check digit mod-93,
   prefisso dominio — confermato leggendo `IuvBD.generaIuv` per intero.
3. **Progressivo a blocchi**: `IuvBD.getNextPrgIuv` usa
   `org.openspcoop2.utils.id.serial.IDSerialGenerator` con `sizeBuffer(100)` e
   chiave `codDominio+iuvPrefix+tipoIUV.toString()` — block reservation +
   buffer in memoria per JVM, non un semplice contatore per chiamata.
4. **Transazione separata**: quel generatore richiede una connessione JDBC con
   autocommit attivo su cui gestisce da solo commit/rollback — incompatibile
   con una transazione JPA aperta, da cui il commento legacy "l'utility di
   generazione non supporta le transazioni" e l'apertura di una connessione
   dedicata quando il chiamante è già in una transazione.

**Decisione: non riusare la libreria openspcoop2, riusare le garanzie e — dove
possibile — la tabella fisica.** Verificato che `ID_MESSAGGIO_RELATIVO`
(`PROTOCOLLO='GovPay'`, `INFO_ASSOCIATA=codDominio+iuvPrefix+tipoIUV`) è già
parte dello schema proprio di GovPay (`gov_pay.sql`, non solo ereditata da
openspcoop2/govway), ed è condivisa con un secondo consumatore reale
(`TracciatiNotificaPagamentiBD.getNextPrgTracciato`, stesso `PROTOCOLLO`,
chiave `codDominio+tipoTracciato`) — disambiguazione solo per convenzione sui
valori delle stringhe, nessun vincolo strutturale. Confermato anche che non ci
sarà mai coesistenza temporale vecchio/nuovo sistema (cutover netto, coerente
con la scelta già presa di non gestire la retrocompatibilità), quindi il
rischio di due strategie di lock diverse sulla stessa riga non si pone.

Continuare a scrivere sulla **stessa riga** (stessa chiave, `PROTOCOLLO`,
`INFO_ASSOCIATA`) invece di copiarne il valore in una tabella nuova elimina
anche la finestra di corsa del cutover (uno snapshot letto e poi copiato può
essere già superato se il vecchio sistema emette un altro IUV nel frattempo;
continuare sulla riga esistente non ha questo problema perché non c'è
snapshot da tenere allineato) — **nessun passo di migrazione dedicato ai
progressivi IUV è quindi necessario**, a differenza di quanto ipotizzato in
un primo momento.

**Implementato**, nuovo package `iuv/`:

- `entity/ProgressivoIuv` + `entity/ProgressivoIuvId` — mappano
  `id_messaggio_relativo` così com'è (stessa tabella, stesse colonne
  `counter`/`protocollo`/`info_associata`), non una tabella nuova.
- `repository/ProgressivoIuvRepository.findByIdForUpdate` — lettura con
  `@Lock(PESSIMISTIC_WRITE)`, equivalente nativo JPA del `SELECT ... FOR
  UPDATE` legacy.
- `iuv/AllocatoreBloccoProgressivoIuv.allocaBlocco` —
  `@Transactional(REQUIRES_NEW)`: transazione dedicata, equivalente nativo
  JPA della connessione separata legacy (un rollback della
  `PosizioneDebitoria` non fa tornare indietro un progressivo già
  consegnato). Classe separata da `GeneratoreProgressivoIuv` apposta:
  `@Transactional` su self-invocation non passerebbe dal proxy Spring.
- `iuv/GeneratoreProgressivoIuv.prossimoValore` — buffer in memoria per
  chiave (`Map<String, Deque<Long>>`), stesso principio del buffer statico
  per JVM legacy; ritenta una volta sola se la prima allocazione per una
  chiave nuova va in conflitto (due chiamate concorrenti che tentano
  entrambe l'INSERT iniziale).
- `iuv/CostruttoreIdentificativiPagamento` — porting fedele
  dell'algoritmo (aux digit, check digit mod-93, formato NAV), verificato
  con valori attesi calcolati indipendentemente (non solo coerenza interna
  fra generazione e conversione). Include anche `convertiDaNumeroAvviso`,
  che ricava e valida (check digit ricalcolato) l'IUV da un NAV fornito,
  senza consumare progressivi.
- `iuv/GeneratoreIuvStandard implements GeneratoreIuv` — legge
  `DominioEntity`/`StazioneEntity` da `govpay-common` (dipendenza di
  libreria, non relazione JPA — M4 vieta solo la seconda), implementazione
  condivisa invece di lasciare l'intero algoritmo a ogni consumatore.
  Registrata da `PendenzeAutoConfiguration` come bean
  `@ConditionalOnMissingBean(GeneratoreIuv.class)` +
  `@ConditionalOnBean(DominioRepository.class)`: attiva solo se il
  consumatore ha già l'anagrafica di govpay-common nel contesto e non ha
  fornito una propria implementazione.

**SPI estesa**: `GeneratoreIuv.convertiDaNumeroAvviso(idDominio,
numeroAvviso)`, `default` (implementazione di base: `UnsupportedOperationException`)
così l'interfaccia resta lambda-compatibile per chi vuole implementare solo
`genera`. `PosizioneDebitoriaService.assegnaIdentificativiPagamento`
distingue ora tre casi: entrambi assenti → genera; solo `numeroAvviso` →
converte; solo `iuv` → rifiuta (nessun percorso legacy verificato per
ricostruire il NAV dal solo IUV, non se ne inventa uno).

**Bug trovato durante l'implementazione (non di logica, di configurazione
di test) e corretto**: lo schema di test H2 (`schema-pendenze-test.sql`) non
era idempotente (`CREATE TABLE`/`CREATE SEQUENCE` senza `IF NOT EXISTS`).
Innocuo finché ogni test apriva una sola connessione; `AllocatoreBloccoProgressivoIuv`
ne apre una seconda (`REQUIRES_NEW`), e H2 riesegue lo script `INIT` su
**ogni** nuova connessione alla stessa URL, non solo alla prima — la seconda
falliva su "already exists" e HikariCP passava 30s a ritentare la creazione
della connessione prima di arrendersi. Corretto rendendo idempotenti tutte le
istruzioni dello schema.

20 nuovi test (53 totali): `CostruttoreIdentificativiPagamentoTest` (11, puro
algoritmo, nessuna dipendenza Spring), `GeneratoreProgressivoIuvTest` (4,
inclusa la prova che la libreria continua da un valore già presente nella
riga — il punto centrale della scelta di riuso della tabella),
`GeneratoreIuvStandardTest` (4, contro anagrafica reale di govpay-common),
`PosizioneDebitoriaServiceConGeneratoreIuvTest` (1 nuovo: conversione da
NAV fornito).

**Punto aperto tracciato, non bloccante**: verificare con chi gestisce la
versione precedente se esistono altri utilizzatori di `ID_MESSAGGIO_RELATIVO`
con `PROTOCOLLO='GovPay'` oltre a `IuvBD`/`TracciatiNotificaPagamentiBD`, per
escludere collisioni di chiave non ancora note.

## 12. Due bug e una lacuna in `GeneratoreIuvStandard`/`CostruttoreIdentificativiPagamento`, trovati in revisione (2026-09-23/24)

Tutti scoperti dal lead con casi concreti riprodotti, verificati sul codice
legacy prima di essere corretti.

**1. I prefissi dinamici del vecchio GovPay non venivano risolti.** Il
prefisso configurato sul dominio veniva usato letteralmente, senza
sostituire i placeholder `%(a)`/`%(p)`/`%(t)`/`%(y)`/`%(Y)`. Verificato in
`CustomIuv.buildPrefix` (delimitatori `%(`/`)`, valori da
`PagamentoContext.getAllIuvProps`) e in `Iuv.generaIUV`, che risolve il
prefisso **prima** di chiamare `IuvBD.generaIuv` — quindi prima sia della
chiave del contatore sia della costruzione dello IUV — e valida
esplicitamente che il risultato sia numerico, con un errore chiaro invece di
un `NumberFormatException` grezzo. Riprodotto il caso `%(y)`: la nuova
generazione terminava con `NumberFormatException`. Corretto: nuova classe
`iuv/RisolutorePrefissoIuv` (porting senza la dipendenza da
`commons-text.StringSubstitutor`, non necessaria per un set fisso di
chiavi), che risolve `%(Y)`/`%(y)` (anno, dall'orologio della libreria) e
`%(a)` (`Applicazione.codApplicazioneIuv`, cercata per `idA2A` — confermato
dal lead: `idA2A` è esattamente `Applicazione.codApplicazione`)
prima di usare il prefisso sia per la chiave del contatore sia per lo IUV.
Un placeholder non risolvibile solleva un errore esplicito che lo nomina,
invece di restare letterale nel testo.

**2. Il controllo di lunghezza della reference usava sempre il limite di 15
caratteri.** `costruisciReference` rifiutava solo se `reference.length() >
15`, anche quando la reference deve essere di 13 (AuxDigit 0 e 3). Caso
riprodotto: AuxDigit 3, segregazione 12, prefisso `123456789012` (12 cifre),
progressivo 10 → NAV di 19 cifre invece di 18. Difetto ereditato dal vecchio
`IuvBD.generaIuv` (stesso controllo sempre `>15`), ma non riportato qui.
Corretto: il controllo usa ora `lunghezzaTotale` (il parametro già passato
alla chiamata, 13 o 15 secondo l'AuxDigit).

**3. `%(p)`/`%(t)` (codifica del tipo pendenza) inizialmente non
supportati.** Nel legacy rappresentano `TipoVersamento.codificaIuv`
(`PagamentoContext`: `%(p)` e `%(t)` sono alias storici dello stesso
valore). Non risolvibili da questa libreria da sola: `govpay-common` non
espone un'anagrafica tipo-versamento (M4). Prima soluzione (rifiuto
esplicito) lasciava gli enti che usano questi prefissi bloccati dopo il
passaggio alla v3 — segnalato esplicitamente dal lead come insufficiente.
Corretto estendendo la SPI: `GeneratoreIuv.genera` ha ora un quarto
parametro, `codificaIuvTipoPendenza`, sorgente un nuovo campo **transiente**
(mai persistito) `Pendenza.codificaIuvTipoPendenza` — il chiamante che
conosce quel valore (fuori da questa libreria, dove vive l'anagrafica
tipo-versamento) lo imposta sulla pendenza prima di `crea()`. Usando il
prefisso risolto per costruire sia la chiave del progressivo sia lo IUV
(già stabilito al punto 1), codifiche diverse sullo stesso dominio ottengono
progressivi indipendenti — preservando la stessa composizione della chiave
del legacy (`codDominio+prefix+tipo`).

9 nuovi test (65 totali): `CostruttoreIdentificativiPagamentoTest` (+1, la
riproduzione esatta del caso AuxDigit 3/prefisso 12 cifre/progressivo 10),
`GeneratoreIuvStandardTest` (+6: risoluzione `%(y)`/`%(a)`/`%(p)`, alias
`%(t)`, rifiuto esplicito senza valore fornito, progressivi indipendenti per
codifiche diverse), `RisolutorePrefissoIuvTest` (nuovo, 5 test puri).

**Confermato dal lead (24/09)**: `idA2A` ≡ `Applicazione.codApplicazione`
(non più un punto aperto).

## 13. Ricerca, criteri, paginazione (2026-09-24)

Nuovo package `criteri/`:

- `OffsetPageRequest implements Pageable` — paginazione a scorrimento
  libero (offset/limit, standard AGID RAC_REST_NAME_005), non a pagine
  allineate come `PageRequest` (che ammette solo `offset = pagina *
  dimensione`): il cliente può avanzare l'offset di un valore qualunque
  seguendo `prossimiRisultati`, non necessariamente un multiplo di `limit`.
- `CriteriOrdinamento` — analizza il parametro di query `sort`
  (`+campo,-campo2`) contro una mappa esplicita di campi ordinabili fornita
  dal chiamante (nome esterno → percorso JPA): nessun campo ordinabile per
  default, un nome non riconosciuto è un errore esplicito
  (`ValidazioneNonSuperataException`), non un ordinamento arbitrario su un
  percorso interno non previsto.

Verificati sui due endpoint di ricerca reali dello YAML v3 (non inventati):

- `GET /posizioni-debitorie/{idA2A}` — `idDebitore` è l'**unico** criterio di
  ricerca ammesso (query, obbligatorio), oltre a `idA2A` (path). Trova la
  posizione se l'identificativo corrisponde a **qualunque** soggetto in
  `soggettiDebitori`, non solo al primo (debitori in solido). Implementato:
  `PosizioneDebitoriaRepository.findDistinctByIdA2AAndSoggettiDebitori_Identificativo`,
  `PosizioneDebitoriaService.cercaPerDebitore`.
- `GET /pendenze/{idA2A}` — `numeroAvviso` è l'**unico** criterio,
  obbligatorio; `idDominio` è un filtro aggiuntivo opzionale, utilizzabile
  solo insieme a `numeroAvviso` (mai da solo, per esplicita indicazione
  dello YAML). Senza `idDominio` può restituire più risultati, perché lo
  stesso NAV può esistere legittimamente su domini diversi (M13).
  Implementato: due varianti in `PendenzaRepository` (con/senza filtro
  dominio), `PosizioneDebitoriaService.cercaPendenze`.

**Non implementato**: il parametro di query `fields` (proiezione parziale
dei campi in risposta) — è un problema di serializzazione JSON lato API,
non di accesso ai dati, fuori perimetro di questa libreria.

14 nuovi test (79 totali): `OffsetPageRequestTest` (5), `CriteriOrdinamentoTest`
(6), 3 di integrazione in `PosizioneDebitoriaServiceTest` contro il DB reale
(incluso uno che dimostra esplicitamente M13: stesso NAV su domini diversi,
trovato senza filtro, ristretto a uno con `idDominio`).

**Bug trovato dal lead e corretto (2026-09-24): `hasNext()` poteva risultare
`true` con i risultati già esauriti.** `Page`/`Pageable` di Spring Data sono
pensati per pagine allineate: `PageImpl.hasNext()` si basa su `getNumber()+1
< getTotalPages()`, e `OffsetPageRequest.getPageNumber()` può solo
approssimare (`offset / limit` troncato) un offset arbitrario non allineato.
Riprodotto: 3 risultati totali, offset 1, limit 2 → restituiti gli ultimi
due, ma `hasNext()` risultava `true` (avrebbe prodotto un `prossimiRisultati`
verso una pagina vuota). Non risolvibile facendo tornare corretto
`getPageNumber()` in generale: non esiste un numero di pagina che renda
`PageImpl.hasNext()` corretto per un offset qualunque. Corretto introducendo
`criteri/PaginaRisultati<T>` (record: `risultati`, `offset`, `limit`,
`numeroRisultatiTotali`), il cui `haAltriRisultati()` usa direttamente
`offset + risultati.size() < numeroRisultatiTotali` — corretto per qualunque
offset. `PosizioneDebitoriaService.cercaPerDebitore`/`cercaPendenze` ora
restituiscono `PaginaRisultati<T>`, non più `Page<T>`: gli unici dati letti
da `Page` sono `getContent()`/`getTotalElements()` (affidabili, vengono da
una query di conteggio separata), mai i suoi metodi derivati. 4 nuovi test
(96 totali): `PaginaRisultatiTest` (3, puri), 1 in `PosizioneDebitoriaServiceTest`
che riproduce esattamente lo scenario segnalato contro il DB reale.

## 14. `dettaglioContabile` (2026-09-24)

Riconciliazione contabile pagoPA (Dizionario dei metadata, issue #877 dello
YAML v3), rimandata esplicitamente al §5. Attaccata a `VocePendenza` di tipo
`RIFERIMENTO_ENTRATA`/`ENTRATA` (mai `BOLLO`, che si classifica solo tramite
`tassonomia`).

**Modello** (`model/DettaglioContabile`): sealed interface, 5 record — 4
scrivibili (`CorrispettivoDl118`, `IncassoTipico`, `Civilistico`,
`SpeseNotifica`) discriminati dal campo `tipo`, più `Sconosciuto`
(`UNKNOWN_ENTRIES`) di sola lettura, mai accettata in scrittura (fallback
per metadata di un intermediario/tecnologia terza).

**Persistenza** (`model/DettaglioContabileConverter`): `AttributeConverter`
JPA, JSON in colonna `voci_pendenza.dettaglio_contabile`
(`@JdbcTypeCode(SqlTypes.LONGVARCHAR)`, stesso principio di
`configurazione.valore` in govpay-common). A differenza del vecchio
`ProprietaPendenzaCodec` (dati storici, decodifica tollerante con log a WARN
su JSON malformato), qui un JSON illeggibile è un bug di questa libreria,
non un dato esterno sporco: nessuna tolleranza, fallisce esplicitamente.

**Bug Jackson 3 trovato e corretto durante l'implementazione**:
`ObjectMapper.writeValueAsString(Object)` su una `List<DettaglioContabile>`
perde il discriminatore polimorfico `tipo`. Causa: per l'erasure dei
generici, Jackson risolve il serializzatore di ogni elemento sulla sua
classe concreta (es. `Civilistico`), non sul tipo dichiarato della lista
(`DettaglioContabile`, dove vive `@JsonTypeInfo`) — verificato con un test
diagnostico standalone: funziona per un singolo valore dichiarato come
`DettaglioContabile`, sparisce per gli elementi di una lista. La lettura
falliva poi con "missing type id property". Corretto usando
`writerFor(JavaType)` esplicito in scrittura, simmetrico a
`readValue(dbData, JavaType)` già usato in lettura.

**Validazioni** (`ValidatorePosizioneDebitoria`): `dettaglioContabile`
vietato su voci `BOLLO`; `CORRISPETTIVO_DL118`/`CIVILISTICO` richiedono
esattamente uno tra i rispettivi campi alternativi (`capitolo`/
`accertamento`/`pianoFinanziario5Livello`; `conto`/`commessa`/
`nrDocumento`); `INCASSO_TIPICO` non ammette sia `emissioneFattura` sia
`nrDocumento` insieme; `UNKNOWN_ENTRIES` sempre rifiutato in scrittura;
`notificaSend` attivo con una voce che ha già `SPESE_NOTIFICA` rifiutato
(altrimenti le spese verrebbero applicate due volte — semantica esplicita
dello YAML v3). Non validati i vincoli di lunghezza dei singoli campi (già
espressi dallo schema JSON, non compito di questa libreria).

13 nuovi test (92 totali): `DettaglioContabileConverterTest` (4, incluso il
round trip di `Sconosciuto`), 9 nuovi in `ValidatorePosizioneDebitoriaTest`,
1 in `PosizioneDebitoriaMappingTest` (round trip attraverso il DB reale).

## 15. Utility IUV pure spostate in `govpay-common` (2026-09-24)

Proposta del lead: le funzioni senza dipendenza da DB/Spring, e funzionalmente
indipendenti dal modello di dominio delle pendenze (non solo prive di
persistenza), vanno in `govpay-common`, non duplicate/isolate qui — riusabili
da chiunque debba solo costruire/validare un formato IUV o sostituire
segnaposto in un testo, senza tirarsi dietro questa libreria.

**Verificato package per package** quali funzioni qualificano:

- **`CostruttoreIdentificativiPagamento`** (algoritmo aux digit/check digit
  mod-93/costruzione-decodifica IUV-NAV) → **spostato in
  `it.govpay.common.utils.IuvUtils`** (`genera`/`convertiDaNumeroAvviso`,
  nuovo record annidato `IuvUtils.IdentificativiPagamento`). Non è
  "processing generico basato su parametri": il "93", l'ordine di
  concatenazione e i vincoli di lunghezza per AuxDigit sono la formula
  pagoPA, non riusabili per altro — ma è comunque IUV, e `govpay-common` ne
  possiede già il concetto (`IuvUtils.isIuvInterno`/`isNumeric`): consolidare
  in un solo posto invece di spezzare la stessa competenza su due librerie.
  L'eccezione di formato non valido diventa `IllegalArgumentException`
  (prima `ValidazioneNonSuperataException`, specifica di questa libreria):
  `govpay-common` non ha un'eccezione di validazione propria, e
  `IllegalArgumentException` è idiomatica per una utility di basso livello.
- **`RisolutorePrefissoIuv`** → **spostato in
  `it.govpay.common.utils.RisolutoreSegnaposto`, anonimizzato**: il suo
  `risolvi(String, Map<String,String>)` è sostituzione di segnaposto
  `%(chiave)` del tutto generica (pattern e mappa sono entrambi parametri
  del chiamante), senza alcuna conoscenza di IUV — il nome "PrefissoIuv"
  descriveva solo chi la chiamava, non cosa faceva. Rinominata per non
  portare un nome fuorviante in una libreria dove non ha piu' alcun legame
  con IUV. Stessa eccezione (`IllegalArgumentException`) per un placeholder
  non risolvibile.

**Cosa resta in questa libreria** (richiede DB/Spring, non movibile):
`GeneratoreIuvStandard`, `GeneratoreProgressivoIuv`,
`AllocatoreBloccoProgressivoIuv`, `ProgressivoIuv`/`ProgressivoIuvId`/
`ProgressivoIuvRepository`. Anche la SPI `GeneratoreIuv` resta qui: lega
generazione a progressivo/DB e al parametro `codificaIuvTipoPendenza`, non è
un'utility pura.

`GeneratoreIuvStandard.genera`/`convertiDaNumeroAvviso` ora delegano a
`IuvUtils`, adattando il record di ritorno (`IuvUtils.IdentificativiPagamento`
→ `it.govpay.pendenze.spi.IdentificativiPagamento`, la SPI resta con il
proprio tipo). `risolviPrefix` usa `RisolutoreSegnaposto` al posto della
classe locale ora rimossa.

**Coordinamento fra i due repository**: cambiamento in `govpay-common`
(stessa versione `2.0.4-SNAPSHOT`, nessun bump necessario — è già uno
snapshot in evoluzione), installato in locale (`mvn install -DskipTests`)
per verificare l'integrazione prima che il lead lo pubblichi davvero. 27
nuovi test in `govpay-common` (`IuvUtilsTest` +12, `RisolutoreSegnapostoTest`
nuovo, 4 — totale libreria 619 test verdi). In `govpay-common-pendenze`:
rimossi `CostruttoreIdentificativiPagamento`/`RisolutorePrefissoIuv` e i
rispettivi test (17 test), 79 totali (da 96).

## 16. Ricevute e rendicontazioni (2026-09-24)

**Perimetro confermato prima di implementare**: la decisione A1 del disegno
abbandonato (`proposta-libreria-pendenze.md`) — "RPT e pagamenti non nel
dettaglio: li richiede il consumatore" — e la conferma diretta del lead sul
vecchio "dettaglio pendenza": caricava tutto (ricevute, rendicontazioni,
proprietà) insieme al resto, producendo circa 200 query per una singola
lettura. In v3 questo non deve ripetersi: `Ricevuta`/`Rendicontazione`/
`FlussoRendicontazione` restano **fuori dall'aggregato `PosizioneDebitoria`**
— `idPendenza` è una FK piatta (`Long`), non una relazione JPA; `Pendenza` non
ha una collezione `@OneToMany` verso queste entità. Sono risorse indipendenti,
interrogate solo su richiesta esplicita, tramite il nuovo
`RicevutaRendicontazioneService` (letture soltanto — nessuna scrittura: questa
libreria non acquisisce flussi pagoPA, lo farà un batch/consumer dedicato che
userà i repository direttamente, senza regole di validazione da rispettare,
il contenuto è prodotto integralmente da pagoPA).

**`FlussoRendicontazione` è un'entità separata**, non incorporata per riga in
`Rendicontazione` (a differenza di una prima ipotesi): un flusso raggruppa più
rendicontazioni, come nel legacy (`fr`/`rendicontazioni`, FK
`rendicontazioni.id_fr`); incorporarlo avrebbe duplicato inutilmente gli
stessi dati di testata. `Rendicontazione.flusso` è invece una vera relazione
JPA (`@ManyToOne`) — a differenza di `idPendenza` — perché
`Rendicontazione`/`FlussoRendicontazione` formano una loro piccola coppia di
aggregato, separata da `PosizioneDebitoria`.

**`Ricevuta.contenuto` conserva l'XML grezzo esattamente come nel legacy**,
non convertito in JSON. Prima ipotesi (poi corretta su indicazione esplicita
del lead): siccome lo YAML v3 espone `Ricevuta` in forma JSON, salvare già in
quella forma sembrava naturale. Sbagliato: l'istruzione "manteniamo il più
possibile i formati per semplificare la migrazione" riguardava lo *storage*,
non la forma esposta dall'API — con l'XML grezzo la migrazione dei dati
esistenti resta una copia diretta del blob (come `rpt.xml_rt BYTEA`), mentre
con JSON avrebbe richiesto scrivere e validare una vera conversione
XML→JSON in fase di migrazione. La conversione in JSON per l'endpoint di
lettura si sposta quindi a runtime, nel livello che espone
`GET .../ricevute`, fuori da questa libreria. Nessuna gerarchia di
entità/record tipizzati per le tre varianti (`ctRicevutaTelematica`/
`ctReceipt`/`ctReceiptV2`): questa libreria non valida il contenuto, lo
conserva e lo restituisce così com'è (a differenza di `DettaglioContabile`,
che ha vere regole di business da far rispettare).

**Tre bug trovati dal lead in revisione e corretti**:

- **Le revisioni dello stesso flusso non erano conservabili.** Verificato in
  `Rendicontazioni.java:718` (business layer legacy): una nuova acquisizione
  con lo stesso `cod_dominio`+`cod_flusso` non sovrascrive la riga esistente,
  ne inserisce una nuova con `revisione` incrementata, marcando obsoleta
  quella precedente (`fr.obsoleto`/`fr.revisione`, vincoli `unique_fr_1`
  `(cod_dominio,cod_flusso,data_ora_flusso)` / `unique_fr_2`
  `(cod_dominio,cod_flusso,cod_psp,revisione)`). Il vincolo iniziale su
  `FlussoRendicontazione`, univoco per sola coppia dominio+identificativo,
  impediva sia di importare tutte le revisioni storiche sia di acquisirne una
  nuova senza sovrascrivere la testata precedente. Corretto aggiungendo
  `revisione`/`obsoleto` all'entità e sostituendo il vincolo con i due
  equivalenti legacy; il metodo di lookup diventa
  `findByIdDominioAndIdFlussoAndObsoletoFalse` (equivalente al legacy
  `FrBD.getFr(dominio, flusso)`, che filtra implicitamente `obsoleto=false`).
- **`Rendicontazione.getFlusso()` lanciava `LazyInitializationException` fuori
  transazione.** Riprodotto dal lead. `flusso` è `@ManyToOne(fetch = LAZY)`;
  senza un caricamento esplicito, leggerlo dopo il ritorno dal servizio
  fallisce. I test iniziali non lo intercettavano perché la transazione di
  test restava aperta. Corretto con `@EntityGraph(attributePaths = "flusso")`
  su `RendicontazioneRepository.findByIdPendenza` (fetch join nella stessa
  query, non un eager permanente sull'entità). Verificato con
  `Hibernate.isInitialized(...)`, non con la sola lettura del campo (che il
  bug avrebbe potuto nascondere di nuovo dentro la transazione di test).
- **L'elenco delle ricevute caricava anche il contenuto XML di ognuna.** Lo
  YAML v3 espone nell'elenco solo `iur`/`tipo`/`data`; il dettaglio si
  recupera separatamente per singola ricevuta. `RicevutaRepository`
  restituiva entità complete. Corretto con una proiezione dedicata
  (`RicevutaElenco`, interfaccia con i tre soli accessori) e una query
  esplicita (`findElencoByIdPendenza`) che seleziona solo quelle colonne,
  evitando di trasferire e allocare gli XML non richiesti.

8 nuovi test (87 totali, da 79): `RicevutaRendicontazioneMappingTest` (4, di
cui una — `piuRevisioniDelloStessoFlussoCoesistono` — riproduce lo scenario
delle revisioni multiple), `RicevutaRendicontazioneServiceTest` (4, di cui
una — `cercaRendicontazioniInizializzaIlFlusso` — verifica il fetch join con
`Hibernate.isInitialized`).

## 17. Riuso diretto delle tabelle legacy, non uno schema v3 separato (2026-09-25)

**Decisione che sostituisce §§1-16**: invece delle tabelle nuove `posizioni_debitorie`/
`pendenze`/`voci_pendenza` (con `opzioni_pagamento`/`ricevute`/`rendicontazioni`/
`flussi_rendicontazione` come già descritto), l'aggregato centrale riusa
column-per-column le tabelle legacy reali:

- **`PosizioneDebitoria` → `documenti`**: 7 colonne additive (`id_unita_operativa`,
  `notifica_send`, `nav_notifica`, `data_ultima_modifica_aca`,
  `data_ultima_comunicazione_aca`, `data_creazione`, `data_ultimo_aggiornamento`).
- **`Pendenza` → `versamenti`** (~50 colonne mappate su ~65 reali): 3 colonne
  additive (`id_opzione_pagamento`, `numero_rata`, `data_caricamento`).
- **`VocePendenza` → `singoli_versamenti`**: 5 colonne additive (`tipo_riferimento`,
  `cod_entrata`, `iban_accredito_v3`, `iban_appoggio_v3`, `tassonomia_v3`); riuso
  as-is di `tipo_bollo`/`hash_documento`/`provincia_residenza` e di `contabilita`
  (nuovo formato JSON, non sovrapposto al vecchio `Contabilita`/`QuotaContabilita`).
- **`OpzionePagamento`/`SoggettoDebitore`**: uniche tabelle davvero nuove
  (`opzioni_pagamento`/`soggetti_debitori`) — la macchina a stati delle opzioni non
  esiste in v2 in nessuna forma; i debitori in v2 vivono solo denormalizzati su
  `versamenti.debitore_*`, un solo soggetto per versamento.

**Motivazione**: minimizzare la differenza strutturale da v2 per ridurre al minimo
la migrazione dati e i problemi di retrocompatibilità — v2 e v3 scrivono le stesse
tabelle fisiche, un cliente è sempre sull'una o sull'altra API, mai su entrambe
contemporaneamente sullo stesso record. Principio M4 esteso: nessun vincolo FK
reale verso l'anagrafica esterna di govpay-common (`domini`/`applicazioni`/
`tipi_versamento`/`tipi_vers_domini`), anche dove il legacy reale ce l'ha — coerenza
con la scelta, già presa, di non avere relazioni JPA verso quelle tabelle.

**Cinque micro-decisioni di mapping**, prese una alla volta:

1. **Due FK per il tipo pendenza** (`idTipoPendenza`/`idTipoVersamento`): il legacy
   ha due livelli (`tipi_versamento` catalogo astratto, `tipi_vers_domini`
   istanza/override per dominio) — `versamenti.id_tipo_versamento` è una
   denormalizzazione di comodo. Il chiamante fornisce entrambi gli ID (M4 puro,
   nessuna query di questa libreria verso l'anagrafica esterna).
2. **`numero_rata` (nuova colonna) invece di `cod_rata`** (deprecata per v3, resta
   `NULL` sulle righe v3): `cod_rata` mischiava tipologia e posizione nello stesso
   formato, ambiguo per un lettore legacy.
3. **`data_caricamento` (nuova colonna)**: "data di emissione della pendenza" dello
   YAML v3, concetto distinto da `data_creazione` (timestamp tecnico di scrittura),
   senza equivalente nel legacy.
4. **`src_iuv`/`src_debitore_identificativo`**: denormalizzazioni `UPPERCASE` per
   la ricerca case-insensitive (verificato in `proposta-libreria-pendenze.md` §4.5),
   non copie di audit. Il legacy ha un bug su un solo percorso di scrittura, non
   replicato.
5. **`StatoPendenza` allineato agli 8 valori di `StatoVersamento`** (non i 6 dello
   YAML v3 attuale — da correggere nello YAML): `Pendenza.stato` mappa direttamente
   `versamenti.stato_versamento`, un valore diverso da quelli legacy farebbe fallire
   `valueOf(...)` su qualunque riga letta da codice legacy.

## 18. Unicità IUV/NAV per dominio e bug della cascade mancante (2026-09-25)

**IUV/NAV non hanno un vincolo DB**: verificato che `versamenti` ha solo un indice
non univoco su `(iuv_versamento, id_dominio)`, mai `UNIQUE` — il legacy la
garantisce solo a livello applicativo (`Versamento.java:184-189`, `VER_025`:
"due pendenze non possono avere lo stesso numero avviso"). Replicato in
`PosizioneDebitoriaService.verificaNumeroAvvisoNonDuplicato`: interroga il DB solo
quando il chiamante fornisce `numeroAvviso` esplicitamente (IUV/NAV generati da
`GeneratoreIuv` sono collision-free per costruzione, nessun controllo necessario).

**Bug della cascade mancante**: `Pendenza.voci` (`@OneToMany(mappedBy = "pendenza")`)
era priva di `cascade = CascadeType.ALL` — sintomo fuorviante: dopo `em.clear()` +
`em.find()`, collezioni `@OneToMany` *diverse e non correlate*
(`PosizioneDebitoria.soggettiDebitori`) risultavano vuote alla rilettura, non
`Pendenza.voci` stessa. Diverse ipotesi sbagliate esplorate (fetch eager,
`@OrderBy`, timing delle transazioni) prima di trovare la causa reale. Verificato
che tutte e 4 le `@OneToMany` della libreria abbiano `cascade = CascadeType.ALL`.

## 19. Fase 2: riuso di `rpt`/`pagamenti`/`fr`/`rendicontazioni` (2026-09-25/26)

Stesso principio di §17 esteso a `Ricevuta`/`Rendicontazione`/`FlussoRendicontazione`:

- **`Rpt` → `rpt`**: nessuna colonna aggiunta. **`iur` è fisicamente la colonna
  `ccp`** — "codice contesto pagamento", nomenclatura SANP storica dello stesso
  identificativo che le specifiche più recenti del Nodo chiamano `receiptId`,
  verificato nel business layer legacy (`RicevuteConverter.setIdRicevuta(rpt.getCcp())`,
  `QuietanzaPagamento.setCcp(pagamento.getIur())`, `CtReceiptUtils.setCcp(receiptId)`;
  il bean v2 `Ricevuta.idRicevuta` è documentato come "Corrisponde al `receiptId`
  oppure al `ccp`"). Quindi `(iuv, ccp, cod_dominio)` — il vincolo naturale reale —
  è esattamente `(iuv, iur, dominio)`: `Rpt` basta da sola per
  `GET .../ricevute/{iur}`, contraddicendo un'ipotesi precedente (poi abbandonata)
  che passava da `Pagamento.iur` pensando che `rpt` non avesse un IUR.
- **`Pagamento` → `pagamenti`**: mappata ma non agganciata alla feature ricevute
  (introdotta solo per l'ipotesi poi abbandonata) — resta disponibile per un uso
  futuro (es. una risorsa "riscossioni").
- **`FlussoRendicontazione` → `fr`**: porta sia `idDominio` (Long, M4) sia
  `codDominio` (String) — verificato che tutta la ricerca applicativa legacy su
  questo gruppo di tabelle (`FrBD`/`RptBD`/`PagamentiBD`) avviene sempre per
  *codice* del dominio, mai per id numerico (colonna presente sui bean ma mai usata
  come parametro di ricerca). `rpt`/`pagamenti` non hanno nemmeno una colonna
  `id_dominio`: `codDominio` era già l'unica opzione lì.
- **`Rendicontazione` → `rendicontazioni`**: **non ha `id_pendenza`** (ipotesi
  iniziale sbagliata) — il legacy correla solo per `iuv` (stringa). La ricerca
  ({@code cercaRendicontazioni}) filtra per `iuv` **e per `flusso.codDominio`**
  (bug trovato in revisione indipendente, §20): lo IUV è univoco solo per dominio,
  non globalmente.

`TipoRicevuta` (model) rimosso: `Rpt.versione` conserva il valore legacy grezzo di
`VersioneRPT`, la mappatura sui valori dello YAML v3 è responsabilità del livello
API, non più un campo tipizzato di questa libreria.

## 20. Cinque bug trovati in revisione indipendente (2026-09-26)

1. **Rendicontazioni cercate senza filtro dominio** — vedi §19, corretto con
   `RendicontazioneRepository.findByIuvAndFlusso_CodDominio`, risolvendo
   `codDominio` dall'`idDominio` della pendenza tramite `DominioRepository` di
   govpay-common.
2. **`srcDebitoreIdentificativo` mai inizializzato**: `NOT NULL` ma nessun codice lo
   valorizzava — `crea()` falliva con `DataIntegrityViolationException`. Corretto
   con lo stesso placeholder fisso di `debitoreIdentificativo` (§21).
3. **NAV duplicato accettato nello stesso aggregato in ingresso**: il controllo di
   `verificaNumeroAvvisoNonDuplicato` (§18) confronta solo contro il DB — due
   pendenze duplicate nella stessa richiesta lo superano entrambe (nessuna delle
   due è ancora persistita quando vengono verificate). Corretto con un controllo
   puramente in memoria in `ValidatorePosizioneDebitoria`, prima di qualunque
   accesso al DB.
4. **Elenco/dettaglio ricevute includevano RPT senza ricevuta acquisita**:
   `RptRepository` selezionava tutte le righe del versamento, incluse quelle con
   `xml_rt`/`data_msg_ricevuta` ancora `NULL` (richiesta di pagamento inviata, RT
   non ancora arrivata). Corretto filtrando `dataMsgRicevuta is not null`.
5. **`idPosizioneDebitoria` non univoco rispetto alla ricerca pubblica** — vedi §22.

## 21. Debitore denormalizzato su `versamenti`: placeholder fissi, non sincronizzati (2026-09-26)

**Decisione finale, dopo un'inversione**: `debitoreTipo`/`debitoreIdentificativo`/
`debitoreAnagrafica`/`srcDebitoreIdentificativo` (quest'ultimo `NOT NULL`,
`UPPERCASE(debitoreIdentificativo)`) sono valorizzati con **placeholder fissi ed
espliciti** (`"VEDERE_SOGGETTI_DEBITORI"`/`"Vedere tabella soggetti_debitori"`),
non sincronizzati col soggetto di ordine 0 di `soggettiDebitori`. `debitoreTipo`
(nullable) resta indefinito.

Percorso della decisione: inizialmente si era detto di sincronizzarli dal soggetto
di ordine 0 a ogni scrittura (fonte di verità v3 = `soggetti_debitori`). Poi si è
notato che `soggettiDebitori` resta modificabile dopo la creazione (PATCH, §5/
sviluppo successivo) e tenerli allineati nel tempo sarebbe complessità pura — da
qui i placeholder. Verificando poi se fosse sicuro farlo, è emerso che il motore
di pagamento **legacy** (attivazione RPT verso il Nodo,
`CtPaymentPABuilder.buildSoggettoPagatore`; stampa dell'avviso PDF,
`AvvisoPagamentoUtils.impostaAnagraficaDebitore`) legge queste colonne
direttamente, per qualunque pendenza indipendentemente da chi l'ha creata — non
un'opzione applicativa v2 come `id_documento` (§17, mai popolato: letto solo da
una funzionalità v2 opzionale che un cliente su v3 non esegue più). Un placeholder
lì produrrebbe un pagamento con il debitore sbagliato.

**Decisione finale**, comunque per i placeholder: quella pipeline legacy è essa
stessa parte di ciò che verrà sostituito/adattato a fine transizione v3 (quando
leggerà `soggetti_debitori` direttamente). Fino ad allora resta un gap noto e
accettato per l'attivazione di un pagamento reale su una pendenza v3 attraverso
l'endpoint non ancora adattato — non qualcosa che questa libreria deve compensare
fingendo un dato che potrebbe comunque disallinearsi.

## 22. Unicità pubblica di `idPosizioneDebitoria` (2026-09-26)

Il vincolo legacy reale su `documenti` è `(cod_documento, id_applicazione,
id_dominio)`, ma l'identità pubblica dello YAML v3 (`GET`/`POST`
`/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}`, 409 "Esiste già...") è
chiavata solo su `idA2A`+`idPosizioneDebitoria`, senza dominio. Due posizioni con
lo stesso identificativo su domini diversi passavano entrambe la creazione, ma la
lettura per identificativo (`Optional`) falliva con
`IncorrectResultSizeDataAccessException`.

Corretto su due livelli:

- **Vincolo DB nuovo**, in migrazione: `unique_documenti_applicazione
  (cod_documento, id_applicazione)`, senza `id_dominio` — il vincolo storico
  `unique_documenti_1` resta, ridondante ma innocuo.
- **`crea()`**: il controllo applicativo (`existsByIdApplicazioneAndIdPosizioneDebitoria`)
  resta per il fallimento rapido nel caso comune (non atomico, check-then-act); la
  garanzia reale contro le creazioni concorrenti è il catch della violazione del
  vincolo DB (`DataIntegrityViolationException` → `RisorsaGiaEsistenteException`,
  nuova eccezione).

Nessuna difesa aggiuntiva necessaria in lettura: eventuali duplicati legacy
preesistenti farebbero fallire l'`ALTER TABLE ADD CONSTRAINT` in fase di
migrazione stessa (script diagnostico incluso come commento), prima che qualunque
codice v3 possa girare su quel DB — e senza la migrazione applicata,
`ddl-auto=validate` fallirebbe comunque all'avvio per le colonne mancanti.

> **Superato (2026-10-24) — vedi §28.** Il vincolo `unique_documenti_applicazione`
> descritto qui sopra è stato rimosso: non serve più. `idPosizioneDebitoria` non è
> più la business key del chiamante, è l'IUPD (standard pagoPA GPD) calcolato da
> `idA2A`+`idDominio`+`codDocumento` — un identificativo che incorpora già tutte e
> tre le colonne del vincolo legacy `unique_documenti_1`, quindi non può mai
> collidere senza che quel vincolo lo impedisca già. Due posizioni con lo stesso
> `codDocumento`+`idA2A` su domini diversi sono di nuovo ammesse (comportamento
> legacy, mai stato un problema reale — il problema era solo di UX della ricerca
> pubblica, risolto diversamente, vedi §28).

## 23. Script di migrazione SQL per un DB v2 esistente (2026-09-26)

`sql/migrazione/` nel repository (non parte della catena di patch versionate del
core GovPay, `src/main/resources/db/sql/<dialetto>/patch` — script a sé stanti,
solo PostgreSQL, da eseguire una sola volta in ordine di numerazione):

- `01_documenti.sql`, `04_versamenti.sql`, `05_singoli_versamenti.sql`: colonne
  additive di §17, con **sentinelle esplicite** per le righe v2 esistenti su
  colonne `NOT NULL` nuove (`1970-01-01` per le date, `-1` per `numero_rata`) —
  un valore ovviamente non plausibile come dato reale, mai un valore verosimile
  come `CURRENT_TIMESTAMP` che potrebbe passare per dato genuino. `01_documenti.sql`
  include anche il vincolo di unicità di §22.
- `02_opzioni_pagamento.sql`, `03_soggetti_debitori.sql`: le due tabelle nuove,
  nessuna riga esistente da migrare.
- `rpt`/`pagamenti`/`fr`/`rendicontazioni` non compaiono: nessuna modifica
  necessaria, tutto quello che serve a v3 esiste già in produzione (§19).

## 24. `PosizioneDebitoria.dataPubblicazione` ripristinato (2026-09-26)

Presente fin dal primissimo disegno (§3.1: `LocalDate`, nullable = "pubblicata
subito"), perso silenziosamente durante il pivot al riuso delle tabelle legacy
(§17) — nel ricostruire l'elenco colonne di `documenti` per quel pivot non è
stato confrontato sistematicamente con la vecchia tabella §3.1, e questo campo
(senza un aggancio ovvio a una colonna legacy) è rimasto fuori senza che ce ne
accorgessimo. Individuato lavorando su `govpay-pendenze-api`, quando
`NuovaPosizioneDebitoria` dello YAML si è rivelata averlo mentre l'entity no.

Colonna aggiunta (nona, non ottava — vedi §17): `documenti.data_pubblicazione`
(`DATE`, nullable, nessuna sentinella di migrazione necessaria: `NULL`
significa "pubblicata subito", esattamente il significato corretto anche per
le righe v2 esistenti).

**Logica applicativa implementata il 2026-09-26, poi rimossa il 2026-09-27 —
non riaprire senza rileggere §27 per intero.** Prima versione: `dataPubblicazione
is null or dataPubblicazione <= :oggi` filtrato direttamente in
`PosizioneDebitoriaRepository.findByIdApplicazioneAndIdPosizioneDebitoria`/
`findDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo` e
`PendenzaRepository.findByIdApplicazioneAndNumeroAvviso`/
`findByIdApplicazioneAndNumeroAvvisoAndIdDominio` (con un `left join` opzionale
per `Pendenza`, per non nascondere le pendenze v2 prive di `opzionePagamento`).
Rimossa perché applicata al consumatore sbagliato: la frase completa dello
YAML v3 è "si comporta come se non esistesse per qualsiasi ricerca/pagamento
**esterno** (Nodo dei Pagamenti, ricerca per avviso); resta invece **sempre
visibile e gestibile per l'applicazione che l'ha creata**" — e ogni chiamante
di questi metodi, tramite `PosizioneDebitoriaService`, è sempre l'applicazione
proprietaria (identificata da `idA2A`/`idApplicazione`), mai un consumatore
realmente esterno. Dettaglio completo del ragionamento e della verifica
(nessun precedente nel legacy, nessuna menzione nella issue GitHub originale,
ogni endpoint dello YAML è scoped su `{idA2A}`) in §27. Il campo
`dataPubblicazione` resta comunque scrivibile/leggibile dal client — questi
metodi semplicemente non lo usano più per nascondere risultati.
`existsByIdApplicazioneAndIdPosizioneDebitoria` e `trovaPerId` non erano mai
stati filtrati.

## Anagrafica `UnitaOperativa` (2026-09-26)

Emersa lavorando su `govpay-pendenze-api`: risolvere `idUnitaOperativa`
(codice, non id numerico — stessa convenzione di `idA2A`/`idDominio`,
verificata nel legacy: `PutUnitaOperativaDTO.idUo`) richiede un'anagrafica che
non esiste affatto in `govpay-common` (a differenza di `Applicazione`/
`Dominio`, già presenti). `UnitaOperativa` è comunque un concetto legacy reale
(tabella `uo`, non una tabella nuova — riuso diretto, stesso principio di
§17), solo mai portato su `govpay-common`.

Decisione del lead: nasce in `govpay-common-pendenze` (non in `govpay-common`,
condivisa con la 3.10.x, la cui strategia di release per l'aggiunta di novità
è da chiarire) — se `govpay-console-api` passerà alla 3.11.x dipendendo da
questa libreria (come previsto), questa resta la sua sede naturale, non un
ripiego da migrare poi. Mappata per intero (tutte le colonne reali di `uo`,
nessuna omissione), anche se per ora serve solo a risolvere l'FK in
scrittura — l'anagrafica in lettura (`GET /domini/{idDominio}/unita-operative/{idUnitaOperativa}`)
resta lavoro separato.

## Anagrafica `TipoVersamento`/`TipoVersamentoDominio` (2026-09-26)

Stesso gap di `UnitaOperativa`, emerso per la stessa ragione: `idTipoPendenza`
dello YAML v3 (`NuovaPendenza.idTipoPendenza`, es. `IMU`) è un codice
testuale — verificato nel legacy, `Versamento.SingoloVersamento.codDominio` è
lo stesso pattern per l'analogo campo dominio — che va risolto negli ID
numerici `Pendenza.idTipoVersamento` (catalogo astratto) e
`Pendenza.idTipoPendenza` (= `id_tipo_versamento_dominio`, l'istanza/override
per dominio). Nessuna anagrafica JPA esisteva per questo, né in
`govpay-common` né qui.

Risoluzione legacy verificata: `AnagraficaManager.getTipoVersamentoDominio(configWrapper,
idDominio, codTipoVersamento)` — `codTipoVersamento` univoco *globalmente* nel
catalogo (`unique_tipi_versamento_1`), l'override è univoco per
`(idDominio, idTipoVersamento)`; se non trovato, il percorso REST "custom"
(il più vicino concettualmente a questa v3) rifiuta esplicitamente, **nessun
fallback automatico** su un dominio di default (l'auto-censimento esiste solo
nel percorso v2 "flessibile", non riprodotto qui).

**Proiezione minimale, non piena fedeltà** (decisione del lead, 2026-09-26,
dopo aver verificato la DDL reale — la scelta iniziale, per analogia con
`UnitaOperativa`, era piena fedeltà, poi corretta): `tipi_versamento` ha ~55
colonne, quasi tutte configurazione di stampa/notifica del BackOffice legacy
(form BO/PagOffice, template email/AppIO di promemoria, tracciati CSV) che la
v3 non usa mai (ha il proprio `notificaSend`/`dataPubblicazione`). Mappate
solo `codTipoVersamento`/`descrizione`/`abilitato` su `TipoVersamento`, e la
sola relazione verso di esso (più `idDominio`, FK piatta M4) su
`TipoVersamentoDominio` — sufficienti a risolvere il codice negli ID
richiesti, senza portarsi dietro superficie JPA per campi che questo
microservizio non userà mai. `TipoVersamentoDominioRepository.findByCodTipoVersamentoAndIdDominio`
risolve entrambi gli ID in un'unica interrogazione.

## `VocePendenza.idDominio` ripristinato (2026-09-26)

Stessa causa di omissione di `PosizioneDebitoria.dataPubblicazione` (§24):
`singoli_versamenti.id_dominio` è una colonna legacy **reale** (FK verso
`domini`, nullable) — concetto pagoPA di multi-beneficiario, un avviso con
voci destinate a enti creditori diversi — mai mappata su `VocePendenza`.
Individuata lavorando sul converter di `govpay-pendenze-api`, quando le 3
varianti di `NuovaVocePendenza` dello YAML si sono rivelate avere tutte un
`idDominio` opzionale (stesso pattern/convenzione di `idDominio` di
posizione: codice testuale, non id numerico — confermato nel legacy,
`PendenzeConverter.java`: `sv.setCodDominio(vocePendenza.getIdDominio())`)
mentre l'entity non aveva alcuna colonna corrispondente.

A differenza di `dataPubblicazione`, qui `NULL` non basta a significare
"eredita dal padre": `PosizioneDebitoriaService.crea()` materializza sempre
esplicitamente `VocePendenza.idDominio` al valore della posizione se il
chiamante non indica un override — stesso principio già in uso per
`Pendenza.idDominio` (mai lasciato implicito), confermato anche dal
comportamento del legacy (`VersamentoUtils.toSingoloVersamentoModel`: se
`codDominio` è assente sulla voce, usa comunque quello del versamento, non lo
lascia indefinito).

## 4 bug trovati dal lead in revisione di `govpay-pendenze-api` e corretti (2026-09-27)

Revisione indipendente del primo endpoint (`POST /posizioni-debitorie/{idA2A}`),
due dei quali hanno richiesto una correzione qui in libreria, non solo nel
converter dell'API:

1. **`TipoVersamento.codificaIuv` mancante**: la prima proiezione minimale di
   §"Anagrafica TipoVersamento/TipoVersamentoDominio" sopra ometteva questa
   colonna — presente in produzione sia su `tipi_versamento` (default) sia su
   `tipi_vers_domini` (override per dominio) — necessaria a
   `GeneratoreIuvStandard` per risolvere il placeholder `%(p)`/`%(t)` del
   prefisso IUV di dominio. Senza, la creazione falliva per qualunque dominio
   il cui prefisso usa quel placeholder. Aggiunta a entrambe le entità, con
   `TipoVersamentoDominio.getCodificaIuvEffettiva()` che applica la stessa
   semantica di override del legacy (valore del dominio se presente,
   altrimenti quello del catalogo).
2. **`LazyInitializationException` leggendo `TipoVersamento` fuori
   transazione**: `TipoVersamentoDominioRepository.findByCodTipoVersamentoAndIdDominio`
   usava `join` semplice, non `join fetch` — `getTipoVersamento()` restava un
   proxy LAZY, inizializzabile solo mentre la sessione Hibernate di scrittura
   è ancora aperta. Un consumatore che lo legge dopo che `crea()` è già
   tornato (tipicamente per costruire una risposta REST, con
   `open-in-view=false`) riceveva l'eccezione invece del valore — la
   posizione restava comunque salvata, quindi un secondo tentativo riceveva
   409, non l'esito atteso. Corretto entrambi i metodi di risoluzione
   (`findByCodTipoVersamentoAndIdDominio` e il nuovo `findByIdFetchTipoVersamento`)
   con `join fetch`. **Nota di metodo**: un test `@DataJpaTest` non
   riproduce questa classe di bug da solo — la sessione Hibernate resta
   aperta per l'intera durata del test, quindi un proxy LAZY si inizializza
   comunque anche dopo `em.clear()`; la riproduzione reale richiede una vera
   sessione chiusa (verificata a livello di integrazione in
   `govpay-pendenze-api`, rimuovendo `@Transactional` dal test del
   controller — vedi lì per il dettaglio).
3. **Violazione del vincolo UNIQUE tradotta in 500, non 409**: `crea()`
   usava `save()`, non `saveAndFlush()` — con id generato da `SEQUENCE`,
   Hibernate può differire l'INSERT fisico oltre il ritorno di `save()`,
   fino al commit della transazione, che avviene fuori dal metodo (al
   ritorno di `crea()` al chiamante). La violazione del vincolo
   `unique_documenti_applicazione` (creazioni concorrenti, che superano
   entrambe il controllo preliminare `existsBy...` — vedi bug del
   2026-09-26 sopra) emergeva quindi dopo che il `try/catch` era già uscito,
   propagandosi grezza fino al livello REST. Corretto con `saveAndFlush()`.
4. **Nello stesso giro, riconoscimento del vincolo specifico**: il
   `catch (DataIntegrityViolationException e)` traduceva *qualunque*
   violazione di integrità in `RisorsaGiaEsistenteException`, mascherando
   con un 409 fuorviante un vincolo diverso (es. un bug che violasse
   `unique_opzioni_pagamento_id_opzione`). Corretto: la traduzione avviene
   solo se il vincolo violato è effettivamente
   `unique_documenti_applicazione` (nome letto da
   `ConstraintViolationException.getConstraintName()`, con fallback sul
   testo del messaggio se il driver non lo popola); altrimenti l'eccezione
   originale si propaga inalterata.

**Secondo giro sullo stesso punto 4, bug del lead, 2026-09-27**: il
riconoscimento copriva solo `unique_documenti_applicazione`, ma `documenti`
ha anche `unique_documenti_1` (`cod_documento`+`id_applicazione`+`id_dominio`,
già nello schema legacy prima della migrazione). Quando il duplicato rilevato
solo al flush ha anche lo stesso `id_dominio` del preesistente, **entrambi** i
vincoli sono violati dalla stessa riga — quale dei due il motore segnali non è
deterministico lato applicativo (verificato: H2 in questo scenario segnala
`unique_documenti_1`, non l'altro). Riconoscere solo il primo lasciava
propagare un 500 in quel caso. Corretto riconoscendo entrambi i nomi.
**Riprodotto con uno spy** (`@MockitoSpyBean` su `PosizioneDebitoriaRepository`,
forzando `existsByIdApplicazioneAndIdPosizioneDebitoria` a restituire `false`
anche col duplicato già presente) — l'unico modo deterministico di simulare a
un solo thread la finestra di corsa fra il controllo preliminare e l'INSERT
reale, verificato disattivando temporaneamente il fix e confermando che il
test lo intercetta davvero (segnala `unique_documenti_1`, non tradotto).

## 27. Filtro `dataPubblicazione` rimosso dalle ricerche: era per il consumatore sbagliato (2026-09-27)

Riaperta la questione — voluta dal lead esplicitamente "una volta per tutte,
per evitare di doverci ritornare" — se il filtro §24 (introdotto il 2026-09-26,
poi rimosso qui) fosse corretto per `govpay-pendenze-api`. Tre riscontri,
raccolti prima di decidere:

1. **Nessun precedente nel legacy**: verificato a fondo (`versamenti` e tutti
   i dialetti SQL) — l'unica colonna "pubblicazione" esistente è
   `fr.data_ora_pubblicazione` (rendicontazione bancaria, concetto estraneo).
   `dataValidita` ha semantica opposta (fine, non inizio, validità).
   `avviso_notificato`/`data_notifica_avviso` serve solo a schedulare
   promemoria, mai a nascondere pagamento o ricerca. Conferma che
   `dataPubblicazione` è una novità pura della v3, sempre `NULL` sui documenti
   v2/migrati (comportamento già corretto, invariato da questa modifica).
2. **La issue GitHub originale del microservizio non la menziona affatto**:
   l'unica fonte di questa semantica è la descrizione inline del campo nello
   YAML.
3. **Quella descrizione, letta per intero**, dice: *"Fino a tale data si
   comporta come se non esistesse per qualsiasi ricerca/pagamento **esterno**
   (Nodo dei Pagamenti, ricerca per avviso); resta invece **sempre visibile e
   gestibile per l'applicazione che l'ha creata**."* — e **ogni** operazione
   di `govpay-api-pendenze.yaml` è scoped su `{idA2A}`: non esiste, in questo
   YAML, un'operazione che rappresenti il Nodo dei Pagamenti o un chiamante
   davvero esterno. "Ricerca/pagamento esterno" si riferisce verosimilmente
   alla reale interazione col Nodo (verifica/attivazione RPT), che nel
   monorepo legacy vive in un livello completamente diverso e in v3 non è
   ancora stata progettata né costruita come servizio.

**Decisione**: il filtro va rimosso da `trovaPerIdentificativo`/
`cercaPerDebitore`/`cercaPendenze` (unici chiamanti attuali, sempre
l'applicazione proprietaria) — vedi §24 per il dettaglio tecnico della
rimozione. Se in futuro nascerà un servizio che implementa la verifica
pagamento verso il Nodo, dovrà applicare questo filtro con una propria query,
non riusando questi metodi pensati per il gestionale.

**Nota collaterale emersa durante questa verifica, non ancora affrontata**:
esiste nel legacy un meccanismo reale di autorizzazione per dominio
(tabella `utenze_domini`, many-to-many utenza↔dominio con `id_uo` opzionale,
flag `autorizzazione_domini_star` per bypassarla; classe
`AuthorizationManager`/`AutorizzazioneUtils` in `jars/core`, usata in modo
pervasivo in api-backoffice/api-ragioneria e nel listato di api-pendenze
**v1**). **api-pendenze v2** (il predecessore diretto di questa v3) è invece
più permissivo: il listato filtra solo per `idA2A` ("un'applicazione vede solo
le sue pendenze", commento esplicito nel codice), non per domini autorizzati;
il controllo di autorizzazione sul dominio scatta lì solo puntualmente, in
lettura/patch di una singola pendenza. **`govpay-pendenze-api` oggi non
implementa alcun controllo di questo tipo**: `risolviIdDominio` nel converter
verifica solo che il dominio esista, non che l'applicazione chiamante sia
autorizzata su di esso — un'applicazione potrebbe creare una posizione
debitoria per qualsiasi dominio censito. Il lead deve verificare con l'autore
della specifica quale dei due modelli (v1 restrittivo, v2 permissivo) la v3
debba seguire, prima che questo venga implementato. Richiederebbe comunque una
nuova anagrafica (`Utenza`/`UtenzeDomini`), non ancora presente da nessuna
parte per questa libreria.

## 28. Causale dei versamenti orfani: documento sintetizzato in migrazione, `descrizione` ampliata a 1024 (2026-10-20)

Il "punto 4" del wrapper v2 (`GET /pendenze/{idA2A}/{idPendenza}` su un'installazione migrata) richiedeva un documento anche per i versamenti v2 senza raggruppamento (`id_documento NULL`): v3 non ammette una `Pendenza` priva di `PosizioneDebitoria`. Decisioni prese, con motivazione:

- **La causale (decodificata) diventa la `descrizione` del documento sintetizzato**, non un sentinel: verificato che `descrizione` e' un campo genuinamente funzionale in v3 (letto da `AvvisoPdfPayloadMapper`/`RicevutaPdfPayloadMapper` per la stampa, non solo per l'eco del wrapper v2) — scartare la causale o sostituirla con un placeholder avrebbe tolto un'informazione che arriva davvero a un debitore in stampa.
- **`documenti.descrizione` ampliata da 255 a 1024** (stessa lunghezza di `causale_versamento`): richiesta esterna (vista completa sul cruscotto) risolta nello stesso movimento che elimina ogni rischio di troncamento in migrazione — lo YAML v3 mantiene comunque il proprio `maxLength: 140` in scrittura, il limite riguarda solo i nuovi dati, non la lettura di quelli esistenti.
- **`cod_documento = 'MIGR-'||id del versamento`**: univoco per costruzione (PK globale), riconoscibile come sintetizzato, tracciabile alla riga sorgente — mai un valore che potrebbe sembrare scelto da un chiamante reale (vedi `PendenzeConverter` legacy: per un documento vero `cod_documento` e' sempre un valore applicativo fornito dal chiamante).
- **Solo causali "01" (`CausaleSemplice`) vengono decodificate**: nessun path di scrittura oggi attivo produce "02"/"03" (`CausaleSpezzoni`/`CausaleSpezzoniStrutturati` compaiono solo dentro `Versamento.decode()` stesso, su dati storici) — una causale non-"01" trovata tra i versamenti orfani blocca lo script (precondizione manuale, non auto-decodificata) invece di rischiare un decode scorretto.
- **Decodifica riusata da `govpay-aca-batch`** (vista `v_versamenti_gpd`, gia' in produzione) invece di un passaggio Java separato: evita di reinventare 5 traduzioni SQL quando esiste gia' un precedente funzionante per postgresql/oracle/mysql (confermato dal lead attivo presso i clienti). SQL Server completato qui (la vista si fermava a `VARBINARY`, senza conversione finale a testo) ma non verificato contro un'istanza reale. **HSQL escluso**: confermato dal lead non utilizzato in produzione.
- **Script a parte (`05_documenti_orfani.sql`, stessa cartella/convenzione di `01_documenti.sql`...`04_versamenti.sql`)**, non modifica retroattiva di `01_documenti.sql`: stesso principio di versioning incrementale degli script di migrazione gia' in uso in questa cartella.

**Punto aperto emerso riverificando la stampa (2026-10-21), da portare al committente**: la precedente nota "nessuna regressione sulla stampa" era imprecisa. Il legacy ha **due** operazioni di stampa distinte: `GET /avvisi/{idDominio}/{numeroAvviso}` (`AvvisiDAO.getAvviso()`, sempre `AvvisoPagamentoUtils.fromVersamento()` → oggetto del pagamento = **causale della singola pendenza**, indipendentemente dal fatto che abbia un documento — verificato, il controllo su `documento != null` li' decide solo il nome del file) e `GET /documenti/.../avvisi` (stampa cumulativa di tutto il documento, **solo qui** `fromDocumento()` usa `documento.getDescrizione()`). `GET /pendenze/{idA2A}/{idPendenza}/stampa` di v3 (`AvvisoPdfPayloadMapper`) e' l'equivalente diretto della **prima** operazione (singola pendenza), ma usa sempre `PosizioneDebitoria.descrizione` (condivisa), mai la causale della singola pendenza. Per una pendenza raggruppata con causali diverse per rata, questo e' una **regressione reale** rispetto a `GET /avvisi/.../numeroAvviso` di v2 (mostrerebbe la descrizione condivisa del documento invece della causale specifica di quella rata) — non per gli orfani, dove i due valori coincidono per costruzione. Nessuna proposta di soluzione ancora formulata: va discusso con il committente prima di decidere se/come differenziare il comportamento di stampa per il wrapper sulle pendenze raggruppate.

**Non affrontato qui, resta apertura nota**: un `Versamento` migrato senza `id_opzione_pagamento` (mai assegnato dalla migrazione, §23: "nessuna riga esistente da migrare" per `opzioni_pagamento`) fa restituire `null` da `Pendenza.getOpzionePagamento()` — i mapper di stampa (`AvvisoPdfPayloadMapper`/`RicevutaPdfPayloadMapper`) risalgono a `descrizione` passando per `pendenza.getOpzionePagamento().getPosizioneDebitoria()`, quindi andrebbero in `NullPointerException` su una pendenza v2 migrata se usati prima che un'opzione di pagamento le venga assegnata. Non e' stato ancora verificato se/come questi mapper gestiscano gia' questo caso (atteso e tollerato altrove, vedi §24 sul `left join` opzionale in ricerca) — da controllare prima di considerare il wrapper v2 completo sul percorso di stampa.

**Punto aperto sul wrapper di scrittura (creazione pendenze dopo la migrazione, non ancora disegnato)**: in v2 un documento non si crea mai da solo — nasce (o si aggiorna) come effetto collaterale di `addPendenza` quando referenzia un `documento.identificativo` nuovo o esistente (`VersamentiBD.insertVersamento`, `jars/orm/.../VersamentiBD.java:264-324`: find-or-create per `(codDocumento, idApplicazione, idDominio)`, con **update della `descrizione` a ogni chiamata successiva che referenzia lo stesso documento** — ultima scrittura vince, non la prima). `PosizioneDebitoria.descrizione` corrisponde 1:1 a `documenti.descrizione`, ma `aggiungiPendenza`/`aggiungiOpzionePagamento` non la toccano mai (operano solo su opzione/pendenza) — se il wrapper deve replicare fedelmente questo comportamento legacy, serve aggiornare esplicitamente anche `PosizioneDebitoria.descrizione` a ogni rata successiva sullo stesso documento, non solo alla prima. Lasciato aperto.

**Decisione sulla validazione del `rata` dichiarato da v2 (2026-10-20)**: verificato che **v2 oggi non valida affatto `cod_rata`** — nessun codice di errore lo menziona, nessun controllo di unicità/sequenzialità in `VersamentiBD.insertVersamento`; `VersamentoUtils.isNumeroRata`/`getTipoSogliaPagamento`/`getGiorniSogliaPagamento` sono codice morto (nessun chiamante in tutto il monorepo), la decodifica reale (`VersamentoConverter.java:143-157`) avviene solo in lettura, per la stampa, senza validare nulla. Il client puo' oggi mandare qualunque intero, in qualunque ordine, anche duplicato, senza errore. **Deciso comunque di non replicare questa permissivita'**: questa API resta pensata per client che vogliono continuare a usare il contratto v2, ma i dati devono comunque entrare nello schema v3 — dove `numeroRata` e' posizionale, mai scritto dal client (vedi Javadoc di `PosizioneDebitoriaService.assegnaIndici`). Si da' errore esplicito (400) se il `rata` dichiarato non coincide con la posizione attesa (1 alla prima chiamata per un documento nuovo, conteggio attuale+1 per le successive) invece di accettarlo e lasciare che diverga silenziosamente da quanto effettivamente assegnato — e' un comportamento nuovo rispetto a v2, accettato consapevolmente: l'alternativa (ignorare `rata` e affidarsi solo all'ordine di arrivo delle chiamate) rischierebbe di divergere silenziosamente da quanto il client crede di aver dichiarato.

**Decisione su `causale_versamento` per-pendenza (2026-10-20)**: v2 restituisce `causale` nel GET di ogni pendenza (`PendenzeConverter.java:90-92,254-256`), anche quelle raggruppate sotto un documento (dove `causale` e `documento.descrizione` sono due testi distinti, a grana diversa — es. un piano rateale puo' avere lo stesso `documento.descrizione` condiviso ma un `causale` diverso per rata, tipicamente perche' il testo stesso riporta il numero di rata). Nessun posto in v3 per questo testo per-pendenza: `PosizioneDebitoria.descrizione` e' condiviso dall'intera posizione, non per singola pendenza. **Stesso trattamento di `descrizioneStato`**: nuovo campo `Pendenza.causaleVersamento` (`@Column(name = "causale_versamento", length = 1024)`, colonna legacy gia' esistente, non ancora mappata), mantenuto **nel formato codificato legacy** (`"01 "+base64`), non decodificato dall'entity — perche' `govpay-aca-batch` ha gia' una vista (`v_versamenti_gpd`) che decodifica la stessa colonna assumendo quel formato: decodificarla qui romperebbe quella vista. Nessun consumatore v3-nativo la legge: **verificato che i mapper di stampa (`AvvisoPdfPayloadMapper`/`RicevutaPdfPayloadMapper`) usano solo `PosizioneDebitoria.descrizione`** per l'oggetto del pagamento, mai la causale della singola pendenza — esattamente come il legacy stesso (`AvvisoPagamentoUtils.fromDocumento()`: oggetto del pagamento = `documento.getDescrizione()`, mai `causale_versamento`, quando la pendenza e' raggruppata). Nessuna regressione quindi sulla stampa: il nuovo campo serve solo al wrapper per il GET, encode/decode resta suo compito.

**Dispatch completo di `rpp[].rpt`/`rpp[].rt` concluso (2026-10-21)**: `Rpt` ampliato con `codStazione`/`codMsgRichiesta`/`tipoVersamento`/`stato`/`descrizioneStato` (colonne legacy gia' esistenti, mai mappate — nessun consumatore v3-nativo le legge). In `govpay-common-pendenze`: `RicostruttoreRichiestaPagamento` (ricostruzione di `CtPaymentPA`/`CtPaymentPAV2` dalla RT quando `xml_rpt` e' assente, caso frequente nei formati paForNode — generale, non solo per il wrapper). Nel wrapper v2 (`it.govpay.pendenze.v2.ricevuta`, `govpay-pendenze-api`), scoped solo li' come deciso: `RichiestaPagamentoV1Mapper` (riformatta sempre nel vecchio formato V1, richiesto esplicitamente per questo contratto, mai per v3 nativo — l'ente beneficiario si risolve dall'unita' operativa "EC" del dominio, con eventuale override dall'unita' specifica della posizione) e `RppContenutoMapper` (dispatch completo per `versione`: SANP_230 passthrough, `ctReceipt`/`ctReceiptV2` letti da `xml_rpt` o ricostruiti dalla RT, poi sempre convertiti in V1 — `rt` invece mai convertito, stesso comportamento del legacy). Verificato che `Rpp.rpt`/`Rpp.rt` nello YAML v2 sono `type: object` generico (non schemi tipizzati): la serializzazione riusa lo stesso meccanismo del legacy (`RicevutaJsonConverter`, Jackson con `JakartaXmlBindAnnotationModule` sulle annotazioni JAXB), non un mapping bean-a-bean.

**Campi "core" di `Pendenza`, assemblaggio di `rpp[]`/`allegati[]` e controller v2 conclusi (2026-10-21)**: completato tutto cio' che restava per `GET /pendenze/{idA2A}/{idPendenza}` del wrapper v2.

- **`PendenzaV2Mapper`** (`it.govpay.pendenze.v2.pendenza`, govpay-pendenze-api): stato (switch identico al legacy, `anomalo` sempre prevalente), documento (sospeso per i documenti sintetizzati `MIGR-`, rata/soglia da `OpzionePagamento`), soggetto, voci (3 varianti, nessun discriminator a differenza di v3), segnalazioni, causale (via `CausaleVersamentoCodec`, nuova classe: stesso formato `CausaleSemplice` "01 "+base64 del monolite), `proprieta`/`datiAllegati` come passthrough JSON diretto (nessuna interpretazione). Ha anche `toIndexDto`, versione senza `voci`/`allegati` e con `rpp`/`pagamenti` come URL invece che contenuti annidati — serve a popolare `Rpp(Index).pendenza`, non accede a `Pendenza.getVoci()` (LAZY), quindi chiamabile anche fuori dalla transazione di caricamento delle voci.
- **Allegati: per scelta progettuale la v3 nativa non li porta avanti** (confermato: nessuno schema/endpoint in `govpay-api-pendenze-v3.yaml`, a differenza della v2 che li ha ancora) — stesso principio gia' applicato alla conversione RPT V1. Riportati **al minimo indispensabile** solo nel wrapper: `AllegatoEntity`/`AllegatoRepository` scoped a `it.govpay.pendenze.v2.pendenza` in govpay-pendenze-api (non in questa libreria), mappano solo `nome`/`tipo`/`descrizione` della tabella legacy `allegati` (mai toccata da nessuno script di migrazione, esiste ancora fisicamente) — `raw_contenuto` (OID) escluso, non serve all'elenco (`AllegatoPendenza.contenuto` e' solo l'URL `/allegati/{id}`, non il contenuto). Nessuna migrazione nuova: la tabella non e' mai stata toccata, quindi non c'e' nulla da migrare.
- **`RppMapper`** (`it.govpay.pendenze.v2.ricevuta`, govpay-pendenze-api): assembla `Rpp`/`RppIndex` completi (stato/dettaglioStato da `Rpt`, `rpt`/`rt` da `RppContenutoMapper`, `pendenza` da `PendenzaV2Mapper#toIndexDto`) — `rt` e' `null` (non errore) se la RT non e' ancora stata acquisita, a differenza di `RppContenutoMapper#toRt` che la considera un errore quando esplicitamente richiesta da sola.
- **Bug trovato e corretto mentre si assemblava `rt`**: verificato nel legacy (`ConverterUtils.getRtJson`/`MessaggiPagoPARtUtils.getMessaggioRT`) che la conversione V1 si applica **anche a `rt`**, non solo a `rpt` come inizialmente documentato qui — portato `MessaggiPagoPAUtils.toCtRicevutaTelematica` (i due overload `PaSendRTReq`/`PaSendRTV2Request`) in `RichiestaPagamentoV1Mapper`, corretto `RppContenutoMapper#toRt` per applicarla.
- **`RptRepository.findByIdVersamentoOrderByDataMsgRichiestaAsc`** (nuovo, questa libreria): elenco completo delle Rpt di una pendenza, RT acquisita o no — a differenza di `findElencoByIdVersamento` (filtra `dataMsgRicevuta is not null`, pensato per l'elenco ricevute v3). Generale, non specifico del wrapper: nessuna logica v2 in questa query, solo accesso ai dati.
- **`PendenzaV2Controller`** (`it.govpay.pendenze.v2.pendenza`, govpay-pendenze-api): implementa `getPendenza` di `PendenzeApi` v2, riusando l'infrastruttura trasversale del servizio (`AclAuthorizer`/`CurrentApplicazioneService`/`PosizioneDebitoriaService`/`ProblemExceptionHandler`) — nessuna di queste e' specifica della v3. **Collisione di routing risolta**: lo YAML v2 e quello v3 dichiarano lo stesso path (`/pendenze/{idA2A}/{idPendenza}`) — con entrambi i controller attivi nello stesso contesto Spring, la proprieta' `govpay.pendenze.api.v2.base-path` (gia' prevista in `application.properties` da una sessione precedente, mai usata finora) va impostata a un prefisso non vuoto quando le due versioni coesistono nello stesso deployment, altrimenti mapping ambiguo all'avvio — impostato `/v2` nel profilo di test.
- Verificato end-to-end con un test di integrazione reale (`PendenzaV2ControllerTest`, scrittura via il vero endpoint v3 `POST /posizioni-debitorie/{idA2A}` sulla stessa entity/tabella, lettura via `GET /v2/pendenze/...`): 265/265 test verdi in govpay-pendenze-api, 198/198 in questa libreria, nessuna regressione.

**Quattro bug trovati in revisione indipendente e corretti (2026-10-21)**:

1. **Conversione V1 sempre attiva, senza flag** — `RppContenutoMapper` convertiva incondizionatamente `ctReceipt`/`ctReceiptV2` (sia `rpt` sia `rt`) nel vecchio formato V1, mentre il legacy lo fa solo dietro un flag spento di default (`GovpayConfig`, confermato altrove in questo documento). Aggiunto `conversioneV1Abilitata` (`govpay.pendenze.v2.conversione-pagopa-v1.enable`, default `false`, govpay-pendenze-api): a flag disattivato, `toRpt`/`toRt` restituiscono `data`/`receipt` nel formato nativo invece di passare per `RichiestaPagamentoV1Mapper`. La separazione dal codice v3 resta intatta: il flag e il convertitore restano entrambi nel wrapper.
2. **Il formato della RPT si determinava da quello della RT** — `toRpt` usava `RicevutaMapper.mapVersioneATipo` (pensato per la RT) anche per decidere il formato della RPT, sbagliato per le tre versioni "miste" del legacy (`RPTSANP230_RTV2`/`RPTV1_RTV2`/`RPTV2_RTV1`, dove richiesta e ricevuta sono in formati pagoPA diversi — porting di `MessaggiPagoPARptUtils.getMessaggioRPT` vs `MessaggiPagoPARtUtils.getMessaggioRT`). Una RPT SANP_230 con RT V2 veniva letta come `ctReceiptV2`, con `data` nullo e `NullPointerException`. Aggiunto `RppContenutoMapper.mapVersioneATipoRpt` (nuovo, scoped qui — nessun consumatore v3-nativo ne ha bisogno), usato solo da `toRpt`; `toRt` continua a usare `RicevutaMapper.mapVersioneATipo`.
3. **La conversione V1 perdeva la marca da bollo** — `RichiestaPagamentoV1Mapper` (formato `ctReceiptV2`) omettitva `richiestaMarcaDaBollo`, a differenza del legacy (`MessaggiPagoPAUtils.toDatiSingoloVersamentoV2`). Corretto: hash del documento codificato in base64 (richiesto dal formato XML V1), provincia e tipo bollo riportati quando presenti.
4. **La ricostruzione della scadenza perdeva il fallback configurabile del legacy** — `RicostruttoreRichiestaPagamento.calcolaDueDate`, in assenza di `dataValidita`/`dataScadenzaAvviso`, saltava direttamente alla sentinella 31/12/2999, mentre il legacy (`CtPaymentPABuilder.calcolaDueDate`) prova prima `dataCreazione + numeroGiorniValiditaPendenza` (se configurato). Corretto senza introdurre una dipendenza dalla configurazione legacy in questa libreria: `ricostruisci`/`calcolaDueDate` accettano ora `numeroGiorniValiditaPendenzaSenzaData` come parametro esplicito, passato dal chiamante (`RppContenutoMapper`, da `govpay.pendenze.v2.giorni-validita-pendenza-senza-data`, non impostata di default).

Verificato di nuovo dopo le correzioni: 270/270 in govpay-pendenze-api, 200/200 in questa libreria.

**Quinto bug trovato in revisione indipendente e corretto (2026-10-21)**: nella ricostruzione della RPT dalla RT, `RicostruttoreRichiestaPagamento.toCtTransferPAV2` copiava `voce.getTipoBollo()` cosi' com'era, mentre il legacy (`MessaggiPagoPAUtils.toCtTransferPAV2`) ricade su `"01"` (`SingoloVersamento.TipoBollo.IMPOSTA_BOLLO`, l'unico valore mai esistito) quando la voce non specifica un tipo bollo. Con `xml_rpt` assente, una RT con `MBDAttachment` e una voce senza `tipoBollo`, la richiesta ricostruita restituiva `tipoBollo=null` invece di `"01"`. Corretto nell'unico punto dove la marca da bollo viene popolata in ricostruzione — la differenza si propagava sia al formato nativo (restituito quando la conversione V1 e' disabilitata) sia alla conversione V1 (che si limita a leggere il valore gia' presente sull'oggetto ricostruito), quindi un solo fix in questo punto risolve entrambi i percorsi. Verificato di nuovo: 270/270 in govpay-pendenze-api, 201/201 in questa libreria.

**Due bug aggiuntivi trovati in revisione indipendente e corretti (2026-10-21), entrambi solo in govpay-pendenze-api**:

1. **La serializzazione finale perdeva il contenuto di `rpt`/`rt`** — `RicevutaJsonConverter` restituiva un `com.fasterxml.jackson.databind.JsonNode` (Jackson 2), ma il mapper applicativo che scrive davvero la risposta HTTP e' Jackson 3 (`tools.jackson`, verificato: Spring Boot 4.1 lo porta come mapper primario, Jackson 2 arriva dalla BOM solo per moduli di compatibilita' come `jackson-module-jakarta-xmlbind-annotations`, non ancora portato su Jackson 3). Un `JsonNode` Jackson 2 passato a un mapper Jackson 3 non viene riconosciuto come albero e finisce serializzato come un bean qualunque (le sue proprieta' interne — `nodeType`/`containerNode`/`array` — al posto del contenuto reale). Corretto: `RicevutaJsonConverter.toAlberoGenerico` restituisce ora una struttura generica di `Map`/`List`/valori semplici (round-trip `convertValue(bean, Object.class)`), che qualunque versione di Jackson scrive correttamente senza bisogno di un modulo dedicato. Aggiunto un test di integrazione vero (`PendenzaV2ControllerTest`, contesto Spring completo, `GET /v2/pendenze/...` con `rpp` popolato) che verifica il contenuto reale (`receiptId`/`creditorReferenceId`) attraverso il mapper applicativo vero — un test solo a livello di `RppContenutoMapper` non avrebbe mai scoperto questo bug, perche' usa un proprio `ObjectMapper` locale.
2. **La conversione V1 cambiava la semantica di `dataEsecuzionePagamento`** — `RichiestaPagamentoV1Mapper.toRpt` (entrambi gli overload) usava `data.getDueDate()` (la scadenza), mentre il legacy (`MessaggiPagoPAUtils.toCtRichiestaPagamentoTelematico`) usa `rpt.getDataMsgRichiesta()` (quando la richiesta e' stata inviata) per entrambi i formati — campi semanticamente diversi. Corretto in entrambi gli overload; `dueDate` resta usata solo per il campo nativo corrispondente (`CtPaymentPA`/`CtPaymentPAV2.dueDate`), mai per `dataEsecuzionePagamento`. Il test esistente confermava involontariamente il comportamento sbagliato (stesso valore per `dueDate` e scadenza nei dati di test) — corretto insieme al fix, con valori deliberatamente diversi per rendere la differenza visibile.

Verificato di nuovo: 271/271 in govpay-pendenze-api. **Nota**: in questa stessa verifica, la suite di govpay-common-pendenze ha iniziato a fallire su `PosizioneDebitoriaServiceTest`/`RicevutaRendicontazioneServiceTest`/`PosizioneDebitoriaServiceConGeneratoreIuvTest` con "Unresolved compilation problems" su `ApplicazioneEntity.getCodApplicazione` — causa esterna a questa sessione (il jar SNAPSHOT installato di `govpay-common` risulta rotto/incompleto, probabilmente una build concorrente in corso altrove), non correlata a nessuna delle modifiche qui descritte: confermato isolando i soli test toccati in questa sessione (`RicostruttoreRichiestaPagamentoTest`, 7/7 verdi) e verificando che govpay-pendenze-api (che dipende dallo stesso `govpay-common` ma con una risoluzione delle dipendenze indipendente) resta 271/271 verde. Risolto dal lead ricompilando `govpay-common`/`govpay-common-pendenze`/`govpay-pendenze-api` (confermato 201/201 e 271/271 dopo).

**`getPendenzaByAvviso` (2026-10-21)**: secondo endpoint di lettura del wrapper v2 (`GET /pendenze/byAvviso/{idDominio}/{numeroAvviso}`), stesso dettaglio di `getPendenza` ma senza `idA2A` nella richiesta — `idDominio`+`numeroAvviso` identificano la pendenza **nella maggioranza dei casi** (univocita' per dominio, §18), ma non sempre: vedi la correzione del 2026-10-24 in §29 più avanti in questo documento — il chiamante deve comunque essere l'applicazione proprietaria. Porting del "filtro sull'applicazione" del legacy (`PendenzeController.getPendenzaByAvviso`): trovata la pendenza, si risolve la sua applicazione proprietaria e si verifica con `CurrentApplicazioneService.verificaIdA2A` (stesso controllo, stesso 403, di ogni altro metodo che prende `idA2A` — qui fatto dopo la ricerca, non prima, perche' l'idA2A proprietario non e' noto in anticipo). Un `idDominio` inconosciuto e' trattato come "pendenza non trovata" (404), non un errore di anagrafica separato. Assemblaggio di `rpp[]`/`allegati[]` estratto in un metodo privato condiviso con `getPendenza` (`costruisciDto`), nessuna duplicazione. Verificato con un nuovo test di integrazione che crea una seconda applicazione per provare il 403 (non bastava nessuno dei fixture esistenti, tutti sotto la stessa applicazione).

- **Bug trovato in revisione indipendente e corretto**: la ricerca partiva senza validare il formato del numero avviso (AuxDigit pagoPA, prima cifra 0/1/2/3 — porting di `IuvUtils.toIuv(String)` del monolite, non il piu' recente `it.govpay.common.utils.IuvUtils.convertiDaNumeroAvviso`, che richiede la configurazione del dominio e il check digit: qui basta il formato grezzo, indipendente dal dominio). Un numero avviso con prima cifra non valida (es. `999999999999999999`) restituiva 404 (nessuna pendenza con quel valore) invece di 400 (input non valido). Aggiunto `validaFormatoNumeroAvviso`, test del 404 corretto con un avviso formalmente valido ma inesistente, nuovo test dedicato per il 400.
- **Decisione esplicita sul recupero esterno (2026-10-21, confermata dal committente)**: il legacy (`LeggiPendenzaDTO.verificaAvviso`), quando la pendenza non e' nel DB, interroga sincronamente il connettore dell'applicazione proprietaria (`VersamentoUtils.acquisisciVersamento`/`IVerificaClient.verificaPendenza`, protocollo "Verifica" GovPay↔gestionale) per recuperarla al volo — verificato che non la persiste mai (`VersamentoUtils.toVersamentoModel` imposta esplicitamente `id=null`, e l'unico `insertVersamento` nel metodo legacy e' in un ramo diverso, non in quello di `chiediVersamento`): e' usata solo per costruire quella singola risposta, poi scartata. **Chiesto esplicitamente al committente se questo protocollo debba mai esistere in v3 nativo: risposta "da valutare", nessuna decisione presa.** Fino a quella decisione, questo wrapper gestisce solo pendenze gia' presenti nel DB (migrate o create dal vero percorso di scrittura v3); un avviso recuperabile dall'applicazione esterna ma non ancora migrato/creato risulta 404 — punto aperto esplicito, non un'omissione.

Verificato di nuovo dopo le correzioni: 275/275 in govpay-pendenze-api.

**Punto aperto emerso lavorando su `getPendenzaByAvviso`, da portare ai committenti (2026-10-21)**: `PendenzaRepository.findByIdDominioAndIuv` esiste gia' (verificato: compila, ma **zero chiamanti** in tutto `govpay-common-pendenze` — nessun controller v3 lo usa) ma non e' mai stato esposto da nessun endpoint, ne' v2 ne' v3. A differenza di `numeroAvviso` (derivato dell'avviso di pagamento cartaceo/PDF, concetto specifico di v2 — l'AuxDigit+check digit incollati davanti allo IUV), `idDominio`+`iuv` e' la coppia di identificativi pagoPA "vera", quella usata dal Nodo dei Pagamenti e dal protocollo RPT/RT indipendentemente dal fatto che sia mai stato generato un avviso stampato (un pagamento spontaneo avviato via API/app potrebbe non averne mai avuto uno). Avrebbe quindi senso una `GET` per pendenza via `idDominio`+`iuv` **nella v3 nativa stessa** (non solo nel wrapper v2): utile a qualunque consumer v3 per risalire dalla transazione pagoPA alla pendenza (riconciliazione, supporto, debug), coerente con `GET /rpp/{idDominio}/{iuv}/{ccp}` che nello YAML v2 chiave gia' le transazioni su questa stessa tripla. **Decisione lasciata ai committenti**: se/quando aggiungere questo endpoint a v3, nessun codice scritto finora.

**Non ancora affrontato**: il percorso di scrittura del wrapper v2 (`addPendenza`/`updatePendenza`/allegati in scrittura), gli altri endpoint di lettura v2 (`findPendenze`, `getAllegatoPendenza`, `/documenti/.../avvisi`, `/profilo`), il recupero dall'applicazione esterna per avvisi non ancora migrati (vedi sopra, in attesa di decisione del committente), l'eventuale `GET` per idDominio+iuv in v3 nativo (vedi sopra, idem), e i punti aperti gia' elencati sopra (opzioni RIDOTTO/SCONTATO/ENTRO-OLTRE, regressione di stampa sulle pendenze raggruppate, verifica degli script di migrazione su Oracle/MySQL/SQL Server reali).

**Precondizione di migrazione ampliata di conseguenza**: il controllo sulle causali in formato diverso da "01" (vedi sopra, §causale dei versamenti orfani) andava scoped solo agli orfani perche' solo quelli la migrazione decodifica — ma ora che `causaleVersamento` e' letta dal wrapper per **qualunque** pendenza (anche quelle gia' raggruppate, mai toccate dalla migrazione), lo stesso controllo va rieseguito su tutta la tabella `versamenti` (non solo `WHERE id_documento IS NULL`) prima di mettere in produzione il wrapper — non blocca la migrazione in se' (che continua a toccare solo gli orfani), ma e' un rischio runtime latente per il wrapper se non risolto. **Nota**: un'ipotesi iniziale ("le raggruppate potrebbero avere causali 02/03 perche' legate a scenari rateali") non si sostiene — una pendenza orfana non puo' essere una rata per definizione (il concetto vive solo nel `documento`), quindi se quei formati storici esistono da qualche parte e' piu' plausibile trovarli proprio tra gli orfani (vecchio modo di codificare informazione multi-parte quando non si usava il raggruppamento via documento). Il controllo va comunque esteso a tutta la tabella per prudenza, indipendentemente da dove ci si aspetta di trovare le righe non conformi.

**Decisione sul limite di opzioni ENTRO/OLTRE per posizione (2026-10-20)**: la libreria non impedisce piu' opzioni `SOLUZIONE_UNICA_ENTRO` (o piu' `SOLUZIONE_UNICA_OLTRE`) sulla stessa posizione — solo la cardinalita' di 1 pendenza per singola opzione resta vincolata (invariata). Il vincolo "al massimo una ENTRO e una OLTRE per posizione" (dedotto dal pattern legacy RIDOTTO/SCONTATO, sempre una coppia binaria) **non va in libreria**: a differenza del massimo di 1 pendenza per opzione (definizione stessa di un'alternativa a soglia singola, vale per qualunque chiamante), questo assume uno scenario specifico che potrebbe non generalizzare a un client v3-nativo con piu' soglie scaglionate (es. ENTRO 5gg/ENTRO 10gg/OLTRE 10gg) — nessuno ha confermato che sia fuori perimetro per v3. Resta quindi una scelta del **wrapper**, scoped al pattern v2 noto oggi, reversibile senza toccare la libreria.

**Proposta del lead per chiudere questo gap (2026-10-20), non ancora disegnata**: sintetizzare in migrazione anche un'`OpzionePagamento` per le pendenze esistenti, non solo il documento. **Non può essere 1:1 per versamento**: andrebbe a spaccare i raggruppamenti già individuati altrove in questo documento — coppie RIDOTTO/SCONTATO (alternative mutuamente esclusive, probabile `SOLUZIONE_UNICA_ENTRO`/`OLTRE`) e `cod_rata` come precursore di `PIANO_RATEALE` (più pendenze sotto la stessa opzione). **Il lead ha definito la cosa "un bel casino" da meditare con calma.**

**Disegno consolidato (2026-10-20, dopo discussione approfondita col lead — sostituisce l'ipotesi iniziale su RIDOTTO/SCONTATO)**: algoritmo per `documento`, classificando i `cod_rata` distinti dei suoi versamenti:

1. **Tutti `cod_rata` NULL** → una sola `OpzionePagamento SOLUZIONE_UNICA`, tutte le pendenze del documento dentro. **Assunzione esplicita da dichiarare**: che siano tutte componenti dello stesso dovuto da pagare, non alternative — il solo `NULL` non lo dimostra, va documentato come ipotesi di migrazione, non come fatto verificato.
2. **Tutti `cod_rata` numerici validi** (parsabili, positivi, nel range previsto, **senza duplicati numerici** anche tra rappresentazioni diverse come `"1"`/`"01"`) → una `OpzionePagamento PIANO_RATEALE`. **Il numero dichiarato va preservato, non rinumerato**: `1,3,5` resta `1,3,5`, non diventa `1,2,3` — possibile perche' la migrazione scrive via SQL diretto sulle tabelle, non chiamando `crea()` (che invece sovrascrive sempre `numeroRata` dalla posizione nelle liste — quella regola vale per le scritture *dal vivo* del wrapper, non per la migrazione di dati storici). Confermato: la libreria ammette gia' un piano con una sola pendenza (modifica di oggi), il minimo di 2 resta solo nel contratto REST v3.
3. **Coppia RIDOTTO/SCONTATO — RISOLTO, si fonde col caso 4 (2026-10-22)**: il lead ha chiarito che non è un pattern concettualmente distinto da ENTRO/OLTRE, ma la stessa soglia applicata al caso specifico delle violazioni CdS, con etichette di stampa differenziate — "stesso concetto", non un mapping da inventare. Confermato anche nel modello legacy stesso: `SCONTATO`/`RIDOTTO` sono due valori dello **stesso enum** `Versamento.TipoSogliaVersamento` che contiene anche `ENTRO`/`OLTRE` (`jars/orm-beans/.../Versamento.java:108`), salvato sulla stessa colonna (`documento.tipoSoglia`) e confrontato con le stesse date già usate per ENTRO/OLTRE generico (`dataValidita`/`dataScadenza`, `VersamentoUtils.getTipoSogliaPagamento`/`isDataDecorsa`) — **l'ipotesi precedente di una colonna "notifica del verbale" separata era sbagliata**, non esiste. Resta vero che `giorni` è esplicitamente vietato in input per questi due tipi (`PendenzaVerificataValidator.java:182`): il valore è fisso per legge e vive solo nelle etichette di stampa (`avvisi.properties:43-44`: "entro 5 giorni" per SCONTATO, "dal 6° al 60° giorno" per RIDOTTO) — per la migrazione si usa quindi `giorni=5` come costante, **SCONTATO→SOLUZIONE_UNICA_ENTRO**, **RIDOTTO→SOLUZIONE_UNICA_OLTRE**, perdendo il limite superiore di 60 giorni (che il modello v3 non rappresenta comunque per nessun OLTRE generico). Si unisce quindi al caso 4 sotto, stesso trattamento — non più sospeso con motivazione separata.
4. **ENTRO/OLTRE espliciti, inclusa ora la coppia RIDOTTO/SCONTATO** (`cod_rata`/`tipoSoglia` con tipo+giorni decodificabili, direttamente o via la costante fissa del punto 3) → pattern riconosciuto e ben compreso (il legacy li decodifica senza ambiguità), ma lasciato fuori dal primo giro di migrazione per semplicità — **da distinguere esplicitamente dai pattern davvero sconosciuti**, stesso trattamento "sospeso con motivazione", non "bloccato per dato anomalo".
5. **Tutto il resto** (pattern non riconosciuto, dati genuinamente incoerenti) → bloccato per revisione manuale.

**Derivazione dello `stato` di ogni opzione sintetizzata (corretta rispetto a una prima formulazione troppo permissiva)**, dagli stati reali delle pendenze (stessa logica di `ValidatorePosizioneDebitoria.validaStati`):

| Stati delle pendenze dell'opzione | Stato risultante |
|---|---|
| Tutte `NON_ESEGUITO` | `DISPONIBILE` |
| Tutte `ANNULLATO` | `ANNULLATA` |
| Almeno una con stato diverso dai due precedenti | `ATTIVATA` |
| Mix di `NON_ESEGUITO` e `ANNULLATO`, nessun altro stato | **Incompatibile — revisione manuale, non normalizzare** |
| Stato nullo o non riconosciuto | **Revisione manuale** |

Il caso "mix NON_ESEGUITO/ANNULLATO" non e' un dato corrotto: v2 permette l'annullamento della singola pendenza, quindi e' una conseguenza legittima di operazioni v2 lecite — ma il validatore attuale non ha una terza categoria per questo caso (`atteso` risulta `null`, eccezione). E' un **gap di modello v3 esposto dalla migrazione**, non un errore nei dati storici: non va "corretto" cambiando stati storici solo per far passare la validazione.

**Gap chiuso in libreria (2026-10-22)**: non era solo una scelta di migrazione — `validaStati` e' una post-condizione richiamata su tutta la `PosizioneDebitoria` dopo *qualunque* scrittura futura che la tocchi (`annulla`, `ripristina`, `registraEsitoPendenza`, persino `registraImportoSend` su una pendenza scollegata), quindi qualunque stato si fosse scritto in migrazione per questo caso, la prima operazione futura su una pendenza qualunque della stessa posizione avrebbe sollevato `ValidazioneNonSuperataException` — non un dettaglio della migrazione, una bomba a orologeria sul validatore. Risolto estendendo `DISPONIBILE` da "tutte `NON_ESEGUITO`" a "nessuna eseguita" (quindi: non tutte annullate, nessun esito) — copre anche il mix senza introdurre alcun nuovo stato. Sicuro perche' v3 **non espone piu' un annullamento indipendente della singola pendenza** (solo a livello di opzione, con cascata su tutte le pendenze): questo mix non puo' quindi mai piu' essere prodotto da una scrittura v3-nativa, esiste solo come artefatto di dati migrati da v2. `06_opzioni_pagamento_documenti_esistenti.sql` aggiornato di conseguenza (questi documenti sono ora migrati come `DISPONIBILE`, non piu' esclusi) e il relativo report diagnostico rimosso (non ha piu' nulla da segnalare). Verificato: 202/202 test in questa libreria (incluso un test aggiornato che prima si aspettava un rifiuto e ora verifica l'accettazione), 295/295 in govpay-pendenze-api, nessuna regressione; nuovo comportamento della migrazione verificato anche su un'istanza PostgreSQL locale reale.

**Controllo d'insieme quando un documento ha piu' opzioni sintetizzate (RISOLTO, 2026-10-22 — non piu' bloccante)**: la regola v3 "al massimo una `ATTIVATA`" (`validaStati`) resta l'invariante per le scritture *dal vivo*, ma il lead ha deciso di **non farne una precondizione della migrazione**: ogni opzione sintetizzata deriva il proprio stato indipendentemente dalla/dalle sue pendenze (`ATTIVATA` se almeno una pendenza e' "pagata", cioe' in uno stato diverso da `NON_ESEGUITO`/`ANNULLATO`), **anche se questo può produrre più di un'opzione `ATTIVATA` sullo stesso documento**. Motivazione esplicita: non dovrebbe succedere nella pratica (il pattern RIDOTTO/SCONTATO e' sempre binario per costruzione), lo schema DB non ha un vincolo che lo impedisca (nessun `CHECK`/indice parziale su "una sola ATTIVATA per documento"), e qualunque violazione storica e' comunque incapsulata: il servizio v3 (`PosizioneDebitoriaService.attiva`, righe 1014-1031) annulla automaticamente le alternative quando una si attiva **dal momento della migrazione in poi** — non puo' *correggere* una violazione già scritta dalla migrazione, ma impedisce che se ne creino di nuove. Nessuna modifica allo script `06_opzioni_pagamento_documenti_esistenti.sql` necessaria: il pattern C (unico punto dove un documento genera più opzioni) già deriva lo stato di ciascuna opzione dal proprio singolo versamento, senza alcun controllo incrociato con le opzioni sorelle — era già il comportamento implementato, solo non ancora dichiarato esplicitamente accettabile.

**Nota — non va confuso con il gap ancora aperto sul mix `NON_ESEGUITO`/`ANNULLATO` dentro una SINGOLA opzione aggregata** (casi A/B, piano rateale o soluzione unica con più pendenze): quella e' una domanda diversa, non risolta da questa decisione — li', per definizione, nessuna pendenza e' "pagata" (sono solo `NON_ESEGUITO`/`ANNULLATO` mescolati), quindi la nuova regola "ATTIVATA se almeno una pagata" non si applica e non c'e' ancora un esito per quel caso specifico.

**Cascata della mutua esclusione implementata (2026-10-22)**: il caso complementare — un'opzione ancora `DISPONIBILE` sulla stessa posizione di un'opzione `ATTIVATA` — non e' ambiguo come il precedente (qui l'esito e' univoco: e' un'alternativa non più applicabile), quindi il lead ha chiesto di risolverlo, non solo tollerarlo. Aggiunto in fondo a `06_opzioni_pagamento_documenti_esistenti.sql` uno step che replica a ritroso sui dati storici la stessa cascata di `PosizioneDebitoriaService.attiva()` (righe 1025-1036: quando un'opzione si attiva, annulla le `DISPONIBILI` sorelle **e** le pendenze sottostanti, `cascataStatoPendenze`) — senza la cascata sulle pendenze, l'opzione risulterebbe `ANNULLATA` con pendenze ancora `NON_ESEGUITO`, incongruente con l'invariante "`ANNULLATA` → tutte le pendenze `ANNULLATO`". Scoped su tutta la tabella `opzioni_pagamento` (non solo le righe appena inserite), quindi idempotente di natura. Verificato sull'istanza PostgreSQL locale: documento con `ENTRO5=ESEGUITO` (→`ATTIVATA`) e `OLTRE5=NON_ESEGUITO` (→`DISPONIBILE`) — dopo lo script, `OLTRE5` risulta `ANNULLATA`/`ANNULLATO` su entrambe le tabelle; seconda esecuzione idempotente (`UPDATE 0`).

**Esito proposto**: procedere con i casi semplici (1 e 2) producendo un report distinto per tre categorie separate — dati non validi, pattern legacy riconosciuti ma non ancora mappati (3 e 4), stati legacy non rappresentabili dalle regole v3 attuali — per decidere in seguito, caso per caso, se serve una correzione dei dati o un'estensione del modello/validatore v3.

**Casi 1, 2 e 3+4 implementati (2026-10-22)**: `sql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql` (solo PostgreSQL per ora, come gli altri punti di questa sezione). Il caso 3+4 (ENTRO/OLTRE espliciti, incl. RIDOTTO/SCONTATO con `giorni=5` costante) crea **una `OpzionePagamento` per versamento** (mai un'aggregazione, cardinalita' massima 1 per queste due tipologie) — conferma pratica che un documento **può avere più opzioni di pagamento** quando coesistono più soglie (es. la coppia ENTRO/OLTRE): lo schema lo ammette già (FK `id_documento` su `opzioni_pagamento` è many-to-one, non unique), nessuna modifica di modello necessaria. Verificato con un'istanza PostgreSQL locale realistica (non solo a mano): coppia ENTRO5/OLTRE5, coppia RIDOTTO/SCONTATO con stati reali diversi (`ESEGUITO`→`ATTIVATA` sullo SCONTATO, `ANNULLATO`→`ANNULLATA` sul RIDOTTO, verificando empiricamente anche l'invariante "al massimo una `ATTIVATA`"), soglia singola senza coppia (solo `ENTRO10`), duplicato di valore soglia nello stesso documento (due `ENTRO5`, correttamente escluso e riportato come anomalia) e pattern misto soglia+numerico (idem) — tutti gli esiti coincidono con l'attesa, inclusa l'idempotenza (seconda esecuzione: `UPDATE 0`/`UPDATE 0`, nessuna opzione duplicata).

Nota di correzione: l'ipotesi iniziale "l'ordine di stampa legacy segue l'inserimento, non il numero di rata dichiarato" era sbagliata — verificato che `AvvisoPagamento.java:364` ordina esplicitamente per `numeroRata`/soglia prima della stampa (un percorso diverso da quello controllato inizialmente). L'ordinamento numerico in migrazione resta comunque corretto, solo non e' la correzione di un bug che in realta' non esiste su quel percorso.

## 29. Wrapper v2: fedeltà al comportamento reale della v2 legacy, `getPendenzaByAvviso` e `getRpp`/`getRpt`/`getRt` (2026-10-22)

**Principio generale deciso dal committente**: questa API v2 nasce già deprecata nella linea v3 — dove emerge un bug/un'asimmetria/un parametro scollegato nel legacy, si replica fedelmente il comportamento **reale** (quello già visto dai client), non una versione "corretta". Istruzione testuale: *"teniamo gli stessi comportamenti della v2 legacy, quello che e' sganciato lascialo cosi, tanto questa api v2 nella nuova versione nasce deprecata"*. Salvato in memoria permanente (`feedback_wrapper_v2_fedelta_legacy`), si applica a tutte le decisioni sotto e a quelle future sullo stesso wrapper.

**`GET /pendenze/byAvviso/{idDominio}/{numeroAvviso}` concluso**: validazione del formato di `numeroAvviso` ripristinata (porting di `IuvUtils.toIuv` del monolite: 18 cifre — già imposto dallo schema — più la prima cifra che deve essere l'AuxDigit pagoPA 0/1/2/3, altrimenti 400 invece di un 404 "naturale"; deliberatamente **non** la verifica più recente e più stretta `it.govpay.common.utils.IuvUtils.convertiDaNumeroAvviso`, che dipende dalla configurazione del dominio e introdurrebbe rigetti nuovi rispetto al legacy).

**Ambiguità reale su `idDominio`+`numeroAvviso` — risolta in deroga alla fedeltà al legacy (2026-10-24)**: confermato dal committente che il NAV è condiviso, legittimamente, da tutte le rate di un piano rateale con avviso cumulativo (GovPay deriva lo stesso `iuv`/`numeroAvviso` da quello fornito dal chiamante, non ne genera uno nuovo per rata) — non un edge case raro, il caso normale per questo scenario. Nessun vincolo DB lo impedisce (solo un indice non univoco su `iuv_versamento, id_dominio`). `findByIdDominioAndNumeroAvviso` ritornava `Optional<Pendenza>`: con più di una corrispondenza, Spring Data lancia `IncorrectResultSizeDataAccessException` non gestita, propagata come 500 — stesso problema, verificato, anche in legacy (`VersamentiBD.getVersamentoByDominioIuv` rilancia `MultipleResultException` come `ServiceException`, mai intercettata da `PendenzeDAO.leggiPendenzaByRiferimentoAvviso`). **Il committente ha scelto esplicitamente di non replicare questo comportamento** (un 500 non è un comportamento significativo da preservare, a differenza delle altre decisioni di questa sezione): `findByIdDominioAndNumeroAvviso` ritorna ora una `List<Pendenza>`, e `getPendenzaByAvviso` risponde **409** (nuova `RicercaNonUnivocaException`) quando trova più di una pendenza, invece di propagare l'errore interno. Stesso fix applicato a `PosizioneDebitoriaService.verificaNumeroAvvisoNonDuplicato` (stesso metodo di repository, stesso rischio in scrittura se nel dominio esiste già un piano rateale con NAV condiviso). Verificato: 212/212 in questa libreria, 296/296 in govpay-pendenze-api.

- **Punto aperto esplicito, non implementato**: il legacy (`LeggiPendenzaDTO.verificaAvviso`/`VersamentoUtils.acquisisciVersamento`) interroga sincronamente il connettore "Verifica" dell'applicazione proprietaria quando l'avviso non è nel DB, solo per costruire quella singola risposta (mai persistito: `VersamentoUtils.toVersamentoModel` imposta `id=null`, nessun `insertVersamento` su questo percorso). Verificato che non esiste alcun equivalente v3-nativo. Il committente, interrogato se la v3 nativa debba mai supportare questo protocollo, ha risposto "da valutare" — per ora il wrapper gestisce solo pendenze già presenti sul DB (migrate o create dal vero percorso di scrittura v3); un avviso recuperabile solo dall'applicazione esterna risulta 404. Resta un punto aperto per i committenti, non per questo progetto.
- **Punto aperto, deferito ai committenti**: `PendenzaRepository.findByIdDominioAndIuv` esiste già (scaffolding senza alcun consumatore) — candidato naturale per un eventuale endpoint v3-nativo "pendenza per idDominio+iuv" (iuv e' l'identificativo pagoPA reale; numeroAvviso e' un derivato v2/di stampa che potrebbe non esistere per flussi v3 puramente API). Se/quando aggiungerlo alla v3 nativa non è una decisione di questo lavoro — spetta ai committenti.

**`GET /rpp/{idDominio}/{iuv}/{ccp}` + `/rpt` + `/rt` conclusi** (`it.govpay.pendenze.v2.transazione.RppV2Controller`, nuovo package in govpay-pendenze-api; nuovo `RptRepository.findByCodDominioAndIuvAndIur` in questa libreria — generico, nessun concetto v2 nella query):

- **200, non il 201 dichiarato dallo YAML** (sia il nostro che quello legacy, copiato): verificato che l'implementazione legacy stessa (`RppController.java`) risponde sempre `Status.OK` — il 201 non e' mai stato realmente emesso da nessun client. Corretto anche nel nostro `govpay-api-pendenze-v2.yaml`.
- **Negoziazione del content-type manuale, mai la standard di Spring**: il legacy sceglie a mano in base all'header `Accept` (substring match case-insensitive, non quality-aware) con default implicito a XML — mai un 406, anche con `Accept` assente o non riconosciuto. Replicato esattamente in `RppV2Controller` (override di `produces = MediaType.ALL_VALUE` sulle due rotte, altrimenti la produces-list generata dallo YAML causerebbe un 406 automatico di Spring per un `Accept` incompatibile con tutte le tre/quattro varianti dichiarate).
- **Asimmetria JSON/XML di `rpt` quando la conversione V1 e' disattivata (default)**: verificato nel legacy (`MessaggiPagoPARptUtils`) che il JSON espone solo `.getData()` (il `CtPaymentPA`/`CtPaymentPAV2` nudo) mentre l'XML e' l'intero messaggio wrapper (`paGetPaymentRes`/`paGetPaymentV2Response`, `outcome` incluso) — non lo stesso oggetto. Replicato fedelmente in `RppContenutoMapper.toRptXml`: passthrough del byte[] originale quando disponibile (fedeltà bit-a-bit, invece di ri-marshallare), altrimenti (RPT ricostruita dalla RT, caso "standin") un wrapper minimale fresco (`outcome=OK`+`data`) — fedele nel contenuto, non byte-identico a un messaggio mai esistito. La RT invece non ha questa asimmetria: il legacy restituisce sempre `rpt.getXmlRt()` anche per l'XML, mai ri-marshallato (replicato in `toRtXml`).
- **La stampa PDF della RT (`GET .../rt` con `Accept: application/pdf`) riusa interamente la catena già costruita per `GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}/stampa` (v3)**: `RicevutaMapper.toRicevuta`+`RicevutaPdfPayloadMapper.toReceipt`+`StampeClient.streamReceipt` prendono le entità `Pendenza`/`Rpt` già risolte, non le chiavi — nessuna duplicazione verso govpay-stampe-api, solo una risoluzione diversa della chiave (`idDominio`+`iuv`+`ccp` invece di `idA2A`+`idPendenza`+`iur`).
- 404 unico (nessuna distinzione) sia per RPT inesistente sia per RPT esistente ma RT non ancora acquisita — stesso trattamento del legacy (`RptDAO.leggiRt`).
- Nessuna validazione di formato su `iuv`/`ccp` (il legacy non ne ha nessuna, solo `idDominio` ha un pattern — già imposto dallo schema generato).

**Punto aperto emerso implementando il 409 sopra, NON chiuso — da verificare col committente**: `ValidatorePosizioneDebitoria.validaNumeroAvvisoNonDuplicatoNellAggregato` rifiuta già oggi (`ValidazioneNonSuperataException`) due pendenze della stessa richiesta con lo stesso `numeroAvviso` — controllo nato per un motivo distinto e legittimo (evitare un duplicato accidentale che il controllo contro il DB da solo non vedrebbe, perché confronta solo righe già persistite, non le pendenze "fratelle" ancora in memoria nella stessa richiesta). Ma è anche esattamente il controllo che impedisce di **creare** da API v3-nativa un piano rateale con avviso cumulativo (NAV condiviso tra le rate) — lo scenario che il committente ha confermato essere quello normale, non l'eccezione. Oggi quindi il NAV condiviso può esistere solo nei dati migrati da v2 (verificato scrivendo il test per il 409 sopra: riprodurlo via `crea()` è bloccato da questa stessa validazione, serve inserire le righe direttamente bypassando il servizio). Due letture, non decise: (1) in v3 ogni pendenza ha sempre un proprio avviso distinto, l'avviso cumulativo resta solo un retaggio storico da gestire in lettura, mai da riprodurre in scrittura — nessuna modifica necessaria; (2) il requisito "avviso cumulativo per piano rateale" resta valido anche per le posizioni create da ora in avanti — la validazione andrebbe rilassata per permettere NAV condiviso **all'interno della stessa opzione di pagamento**, pur continuando a vietarlo tra opzioni/posizioni diverse. Non deducibile dal codice, è una domanda di prodotto.

## 28. `idPosizioneDebitoria` diventa l'IUPD (2026-10-24)

Decisione del lead, dopo un confronto diretto col committente sullo standard pagoPA
GPD: **`idPosizioneDebitoria` non è più un valore scelto dal chiamante — è l'IUPD
stesso**, calcolato da GovPay. Supera sia §22 (il vincolo
`unique_documenti_applicazione`) sia parte di M7 (l'IUPD derivato al volo smette di
essere un side-value solo per ACA/GPD: diventa l'identità pubblica di *tutta*
questa API).

**Composizione**: concatenazione, **senza separatore**, di `idA2A`+`idDominio`+
`codDocumento` (quest'ultimo è il nuovo nome del campo che il client sceglie
liberamente — stesso valore/stesso vincolo di quello che prima si chiamava
`idPosizioneDebitoria` nel body della creazione). Nessuna nuova colonna: si
compone/decompone al volo a ogni richiesta, mai persistito — stesso principio di
M7, solo applicato più in generale.

**Perché è decomponibile senza separatore**: `idA2A` è sempre già noto a priori dal
chiamante (arriva separatamente dal path HTTP, non va "indovinato" dentro la
stringa — si verifica solo che la stringa inizi con quel prefisso letterale, poi si
strippa) e `idDominio` ha lunghezza fissa, esattamente 11 cifre (codice fiscale
ente pagoPA, già vincolato così nello YAML). Quello che resta dopo aver strippato
entrambi è `codDocumento`.

**Nessuna modifica di schema**: la tripla reale resta `cod_documento`+
`id_applicazione`+`id_dominio`, le stesse tre colonne già usate dal vincolo legacy
`unique_documenti_1` — che quindi basta da solo, senza bisogno di
`unique_documenti_applicazione` (rimosso da `01_documenti.sql` in tutti e 5 i
dialetti). Due posizioni con lo stesso `codDocumento`+`idA2A` su domini diversi
tornano ad essere ammesse, comportamento legacy mai stato davvero un problema (il
problema di §22 era solo una conseguenza della ricerca pubblica chiavata solo su
`idA2A`+`idPosizioneDebitoria` — ora risolto all'origine, perché quella stessa
stringa pubblica incorpora già il dominio).

**Perché `idA2A` resta comunque nel path** (`/posizioni-debitorie/{idA2A}/
{idPosizioneDebitoria}...`, nessun segmento `{idDominio}` aggiunto): tecnicamente
`idPosizioneDebitoria` da solo basterebbe a identificare la riga — ma `idA2A` resta
per la stessa ragione per cui era già lì, l'ownership/ambito del gestionale, non
l'unicità.

**Implementato**:
- `it.govpay.pendenze.identificativo.IdentificatorePosizioneDebitoria` (nuovo
  package), `comporre`/`scomponi` — quest'ultimo ritorna `Optional`, mai
  un'eccezione: un `idPosizioneDebitoria` sintatticamente non valido per questo
  schema (prefisso `idA2A` non corrispondente, troppo corto) semplicemente non
  corrisponde a nessuna posizione, esattamente come un vero "non trovato".
- `PosizioneDebitoria.codDocumento` (rinominato da `idPosizioneDebitoria`), stesso
  `@Column(name = "cod_documento")`; `@Table` perde `unique_documenti_applicazione`.
- `PosizioneDebitoriaRepository.findByIdApplicazioneAndIdDominioAndCodDocumento`/
  `existsBy...` (rinominati da `...AndIdPosizioneDebitoria`, 3 argomenti invece di
  2: `idApplicazione`, `idDominio`, `codDocumento`).
- `PosizioneDebitoriaService`: nuova dipendenza obbligatoria da `DominioRepository`
  (govpay-common) — serve a risolvere `idDominio` stringa↔id, esattamente come
  `ApplicazioneRepository` già serviva per `idA2A`. Nuovo metodo pubblico
  `idPosizioneDebitoriaPubblica(PosizioneDebitoria)` (compone, per le risposte);
  nuovo privato `trovaPerIdentificativoInterno` (decompone+risolve+interroga,
  usato da tutti i metodi scoped per identificativo — `trovaPerIdentificativo`,
  `aggiorna`, `aggiungiOpzionePagamento`, e l'`appartiene` condiviso da
  `aggiungiPendenza`/`annulla`/`ripristina`).
- **Conseguenza non ovvia, trovata durante l'implementazione**: rendere
  `DominioRepository` una dipendenza sempre presente rompe l'uso che
  `PendenzeAutoConfiguration` ne faceva come segnale di opt-in per la generazione
  IUV automatica (`@ConditionalOnBean(DominioRepository.class)` su
  `generatoreIuvStandard`) — sarebbe scattato ovunque, anche nei test che
  deliberatamente verificano lo scenario "nessun `GeneratoreIuv` configurato".
  Risolto spostando quel bean in una classe di autoconfigurazione separata,
  `GeneratoreIuvAutoConfiguration` (stesse condizioni, registrata a parte in
  `AutoConfiguration.imports`), così resta escludibile indipendentemente da
  `PendenzeAutoConfiguration` (che ora fornisce solo fuso orario/orologio). Un
  consumatore reale non nota la differenza (entrambe le classi vengono comunque
  auto-importate insieme); solo i test che selezionano esplicitamente quali
  autoconfigurazioni importare devono scegliere quali delle due vogliono.
- Lato `govpay-pendenze-api`: YAML — il campo scrivibile della creazione si chiama
  ora `codDocumento` (stesso pattern/lunghezza di prima); `idPosizioneDebitoria`
  resta nello schema di risposta, ma come valore calcolato (descrizione aggiornata,
  `maxLength` esteso a 81 = 35+11+35). `PosizioneDebitoriaMapper` compone/decompone
  riusando `risolviCodApplicazione`/`risolviCodDominio` già esistenti (nessuna
  dipendenza nuova). Il wrapper v2 (`PendenzaV2Mapper.toDocumentoDto`) **non**
  cambia: `Documento.identificativo` deve restare il semplice `codDocumento` (v2
  non ha mai avuto il concetto di IUPD) — fedeltà al legacy, non un'omissione.

**Verificato**: 211/211 test in `govpay-common-pendenze`, 295/295 in
`govpay-pendenze-api`, entrambi `mvn clean test` reali (non euristica).

## 30. Wrapper v2: endpoint `GET /profilo` implementato (2026-10-24)

Audit di completezza di tutti gli operationId generati dalle due interfacce v3
(`PosizioniDebitorieApi`/`PendenzeApi`, 7+8 operazioni: **tutte** già implementate,
nessun debito) e delle tre interfacce v2 wrapper (`PendenzeApi`, `TransazioniApi`,
`ProfiloApi`) ha isolato `GET /profilo` come primo punto da chiudere: endpoint di
sola lettura, isolato, senza dipendenze da altri endpoint ancora mancanti
(`addPendenza`/`updatePendenza`/`findRpp`/`getAllegatoPendenza`/`getAvviso`/
`getAvvisiDocumento` restano 501, tracciati per il seguito).

- **Comportamento replicato**: legacy `ProfiloController.profiloGET` (v2
  `wars/api-pendenze`) risolve il profilo dall'`Authentication` via
  `UtentiDAO.getProfilo`, senza nessun controllo ACL aggiuntivo (a differenza di
  ogni altro controller v2 di questo servizio, qui non c'è un equivalente di
  `AclAuthorizer.richiedeLettura()`: l'endpoint riporta le autorizzazioni
  dell'utenza, non ne richiede una specifica per essere letto). `nome` = principal
  dell'utenza; `domini`/`tipiPendenza` seguono lo stesso meccanismo "star oppure
  elenco esplicito" già usato per l'autorizzazione al tipo versamento in fase di
  creazione pendenza (`PosizioneDebitoriaMapper#verificaAutorizzazioneTipoVersamento`),
  qui applicato anche ai domini.
- **Scoperta non ovvia**: `ApplicazioneEntity` non ha mai avuto nessuna colonna di
  scoping sul dominio, e nessun codice v3 applica mai una restrizione per dominio
  in fase di creazione/lettura pendenza — si poteva pensare che il concetto di
  "domini su cui l'utenza è abilitata ad operare" non avesse più senso in v3. In
  realtà il dato di infrastruttura condivisa (tabella `utenze`, colonna
  `autorizzazione_domini_star`, e tabella `utenze_domini`) esiste comunque, è la
  stessa tabella condivisa con la v2 legacy, e semplicemente non era mai stata
  mappata in `govpay-pendenze-api` perché finora nessun endpoint ne aveva bisogno.
  Questo endpoint è il primo a doverlo leggere, e lo fa a piena fedeltà: aggiunta
  `UtenzaEntity.autorizzazioneDominiStar` (colonna già presente nello schema di
  test, semplicemente non mappata) e nuova `UtenzaDominioEntity`/
  `UtenzaDominioRepository` (mirror di `UtenzaTipoVersamentoEntity`/
  `UtenzaTipoVersamentoRepository`, già esistenti per i tipi versamento). Una riga
  di `utenze_domini` ha `idDominio` oppure `idUo` (mai entrambi): quando è una UO
  il dominio è quello del padre (`UnitaOperativaEntity.getDominio()`, già in
  govpay-common) — stessa risoluzione di `UtentiDAO.convertIdUnitaOperativeToDomini`
  nel legacy, qui semplificata perché lo schema v2 `Dominio` non espone la UO
  (basta deduplicare per dominio risolto).
- **Bug del legacy replicato fedelmente**: `acl` è `required` nello schema YAML
  `Profilo`, ma il `ProfiloConverter` del legacy non lo popola mai (né
  `anagrafica`/`identityData`) — solo `nome`/`domini`/`tipiPendenza`. Stessa
  omissione qui, per fedeltà (principio generale "fedeltà al legacy, bug
  compresi").
- Nuovo file `it.govpay.pendenze.v2.profilo.ProfiloMapper` (costruzione del bean,
  javadoc di classe con il dettaglio completo) e `ProfiloV2Controller` (nessuna
  `ApplicazioneEntity` necessaria: l'autenticazione risolve solo `UtenzaEntity`,
  vedi `PendenzeGovpayPrincipalLoader`). Aggiunto `utenze_domini` allo schema H2 di
  test (`schema-pendenze-test.sql`, mancava — nessuna FK reale verso `domini`/`uo`,
  stesso principio M4 già applicato a `uo.id_dominio`).

**Verificato**: 298/298 test in `govpay-pendenze-api` (`mvn clean test` reale), +2
rispetto a prima (i nuovi test di `ProfiloV2ControllerTest`); nessuna modifica a
`govpay-common-pendenze` in questo punto.

## 31. Wrapper v2: `addPendenza` (PUT `/pendenze/{idA2A}/{idPendenza}`) implementato (2026-10-25)

Ultimo dei sei operationId ancora 501 elencati al §30 a essere chiuso
(`updatePendenza`/`findRpp`/`getAllegatoPendenza`/`getAvviso`/`getAvvisiDocumento`
restano tracciati per il seguito). Upsert: crea la pendenza se `idPendenza` non
esiste ancora (201), altrimenti la aggiorna (200) — stessa semantica e stessi due
status del controller legacy (`PendenzeController.pendenzeIdA2AIdPendenzaPUT`,
`createOrUpdate.isCreated() ? 201 : 200`).

**Decisioni del committente, vincolanti per questa implementazione**:

- **`documento` assente**: v3 non ammette una `Pendenza` senza
  `PosizioneDebitoria`/`OpzionePagamento` (schema obbligatorio), mentre il legacy
  dal vivo crea un versamento senza documento (`id_documento IS NULL`) quando
  `documento` è omesso (verificato in `VersamentiBD.insertVersamentoEngine`: il
  blocco che risolve/crea la riga `documenti` è interamente condizionato su
  `versamento.getDocumento(this) != null`). Per restare nello schema v3 si
  sintetizza comunque un documento: `codDocumento` = UUID random **senza
  trattini** (32 caratteri hex, entro `VARCHAR(35)`), generato **solo in fase di
  creazione** (mai ricalcolato in update). Deliberatamente diverso dal prefisso
  `MIGR-` riservato alla migrazione (`PendenzaV2Mapper.PREFISSO_DOCUMENTO_SINTETIZZATO`):
  le scritture dal vivo di questo wrapper non lo condividono, altrimenti
  risulterebbero indistinguibili da un orfano di migrazione in lettura (vedi
  `PendenzaV2Mapper#toDocumentoDto`, che usa quel prefisso proprio per
  nascondere `documento` sulle pendenze orfane).
- **Tipologia dell'opzione quando si crea un nuovo documento** (sia sintetico sia
  con `documento.identificativo` esplicito): `documento.rata` assente →
  `SOLUZIONE_UNICA`; presente → `PIANO_RATEALE`, con la prima rata ammessa sempre
  `1` (vedi Javadoc di `TipologiaOpzionePagamento`, che anticipa esplicitamente
  questo scenario: "la prima persistita subito come `PIANO_RATEALE` di 1 sola
  pendenza, che diventa 2+ solo con la seconda chiamata"). Nessun limite di
  cardinalità in gioco (`cardinalitaPendenzeMassima()` è `null` per entrambe le
  tipologie): la scelta è puramente semantica.
- **`documento.identificativo` che coincide con un documento già esistente**
  (stessa tripla `codDocumento`+`idApplicazione`+`idDominio`): aggiunge una
  pendenza all'opzione già esistente (`PosizioneDebitoriaService#aggiungiPendenza`),
  non crea una nuova posizione. La tipologia è decisa alla creazione e non cambia
  mai dopo: un mismatch (rata dichiarata su un'opzione `SOLUZIONE_UNICA`, o
  viceversa) è un errore esplicito (`ValidazioneNonSuperataException`, 400),
  stessa decisione di validazione della rata del §28/2026-10-20 — mai
  rinumerazione silenziosa. La rata dichiarata deve coincidere con la prossima
  posizione attesa (`max(numeroRata esistenti) + 1`): stesso identico calcolo già
  usato internamente da `PosizioneDebitoriaService#aggiungiPendenza` per
  assegnare `numeroRata`, qui anticipato in validazione prima di invocare quel
  metodo.
- **Update (`idPendenza` già esistente)**: nessuna logica di update parallela,
  si delega interamente a `PosizioneDebitoriaService#aggiornaPendenza`, che
  tocca SOLO i campi della pendenza stessa (importo, numeroAvviso se non già
  assegnato, dataValidita, dataScadenzaAvviso, voci). **Deviazione consapevole
  dal legacy**: `documento`/`soggettoPagatore` dichiarati in una PUT di
  aggiornamento sono ignorati, non applicati e non un errore — il legacy li
  sovrascriverebbe ("ultima scrittura vince", `VersamentiBD.insertVersamento`),
  qui si riusa deliberatamente il metodo di libreria così com'è (per fedeltà
  alla libreria, non al legacy, su questo punto specifico).
- **`PendenzaCreata.UUID`**: sempre `null` nella risposta — popolato solo da
  flussi di pagamento online/sessione, mai da una creazione/aggiornamento da
  gestionale via `addPendenza`.

**File nuovi/modificati** (tutti in `govpay-pendenze-api`, nessuna modifica a
questa libreria):

- Nuovo `it.govpay.pendenze.v2.pendenza.NuovaPendenzaMapper`: tutta la logica di
  cui sopra, più i campi "solo legacy" senza comando dedicato, applicati con i
  setter dell'entity dopo `crea`/`aggiungiPendenza`/`aggiornaPendenza`
  (`causaleVersamento` via `CausaleVersamentoCodec`, `direzione`, `divisione`,
  `tassonomia`, `tassonomiaAvviso`, `datiAllegati` e `proprieta` come JSON
  passthrough, `codLotto`, `codAnnoTributario`, `sendAbilitato` sulla pendenza;
  `tipoContabilita`/`codiceContabilita`/`descrizioneCausaleRpt` sulle voci,
  appaiate per `idVocePendenza` — campi che esistono sull'entity ma non hanno
  alcun comando della libreria che li accetti, stesso principio già applicato a
  `causaleVersamento` in una sessione precedente).
- `PendenzaV2Controller`: nuovo override `addPendenza` (l'interfaccia generata
  `PendenzeApi` la espone già come default-501), nuove dipendenze iniettate
  (`NuovaPendenzaMapper`, `AvvisoPdfPayloadMapper`, `StampeClient`,
  `ExternalCallMetricsRecorder` per `stampaAvviso=true`, che riusa la stessa
  generazione PDF di `PendenzaController#getStampaPendenza` v3-nativo,
  catturata su buffer invece che in streaming diretto).
- `AllegatoEntity`: aggiunta la scrittura (prima era solo lettura) —
  `@GeneratedValue` sulla sequenza legacy `seq_allegati` e nuovo campo
  `dataCreazione`. `raw_contenuto` resta deliberatamente non mappato (vedi punto
  aperto sotto).
- YAML `src/main/resources/govpay-api-pendenze-v2.yaml`: due correzioni a un
  difetto di generazione (non una scelta di design) riscontrato durante
  l'implementazione — vedi "Scoperta non ovvia" sotto.
- Nuovo test di integrazione `AddPendenzaControllerTest` (stesso impianto
  `@SpringBootTest`/`MockMvc`/H2 di `PendenzaV2ControllerTest`, in una classe
  propria per isolare lo stub HTTP embedded del microservizio govpay-stampe
  usato solo dal test di `stampaAvviso=true`).

**Scoperta non ovvia (difetto di generazione OpenAPI, non una decisione di
design)**: lo YAML dichiarava `TipoRiferimentoDocumento` (`rata` | `soglia`) e
`TipoRiferimentoVocePendenza` (`codEntrata` | `ibanAccredito`+`tipoContabilita`+
`codiceContabilita` | `tipoBollo`+`hashDocumento`+`provinciaResidenza`) come
`oneOf` non discriminati, ciascun ramo con i propri campi `required`.
openapi-generator (SpringCodegen) appiattisce un simile `oneOf` annidato in
`allOf` in un'UNICA classe Java con i campi di TUTTI i rami, issando i
`required` di ciascun ramo con l'AND logico sulla classe appiattita invece
dell'XOR del `oneOf`: il risultato (`NuovoDocumento`/`NuovaVocePendenza`
generate) marcava `@NotNull` simultaneamente campi mutuamente esclusivi (es.
`rata` E `soglia` entrambi obbligatori, o `codEntrata` E `ibanAccredito` E
`tipoBollo` tutti e tre obbligatori) — la bean validation cascata da `@Valid
NuovaPendenza` avrebbe rifiutato con 400 OGNI richiesta reale (che per
costruzione popola sempre un solo ramo). Corretto rimuovendo `required` dai
singoli rami nello YAML (`RataDocumento`, `VincoloDocumento`, `RiferimentoEntrata`,
`Entrata`, `Bollo`): la scelta "quale ramo è popolato" è comunque già validata a
mano in `NuovaPendenzaMapper` (stesso ordine di priorità del converter legacy,
bollo prima di codEntrata prima del default IBAN). Stessa causa anche per il
parametro di query `dataAvvisatura` (`oneOf[string enum "MAI", string
format:date]`): generava un'interfaccia marker vuota senza implementazioni né
`Converter` Spring registrato, binding impossibile out-of-the-box — semplificato
a `type: string` semplice nello YAML (il parsing "MAI" vs data è comunque manuale
lato applicativo, non avrebbe guadagnato nulla dall'interfaccia marker).

**Punti aperti, non decisi silenziosamente**:

1. **`dataAvvisatura` non ha alcun effetto persistito**: nessun equivalente v3
   trovato (cercato "avvisatura" in tutto `govpay-common-pendenze`, zero
   riscontri) — né una colonna nell'entity `Pendenza`, né altrove. Il legacy ha
   invece colonne reali su `versamenti` (`data_notifica_avviso`,
   `avviso_notificato`, e **due varianti parallele** `avv_mail_data_prom_scadenza`/
   `avv_app_io_data_prom_scadenza` per il promemoria scadenza, mail vs AppIO).
   Implementato: solo la validazione del formato (`"MAI"` o data ISO), mai
   applicato a nulla. Decisione di design non presa: se/come estendere
   `Pendenza` con questi concetti è un cambio di schema più ampio di una
   semplice estensione di campo, lasciato al committente.
2. **`sendAbilitato`/campi "solo legacy" senza equivalente v3 individuale**:
   `Pendenza.proprieta` esiste già come singola colonna JSON grezza (nessun
   sotto-campo mappato individualmente) — `ProprietaPendenza` (`linguaSecondaria`,
   `descrizioneImporto`, `lineaTestoRicevuta1/2`, ecc.) viene quindi scritta per
   intero come JSON passthrough, non letta/validata campo per campo da questa
   libreria. Comportamento accettabile (simmetrico alla lettura, già fatta così
   da `PendenzaV2Mapper`), segnalato comunque per completezza.
3. **Contenuto binario degli allegati (`NuovoAllegatoPendenza.contenuto`) non
   persistito**: `allegati.raw_contenuto` è un **OID** Postgres in produzione
   (Large Object, non un array di byte ordinario — richiede
   `org.postgresql.largeobject.LargeObjectManager` sulla connessione JDBC
   sottostante), mentre lo schema H2 di test lo approssima come `BYTEA` solo per
   compatibilità DDL (commento preesistente nello schema, confermato in questa
   sessione). Un mapping JPA semplice (`@Lob byte[]`) funzionerebbe contro H2 ma
   non necessariamente contro il vero OID di PostgreSQL: implementata solo la
   scrittura dei metadati (nome/tipo/descrizione/dataCreazione, con
   `@GeneratedValue` sulla sequenza legacy `seq_allegati`), il contenuto base64
   in ingresso viene scartato. Alternative non scelte: (a) `AttributeConverter`/
   `UserType` dedicato basato su `LargeObjectManager` (comportamento corretto in
   produzione, ma non verificabile in questo ambiente di test H2, che non
   implementa la semantica OID di Postgres), (b) cambiare schema di storage
   dell'allegato (es. colonna `BYTEA` anche in produzione, richiede una
   migrazione DB fuori dallo scope di questa sessione).
4. **Semantica di aggiornamento degli allegati su una PUT di update non
   verificata contro il legacy**: implementato in modo conservativo — gli
   allegati dichiarati in un aggiornamento vengono sempre AGGIUNTI, mai usati
   per rimpiazzare quelli già presenti (nessuna cancellazione). Il comportamento
   esatto del legacy su questo punto (append vs replace) non è stato verificato
   a fondo nel tempo disponibile per questa sessione.
5. **Documenti con più di un'opzione di pagamento**: `aggiungiAPosizioneEsistente`
   richiede che il documento riusato abbia esattamente un'opzione di pagamento
   (il caso sempre vero per un documento creato da questo stesso wrapper) —
   un documento con zero o più opzioni (es. creato/modificato dal percorso
   v3-nativo) è rifiutato con un 400 esplicito invece di un comportamento
   indovinato.
6. **`descrizione` del documento sintetico**: `documenti.descrizione` è
   `NOT NULL` nello schema, ma un documento sintetico (nessun `documento` nella
   richiesta) non ha alcuna descrizione fornita dal chiamante — si riusa la
   `causale` della pendenza come fallback. Scelta pragmatica a basso rischio, non
   una vera decisione architetturale, segnalata comunque.
7. **`Soggetto.cellulare` (v2) e `NuovaVocePendenza.datiAllegati`/`contabilita`/
   `metadata` (v2, a livello di singola voce)**: nessun campo equivalente in
   `DatiSoggettoDebitore`/`DatiVocePendenza`/`VocePendenza` (v3) — gap noto,
   nessuna colonna li riceve. In particolare `contabilita.quote`
   (frazionamento per quote di una voce v2) e `VocePendenza.dettaglioContabile`
   (v3) sono concetti non sovrapponibili, non è stato tentato un mapping fra i
   due.
8. **Deviazione deliberata da un bug del legacy**: `fillSingoliVersamentiFromVociPendenza`
   del converter legacy va in NPE non gestita (`getTipoContabilita().name()`) se
   una voce non indica né bollo, né `codEntrata`, né un IBAN diretto completo.
   Qui si solleva invece un 400 esplicito (`ValidazioneNonSuperataException`) —
   stesso principio già adottato altrove in questa libreria per un elemento
   `null` della lista voci, per evitare un 500 non gestito al posto di un 400
   controllato.

**Verificato**: 305/305 test in `govpay-pendenze-api` (`mvn clean test` reale,
+7 rispetto a prima — i nuovi test di `AddPendenzaControllerTest`); nessuna
modifica a `govpay-common-pendenze` in questo punto (212/212 test, verificati
comunque con `mvn clean test` reale — +1 rispetto al conteggio di 211 riportato
al §30, non imputabile a questa sessione).

## 32. Wrapper v2: `updatePendenza` (PATCH `/pendenze/{idA2A}/{idPendenza}`) implementato (2026-10-25)

Chiude un altro dei cinque operationId ancora 501 elencati al §31
(`findRpp`/`getAllegatoPendenza`/`getAvviso`/`getAvvisiDocumento` restano
tracciati per il seguito). JSON-Patch sui 4 path fissi del legacy (`/stato`,
`/descrizioneStato`, `/ack`, `/nota` — stesso elenco di
`PendenzeDAO.OPERAZIONI_CONSENTITE_PENDENZE`), applicati nell'ordine della
richiesta alla stessa pendenza gestita, in un'unica transazione (nessun commit
intermedio fra le operazioni, come nel legacy).

**`/descrizioneStato`, `/ack`, `/nota`**: nessuna precondizione di stato nel
legacy per questi tre path, nessun comando dedicato in questa libreria — stesso
principio già applicato ai campi solo-legacy di `NuovaPendenzaMapper
#applicaCampiLegacy` (§31): setter diretti sull'entity gestita, trovata via
`PendenzaRepository#findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull`
nella transazione del controller. **`/descrizioneStato` e `/nota` scrivono
sulla STESSA colonna** `descrizioneStato` — replica fedele di un quirk legacy
(`PendenzeDAO.patch`, path `/nota`: estrae `value.oggetto` e lo scrive con lo
stesso `setDescrizioneStato` di `patchDescrizioneStato`); "ultima scrittura
vince" se entrambi i path appaiono nella stessa richiesta, come nel legacy. Il
parametro `motivazione` che `patch()` estrae dal sibling `/descrizioneStato`
per passarlo a `patchStato` non viene invece mai letto dentro quel metodo
(dead code legacy, verificato leggendo il corpo del metodo): non replicato,
non ha alcun effetto da riprodurre.

**`/stato`**: solo i due target che il legacy ammette (`ANNULLATA` →
annullamento, `NON_ESEGUITA` → ripristino; ogni altro valore, incluso
`SCADUTA` — che nel legacy non è nemmeno un vero `StatoVersamento`, solo un
filtro calcolato — è un 400, come nel legacy).

**Nota finale, vincolante per questo endpoint** (limitazione v3 che si
riflette deliberatamente nel wrapper, non aggirata): a differenza del legacy,
la v3 nativa non annulla/ripristina più una singola pendenza — lo fa solo a
livello di `OpzionePagamento`, con cascata su tutte le sue pendenze (chiusura
deliberata del gap "stati misti nella stessa opzione", vedi Javadoc di
`PosizioneDebitoriaService#registraEsitoPendenza`, nota 2026-10-22). Questo
wrapper non aggira quella scelta scrivendo lo stato direttamente sull'entity
(ricreerebbe esattamente l'artefatto che la libreria ha chiuso apposta): se
l'opzione della pendenza contiene PIÙ di una pendenza (es. una rata di un
piano rateale), la richiesta — che è per costruzione su una singola pendenza —
risponde **409**, invece di annullare/ripristinare anche le pendenze sorelle
non menzionate dal chiamante. Se l'opzione ha una sola pendenza, il
comportamento coincide con quello del legacy. Il wrapper eredita anche le
guardie v3 senza equivalente legacy (es. `ripristina` rifiuta se un'altra
opzione della stessa posizione è `ATTIVATA` — il legacy non ha mai avuto
questo controllo, non avendo il concetto di opzione): nessuna di queste viene
scavalcata, per lo stesso motivo. In generale: ogni limitazione introdotta
deliberatamente dalla libreria v3 (qui e altrove) si propaga al wrapper tale
e quale, non viene mai scritta attorno per inseguire una fedeltà al legacy
più ampia di quella che la libreria sottostante permette.

**Idempotenza di annullamento/ripristino: simmetrica nella libreria v3,
asimmetrica nel legacy — differenza di comportamento accettata, non una
limitazione** (verificato leggendo `PendenzeDAO.patchStato`, righe 666-707):
il legacy tratta l'annullamento come idempotente (da `NON_ESEGUITO` o già
`ANNULLATO` → sempre 200) ma il ripristino **non** lo è (accettato solo da
`ANNULLATO`; da `NON_ESEGUITO`, cioè già ripristinato, il legacy risponde 400,
non un no-op). Il service v3 (`PosizioneDebitoriaService#annulla(UUID)`/
`#ripristina(UUID)`) è invece simmetrico: entrambe le operazioni sono no-op
quando l'opzione è già nello stato target. Il wrapper eredita questa
simmetria senza riprodurre l'asimmetria legacy: un ripristino ripetuto su
un'opzione già `DISPONIBILE` risponde 200 (no-op), non 400 come nel legacy.
A differenza della nota precedente, qui non si tratta di un vincolo che il
wrapper subisce passivamente, ma di una differenza di comportamento
acconsentita esplicitamente. **Punto indipendente dal 409 sulle opzioni con
più di una pendenza descritto sopra**: quella guardia resta incondizionata —
non diventa permissiva nei casi in cui la richiesta, a livello di singola
pendenza, sarebbe comunque un no-op; nessuna eccezione implicita è stata
aggiunta a quel controllo.

**File nuovi/modificati** (tutti in `govpay-pendenze-api`, nessuna modifica a
questa libreria): `PendenzaV2Controller` (nuovo override `updatePendenza` più
i metodi privati `applicaPatch`/`patchDescrizioneStato`/`patchNota`/`patchAck`/
`patchStato`/`validaOp`/`parseStatoPendenza`); nuovo test di integrazione
`UpdatePendenzaControllerTest` (stesso impianto `@SpringBootTest`/`MockMvc`/H2
di `AddPendenzaControllerTest`, senza lo stub di `govpay-stampe`, qui non
necessario).

**Verificato**: 314/314 test in `govpay-pendenze-api` (`mvn clean test` reale,
+9 rispetto al §31 — i nuovi test di `UpdatePendenzaControllerTest`); nessuna
modifica a `govpay-common-pendenze` in questo punto.

## 33. Wrapper v2: `findRpp` (GET `/rpp`) implementato (2026-10-25)

Chiude un altro dei quattro operationId ancora 501 elencati al §32
(`getAllegatoPendenza`/`getAvviso` restano sospesi su decisione esplicita,
vedi §35). Vive in `RppV2Controller`, già sede di `getRpp`/`getRpt`/`getRt`.

Richiede una capacità non ancora presente in questa libreria: una query
filtrata a join fra `Rpt` e `Pendenza` (nessuna relazione JPA mappata fra le
due entity, solo FK piatte — join ad-hoc in JPQL, analogo a
`PendenzaRepository#cercaFiltrate`). Aggiunte a `RptRepository`:
`cercaFiltrate`/`cercaFiltrateSenzaConteggio`, un nuovo
`record RptConPendenza(Rpt rpt, Pendenza pendenza)` popolato via
`select new ...`, e un nuovo value object `FiltriRpt` (analogo a
`FiltriPendenze`). `RicevutaRendicontazioneService` espone
`cercaRptFiltrate`/`cercaRptFiltrateSenzaConteggio` sopra queste query.
`Rpt.codEsitoPagamento` resta un Integer "non interpretato" in questa
libreria per scelta deliberata — la mappatura verso l'enum v2 (`EsitoRpp`)
vive solo nel wrapper, mai qui:

| `EsitoRpp` (v2) | `Rpt.codEsitoPagamento` |
|---|---|
| `ESEGUITO` | 0 |
| `NON_ESEGUITO` | 1 |
| `ESEGUITO_PARZIALE` | 2 |
| `DECORRENZA` | 3 |
| `DECORRENZA_PARZIALE` | 4 |
| `IN_CORSO` | 100 |
| `RIFIUTATO` | 101 |

**Tre scostamenti dal mandato iniziale dato all'agente, risolti a favore
della fedeltà al legacy (verificata leggendo il codice) invece delle
istruzioni originarie, confermati dal committente**:

1. **`idA2A` non è un filtro aggiuntivo**: il legacy lo valida ma lo
   sovrascrive sempre con l'applicazione del chiamante autenticato (stesso
   comportamento già stabilito per `findPendenze`) — nessuna risoluzione
   verso un'altra applicazione è mai possibile.
2. **`iuv` è un "contiene" case-insensitive**, non un match esatto — stesso
   trattamento di `ccp` (`RptFilter` usa `ilike(IUV, iuv, LikeMode.ANYWHERE)`
   per entrambi).
3. **Solo due campi sono ordinabili**: `dataRichiesta` (→
   `Rpt.dataMsgRichiesta`) e `stato` (→ `Rpt.stato`) — gli unici due che
   `ListaRptDTO` abbia mai registrato nel legacy. Ordinamento di default:
   `dataMsgRichiesta DESC`, come nel legacy.

**Bug legacy replicato fedelmente**: il parametro `idPagamento` è letto e
passato al DTO, ma `RptFilter` non ha mai avuto un campo corrispondente — un
no-op puro, accettato ma sempre ignorato come filtro, documentato in Javadoc
anziché corretto.

**File nuovi/modificati**: `govpay-pendenze-api` — `RppV2Controller` (nuovo
override `findRpp`, `codificaEsito(EsitoRpp)`, helper di paginazione/
ordinamento), nuovo `FindRppControllerTest` (11 test); `govpay-common-pendenze`
— `RptRepository` (nuove query), nuovo `RptConPendenza`, nuovo `FiltriRpt`,
`RicevutaRendicontazioneService` (nuovi metodi di ricerca filtrata). Nessun
nuovo test diretto in questa libreria per la nuova query JPQL — coperta solo
indirettamente dal test di integrazione del wrapper.

## 34. Wrapper v2: `getAvvisiDocumento` (GET `/documenti/{idDominio}/{numeroDocumento}/avvisi`) implementato (2026-10-25)

Chiude l'ultimo degli operationId "senza gap" del §32. Vive in
`PendenzaV2Controller`. Il documento (= `PosizioneDebitoria`) è risolto
sempre e solo sull'applicazione autenticata corrente (nessun `idA2A` nella
richiesta — stesso principio del legacy, "un'applicazione vede solo i suoi
documenti"): `idDominio` + `numeroDocumento` (= `codDocumento` diretto,
nessuna traduzione) via
`PosizioneDebitoriaRepository#findByIdApplicazioneAndIdDominioAndCodDocumento`.
Le rate stampabili sono filtrate a `StatoPendenza.NON_ESEGUITO`, ordinate per
`numeroRata` crescente. `AvvisoPdfPayloadMapper` è stata estesa con
`toPaymentNoticeRate(List<Pendenza>)` per popolare `instalments` (un
`Instalment` per rata) sul modello client di `govpay-stampe`, che già
supportava questo campo senza alcuna estensione lato microservizio.

**Bug legacy replicato fedelmente**: `numeriAvviso` è validato nel formato
(stesso controllo di `validaFormatoNumeroAvviso`, riusato qui) ma non è mai
stato un filtro reale nel legacy (`IuvUtils.toIuv` valida, non filtra) — tutte
le rate pagabili del documento sono sempre incluse indipendentemente dal
valore passato.

**Punto chiuso (rivisto il 2026-10-26)**: la scelta fra `full` e `instalments`
nel payload per `govpay-stampe` era stata inizialmente decisa per tipologia
dell'opzione (`PIANO_RATEALE` → sempre `instalments`, anche con una sola rata;
`SOLUZIONE_UNICA` con una pendenza → `full`; `SOLUZIONE_UNICA` con più di una
pendenza → `instalments` come ripiego, basandosi sull'assunzione — mai
verificata — che il legacy stampasse un avviso separato per pendenza in
quest'ultimo caso). Verificando `AvvisoPagamento#getBytesAvvisoDocumento` del
legacy, quell'assunzione era sbagliata: il metodo costruisce **sempre un solo
PDF** per l'intera lista di versamenti pagabili del documento — mai una
chiamata ripetuta per versamento — ordinando le rate per `numeroRata`/soglia e
raggruppando esplicitamente "su una sola pagina" le pendenze senza
`numeroRata` (commento originale nel codice, che anticipa proprio il caso di
più di una). La scelta `full`/`instalments` segue quindi la **cardinalità**
delle rate pagabili, non la tipologia dell'opzione: una sola rata pagabile →
`full` (anche per un `PIANO_RATEALE` che ha persistito solo la prima rata);
più di una → `instalments` — questo vale anche per una `SOLUZIONE_UNICA` con
più pendenze (possibile in v3, §3.3 — a differenza di
`SOLUZIONE_UNICA_ENTRO`/`_OLTRE`, che restano sempre a esattamente una), per lo
stesso motivo strutturale delle rate, non come ripiego. Nuovi test:
`pianoRatealeConUnaSolaRataProduceFull`, `soluzioneUnicaConPiuPendenzeProduceInstalments`.

Altri casi gestiti, non in discussione: nessuna rata pagabile sul documento →
422 (`AvvisoDocumentoNonDisponibileException`, nuova, mappata come le altre
eccezioni "avviso non generabile"); `linguaSecondaria` diverso da `false` →
stesso 422 (nessun parametro `causaleTradotta` su questo endpoint — bilingue
rifiutato esplicitamente, per decisione già presa a monte, non lasciata
all'agente); documento di un'altra applicazione → 404 (mai 403, perché la
query di risoluzione è già scoperta solo sull'applicazione corrente — non
testato con una seconda applicazione fittizia). Margine noto allora segnalato
(rate con IBAN postale diversi fra loro in uno stesso avviso multi-rata, il
flag "postale" seguiva solo la prima rata) **risolto in revisione successiva
(2026-10-27, vedi §39)**: ora `postal` è `true` solo se TUTTE le rate hanno
un IBAN postale, altrimenti l'intero avviso è non postale.

**File nuovi/modificati**: `PendenzaV2Controller` (nuovo override
`getAvvisiDocumento`, nuova dipendenza `PosizioneDebitoriaRepository`);
`AvvisoPdfPayloadMapper` (nuovo `toPaymentNoticeRate`, header condiviso
estratto in `intestazione()`/`dominioDi()`); nuova
`AvvisoDocumentoNonDisponibileException`; `ProblemExceptionHandler` (nuova
eccezione aggiunta all'elenco esistente, mappata a 422); nuovo test
`GetAvvisiDocumentoControllerTest` (458 righe, riusa l'impianto di stub HTTP
di `AddPendenzaControllerTest` per `govpay-stampe`).

**Verificato**: 351/351 test in `govpay-pendenze-api` (+2 rispetto al §37);
nessuna modifica a `govpay-common-pendenze` in questo punto.

## 38. govpay-pendenze-api#5 "Aggiunta stampa posizione debitoria": implementato end-to-end (2026-10-26)

Issue nata come una riga ("Aggiungere alle API Pendenze la funzionalità di
stampa della posizione debitoria", stato *Triage*/*Unplanned*, nessun
dettaglio) — emersa riesaminando il §34, ma di scope **più ampio e distinto**
da quel fix: non blocca né è bloccata da esso (il §34 è stato chiuso senza
attendere questa issue).

**Scoping deciso col committente**: copre tutta la `PosizioneDebitoria`,
incluse eventuali `OpzionePagamento` alternative (es. "paga tutto" vs "paga a
rate" sullo stesso debito — concetto reale pagoPA/GPD, `PaymentOptionModel`
multiplo su un `PaymentPositionModel`); artefatto di output: avviso pagoPA via
`govpay-stampe` (stesso meccanismo `PaymentNotice`/`StampeClient` già in uso).

**Vincolo strutturale emerso, non ancora risolto**: `numeroAvviso`/`iuv`
vivono su `Pendenza` (M8/M13), non su `OpzionePagamento` né su
`PosizioneDebitoria` — ogni opzione alternativa ha quindi un proprio IUV
distinto, un arrangiamento di pagamento pagoPA realmente separato. Un avviso
PDF è per costruzione legato a un solo IUV (o a un NAV condiviso fra le rate
di UNA stessa opzione piano rateale, §29): non esiste nel protocollo pagoPA
il concetto di "un avviso che offre la scelta fra opzioni alternative". Lo
scoping "tutta la posizione, incluse le opzioni alternative, come avviso
pagoPA" si scontra quindi con lo stesso vincolo del §34 (un PDF, un IUV) ma a
un livello più alto: resta da decidere come l'endpoint debba comportarsi
quando la posizione ha più opzioni alternative disponibili (un parametro che
seleziona l'opzione da stampare, con un avviso per chiamata; una risposta
multi-documento come uno zip; o altro) — **non ancora deciso**, prossimo
punto da chiarire prima di iniziare l'implementazione.

**Risolto (2026-10-26): un solo PDF, una pagina per opzione, nessun merge**.
Verificato in `AvvisoPagamentoMapper` (`govpay-stampe-api`) che la
paginazione multi-pagina da UNA sola chiamata esiste già — è esattamente
come `instalments` dispone le rate su più pagine (`creaPagineMultiple`,
layout a 1/2/3 colonne) — e che ogni riga porta già il proprio
`notice_number`/IBAN distinto (`prossimaRata`/`impostaDatiPostaliNellaRata`):
più IUV diversi nello stesso PDF non è un problema nuovo. L'unico gap reale
era l'assenza di un confine esplicito fra gruppi: l'algoritmo impacchetta
liberamente più righe per pagina, quindi senza un confine l'ultima rata di
un'opzione e la prima della successiva finirebbero sulla stessa pagina.
**Decisione del committente**: il criterio di stampa non distingue per
tipologia dell'opzione (quella distinzione resta solo in fase di inserimento
dati, dove decide la cardinalità massima ammessa, §3.3) — in stampa conta
solo quante pendenze ha l'opzione: più di una va su un unico foglio
cumulativo (stesso criterio del §34), e ogni opzione occupa sempre pagina(e)
propria, mai condivisa con un'altra opzione.

**Contratto proposto in `govpay-stampe-api`** (`govpay-stampe.yaml`, nessuna
nuova rotta — stesso `POST /standard`/`createPaymentNotice`): nuovo campo
`PaymentNotice.options` (array, minimo 2 elementi, mutuamente esclusivo con
`full`/`instalments`/`reduced_payments` a livello di documento) di
`PaymentNoticeOption` (stessa struttura di importo di `PaymentNotice`, senza i
metadati del documento — creditore/debitore/lingua restano unici e condivisi
a livello di `PaymentNotice`, dato che tutte le opzioni di una stessa
posizione hanno lo stesso creditore e lo stesso debitore). Generazione
verificata (`mvn generate-sources`): nessun campo marcato erroneamente
obbligatorio dall'`allOf` (`PaymentNoticeOption` è un oggetto piatto, non
un'estensione via `allOf`, quindi non soggetto al problema di generazione già
noto in `govpay-pendenze-api`, §31 "Scoperta non ovvia" — qui annotato solo
come riferimento tecnico, non nello YAML pubblico che descrive solo le
regole del dato).

**Implementato (2026-10-26)**: `SemanticValidator#validazioneSemanticaOptions`
gestisce la mutua esclusività (`options` vs `full`/`instalments`/
`reducedPayments` a livello di documento, e `options` vs `secondLanguage` —
quest'ultimo rifiutato esplicitamente con 422, il mapper bilingue non conosce
ancora `options`) e valida ogni elemento con le stesse regole già esistenti
per il documento a opzione singola (rata unica/rate/soglie obbligatorie,
mutua esclusività rate/soglie, IBAN su avviso postale). `AvvisoPagamentoMapper
#creaPagineOpzioni` costruisce le pagine di ciascuna opzione delegando, IN
SEQUENZA, agli stessi metodi di dispatch già usati per il documento a
opzione singola (`creaRatePerAvvisoSemplice`/`Postale`/`creaRateRidotte...`),
su un `PaymentNotice` sintetico che porta solo i campi di importo
dell'opzione — nessuna modifica ai metodi di basso livello che costruiscono
le pagine (`creaPaginaUnicaConRate`/`creaPagineMultiple`/ecc.), quindi nessun
rischio per il comportamento già testato del documento a opzione singola.
Il confine fra opzioni è garantito dalla sequenzialità delle chiamate (ogni
opzione riparte da una lista di rate vuota), non da un controllo esplicito
aggiuntivo — verificato con test dedicati che ispezionano
`AvvisoPagamentoInput.getPagine()` pagina per pagina.
`options` + avviso bilingue resta esplicitamente non supportato (422), non
un gap silenzioso.

**Verificato**: 219/219 test in `govpay-stampe-api` (`mvn test` reale, +12 —
`UC_12_AvvisoOpzioniPagamentoFailTest`, 6 casi di validazione,
`UC_13_AvvisoOpzioniPagamentoTest`, 6 casi positivi inclusa la verifica
struttura-pagine).

**Tre difetti trovati in revisione indipendente di questa implementazione, confermati e
corretti (2026-10-26)**:

1. **Un'opzione poteva perdere un avviso accettato dalla validazione**: `creaPagineOpzioni`
   riusa `creaRatePerAvvisoSemplice` per ciascuna opzione — questo metodo, se `full` e'
   presente insieme a esattamente una rata in formato non postale, stampa SOLO la rata (ramo
   dedicato `instalments.size() == 1`, riga 203): `full` scompare senza errore. Comportamento
   preesistente (lo stesso gap esisterebbe anche su un documento a opzione singola con questa
   combinazione), riusato senza modifiche dal nuovo codice. **Fix**: nuovo controllo in
   `SemanticValidator#validazioneSemanticaOption` — `full` insieme a esattamente una rata in
   un elemento di `options` non postale e' rifiutato (422), invece di perdere silenziosamente
   `full` in stampa. Non postale perche' in formato postale la combinazione NON perde nulla
   (`creaRatePerAvvisoPostale` stampa `full` e la rata su due pagine singole separate, sempre).
   Nuovi test: `UC_12_08_Opzioni_FullConUnaSolaRataNonPostale_Rifiutata`,
   `UC_13_07_FullConUnaSolaRataPostale_DuePagineSingoleSeparate` (verifica che il caso postale,
   non rifiutato, produca davvero due pagine distinte).
2. **L'intestatario postale dell'ultima opzione sovrascriveva quello delle precedenti**:
   `AvvisoPagamentoInput.intestatarioContoCorrentePostale` e' un campo UNICO per l'intero
   documento (`BaseAvvisoMapper#impostaDatiPostaliNellaRata`, riga 68, lo sovrascrive a ogni
   importo processato — mai un dato per pagina/rata), condiviso dallo stesso
   `AvvisoPagamentoInput` fra tutte le opzioni (`creaPagineOpzioni`, riga 118). Con intestatari
   diversi tra opzioni, anche le pagine delle opzioni precedenti avrebbero mostrato in stampa
   l'intestatario dell'ultima opzione processata. **Fix temporaneo** (il dato per pagina/rata
   richiederebbe toccare il modello/template Jasper, fuori scope qui): nuovo
   `SemanticValidator#validazioneSemanticaIntestatarioPostaleUnico`, chiamato solo quando
   `postal=true` — rifiuta (422) se le opzioni risolvono IBAN con intestatari diversi (stessa
   logica di fallback di `impostaDatiPostaliNellaRata`: intestatario IBAN se non vuoto,
   altrimenti l'ente creditore). Nuovo test:
   `UC_12_09_Opzioni_PostaleConIntestatariDiversi_Rifiutata`.
3. **Un elemento `null` in `options` causava una `NullPointerException` (500)**: la
   validazione strutturale (bean validation) non impedisce un elemento `null` in una lista
   `@Valid`; il ciclo di `SemanticValidator#validazioneSemanticaOptions` chiamava
   `validazioneSemanticaOption(option, ...)` senza controllare `option == null` prima di
   accedere ai suoi campi. **Fix**: controllo esplicito nel ciclo, 422 invece di 500. Nuovo
   test: `UC_12_07_Opzioni_ElementoNull_Rifiutato`.

Corretto anche lo YAML pubblico: la descrizione di `options` diceva che `full`/`instalments`/
`reduced_payments` vengono "ignorati" se presenti insieme a `options`, mentre il codice li
rifiuta (422) — comportamento diverso da quanto descritto. Aggiunta anche la dichiarazione,
mancante, che `options` non supporta ancora l'avviso bilingue.

**Verificato**: 223/223 test in `govpay-stampe-api` (`mvn test` reale, +4 rispetto alla prima
implementazione di questo paragrafo).

**Endpoint `govpay-pendenze-api` implementato (2026-10-26)**: nuovo
`GET /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/stampa`
(`getStampaPosizioneDebitoria`, `PosizioneDebitoriaController`) — il contratto
lato `govpay-stampe-api` sopra era necessario ma non sufficiente, mancava
ancora chi lo invoca per questo caso d'uso. Copia locale di
`govpay-stampe.yaml` (usata solo per generare il client HTTP, vedi `pom.xml`)
sincronizzata con la versione aggiornata.

**Opzioni considerate — corretto dopo revisione del committente**: la prima
versione escludeva esplicitamente le opzioni `ANNULLATA` con un controllo
dedicato sullo stato dell'opzione — una regola senza alcun precedente nel
legacy (che non ha mai avuto il concetto di opzione), introdotta di
iniziativa durante l'implementazione e non confermata in anticipo dal
committente nello scoping del §38. Richiesta verifica puntuale nel legacy
(`AvvisoPagamento.getBytesAvvisoDocumento`/`Documento.getVersamentiPagabili`,
`jars/core`/`jars/orm`): il filtro legacy e' **solo**
`StatoVersamento.NON_ESEGUITO`, sul flat dei versamenti del documento, senza
alcun concetto di opzione. Verificato poi in `PosizioneDebitoriaService`
(`cascataStatoPendenze`, chiamata sia dalla cascata automatica di `attiva()`
che da `annulla()`) che quando un'opzione passa ad `ANNULLATA` TUTTE le sue
pendenze vengono esplicitamente riscritte a `StatoPendenza.ANNULLATO` — non
restano `NON_ESEGUITO`. Il controllo sullo stato dell'opzione era quindi
ridondante: il solo filtro legacy (`NON_ESEGUITO`) esclude gia' da se' le
opzioni annullate, per costruzione (l'invariante e' verificata anche da
`verificaTutteLePendenzeNelloStato`). **Rimosso** da `opzioniStampabili`:
resta solo il filtro sulle pendenze pagabili di ciascuna opzione, con le
opzioni senza alcuna pendenza pagabile (annullate o del tutto eseguite, es.
l'ultima rata di un piano rateale appena pagata) escluse senza distinzione —
nessun errore, semplicemente niente da stampare per quell'opzione. Nessuna
opzione stampabile: 422 (`AvvisoDocumentoNonDisponibileException`, stessa
classe usata da `PendenzaV2Controller.getAvvisiDocumento`, §34).

**Stesso fix applicato al wrapper v2** (`PendenzaV2Controller.getAvvisiDocumento`,
§34): il controllo `opzioni.size() != 1` → 400 contava TUTTE le opzioni del
documento, incluse quelle ormai `ANNULLATA` — un documento creato da questo
wrapper (sempre con una sola opzione all'origine) a cui un client v3-nativo
avesse aggiunto un'opzione alternativa, poi automaticamente annullata
dall'attivazione dell'una o dell'altra, risultava quindi erroneamente 400
(due opzioni) anche quando restava esattamente un'opzione ancora pagabile.
Corretto a parita' di principio con `opzioniStampabili`: si raggruppano le
pendenze pagabili per opzione, si scartano le opzioni senza alcuna pendenza
pagabile, e solo SE ne resta piu' di una il wrapper rifiuta con 400 (ambiguo,
questo wrapper gestisce solo un'opzione pagabile per documento) — nessun
controllo sullo stato dell'opzione, stesso motivo di cui sopra.

**Layout**: esattamente un'opzione stampabile → stesso avviso di
`getStampaPendenza`/`getAvvisiDocumento` (`full` o `instalments` secondo la
cardinalita' delle sue rate pagabili, mai la tipologia). Piu' di una → un
solo PDF con `PaymentNotice.options`
(`AvvisoPdfPayloadMapper#toPaymentNoticeOpzioni`, nuovo metodo, stessa
intestazione/bollettino postale della prima pendenza della prima opzione,
stesso principio di `toPaymentNoticeRate`). MBT/anagrafica debitore assente
su QUALUNQUE pendenza stampabile di QUALUNQUE opzione fa fallire l'intera
stampa con 422, nessuna stampa parziale che ometta solo l'opzione difettosa
— stessa scelta del §34.

**Nessun formato bilingue per questo endpoint** (nessun parametro
`linguaSecondaria`/`causaleTradotta`): con piu' di un'opzione stampabile il
contratto di `govpay-stampe-api` non supporta comunque `options` insieme a
`secondLanguage`, e introdurre il bilingue solo per il caso a una opzione
renderebbe il comportamento dell'endpoint incoerente in base ai dati — stesso
compromesso gia' scelto per `getAvvisiDocumento` (che pero' rifiuta con 422
anziche' non esporre affatto il parametro, visibile solo perche' quell'
endpoint e' un wrapper v2 con un parametro legacy da replicare fedelmente;
qui non c'e' alcun vincolo di fedelta' da rispettare, quindi il parametro e'
stato omesso del tutto dal contratto invece che accettato e rifiutato).

**Verificato**: 364/364 test in `govpay-pendenze-api` (+13 — 4 su
`AvvisoPdfPayloadMapper#toPaymentNoticeOpzioni`, 9 su
`GetStampaPosizioneDebitoriaControllerTest`, incluso il caso di un'opzione
`ATTIVATA` con rate ancora pagabili e l'alternativa `ANNULLATA`
automaticamente). Rimasti 364/364 anche dopo la correzione sopra (rimozione
del controllo ridondante su `ANNULLATA` in `opzioniStampabili` e stesso fix
in `getAvvisiDocumento`): nessun test esistente dipendeva dal controllo
rimosso, il comportamento osservabile non cambia. Nessuna modifica a
`govpay-common-pendenze` in questo punto.

## 39. Due difetti trovati in revisione del committente su `AvvisoPdfPayloadMapper`, corretti (2026-10-27)

Revisione puntuale (non di questa sessione di sviluppo) su `AvvisoPdfPayloadMapper`, con
riferimenti precisi a riga.

1. **`SOLUZIONE_UNICA_ENTRO`/`_OLTRE` perdevano la condizione temporale**: il mapper
   (`toPaymentNotice`/`toOption`) decideva `full` vs `instalments` solo sulla CARDINALITA' delle
   pendenze pagabili, senza mai controllare `OpzionePagamento.getTipologia()` — una pendenza
   singola di un'opzione `SOLUZIONE_UNICA_ENTRO`/`_OLTRE` finiva quindi in `full`, perdendo
   l'indicazione "entro"/"oltre N giorni" che la contraddistingue (`giorni` dell'opzione mai
   letto). Confermato nel contratto `govpay-stampe.yaml` (`PaymentNotice`/`PaymentNoticeOption`,
   righe 328-426) che esiste un campo dedicato per questo, `reduced_payments` (array di
   `ThresholdPayment`, `allOf` di `Amount` + `threshold_type`/`threshold_days` — **non** un
   oggetto a parte di `full`, ogni elemento porta gia' il proprio importo) — e nel mapper lato
   render (`AvvisoPagamentoMapper`, `govpay-stampe-api`) che la dispatch controlla
   `hasReducedPayments` PRIMA di `full`/`instalments`: popolare `full` da solo per queste due
   tipologie non avrebbe mai attivato il rendering con l'etichetta di soglia. Stesso pattern
   gia' corretto lato JSON (`PendenzaV2Mapper`, switch su `getTipologia()` verso
   `VincoloPagamento`) — qui mancava il porting equivalente lato PDF. **Fix**: nuovo
   `isSogliaTemporale`/`mapReducedPayment` in `AvvisoPdfPayloadMapper` — una pendenza di
   un'opzione `SOLUZIONE_UNICA_ENTRO`/`_OLTRE` produce sempre `reduced_payments` (un solo
   elemento, cardinalita' massima 1 per queste due tipologie) invece di `full`, con
   `threshold_type`/`threshold_days` dalla tipologia/`giorni` dell'opzione. Applicato sia al
   caso singola-opzione (`toPaymentNotice`, usato da `getStampaPendenza`/`getAvvisiDocumento`)
   sia al caso opzioni-alternative (`toOption`, dentro `toPaymentNoticeOpzioni`, §38) — nello
   stesso punto in cui prima si perdeva l'indicazione.

2. **`postal` seguiva solo la prima opzione/rata**: `toPaymentNoticeRate`/`toPaymentNoticeOpzioni`
   impostavano `PaymentNotice.postal` guardando solo l'IBAN della prima pendenza (rispettivamente
   la prima rata, la prima pendenza della prima opzione) — gia' segnalato come margine noto per
   `toPaymentNoticeRate` nel §34, non ancora per `toPaymentNoticeOpzioni` (dove il caso e' ancora
   piu' probabile: opzioni alternative come "paga tutto"/"paga a rate" possono avere tributi/IBAN
   diversi). Con una configurazione mista (alcune pendenze con IBAN postale, altre senza),
   govpay-stampe riceveva `postal=true` ma righe senza IBAN — rifiutato, propagato come 502
   opaco da questo wrapper — oppure, nell'ordine inverso, `postal=false` che fa perdere in
   silenzio il bollettino alle pendenze che un IBAN lo avrebbero. **Decisione del committente**:
   formato non postale per l'intero avviso quando la configurazione è mista (non un rifiuto
   esplicito, nonostante il precedente di `govpay-stampe-api` per l'intestatario postale diverso
   fra opzioni, §38). **Fix, in entrambi i metodi**: `postal` è `true` solo se TUTTE le
   pendenze considerate (tutte le rate, o tutte le pendenze di tutte le opzioni) hanno un IBAN
   postale — non più solo la prima.

Nuovi test in `AvvisoPdfPayloadMapperTest`: 5 per `reduced_payments`
(`soluzioneUnicaEntroProduceReducedPaymentsConSogliaInveceDiFull`,
`soluzioneUnicaOltreProduceReducedPaymentsConSogliaOltre`,
`soluzioneUnicaSenzaSogliaRestaFull`, `ibanPostaleSuReducedPaymentQuandoLaPendenzaHaUnBollettino`,
`opzioneEntroDentroOpzioniAlternativeProduceReducedPaymentsSullOpzione`), 3 per il `postal` misto
(`toPaymentNoticeRatePostaleSoloSeTutteLeRateHannoUnIbanPostale`,
`toPaymentNoticeRatePostaleSeTutteLeRateHannoUnIbanPostale`,
`toPaymentNoticeOpzioniPostaleSoloSeTutteLeOpzioniHannoUnIbanPostale`).

**Verificato**: 372/372 test in `govpay-pendenze-api` (+8 rispetto al §38). Nessuna modifica a
`govpay-common-pendenze` in questo punto.

## 40. Coppia `SOLUZIONE_UNICA_ENTRO`/`_OLTRE`: un solo avviso con `reduced_payments` a due elementi, non `options` (2026-10-27)

Verificato nel vero codice legacy (`jars/core`, non in `govpay-console-api`, che non implementa affatto
questa funzionalità): `AvvisoPagamento.getBytesAvvisoDocumento` non rifiuta mai un documento con più
versamenti ancora pagabili (eccezione solo se la lista è vuota) e, con una coppia ENTRO+OLTRE, li stampa
sulla STESSA pagina come due righe indipendenti (`AvvisoPagamentoUtils.creaPaginaPrincipaleDoppia`). Non
esiste una struttura che fonda esplicitamente le due righe in una sola (quella esiste solo per il caso
RIDOTTO+SCONTATO, diverso da ENTRO/OLTRE) — ma il layout "una pagina, due righe" è il comportamento reale.

L'implementazione precedente (§38/§39) trattava invece ENTRO e OLTRE come due opzioni alternative generiche
(stesso trattamento di "paga tutto"/"paga a rate"): in v3 due pagine separate via `options`, nel wrapper v2
un 400. Nessuno dei due replica il layout del legacy. **Corretto**: quando le opzioni ancora pagabili sono
ESATTAMENTE la coppia `SOLUZIONE_UNICA_ENTRO`/`_OLTRE` (nuovo `AvvisoPdfPayloadMapper#sonoTutteSoglie` +
`#toPaymentNoticeSoglie`), si produce un solo `PaymentNotice` con `reducedPayments` a due elementi (ordinati
per `giorni` crescente, a parità `ENTRO` prima di `OLTRE` — stesso criterio del comparator legacy in
`AvvisoPagamento.java`), sia in `getStampaPosizioneDebitoria` (v3) sia in `getAvvisiDocumento` (wrapper v2,
che quindi NON rifiuta più più questo caso specifico con 400). Il caso di opzioni alternative genuine (non
soglia) resta come deciso in precedenza: nessun precedente legacy, `options`/400.

**Gap scoperto incidentalmente e corretto**: il percorso di scrittura del wrapper v2
(`NuovaPendenzaMapper.crea`) leggeva solo `documento.rata` per decidere la tipologia dell'opzione — non
leggeva mai `documento.soglia` (`VincoloDocumento`/`VincoloPagamento`, ENTRO/OLTRE), nonostante lo schema
v2 lo dichiari in scrittura. Una posizione con opzioni `SOLUZIONE_UNICA_ENTRO`/`_OLTRE` poteva quindi
esistere solo se creata via l'endpoint v3-nativo (o migrata), mai tramite questo stesso wrapper — stesso
tipo di gap già visto per `numeriAvviso` (validato ma mai applicato), qui per la scrittura invece che per
la lettura. **Corretto**: nuovo `NuovaPendenzaMapper#risolviTipologia` (usato sia in creazione sia in
`aggiungiAPosizioneEsistente`) — `documento.soglia` presente → `SOLUZIONE_UNICA_ENTRO`/`_OLTRE` con
`giorni` dalla soglia; `rata` e `soglia` insieme nello stesso body → 400 (mutua esclusività, già prevista
nel commento dello YAML ma non ancora implementata). Nuovi test in `AddPendenzaControllerTest`:
`creaConDocumentoESogliaEntroCreaSoluzioneUnicaEntro`, `creaConDocumentoESogliaOltreCreaSoluzioneUnicaOltre`,
`rataESogliaInsiemeRestituisce400`.

**Verificato**: 382/382 test in `govpay-pendenze-api` (+10 rispetto al §39 — 4 test sul mapper
`toPaymentNoticeSoglie`/`sonoTutteSoglie`, 1 su `GetStampaPosizioneDebitoriaControllerTest`, 2 su
`GetAvvisiDocumentoControllerTest` incluso il caso di due opzioni alternative genuine che restano 400, 3 su
`AddPendenzaControllerTest` per `documento.soglia` in scrittura).
Nessuna modifica a `govpay-common-pendenze` in questo punto.

## 41. `RIDOTTO`/`SCONTATO` distinti da `ENTRO`/`OLTRE`, instradati su `POST /cds_violation` (2026-10-27)

Verificato che `govpay-stampe-api` ha già un endpoint dedicato (`POST /cds_violation`,
`createCdsViolationNotice`, schema `CdsViolation` con `discounted_amount`/`reduced_amount`) con
mapper (`ViolazioneCdsMapper`), service e template Jasper propri (`ViolazioneCDS.jasper`,
`RidottoScontato.jasper`) per questo caso — porting completo, non vestigiale. govpay-pendenze-api
non lo richiamava mai: `StampeClient` instradava solo su `/standard`, e la migrazione (§28,
`06_opzioni_pagamento_documenti_esistenti.sql`) fondeva `SCONTATO`/`RIDOTTO` in
`SOLUZIONE_UNICA_ENTRO`/`_OLTRE`, perdendo l'informazione necessaria per instradare correttamente.

**Corretto, sui tre repository**:
- **govpay-common-pendenze**: `TipologiaOpzionePagamento` esteso a 6 valori
  (`SOLUZIONE_UNICA_RIDOTTO`/`_SCONTATO` nuovi). `richiedeGiorni()` resta `true` solo per
  `ENTRO`/`OLTRE` — per `RIDOTTO`/`SCONTATO` `giorni` non è un valore dichiarabile (vietato in
  input anche nel vero legacy, nessuna colonna lo porta). Migrazione PostgreSQL corretta per
  mappare `SCONTATO`→`SOLUZIONE_UNICA_SCONTATO`, `RIDOTTO`→`SOLUZIONE_UNICA_RIDOTTO` (non più
  fusi in ENTRO/OLTRE); gli altri tre dialetti (oracle/mysql/sqlserver) non implementano ancora
  questo pattern C, nessuna modifica necessaria lì.
- **govpay-pendenze-api**: schema v3 esteso (`TipologiaOpzionePagamento`, famiglie
  `NuovaOpzionePagamento`/`OpzionePagamento`/`OpzionePagamentoIndex` con le due nuove varianti,
  senza campo `giorni`); schema v2 (`TipoSogliaVincoloPagamento`) esteso a 4 valori, allineato al
  vero enum legacy (`wars/api-pendenze`, non al suo YAML pubblico, che era incompleto). Tutti gli
  switch su `TipologiaOpzionePagamento` (`PosizioneDebitoriaMapper`, `PendenzaV2Mapper`,
  `NuovaPendenzaMapper#risolviTipologia`) estesi. Nuovo `AvvisoPdfPayloadMapper#toCdsViolation`/
  `#sonoRidottoEScontato`; nuovo `StampeClient#streamCdsViolation` (`/cds_violation`). Entrambi i
  controller (`getStampaPosizioneDebitoria` v3, `getAvvisiDocumento` v2) instradano la coppia
  `RIDOTTO`/`SCONTATO` su questo nuovo percorso invece di `options`/400; il wrapper v2 può anche
  creare direttamente un'opzione `RIDOTTO`/`SCONTATO` (non una coppia completa: questo percorso di
  scrittura aggiunge pendenze a un'opzione esistente o ne crea una sola per documento nuovo, mai
  una seconda opzione diversa sullo stesso documento — una coppia completa richiede comunque la
  creazione v3-nativa).

**Verificato**: 390/390 test in `govpay-pendenze-api` (+8 rispetto al §40), 212/212 in
`govpay-common-pendenze` (nessuna regressione dall'estensione dell'enum).

## 42. Tre difetti sull'accorpamento soglie (§40), confermati e corretti (2026-10-27)

Revisione di codice (non di questa sessione di sviluppo) sull'accorpamento `reduced_payments`
introdotto al §40, con riferimenti precisi a riga. Tutti i tre punti sono stati verificati
leggendo il codice prima di correggere.

1. **L'accorpamento non era limitato alla coppia `ENTRO`/`OLTRE`**: `sonoTutteSoglie`
   accettava un numero qualsiasi di opzioni a soglia (anche quattro, o due entrambe `ENTRO`),
   purché ciascuna avesse una sola pendenza — nessun controllo su quante opzioni né su quali
   tipologie. Con più di due soglie non postali, `govpay-stampe-api` accetta comunque il
   payload (`reducedPayments` non ha un vincolo di cardinalità) ma il mapper genera una pagina
   corretta solo per la coppia a cui il template è pensato: con tre o più elementi la stampa
   non rispecchia la richiesta. **Fix**: `sonoTutteSoglie` ora richiede esattamente due opzioni,
   una pendenza ciascuna, una `SOLUZIONE_UNICA_ENTRO` e una `SOLUZIONE_UNICA_OLTRE` (stesso
   pattern già usato da `sonoRidottoEScontato`) — negli altri casi resta `options`/400.
2. **Il wrapper v2 non riusciva a creare la coppia sullo stesso documento**: una PUT con
   `soglia.tipo=ENTRO` creava il documento (201); una seconda PUT con `soglia.tipo=OLTRE` sullo
   stesso `documento.identificativo` falliva con 400 per mismatch di tipologia —
   `aggiungiAPosizioneEsistente` gestiva solo l'aggiunta di una pendenza all'opzione esistente,
   mai una seconda opzione diversa. Le due soglie sono però due `OpzionePagamento` DISPONIBILI
   distinte nel modello v3 (alternative fra loro), non due pendenze della stessa opzione — la
   libreria espone già `PosizioneDebitoriaService#aggiungiOpzionePagamento` per questo caso.
   **Fix**: quando la tipologia richiesta e quella del documento esistente sono la coppia
   complementare `ENTRO`/`OLTRE` o `RIDOTTO`/`SCONTATO` (in qualunque ordine), il wrapper
   aggiunge una seconda opzione invece di rifiutare (nuovo
   `NuovaPendenzaMapper#sonoSoglieComplementari`/`#completaCoppiaSoglia`); una terza soglia
   sullo stesso documento (coppia già completa) resta 400, perché il guard iniziale continua ad
   accettare solo documenti con esattamente un'opzione.
3. **L'accorpamento aggirava il controllo sugli intestatari postali differenti**: il controllo
   introdotto in `govpay-stampe-api` per IBAN con intestatari diversi
   (`validazioneSemanticaIntestatarioPostaleUnico`, causa originaria: un solo campo documento
   per l'intestatario postale, sovrascritto per ogni pagina) era cablato solo sul ramo
   `options`. Passando da `options` a `reducedPayments` a livello di documento per la coppia di
   soglie, lo stesso rischio (due soglie, due IBAN, due intestatari) non veniva più
   intercettato — l'ultimo intestatario processato sarebbe tornato a comparire anche sulle
   pagine precedenti, in silenzio. **Fix** (in `govpay-stampe-api`, non in `govpay-pendenze-api`:
   il problema è nel validatore condiviso, non nella scelta di accorpare): il controllo è stato
   generalizzato per accettare una lista di IBAN invece della sola lista di opzioni, e viene
   applicato anche al ramo `reducedPayments` a livello di documento quando l'avviso è postale.
   Nuovo test: `UC_11_AvvisoRidottoFailTest#UC_11_04_AvvisoRidotto_PostaleIntestatariDiversi`.

**Verificato**: 392/392 test in `govpay-pendenze-api` (+2 rispetto al §41), 212/212 in
`govpay-common-pendenze` (invariato, nessuna modifica in questo punto), 224/224 in
`govpay-stampe-api` (+1).

## 43. Tre ulteriori difetti su §40/§42, confermati e corretti (2026-10-27)

Revisione di codice (non di questa sessione di sviluppo) sui fix dei §40/§42, con riferimenti
precisi a riga. Tutti i tre punti sono stati verificati leggendo il codice prima di correggere.

1. **La migrazione PostgreSQL continuava a scrivere `giorni=5` per `RIDOTTO`/`SCONTATO`**: il
   `CASE` che calcola `giorni` in `06_opzioni_pagamento_documenti_esistenti.sql` cambiava
   correttamente la `tipologia` (§41) ma il suo `ELSE` restava `5` — un residuo dell'epoca in cui
   queste due tipologie erano fuse in `ENTRO`/`OLTRE` (che richiedono `giorni`). Dato che il
   validatore ora vieta `giorni` per `RIDOTTO`/`SCONTATO` (`richiedeGiorni()`, §41), la migrazione
   produceva righe che il nuovo validatore avrebbe rifiutato se mai rivalidate. **Fix**: `ELSE
   NULL` (la colonna `opzioni_pagamento.giorni` è già nullable, nessuna modifica DDL necessaria).
2. **Il wrapper v2 richiedeva ancora `giorni` anche per `RIDOTTO`/`SCONTATO`**: lo schema v2
   (`VincoloPagamento`) aveva `giorni` in `required` indipendentemente da `tipo`, quindi la
   validazione bean (`@NotNull` generato dall'OpenAPI generator) rifiutava la richiesta con 400
   PRIMA che il mapper (che già ignorava correttamente `giorni` per queste due tipologie, §41)
   venisse eseguito — un vincolo condizionale (obbligatorio solo per `ENTRO`/`OLTRE`) non
   esprimibile in uno schema OpenAPI non-`oneOf`. I test del §41 passavano comunque perché
   inviavano sempre `giorni:5` (ignorato dal mapper), senza coprire il caso realistico di
   un'integrazione che per `RIDOTTO`/`SCONTATO` non lo manda affatto. **Fix**: `giorni` rimosso da
   `required` nello schema (resta `minimum: 1` quando presente); il vincolo condizionale è ora
   applicato in `NuovaPendenzaMapper#risolviTipologia` (400 esplicito se assente per `ENTRO`/
   `OLTRE`, ignorato se presente per `RIDOTTO`/`SCONTATO`, invariato). Nuovi test:
   `creaConDocumentoESogliaRidottoSenzaGiorniNelBodyRestituisce201`,
   `creaConDocumentoESogliaEntroSenzaGiorniNelBodyRestituisce400`.
3. **`POST /cds_violation` non applicava il controllo sugli intestatari postali differenti**: il
   fix del §42 (punto 3) estendeva il controllo al ramo `reducedPayments` di `PaymentNotice`, ma
   `validazioneSemanticaViolazioneCds` (percorso separato, stessa causa radice — `ViolazioneCdsMapper`
   condivide lo stesso campo documento unico per l'intestatario postale) non lo richiamava affatto:
   una coppia `RIDOTTO`+`SCONTATO` postale con intestatari diversi veniva accettata, riproducendo
   lo stesso difetto per questo percorso. **Fix** (in `govpay-stampe-api`): stessa chiamata al
   controllo condiviso, applicata a `reducedAmount.iban`/`discountedAmount.iban` quando
   `postal=true`. Nuovo test: `UC_1_ViolazioneCdsFailTest#UC_1_41_ViolazioneCds_PostaleIntestatariDiversi`.

**Verificato**: 394/394 test in `govpay-pendenze-api` (+2 rispetto al §42), 212/212 in
`govpay-common-pendenze` (invariato — fix di sola migrazione SQL, nessun test dedicato esiste per
questi script in questo repository), 225/225 in `govpay-stampe-api` (+1).

## 44. `getAvvisiDocumento` (v2): causale/informativa tradotte dalla `proprieta` persistita, non piu' un 422 incondizionato (2026-10-27)

Il rifiuto incondizionato di `linguaSecondaria≠false` era descritto (§3, analisi di gap
dell'issue, e nel Javadoc del metodo) come bloccato da `govpay-stampe-api` ("nessun parametro
`causaleTradotta` nello schema"): verificato che non e' mai stato cosi'. `govpay-stampe-api`
supporta il bilingue da prima di questa sessione (`second_language.title`/`informativa_importo`);
`PendenzaController#getStampaPendenza` (v3-nativo) lo usa gia' con successo, ricevendo
`causaleTradotta`/`informativaImporto`/`informativaImportoTradotta` come query param espliciti.
Il vero motivo per cui questo endpoint v2 non li ha come query param e' lo stesso del vero
legacy: non sono valori di richiesta, vivono sul documento. E il wrapper li persiste gia' da
prima di questa sessione — `NuovaPendenzaMapper#applicaCampiLegacy` scrive l'intero
`ProprietaPendenza` (quindi `linguaSecondariaCausale`/`informativaImportoAvviso`/
`linguaSecondariaInformativaImportoAvviso`) su `pendenza.proprieta`, e `PendenzaV2Mapper` lo
decodifica in lettura — semplicemente non era mai stato letto per la stampa.

**Corretto**: rimosso il rifiuto incondizionato. Nuovo
`PendenzaV2Controller#risolviParametriBilingue`: decodifica `pendenza.proprieta` (riusando
`PendenzaV2Mapper#toProprietaPendenzaDto`, reso non piu' `private`) e costruisce i parametri per
`AvvisoPdfPayloadMapper#toPaymentNotice` — applicato SOLO al layout a singola rata
rappresentativa (una sola rata pagabile, non piano rateale, non coppia soglie/CdS, che non
supportano questi parametri): 422 se bilingue richiesto senza `linguaSecondariaCausale`
persistita, o se il layout ha piu' di una rata/opzione pagabile (stessa logica di "chi e' la
pendenza rappresentativa" gia' usata per il titolo non tradotto, §1.1/§36). `informativaImporto`
(italiano, non solo la sua traduzione) e' ora letto da `proprieta` anche senza bilingue — prima
si perdeva sempre su questo percorso, anche senza richiedere il bilingue.

**Non toccato**: `getAvviso` resta sospeso (decisione del committente, §35) — stessa causa
radice vista qui, ma non riaperta in questa sessione. Piano rateale multi-rata e coppie
soglie/CdS restano senza bilingue: richiederebbe decidere quale pendenza "rappresenta" la
causale tradotta condivisa fra piu' rate/opzioni, la stessa ambiguita' gia' accettata per la
causale non tradotta (una sola `descrizione` per posizione, §3).

**Verificato**: 397/397 test in `govpay-pendenze-api` (+3 rispetto al §43); nessuna modifica a
`govpay-common-pendenze`/`govpay-stampe-api` in questo punto.

## 35. Punti ancora sospesi (non ripresi in questa sessione)

Per decisione esplicita del committente, `getAllegatoPendenza` e `getAvviso`
restano sospesi — entrambi hanno un gap di design non ancora risolto (vedi
ricerca dedicata, non riportata qui): per `getAllegatoPendenza`, il contenuto
binario degli allegati non è mai persistito da nessuna parte nel modello v3
(nessuna colonna riceve `raw_contenuto`), quindi l'endpoint non può restituire
un contenuto reale oggi — da decidere se 404, un errore esplicito diverso (lo
YAML dichiara solo 401/403/404/500) o affrontare la causa radice (fuori
scope); per `getAvviso`, la traduzione bilingue del legacy è puramente
locale (etichette fisse), ma il nuovo microservizio `govpay-stampe` richiede
obbligatoriamente `second_language.title` (`causaleTradotta`) quando il
bilingue è richiesto, e questo endpoint non ha un parametro corrispondente
nello YAML — riusare il mapper esistente trasformerebbe ogni richiesta
bilingue in un 400, una regressione rispetto al legacy.

## 36. Revisione indipendente del wrapper v2: 6 difetti confermati e corretti (2026-10-25)

Revisione di codice (non di questa sessione di sviluppo) su `updatePendenza`/`addPendenza`/
`findPendenze`/`findRpp` gia' implementati (§31-34), con riferimenti precisi a riga sia nel
wrapper sia nel legacy. Tutti i 6 punti sono stati verificati leggendo il codice legacy citato
(non assunti) prima di correggere.

1. **PATCH senza controllo dominio/tipoVersamento (sicurezza)**: `updatePendenza` chiamava solo
   `aclAuthorizer.richiedeScrittura()`+`verificaIdA2A`, mai l'equivalente di
   `AuthorizationManager#isTipoVersamentoDominioAuthorized` del legacy (`PendenzeDAO#patch`,
   riga 591, **prima** di applicare qualunque patch — `NotAuthorizedException`→403). Un'
   applicazione non trusted, autorizzata a un tipo pendenza/dominio diverso da quello della
   pendenza, otteneva 200 invece di 403. **Scoperta collegata**: `addPendenza` aveva solo meta'
   del controllo (`NuovaPendenzaMapper#verificaAutorizzazioneTipoVersamento`, solo tipoVersamento,
   400 invece di 403) — corretto anche questo, per consistenza, non solo la PATCH.
   **Fix**: nuovo `NuovaPendenzaMapper#verificaAutorizzazioneDominioETipoVersamento` (dominio E
   tipoVersamento, 403 via `AccessoNegatoException`), usato da `updatePendenza` (su
   `pendenza.getIdDominio()`/`getIdTipoVersamento()`, prima del loop di patch) e da
   `crea`/`toAggiornamentoPendenza` (sostituendo il vecchio controllo). Nuovi test:
   `AddPendenzaControllerTest#putNonAutorizzataRispondeAccessoNegato`,
   `UpdatePendenzaControllerTest#patchNonAutorizzataRispondeAccessoNegato`.
2. **Il filtro `idDebitore` non trovava le pendenze scritte dal percorso v3-nativo**:
   `PendenzaRepository#cercaFiltrate`/`RptRepository#cercaFiltrate` filtravano su
   `Pendenza.srcDebitoreIdentificativo`, che per queste pendenze e' sempre il placeholder
   `"VEDERE_SOGGETTI_DEBITORI"` (§21) — il debitore reale vive solo su `soggetti_debitori`.
   **Decisione del committente**: match su QUALSIASI debitore "in solido" della posizione, non
   solo il pagatore (ordine 0). **Fix**: la condizione diventa una sottoquery `EXISTS` su
   `SoggettoDebitore` (via `p.opzionePagamento.posizioneDebitoria`), in entrambi i repository.
   Nuovi test: `FindPendenzeV2ControllerTest#findPendenzeFiltroIdDebitoreCoDebitore` (match su un
   co-debitore, non il pagatore), più la correzione dei due test preesistenti che mutavano
   direttamente la colonna placeholder (falso positivo: il test passava per coincidenza, non
   per il meccanismo che diceva di verificare).
3. **PUT: cambio di dominio ignorato, risposta incoerente**: il ramo di aggiornamento passava
   sempre il dominio della pendenza ESISTENTE a `toAggiornamentoPendenza`, ignorando quello nel
   body — e la risposta usava il valore grezzo del body (anche se inesistente) per
   `PendenzaCreata.idDominio`. Il legacy rifiuta un cambio di beneficiario
   (`VersamentoUtils#validazioneSemanticaAggiornamento`, righe 167-172: confronto su `idUo`, che
   implica il dominio — `GovPayException(VER_004)`). **Fix**: nuovo
   `NuovaPendenzaMapper#validaBeneficiarioInvariato` (confronta dominio E unita' operativa
   richiesti contro quelli della `PosizioneDebitoria` esistente, 400 su mismatch), chiamato dal
   controller PRIMA di `toAggiornamentoPendenza`. Un dominio richiesto inesistente e' gia' un 404
   (`risolviIdDominioAbilitato`), non piu' un 200 con dominio fantasma. Nuovi test:
   `aggiornamentoConDominioDiversoRifiutato`, `aggiornamentoConDominioInesistenteRifiutato`.
4. **`voci[].datiAllegati`/`contabilita`/`metadata` (v2) scartati silenziosamente**: nessun
   campo equivalente in `DatiVocePendenza`/`VocePendenza` — la colonna fisica `contabilita` e'
   gia' presa da `VocePendenza.dettaglioContabile` (struttura v3-nativa diversa, non
   passthrough). **Decisione del committente**: una richiesta che popola questi campi con dati
   reali va rifiutata (400), non accettata con successo e con perdita silenziosa. **Fix**: nuovo
   `NuovaPendenzaMapper#verificaCampiVoceSenzaEquivalenteV3`, chiamato da `toDatiVocePendenza`
   (sia in creazione sia in aggiornamento). Nuovi test: `voceConDatiAllegatiRifiutata`,
   `voceConContabilitaRifiutata`.
5. **Allegati: confermato APPEND invece di REPLACE su un aggiornamento** (non piu' un punto
   aperto — il §31 lo segnalava come "non verificato contro il legacy"): il legacy
   (`VersamentiBD#updateVersamento`, righe 494-516) cancella SEMPRE gli allegati esistenti del
   versamento prima di inserire quelli della richiesta (anche vuota — svuota). **Fix**:
   `PendenzaV2Controller#salvaAllegati` cancella (`AllegatoRepository#deleteByIdVersamento`,
   nuovo) prima di inserire, solo sul ramo di aggiornamento (in creazione non c'e' nulla da
   cancellare). Nuovo test: `allegatiSuAggiornamentoRimpiazzanoIPrecedenti`.
6. **`mostraSpontaneiNonPagati` non puo' essere ignorato sui dati migrati**: la Javadoc di
   `findPendenze` assumeva `Pendenza.tipo` sempre `"DOVUTO"` per questa libreria — vero solo per
   le righe scritte dal percorso v3-nativo, ma `Pendenza` e' mappata sulla tabella legacy
   `versamenti` RIUSATA IN-PLACE (§17): righe storiche con `tipo=SPONTANEO` restano visibili
   attraverso questa stessa entity. Il legacy (`VersamentoFilter`, righe 402-410) esclude
   `tipo=SPONTANEO AND stato=NON_ESEGUITO` quando il parametro e' `false`/assente. **Fix**: nuovo
   campo `FiltriPendenze.escludiSpontaneiNonEseguiti`, nuova condizione in
   `PendenzaRepository#cercaFiltrate`/`cercaFiltrateSenzaConteggio`. Nuovo test:
   `findPendenzeMostraSpontaneiNonPagati` (mutazione diretta dell'entity per simulare il dato
   migrato, stesso pattern degli altri test della classe).

**Verificato**: 212/212 test in `govpay-common-pendenze` (`mvn clean test` reale, invariato —
nessun nuovo test diretto qui, solo query modificate, coperte indirettamente dai test del
wrapper); 345/345 in `govpay-pendenze-api` (+9 rispetto al §33-34: 2 in
`FindPendenzeV2ControllerTest`, 6 in `AddPendenzaControllerTest`, 1 in
`UpdatePendenzaControllerTest`).

## 37. Due ulteriori difetti sulle correzioni del §36, confermati e corretti (2026-10-26)

Trovati in revisione indipendente dei fix del §36, non del codice originario.

1. **`validaBeneficiarioInvariato` rifiutava un aggiornamento che omette
   `idUnitaOperativa` su una pendenza associata a EC**: il confronto (§36, punto 3)
   normalizza l'omissione a `null` ma confronta direttamente con
   `posizioneEsistente.getIdUnitaOperativa()`, che per EC non e' `null` ma l'id
   reale della riga `unita_operativa` con `codUo="EC"` (l'ente creditore stesso,
   non una vera UO — stesso concetto gia' nascosto nelle risposte da
   `PendenzaV2Mapper#risolviUnitaOperativaDto`). Un aggiornamento che omette il
   campo su una pendenza del genere rispondeva 400 per un cambio di beneficiario
   che non c'e' mai stato: il legacy interpreta l'omissione come EC. **Fix**: nuovo
   `NuovaPendenzaMapper#normalizzaUnitaOperativaEc`, applicato a entrambi i lati
   del confronto — `null` e l'id della UO "EC" sono ora equivalenti. Il rifiuto per
   un cambio di UO realmente diverso (non EC) resta invariato — verificato con un
   test dedicato. Nuovi test:
   `aggiornamentoSenzaUnitaOperativaSuPendenzaEcNonRifiutato`,
   `aggiornamentoConUnitaOperativaDiversaRifiutato`.
2. **`verificaCampiVoceSenzaEquivalenteV3` andava in `NullPointerException` (500)
   su una lista esplicitamente `null`**: il controllo (§36, punto 4) chiamava
   `contabilita.getQuote().isEmpty()`/`metadata.getMapEntries().isEmpty()` senza
   considerare che il default dei generati OpenAPI e' una lista vuota, ma una
   richiesta con `"contabilita": {"quote": null}` (o l'equivalente per
   `metadata.mapEntries`) sovrascrive quel default con `null` via Jackson. **Fix**:
   controllo null-safe prima di `isEmpty()` su entrambi i campi — una lista
   esplicitamente `null` e' trattata come assente (nessun dato reale da perdere),
   stessa conclusione logica della lista vuota, non piu' un errore non gestito.
   Nuovi test: `voceConContabilitaQuoteNullNonLanciaNpe`,
   `voceConMetadataMapEntriesNullNonLanciaNpe`.

**Verificato**: 349/349 test in `govpay-pendenze-api` (`mvn clean test` reale, +4
rispetto al §36); nessuna modifica a `govpay-common-pendenze` in questo punto.

## Stato dei test

212 test totali in questa libreria, tutti verdi (`mvn clean test`); 364 in govpay-pendenze-api;
223 in govpay-stampe-api.

> **Aggiornato al 2026-10-26 (§38, endpoint govpay-pendenze-api)**: 364 test
> in `govpay-pendenze-api` (+13 — nuovo `GET .../posizioni-debitorie/{idA2A}/
> {idPosizioneDebitoria}/stampa`); nessuna modifica a `govpay-common-pendenze`
> in questo punto.
>
> **Aggiornato al 2026-10-26 (§38, revisione indipendente)**: 223 test in
> `govpay-stampe-api` (+4 — tre difetti sulla prima implementazione: perdita
> di 'full' con una rata non postale, intestatario postale condiviso fra
> opzioni, elemento null in 'options'); nessuna modifica a
> `govpay-common-pendenze` o `govpay-pendenze-api` in questo punto.
>
> **Aggiornato al 2026-10-26 (§38)**: 219 test in `govpay-stampe-api` (`mvn
> test` reale, +12 — `UC_12_AvvisoOpzioniPagamentoFailTest`/
> `UC_13_AvvisoOpzioniPagamentoTest`); nessuna modifica a `govpay-common-pendenze`
> o `govpay-pendenze-api` in questo punto.
>
> **Aggiornato al 2026-10-26 (§34, riesame)**: 212 in questa libreria (invariata),
> 351 in `govpay-pendenze-api` (+2 rispetto al §37, chiusura del punto aperto
> `full`/`instalments` con il criterio basato sulla cardinalita').
>
> **Aggiornato al 2026-10-26 (§37)**: 212 in questa libreria (invariata), 349 in
> `govpay-pendenze-api` (+4, due difetti sui fix del §36).
>
> **Aggiornato al 2026-10-24 (§28)**: 211 in questa libreria, 295 in
> `govpay-pendenze-api`.
>
> **Aggiornato al 2026-10-24 (§30)**: 211 in questa libreria (invariata), 298 in
> `govpay-pendenze-api`.
>
> **Aggiornato al 2026-10-25 (§31)**: 212 in questa libreria (+1 non imputabile
> a questa sessione, nessuna modifica fatta qui), 305 in `govpay-pendenze-api`.
>
> **Aggiornato al 2026-10-25 (§32)**: 212 in questa libreria (invariata), 314
> in `govpay-pendenze-api` (+9, `updatePendenza`).
>
> **Aggiornato al 2026-10-25 (§33-34)**: 212 in questa libreria (invariata —
> nuove query su `RptRepository`, nessun nuovo test diretto qui), 336 in
> `govpay-pendenze-api` (+22, `findRpp` + `getAvvisiDocumento`).
>
> **Aggiornato al 2026-10-25 (§36)**: 212 in questa libreria (invariata), 345
> in `govpay-pendenze-api` (+9, revisione dei 6 difetti).
