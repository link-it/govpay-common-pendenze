-- ---------------------------------------------------------------------------
-- Tabelle nuove (non esistono nel legacy): i debitori e il loro legame con la
-- posizione. In v2 il debitore vive solo denormalizzato su versamenti.debitore_*,
-- un solo soggetto per versamento. Nessuna riga esistente da migrare: tabelle
-- vuote alla creazione.
--
-- debitori: anagrafica per occorrenza, una riga per ogni legame con una
-- posizione. Nessun vincolo di unicita' su identificativo: lo stesso soggetto
-- puo' comparire su piu' posizioni come righe distinte.
--
-- pd_debitori: il debitore di una posizione. Le opzioni di pagamento (e quindi
-- le pendenze e i NAV) si agganciano a questa coppia, non alla posizione:
-- ogni debitore ha il proprio piano di pagamento. posizione e' l'ordine di
-- presentazione dei debitori nella posizione.
-- data_rimozione: valorizzata dalla rimozione logica del debitore (le sue opzioni e
-- pendenze sono annullate); un debitore rimosso e' ignorato da letture e ricerche.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS seq_debitori
    START 1 INCREMENT 1 MAXVALUE 9223372036854775807 MINVALUE 1 CACHE 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS debitori
(
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
    cellulare VARCHAR(35),
    -- fk/pk columns
    id BIGINT DEFAULT nextval('seq_debitori') NOT NULL,
    -- fk/pk keys constraints
    CONSTRAINT pk_debitori PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_deb_identificativo ON debitori (identificativo);

CREATE SEQUENCE IF NOT EXISTS seq_pd_debitori
    START 1 INCREMENT 1 MAXVALUE 9223372036854775807 MINVALUE 1 CACHE 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS pd_debitori
(
    posizione INT NOT NULL,
    notifica_send_payed BOOLEAN NOT NULL DEFAULT false,
    notifica_send_nav VARCHAR(18),
    data_rimozione TIMESTAMP,
    -- fk/pk columns
    id BIGINT DEFAULT nextval('seq_pd_debitori') NOT NULL,
    id_documento BIGINT NOT NULL,
    id_debitore BIGINT NOT NULL,
    -- unique constraints
    CONSTRAINT unique_pd_debitori_1 UNIQUE (id_documento, id_debitore),
    -- fk/pk keys constraints
    CONSTRAINT fk_pdd_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
    CONSTRAINT fk_pdd_id_debitore FOREIGN KEY (id_debitore) REFERENCES debitori(id),
    CONSTRAINT pk_pd_debitori PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_pdd_id_debitore ON pd_debitori (id_debitore);
