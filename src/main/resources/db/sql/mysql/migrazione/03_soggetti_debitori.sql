-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): tutti i debitori della posizione,
-- incluso il primo (ordine 0) — in v2 il debitore vive solo denormalizzato su
-- versamenti.debitore_*, un solo soggetto per versamento. Nessuna riga
-- esistente da migrare: tabella vuota alla creazione.
--
-- L'id NON usa AUTO_INCREMENT: MySQL non supporta CREATE SEQUENCE, e
-- l'entity JPA dichiara GenerationType.SEQUENCE (@SequenceGenerator su
-- seq_soggetti_debitori) — un id auto-incrementante in colonna e' in
-- conflitto con l'id esplicito che Hibernate fornisce nell'INSERT. Per
-- questo dialetto Hibernate 7 emula la sequenza con una tabella dedicata a
-- una riga (nome uguale al nome della sequenza, colonna next_val), che
-- interroga e aggiorna prima di ogni INSERT. La tabella va creata qui
-- manualmente con la stessa struttura, popolata con la riga iniziale.
-- ---------------------------------------------------------------------------

CREATE TABLE seq_soggetti_debitori (next_val BIGINT) ENGINE INNODB;
INSERT INTO seq_soggetti_debitori (next_val) VALUES (1);

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
)ENGINE INNODB CHARACTER SET latin1 COLLATE latin1_general_cs;
