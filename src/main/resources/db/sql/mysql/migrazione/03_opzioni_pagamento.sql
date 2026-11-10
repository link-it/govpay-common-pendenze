-- ---------------------------------------------------------------------------
-- Tabella nuova (non esiste nel legacy): la macchina a stati delle opzioni di
-- pagamento alternative (DISPONIBILE/ATTIVATA/ANNULLATA) — v2 non ha alcuna
-- forma di mutua esclusione tra versamenti "fratelli", nemmeno manuale.
-- Ogni opzione appartiene a un debitore della posizione (pd_debitori), non
-- alla posizione: la mutua esclusione resta pero' a livello di intera
-- posizione, tra le opzioni di tutti i suoi debitori.
-- Nessuna riga esistente da migrare: tabella vuota alla creazione.
--
-- id_opzione_pagamento e' BINARY(16): e' la rappresentazione binaria in cui
-- Hibernate 7 serializza un campo Java UUID per il dialetto MySQL (verificato
-- generando lo schema con SchemaManagementToolCoordinator per MySQLDialect
-- contro l'esatta versione di Hibernate usata dal progetto, non per lettura
-- di documentazione) — NON e' una stringa testuale.
--
-- ENGINE/CHARACTER SET/COLLATE come da convenzione dello schema base del core
-- per MySQL (vedi src/main/resources/db/sql/mysql/gov_pay.sql).
--
-- L'id usa AUTO_INCREMENT: l'entity JPA dichiara GenerationType.IDENTITY,
-- stessa convenzione gia' in uso dal core per questo dialetto sulle tabelle
-- legacy (vedi id delle tabelle esistenti in gov_pay.sql) — issue #10.
-- ---------------------------------------------------------------------------

CREATE TABLE opzioni_pagamento
(
	id_opzione_pagamento BINARY(16) NOT NULL,
	tipologia VARCHAR(35) NOT NULL,
	giorni INT,
	stato VARCHAR(35) NOT NULL,
	versione BIGINT NOT NULL,
	data_inizio_validita DATE,
	data_scadenza DATE,
	data_creazione DATETIME(3) NOT NULL,
	data_ultimo_aggiornamento DATETIME(3) NOT NULL,
	-- fk/pk columns
	id BIGINT NOT NULL AUTO_INCREMENT,
	id_pd_debitore BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_opzioni_pagamento_id_opzione UNIQUE (id_opzione_pagamento),
	-- fk/pk keys constraints
	CONSTRAINT fk_opz_id_pd_debitore FOREIGN KEY (id_pd_debitore) REFERENCES pd_debitori(id),
	CONSTRAINT pk_opzioni_pagamento PRIMARY KEY (id)
)ENGINE INNODB CHARACTER SET latin1 COLLATE latin1_general_cs;

CREATE INDEX idx_opz_id_pd_debitore ON opzioni_pagamento (id_pd_debitore);
