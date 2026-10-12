-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): tutti i debitori della posizione,
-- incluso il primo (ordine 0) — in v2 il debitore vive solo denormalizzato su
-- versamenti.debitore_*, un solo soggetto per versamento. Nessuna riga
-- esistente da migrare: tabella vuota alla creazione.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS seq_soggetti_debitori
    START 1 INCREMENT 1 MAXVALUE 9223372036854775807 MINVALUE 1 CACHE 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS soggetti_debitori
(
    ordine INT NOT NULL,
    tipo VARCHAR(1) NOT NULL,
    identificativo VARCHAR(35) NOT NULL,
    anagrafica VARCHAR(70),
    indirizzo VARCHAR(70),
    civico VARCHAR(16),
    cap VARCHAR(16),
    localita VARCHAR(35),
    provincia VARCHAR(35),
    nazione VARCHAR(2),
    email VARCHAR(256),
    -- fk/pk columns
    id BIGINT DEFAULT nextval('seq_soggetti_debitori') NOT NULL,
    id_documento BIGINT NOT NULL,
    -- unique constraints
    CONSTRAINT unique_soggetti_debitori_1 UNIQUE (id_documento, ordine),
    -- fk/pk keys constraints
    CONSTRAINT fk_sgd_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
    CONSTRAINT pk_soggetti_debitori PRIMARY KEY (id)
);
