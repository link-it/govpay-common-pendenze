-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): tutti i debitori della posizione,
-- incluso il primo (ordine 0) — in v2 il debitore vive solo denormalizzato su
-- versamenti.debitore_*, un solo soggetto per versamento. Nessuna riga
-- esistente da migrare: tabella vuota alla creazione.
--
-- L'id usa IDENTITY: l'entity JPA dichiara GenerationType.IDENTITY, stessa
-- convenzione gia' in uso dal core per questo dialetto sulle tabelle legacy
-- (vedi id delle tabelle esistenti in gov_pay.sql) — issue #10.
-- ---------------------------------------------------------------------------

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
	cellulare VARCHAR(35),
	-- fk/pk columns
	id BIGINT IDENTITY,
	id_documento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_soggetti_debitori_1 UNIQUE (id_documento, ordine),
	-- fk/pk keys constraints
	CONSTRAINT fk_sgd_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_soggetti_debitori PRIMARY KEY (id)
);
