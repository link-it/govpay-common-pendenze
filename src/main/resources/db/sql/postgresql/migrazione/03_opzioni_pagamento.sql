-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): la macchina a stati delle opzioni di
-- pagamento alternative (DISPONIBILE/ATTIVATA/ANNULLATA) — v2 non ha alcuna
-- forma di mutua esclusione tra versamenti "fratelli", nemmeno manuale.
-- Ogni opzione appartiene a un debitore della posizione (pd_debitori), non
-- alla posizione: la mutua esclusione resta pero' a livello di intera
-- posizione, tra le opzioni di tutti i suoi debitori.
-- Nessuna riga esistente da migrare: tabella vuota alla creazione.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS seq_opzioni_pagamento
    START 1 INCREMENT 1 MAXVALUE 9223372036854775807 MINVALUE 1 CACHE 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS opzioni_pagamento
(
    id_opzione_pagamento UUID NOT NULL,
    tipologia VARCHAR(35) NOT NULL,
    giorni INT,
    stato VARCHAR(35) NOT NULL,
    versione BIGINT NOT NULL,
    data_inizio_validita DATE,
    data_scadenza DATE,
    data_creazione TIMESTAMP NOT NULL,
    data_ultimo_aggiornamento TIMESTAMP NOT NULL,
    -- fk/pk columns
    id BIGINT DEFAULT nextval('seq_opzioni_pagamento') NOT NULL,
    id_pd_debitore BIGINT NOT NULL,
    -- unique constraints
    CONSTRAINT unique_opzioni_pagamento_id_opzione UNIQUE (id_opzione_pagamento),
    -- fk/pk keys constraints
    CONSTRAINT fk_opz_id_pd_debitore FOREIGN KEY (id_pd_debitore) REFERENCES pd_debitori(id),
    CONSTRAINT pk_opzioni_pagamento PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_opz_id_pd_debitore ON opzioni_pagamento (id_pd_debitore);
