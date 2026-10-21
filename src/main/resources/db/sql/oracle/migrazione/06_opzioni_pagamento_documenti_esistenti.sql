-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo, verificato con dati di
-- esempio su un'istanza PostgreSQL reale) — qui la sintassi Oracle, NON
-- verificata contro un'istanza Oracle reale.
--
-- Oracle non supporta CTE scrivibili (niente "WITH cte AS (INSERT ...
-- RETURNING ...) UPDATE ..." come in PostgreSQL): la classificazione viene
-- materializzata in due tabelle di appoggio (DROP alla fine).
-- ---------------------------------------------------------------------------

CREATE TABLE tmp_vrs_rata AS
SELECT
    v.id,
    v.id_documento,
    v.stato_versamento,
    CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^[1-9][0-9]*$') THEN 1 ELSE 0 END AS flag_numerico,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^[1-9][0-9]*$') THEN TO_NUMBER(v.cod_rata) END AS valore_numerico,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$') THEN 1 ELSE 0 END AS flag_riconosciuto
FROM versamenti v
WHERE v.id_documento IS NOT NULL
  AND v.id_opzione_pagamento IS NULL;

CREATE TABLE tmp_classificazione AS
SELECT
    aggr.*,
    CASE WHEN aggr.n_null = aggr.totale THEN 'SOLUZIONE_UNICA'
         WHEN aggr.n_numerico = aggr.totale AND aggr.n_numerico_distinti = aggr.totale THEN 'PIANO_RATEALE'
    END AS tipologia,
    -- Vedi tabella "derivazione dello stato" in proposta-modello-nativo-v3.md
    -- §28: il mix NON_ESEGUITO+ANNULLATO senza altro stato (n_altro=0) non
    -- rientra in nessuna delle tre categorie ammesse -> stato NULL.
    CASE WHEN aggr.n_non_eseguito = aggr.totale THEN 'DISPONIBILE'
         WHEN aggr.n_annullato = aggr.totale THEN 'ANNULLATA'
         WHEN aggr.n_altro > 0 THEN 'ATTIVATA'
    END AS stato
FROM (
    SELECT
        id_documento,
        COUNT(*) AS totale,
        SUM(flag_null) AS n_null,
        SUM(flag_numerico) AS n_numerico,
        COUNT(DISTINCT valore_numerico) AS n_numerico_distinti,
        SUM(CASE WHEN stato_versamento = 'NON_ESEGUITO' THEN 1 ELSE 0 END) AS n_non_eseguito,
        SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
        SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro,
        SUM(flag_riconosciuto) AS n_riconosciuto
    FROM tmp_vrs_rata
    GROUP BY id_documento
) aggr;

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_documento, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT
    seq_opzioni_pagamento.NEXTVAL,
    SYS_GUID(),
    c.id_documento,
    c.tipologia,
    c.stato,
    0,
    SYSTIMESTAMP,
    SYSTIMESTAMP
FROM tmp_classificazione c
WHERE c.tipologia IS NOT NULL AND c.stato IS NOT NULL;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL, nessun valore da preservare), posizione
-- 1-based per id crescente dentro il documento — solo per garantire indici
-- distinti, non un'informazione dichiarata dal chiamante.
UPDATE versamenti v
SET (id_opzione_pagamento, numero_rata) = (
    SELECT o.id,
           COALESCE(
               (SELECT vr.valore_numerico FROM tmp_vrs_rata vr WHERE vr.id = v.id),
               (SELECT COUNT(*) FROM tmp_vrs_rata vr2
                WHERE vr2.id_documento = v.id_documento AND vr2.id <= v.id)
           )
    FROM opzioni_pagamento o
    WHERE o.id_documento = v.id_documento
)
WHERE v.id IN (SELECT id FROM tmp_vrs_rata)
  AND EXISTS (SELECT 1 FROM opzioni_pagamento o WHERE o.id_documento = v.id_documento);

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi — da eseguire ed esaminare DOPO
-- lo script, non parte dell'automazione.
-- ---------------------------------------------------------------------------

-- 1) Pattern legacy riconosciuti ma non ancora mappati (RIDOTTO/SCONTATO,
--    ENTRO/OLTRE espliciti).
SELECT id_documento FROM tmp_classificazione
WHERE n_riconosciuto = totale AND tipologia IS NULL;

-- 2) Documenti con cod_rata genuinamente anomalo.
SELECT id_documento, totale, n_null, n_numerico, n_numerico_distinti, n_riconosciuto
FROM tmp_classificazione
WHERE tipologia IS NULL AND n_riconosciuto < totale;

-- 3) Documenti con pattern cod_rata riconosciuto ma stati storici
--    incompatibili con le 3 categorie ammesse.
SELECT id_documento, n_non_eseguito, n_annullato, n_altro, totale
FROM tmp_classificazione
WHERE tipologia IS NOT NULL AND stato IS NULL;

DROP TABLE tmp_vrs_rata;
DROP TABLE tmp_classificazione;
