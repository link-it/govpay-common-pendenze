-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): tutti i debitori della posizione,
-- incluso il primo (ordine 0) — in v2 il debitore vive solo denormalizzato su
-- versamenti.debitore_*, un solo soggetto per versamento. Nessuna riga
-- esistente da migrare: tabella vuota alla creazione.
--
-- L'id usa una sequenza esplicita (CREATE SEQUENCE + colonna BIGINT
-- semplice), non IDENTITY: l'entity JPA dichiara GenerationType.SEQUENCE
-- (@SequenceGenerator su seq_soggetti_debitori) — Hibernate quindi interroga
-- la sequenza e fornisce l'id esplicito nell'INSERT, mentre una colonna
-- IDENTITY rifiuta un INSERT che specifichi un valore esplicito per quella
-- colonna (a meno di SET IDENTITY_INSERT, che Hibernate non usa).
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_soggetti_debitori START WITH 1 INCREMENT BY 1;

CREATE TABLE soggetti_debitori
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
	id BIGINT NOT NULL,
	id_documento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_soggetti_debitori_1 UNIQUE (id_documento, ordine),
	-- fk/pk keys constraints
	CONSTRAINT fk_sgd_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_soggetti_debitori PRIMARY KEY (id)
);
