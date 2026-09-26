-- ---------------------------------------------------------------------------
-- Va eseguito dopo 02_opzioni_pagamento.sql: id_opzione_pagamento referenzia
-- quella tabella.
-- ---------------------------------------------------------------------------

ALTER TABLE versamenti ADD COLUMN IF NOT EXISTS id_opzione_pagamento BIGINT;
ALTER TABLE versamenti ADD CONSTRAINT fk_vrs_id_opzione_pagamento
    FOREIGN KEY (id_opzione_pagamento) REFERENCES opzioni_pagamento(id);

-- Sentinella -1: nessuna pendenza v3 avra' mai un numero_rata non positivo
-- (1-based) — inequivocabile "questo versamento e' stato inserito dalla v2",
-- a differenza di un piu' plausibile ma ambiguo "1".
ALTER TABLE versamenti ADD COLUMN IF NOT EXISTS numero_rata INT NOT NULL DEFAULT -1;

-- Sentinella 1970-01-01, stesso principio di documenti.data_creazione: nessun
-- equivalente v2 sensato da cui derivare "data di emissione della pendenza"
-- per le righe esistenti.
ALTER TABLE versamenti ADD COLUMN IF NOT EXISTS data_caricamento DATE NOT NULL DEFAULT '1970-01-01';
