-- ---------------------------------------------------------------------------
-- Sintesi di un'OpzionePagamento per i documenti esistenti, limitata ai due
-- casi "semplici" classificati in docs/proposta-modello-nativo-v3.md §28
-- (leggere quella sezione per il ragionamento completo):
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
--
-- QUALUNQUE altro pattern NON viene toccato da questo script (coppie
-- RIDOTTO/SCONTATO, ENTRO/OLTRE espliciti, cod_rata anomali/duplicati, o
-- stati storici incompatibili con le 3 categorie di stato ammesse — vedi
-- tabella nel documento): quei documenti restano senza opzione. Le query
-- diagnostiche in fondo a questo file li elencano per un report separato,
-- da rivedere caso per caso prima di decidere una correzione dati o
-- un'estensione del modello v3.
--
-- Idempotente: considera solo versamenti con id_opzione_pagamento ancora
-- NULL — sicuro rieseguirlo dopo aver risolto manualmente altri documenti
-- (es. dopo aver corretto un dato anomalo segnalato dal report).
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
        -- Vedi tabella "derivazione dello stato" in proposta-modello-nativo-v3.md §28:
        -- il mix NON_ESEGUITO+ANNULLATO senza alcun altro stato (n_altro=0) non rientra
        -- in nessuna delle tre categorie ammesse -> stato NULL, documento escluso.
        CASE
            WHEN n_non_eseguito = totale THEN 'DISPONIBILE'
            WHEN n_annullato = totale THEN 'ANNULLATA'
            WHEN n_altro > 0 THEN 'ATTIVATA'
            ELSE NULL
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
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
-- ---------------------------------------------------------------------------

-- 1) Pattern legacy riconosciuti ma non ancora mappati su v3 (RIDOTTO/SCONTATO,
--    ENTRO/OLTRE espliciti) — non sono dati anomali, manca solo la regola di
--    conversione (vedi §28).
SELECT v.id_documento, array_agg(DISTINCT v.cod_rata ORDER BY v.cod_rata) AS valori_cod_rata
FROM versamenti v
WHERE v.id_documento IS NOT NULL
  AND v.id_opzione_pagamento IS NULL
  AND v.cod_rata ~ '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$'
GROUP BY v.id_documento;

-- 2) Documenti con cod_rata genuinamente anomalo: non tutti NULL, non tutti
--    numerici validi senza duplicati, e nessun valore riconosciuto come
--    RIDOTTO/SCONTATO/ENTRO/OLTRE — pattern misto o dato corrotto, da
--    rivedere a mano.
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
        COUNT(*) FILTER (WHERE cod_rata ~ '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$') AS n_riconosciuto
    FROM versamenti_candidati
    GROUP BY id_documento
)
SELECT id_documento, totale, n_null, n_numerico, n_numerico_distinti, n_riconosciuto
FROM classificazione
WHERE NOT (n_null = totale)
  AND NOT (n_numerico = totale AND n_numerico_distinti = totale)
  AND NOT (n_riconosciuto = totale);

-- 3) Documenti con pattern cod_rata riconosciuto (NULL o numerico valido) ma
--    stati storici delle pendenze incompatibili con le 3 categorie ammesse
--    (mix NON_ESEGUITO/ANNULLATO senza altri stati) — gap di modello v3
--    esposto dalla migrazione, non un dato da "correggere".
WITH versamenti_candidati AS (
    SELECT v.id, v.id_documento, v.cod_rata, v.stato_versamento
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
        COUNT(*) FILTER (WHERE stato_versamento = 'NON_ESEGUITO') AS n_non_eseguito,
        COUNT(*) FILTER (WHERE stato_versamento = 'ANNULLATO') AS n_annullato,
        COUNT(*) FILTER (WHERE stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO')) AS n_altro
    FROM versamenti_candidati
    GROUP BY id_documento
)
SELECT id_documento, n_non_eseguito, n_annullato, n_altro, totale
FROM classificazione
WHERE (n_null = totale OR (n_numerico = totale AND n_numerico_distinti = totale))
  AND n_non_eseguito < totale AND n_annullato < totale AND n_altro = 0;
