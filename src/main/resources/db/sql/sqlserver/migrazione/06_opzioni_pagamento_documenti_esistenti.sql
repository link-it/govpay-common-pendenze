-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo sui tre pattern A/B/C e
-- sulla cascata di mutua esclusione) — qui la sintassi SQL Server, NON
-- verificata contro un'istanza reale.
--
-- Niente REGEXP nativo in T-SQL: i pattern su cod_rata sono espressi con
-- LIKE/SUBSTRING. La classificazione viene materializzata in tabelle
-- temporanee locali (#tmp, droppate automaticamente a fine sessione,
-- comunque droppate esplicitamente in fondo).
-- ---------------------------------------------------------------------------

SELECT
    v.id,
    v.id_documento,
    v.cod_rata,
    v.stato_versamento,
    CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
    CASE WHEN v.cod_rata IS NOT NULL AND v.cod_rata NOT LIKE '%[^0-9]%' AND v.cod_rata NOT LIKE '0%'
         THEN 1 ELSE 0 END AS flag_numerico,
    CASE WHEN v.cod_rata IS NOT NULL AND v.cod_rata NOT LIKE '%[^0-9]%' AND v.cod_rata NOT LIKE '0%'
         THEN CAST(v.cod_rata AS INT) END AS valore_numerico,
    CASE WHEN v.cod_rata IN ('RIDOTTO', 'SCONTATO')
           OR (v.cod_rata LIKE 'ENTRO[0-9]%' AND SUBSTRING(v.cod_rata, 6, 8000) NOT LIKE '%[^0-9]%')
           OR (v.cod_rata LIKE 'OLTRE[0-9]%' AND SUBSTRING(v.cod_rata, 6, 8000) NOT LIKE '%[^0-9]%')
         THEN 1 ELSE 0 END AS flag_soglia
INTO #tmp_vrs_rata
FROM versamenti v
WHERE v.id_documento IS NOT NULL
  AND v.id_opzione_pagamento IS NULL;

-- ---------------------------------------------------------------------------
-- Pattern A/B: un'opzione per documento. Stato: DISPONIBILE e' il caso di
-- default — copre sia "tutte NON_ESEGUITO" sia il mix NON_ESEGUITO/ANNULLATO
-- senza alcuna pendenza eseguita (annullamento indipendente della singola
-- pendenza, possibile solo in v2).
-- ---------------------------------------------------------------------------

SELECT
    aggr.*,
    CASE WHEN aggr.n_null = aggr.totale THEN 'SOLUZIONE_UNICA'
         WHEN aggr.n_numerico = aggr.totale AND aggr.n_numerico_distinti = aggr.totale THEN 'PIANO_RATEALE'
    END AS tipologia,
    CASE WHEN aggr.n_annullato = aggr.totale THEN 'ANNULLATA'
         WHEN aggr.n_altro > 0 THEN 'ATTIVATA'
         ELSE 'DISPONIBILE'
    END AS stato
INTO #tmp_classificazione
FROM (
    SELECT
        id_documento,
        COUNT(*) AS totale,
        SUM(flag_null) AS n_null,
        SUM(flag_numerico) AS n_numerico,
        COUNT(DISTINCT valore_numerico) AS n_numerico_distinti,
        SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
        SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro,
        SUM(flag_soglia) AS n_soglia
    FROM #tmp_vrs_rata
    GROUP BY id_documento
) aggr;

SELECT id_documento, tipologia, stato, NEXT VALUE FOR seq_opzioni_pagamento AS id
INTO #tmp_nuove_opzioni
FROM #tmp_classificazione
WHERE tipologia IS NOT NULL;

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_documento, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT id, NEWID(), id_documento, tipologia, stato, 0, SYSDATETIME(), SYSDATETIME()
FROM #tmp_nuove_opzioni;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il documento — solo per garantire indici distinti. Il join parte da
-- #tmp_nuove_opzioni (solo documenti A/B): nessuna ambiguita' con le opzioni
-- del pattern C sotto, che restano sotto lo stesso id_documento ma non
-- finiscono in questa tabella.
UPDATE v
SET v.id_opzione_pagamento = no.id,
    v.numero_rata = vn.numero_rata_calcolato
FROM versamenti v
JOIN #tmp_nuove_opzioni no ON no.id_documento = v.id_documento
JOIN (
    SELECT id, id_documento,
           COALESCE(valore_numerico, ROW_NUMBER() OVER (PARTITION BY id_documento ORDER BY id)) AS numero_rata_calcolato
    FROM #tmp_vrs_rata
) vn ON vn.id = v.id;

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO. Una
-- opzione PER VERSAMENTO (cardinalita' massima 1 per queste tipologie), non
-- un'aggregazione per documento come A/B — solo se TUTTO il documento e'
-- fatto di valori-soglia riconosciuti e senza duplicati. Id e GUID generati
-- qui, in #tmp_vrs_soglia: una volta materializzati sono stabili, il join
-- verso versamenti sotto si fa sul loro id originale (vc.id), senza bisogno
-- di un correlatore separato.
-- ---------------------------------------------------------------------------

SELECT
    vc.id,
    vc.id_documento,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' THEN 'SOLUZIONE_UNICA_ENTRO'
         WHEN vc.cod_rata LIKE 'OLTRE%' THEN 'SOLUZIONE_UNICA_OLTRE'
         WHEN vc.cod_rata = 'SCONTATO' THEN 'SOLUZIONE_UNICA_SCONTATO'
         ELSE 'SOLUZIONE_UNICA_RIDOTTO'
    END AS tipologia,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' OR vc.cod_rata LIKE 'OLTRE%'
         THEN CAST(SUBSTRING(vc.cod_rata, 6, 8000) AS INT)
    END AS giorni,
    CASE WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
         WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
         ELSE 'ATTIVATA'
    END AS stato,
    NEXT VALUE FOR seq_opzioni_pagamento AS id_opzione,
    NEWID() AS id_opzione_pagamento
INTO #tmp_vrs_soglia
FROM #tmp_vrs_rata vc
JOIN (
    SELECT id_documento
    FROM #tmp_vrs_rata
    GROUP BY id_documento
    HAVING SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT cod_rata) = COUNT(*) AND COUNT(*) > 0
) ds ON ds.id_documento = vc.id_documento;

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_documento, tipologia, giorni, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT id_opzione, id_opzione_pagamento, id_documento, tipologia, giorni, stato, 0, SYSDATETIME(), SYSDATETIME()
FROM #tmp_vrs_soglia;

UPDATE v
SET v.id_opzione_pagamento = vs.id_opzione,
    v.numero_rata = 1
FROM versamenti v
JOIN #tmp_vrs_soglia vs ON vs.id = v.id;

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() della libreria.
-- Vale su tutta la tabella, non solo sulle righe appena migrate. Prima i
-- versamenti: dopo, le opzioni da annullare non sarebbero piu'
-- riconoscibili (non sarebbero piu' DISPONIBILE).
-- ---------------------------------------------------------------------------

UPDATE v
SET v.stato_versamento = 'ANNULLATO',
    v.data_ora_ultimo_aggiornamento = SYSDATETIME()
FROM versamenti v
WHERE v.stato_versamento = 'NON_ESEGUITO'
  AND v.id_opzione_pagamento IN (
      SELECT op.id FROM opzioni_pagamento op
       WHERE op.stato = 'DISPONIBILE'
         AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                      WHERE altra.id_documento = op.id_documento
                        AND altra.stato = 'ATTIVATA'));

UPDATE op
SET op.stato = 'ANNULLATA',
    op.data_ultimo_aggiornamento = SYSDATETIME()
FROM opzioni_pagamento op
WHERE op.stato = 'DISPONIBILE'
  AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
               WHERE altra.id_documento = op.id_documento
                 AND altra.stato = 'ATTIVATA');

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
-- Documenti con cod_rata genuinamente anomalo: non tutti NULL, non tutti
-- numerici validi senza duplicati, e non tutti valori-soglia riconosciuti
-- senza duplicati — pattern misto tra le tre famiglie, duplicato di
-- valore-soglia nello stesso documento, o dato corrotto. Da rivedere a mano.
-- ---------------------------------------------------------------------------

SELECT
    id_documento,
    COUNT(*) AS totale,
    SUM(flag_null) AS n_null,
    SUM(flag_numerico) AS n_numerico,
    COUNT(DISTINCT valore_numerico) AS n_numerico_distinti,
    SUM(flag_soglia) AS n_soglia,
    COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti
FROM #tmp_vrs_rata
GROUP BY id_documento
HAVING NOT (SUM(flag_null) = COUNT(*))
   AND NOT (SUM(flag_numerico) = COUNT(*) AND COUNT(DISTINCT valore_numerico) = COUNT(*))
   AND NOT (SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) = COUNT(*));

DROP TABLE #tmp_vrs_rata;
DROP TABLE #tmp_classificazione;
DROP TABLE #tmp_nuove_opzioni;
DROP TABLE #tmp_vrs_soglia;
