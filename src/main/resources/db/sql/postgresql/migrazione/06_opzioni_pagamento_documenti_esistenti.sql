-- ---------------------------------------------------------------------------
-- Sintesi di una o piu' OpzionePagamento per i documenti esistenti, per i tre
-- pattern classificati in docs/proposta-modello-nativo-v3.md §28 (leggere
-- quella sezione per il ragionamento completo):
--
--  A) tutti i versamenti del documento con cod_rata NULL -> una
--     OpzionePagamento SOLUZIONE_UNICA con tutte le pendenze dentro
--     (l'assunzione che siano componenti dello stesso dovuto, non
--     alternative, non e' dimostrata dal solo NULL: resta un'ipotesi di
--     migrazione, non un fatto verificato nei dati).
--  B) tutti i versamenti con cod_rata numerico valido (intero positivo),
--     SENZA duplicati tra loro -> una OpzionePagamento PIANO_RATEALE.
--     numero_rata = valore dichiarato, preservato (1,3,5 resta 1,3,5, non
--     diventa 1,2,3): la regola "numeroRata sempre dalla posizione nelle
--     liste" (assegnaIndici) vale solo per le scritture dal vivo del
--     wrapper via crea()/aggiungiPendenza(), non per questo script che
--     scrive direttamente sulle tabelle.
--  C) versamenti con cod_rata riconosciuto come soglia (`ENTRO<gg>`,
--     `OLTRE<gg>`, `RIDOTTO`, `SCONTATO` — decodifica verificata su
--     VersamentoConverter.java:143-157), SENZA duplicati di valore nello
--     stesso documento -> una OpzionePagamento SOLUZIONE_UNICA_ENTRO/OLTRE/
--     RIDOTTO/SCONTATO PER VERSAMENTO (cardinalita' massima 1 pendenza per
--     queste tipologie, M2/TipologiaOpzionePagamento), quindi piu' opzioni
--     sotto lo stesso documento quando coesistono piu' soglie (es. la coppia
--     ENTRO/OLTRE, o RIDOTTO+SCONTATO). RIDOTTO/SCONTATO restano distinti da
--     ENTRO/OLTRE: nessuna colonna porta il numero di giorni per questi due
--     codici (vietato in input, PendenzaVerificataValidator.java:182) perche'
--     e' un valore fisso per legge — usato qui come costante `giorni = 5`.
--     Lo stato di ciascuna opzione deriva dal suo
--     unico versamento (NON_ESEGUITO->DISPONIBILE, ANNULLATO->ANNULLATA,
--     altro->ATTIVATA): a differenza dei casi A/B non c'e' aggregazione su
--     piu' righe, quindi nessuno dei due documenti puo' incorrere nel gap
--     "mix NON_ESEGUITO/ANNULLATO" del caso successivo. Nessun controllo
--     incrociato tra le opzioni sorelle dello stesso documento: e'
--     intenzionale che una coppia possa risultare entrambe ATTIVATA (decisione
--     del lead, 2026-10-22 — non dovrebbe succedere nella pratica, il DB non
--     ha un vincolo che lo impedisca, e il servizio v3 (PosizioneDebitoriaService
--     .attiva) evita nuove violazioni dal momento della migrazione in poi, anche
--     se non puo' correggere quelle gia' scritte qui).
--
-- QUALUNQUE altro pattern NON viene toccato da questo script (cod_rata
-- anomali/duplicati/misti tra i tre pattern sopra): quei documenti restano
-- senza opzione. La query diagnostica in fondo a questo file li elenca per
-- un report separato, da rivedere caso per caso prima di decidere una
-- correzione dati o un'estensione del modello v3.
--
-- Idempotente: ciascuna delle due UPDATE/INSERT considera solo versamenti
-- con id_opzione_pagamento ancora NULL — sicuro rieseguire l'intero file
-- dopo aver risolto manualmente altri documenti (es. dopo aver corretto un
-- dato anomalo segnalato dal report), ed e' indifferente l'ordine A/B
-- rispetto a C (nessuno dei due pattern puo' matchare i versamenti dell'altro).
--
-- Dialetto: PostgreSQL soltanto. A differenza di 05_documenti_orfani.sql,
-- qui non e' stata ancora scritta/verificata una traduzione per gli altri 4
-- dialetti: la classificazione usa espressioni regolari e funzioni finestra
-- la cui sintassi varia sensibilmente (es. FILTER non esiste su MySQL/
-- SQL Server, servirebbe riscriverla con CASE WHEN dentro COUNT) — da
-- valutare se e quando servira' davvero su quei motori.
-- ---------------------------------------------------------------------------

WITH versamenti_candidati AS (
    SELECT v.id, v.id_documento, v.cod_rata, v.stato_versamento
    FROM versamenti v
    WHERE v.id_documento IS NOT NULL
      AND v.id_opzione_pagamento IS NULL
),
classificazione AS (
    SELECT
        id_documento,
        COUNT(*) AS totale,
        COUNT(*) FILTER (WHERE cod_rata IS NULL) AS n_null,
        COUNT(*) FILTER (WHERE cod_rata ~ '^[1-9][0-9]*$') AS n_numerico,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^[1-9][0-9]*$' THEN cod_rata::int END) AS n_numerico_distinti,
        COUNT(*) FILTER (WHERE stato_versamento = 'NON_ESEGUITO') AS n_non_eseguito,
        COUNT(*) FILTER (WHERE stato_versamento = 'ANNULLATO') AS n_annullato,
        COUNT(*) FILTER (WHERE stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO')) AS n_altro
    FROM versamenti_candidati
    GROUP BY id_documento
),
documenti_eligibili AS (
    SELECT
        id_documento,
        CASE
            WHEN n_null = totale THEN 'SOLUZIONE_UNICA'
            WHEN n_numerico = totale AND n_numerico_distinti = totale THEN 'PIANO_RATEALE'
            ELSE NULL
        END AS tipologia,
        -- DISPONIBILE e' il caso di default (ValidatorePosizioneDebitoria.validaStati,
        -- corretto 2026-10-22): copre sia "tutte NON_ESEGUITO" sia il mix
        -- NON_ESEGUITO/ANNULLATO senza alcuna pendenza eseguita (annullamento
        -- indipendente della singola pendenza, possibile solo in v2 — l'opzione non e'
        -- mai stata "esercitata" in nessuno dei due casi, nessun pagamento registrato).
        CASE
            WHEN n_annullato = totale THEN 'ANNULLATA'
            WHEN n_altro > 0 THEN 'ATTIVATA'
            ELSE 'DISPONIBILE'
        END AS stato
    FROM classificazione
),
versamenti_numerati AS (
    SELECT
        v.id,
        v.id_documento,
        CASE
            WHEN v.cod_rata ~ '^[1-9][0-9]*$' THEN v.cod_rata::int
            -- Caso A (SOLUZIONE_UNICA, cod_rata NULL): nessun valore dichiarato da
            -- preservare, numerate per id (ordine di inserimento) solo per garantire
            -- indici 1..N distinti dentro l'opzione (vincolo di unicita' su numero_rata).
            ELSE ROW_NUMBER() OVER (PARTITION BY v.id_documento ORDER BY v.id)
        END AS numero_rata_calcolato
    FROM versamenti_candidati v
),
nuove_opzioni AS (
    INSERT INTO opzioni_pagamento
        (id, id_opzione_pagamento, id_documento, tipologia, stato, versione,
         data_creazione, data_ultimo_aggiornamento)
    SELECT
        nextval('seq_opzioni_pagamento'),
        gen_random_uuid(),
        de.id_documento,
        de.tipologia,
        de.stato,
        0,
        now(),
        now()
    FROM documenti_eligibili de
    WHERE de.tipologia IS NOT NULL AND de.stato IS NOT NULL
    RETURNING id, id_documento
)
UPDATE versamenti v
SET id_opzione_pagamento = no.id,
    numero_rata = vn.numero_rata_calcolato
FROM nuove_opzioni no
JOIN versamenti_numerati vn ON vn.id_documento = no.id_documento
WHERE v.id = vn.id;

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO
-- (vedi commento in testa al file). Una OpzionePagamento per versamento,
-- mai un'aggregazione: cardinalita' massima 1 per queste due tipologie.
-- ---------------------------------------------------------------------------

WITH versamenti_candidati AS (
    SELECT v.id, v.id_documento, v.cod_rata, v.stato_versamento
    FROM versamenti v
    WHERE v.id_documento IS NOT NULL
      AND v.id_opzione_pagamento IS NULL
),
classificazione_soglia AS (
    SELECT
        id_documento,
        COUNT(*) AS totale,
        COUNT(*) FILTER (WHERE cod_rata ~ '^(ENTRO[0-9]+|OLTRE[0-9]+|RIDOTTO|SCONTATO)$') AS n_soglia,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^(ENTRO[0-9]+|OLTRE[0-9]+|RIDOTTO|SCONTATO)$' THEN cod_rata END) AS n_soglia_distinti
    FROM versamenti_candidati
    GROUP BY id_documento
),
documenti_soglia AS (
    -- Tutto il documento e' fatto solo di valori-soglia riconosciuti, e
    -- nessun valore si ripete (altrimenti due versamenti finirebbero sotto
    -- la stessa opzione, violando la cardinalita' massima 1) — chi non
    -- qualifica resta nel report diagnostico #2 sotto.
    SELECT id_documento
    FROM classificazione_soglia
    WHERE n_soglia = totale AND n_soglia_distinti = totale AND totale > 0
),
versamenti_soglia AS (
    SELECT
        vc.id,
        vc.id_documento,
        CASE
            WHEN vc.cod_rata ~ '^ENTRO[0-9]+$'  THEN 'SOLUZIONE_UNICA_ENTRO'
            WHEN vc.cod_rata ~ '^OLTRE[0-9]+$'  THEN 'SOLUZIONE_UNICA_OLTRE'
            WHEN vc.cod_rata = 'SCONTATO'       THEN 'SOLUZIONE_UNICA_SCONTATO'
            WHEN vc.cod_rata = 'RIDOTTO'        THEN 'SOLUZIONE_UNICA_RIDOTTO'
        END AS tipologia,
        CASE
            WHEN vc.cod_rata ~ '^(ENTRO|OLTRE)[0-9]+$' THEN substring(vc.cod_rata FROM '[0-9]+$')::int
            ELSE NULL -- RIDOTTO/SCONTATO: 'giorni' non e' un valore ammesso per queste tipologie
        END AS giorni,
        CASE
            WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
            WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
            ELSE 'ATTIVATA'
        END AS stato,
        nextval('seq_opzioni_pagamento') AS nuovo_id,
        gen_random_uuid() AS nuovo_id_opzione_pagamento
    FROM versamenti_candidati vc
    JOIN documenti_soglia ds ON ds.id_documento = vc.id_documento
),
nuove_opzioni_soglia AS (
    INSERT INTO opzioni_pagamento
        (id, id_opzione_pagamento, id_documento, tipologia, giorni, stato, versione,
         data_creazione, data_ultimo_aggiornamento)
    SELECT
        nuovo_id,
        nuovo_id_opzione_pagamento,
        id_documento,
        tipologia,
        giorni,
        stato,
        0,
        now(),
        now()
    FROM versamenti_soglia
    RETURNING id
)
-- Il join con nuove_opzioni_soglia non e' solo logico: un CTE data-modifying
-- in PostgreSQL esegue solo se e' effettivamente referenziato dalla query
-- finale — senza questo join l'INSERT sopra non verrebbe mai eseguito.
UPDATE versamenti v
SET id_opzione_pagamento = vs.nuovo_id,
    numero_rata = 1
FROM versamenti_soglia vs
JOIN nuove_opzioni_soglia nos ON nos.id = vs.nuovo_id
WHERE v.id = vs.id;

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() (righe 1025-1036:
-- quando un'opzione si attiva, annulla automaticamente le DISPONIBILI della
-- stessa posizione e, tramite cascataStatoPendenze, le pendenze sottostanti),
-- applicata qui a ritroso sui dati storici (decisione del lead, 2026-10-22).
-- Diverso dal punto precedente (piu' opzioni ATTIVATA sullo stesso documento,
-- tollerato e non corretto): qui il caso e' l'opposto e univoco — un'opzione
-- ATTIVATA accanto a una o piu' DISPONIBILI ha un esito chiaro, nessuna
-- scelta arbitraria da fare.
--
-- Cascata anche sulle pendenze sottostanti (DISPONIBILE implica che nessuna
-- e' mai stata eseguita, anche se una puo' essere ANNULLATO per annullamento
-- indipendente v2 — vedi il caso del mix NON_ESEGUITO/ANNULLATO sopra): senza
-- questa cascata, le pendenze ancora NON_ESEGUITO di un'opzione cosi'
-- annullata resterebbero incongruenti con l'invariante "ANNULLATA -> tutte
-- le pendenze ANNULLATO" dello stesso modello (vedi §28).
--
-- Scoped su TUTTA la tabella opzioni_pagamento (non solo le righe appena
-- inserite sopra): e' una regola di consistenza generale del modello, non
-- specifica di questa esecuzione — idempotente di conseguenza (una seconda
-- esecuzione non trova piu' nessuna DISPONIBILE accanto a un'ATTIVATA).
-- ---------------------------------------------------------------------------

WITH opzioni_da_annullare AS (
    SELECT op.id
    FROM opzioni_pagamento op
    WHERE op.stato = 'DISPONIBILE'
      AND EXISTS (
          SELECT 1 FROM opzioni_pagamento altra
          WHERE altra.id_documento = op.id_documento
            AND altra.stato = 'ATTIVATA'
      )
),
aggiornamento_opzioni AS (
    UPDATE opzioni_pagamento
    SET stato = 'ANNULLATA',
        data_ultimo_aggiornamento = now()
    WHERE id IN (SELECT id FROM opzioni_da_annullare)
    RETURNING id
)
UPDATE versamenti v
SET stato_versamento = 'ANNULLATO',
    data_ora_ultimo_aggiornamento = now()
FROM aggiornamento_opzioni ao
WHERE v.id_opzione_pagamento = ao.id;

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
--
-- Non c'e' piu' un report sul mix NON_ESEGUITO/ANNULLATO (gap di
-- ValidatorePosizioneDebitoria.validaStati, chiuso in libreria il
-- 2026-10-22): quei documenti sono ora migrati normalmente come DISPONIBILE
-- dal blocco sopra, non piu' esclusi.
-- ---------------------------------------------------------------------------

-- Documenti con cod_rata genuinamente anomalo: non tutti NULL, non tutti
-- numerici validi senza duplicati, e non tutti valori-soglia riconosciuti
-- senza duplicati (RIDOTTO/SCONTATO/ENTRO/OLTRE) — pattern misto tra le
-- tre famiglie, o duplicato di valore-soglia nello stesso documento (es.
-- due "ENTRO5"), o dato corrotto. Da rivedere a mano.
WITH versamenti_candidati AS (
    SELECT v.id, v.id_documento, v.cod_rata
    FROM versamenti v
    WHERE v.id_documento IS NOT NULL AND v.id_opzione_pagamento IS NULL
),
classificazione AS (
    SELECT
        id_documento,
        COUNT(*) AS totale,
        COUNT(*) FILTER (WHERE cod_rata IS NULL) AS n_null,
        COUNT(*) FILTER (WHERE cod_rata ~ '^[1-9][0-9]*$') AS n_numerico,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^[1-9][0-9]*$' THEN cod_rata::int END) AS n_numerico_distinti,
        COUNT(*) FILTER (WHERE cod_rata ~ '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$') AS n_soglia,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$' THEN cod_rata END) AS n_soglia_distinti
    FROM versamenti_candidati
    GROUP BY id_documento
)
SELECT id_documento, totale, n_null, n_numerico, n_numerico_distinti, n_soglia, n_soglia_distinti
FROM classificazione
WHERE NOT (n_null = totale)
  AND NOT (n_numerico = totale AND n_numerico_distinti = totale)
  AND NOT (n_soglia = totale AND n_soglia_distinti = totale);
