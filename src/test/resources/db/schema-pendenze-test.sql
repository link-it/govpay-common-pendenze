-- ---------------------------------------------------------------------------
-- Schema di test dell'aggregato pendenza.
--
-- Provenienza: govpay 3.10.x, src/main/resources/db/sql/postgresql/gov_pay.sql
--   (branch 3.10.x, commit 305365385). Colonne, tipi, lunghezze e vincoli di
--   unicita' sono copiati alla lettera: e' su questo che i test verificano il
--   mapping con spring.jpa.hibernate.ddl-auto=validate.
--
-- Differenze volute rispetto al DDL di produzione, e solo queste:
--   1. sono presenti unicamente le tre tabelle dell'aggregato
--      (versamenti, singoli_versamenti, documenti) con le loro sequenze;
--   2. sono state rimosse le foreign key verso l'anagrafica (applicazioni,
--      domini, uo, tipi_versamento, tipi_vers_domini, tributi, iban_accredito),
--      che qui non esiste: la libreria mappa quelle colonne come semplici FK
--      Long, senza relazioni JPA, quindi l'integrita' referenziale verso
--      l'anagrafica non e' oggetto di questi test.
--
-- Se il DDL upstream cambia, questo file va riallineato.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_documenti start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE seq_versamenti start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE seq_singoli_versamenti start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE documenti
(
	cod_documento VARCHAR(35) NOT NULL,
	descrizione VARCHAR(255) NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_documenti') NOT NULL,
	id_dominio BIGINT NOT NULL,
	id_applicazione BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_documenti_1 UNIQUE (cod_documento,id_applicazione,id_dominio),
	-- fk/pk keys constraints
	CONSTRAINT pk_documenti PRIMARY KEY (id)
);

CREATE TABLE versamenti
(
	cod_versamento_ente VARCHAR(35) NOT NULL,
	nome VARCHAR(35),
	importo_totale DOUBLE PRECISION NOT NULL,
	stato_versamento VARCHAR(35) NOT NULL,
	descrizione_stato VARCHAR(255),
	-- Indica se, decorsa la dataScadenza, deve essere aggiornato da remoto o essere considerato scaduto
	aggiornabile BOOLEAN NOT NULL,
	data_creazione TIMESTAMP NOT NULL,
	data_validita TIMESTAMP,
	data_scadenza TIMESTAMP,
	data_ora_ultimo_aggiornamento TIMESTAMP NOT NULL,
	causale_versamento VARCHAR(1024),
	debitore_tipo VARCHAR(1),
	debitore_identificativo VARCHAR(35) NOT NULL,
	debitore_anagrafica VARCHAR(70) NOT NULL,
	debitore_indirizzo VARCHAR(70),
	debitore_civico VARCHAR(16),
	debitore_cap VARCHAR(16),
	debitore_localita VARCHAR(35),
	debitore_provincia VARCHAR(35),
	debitore_nazione VARCHAR(2),
	debitore_email VARCHAR(256),
	debitore_telefono VARCHAR(35),
	debitore_cellulare VARCHAR(35),
	debitore_fax VARCHAR(35),
	tassonomia_avviso VARCHAR(35),
	tassonomia VARCHAR(35),
	cod_lotto VARCHAR(35),
	cod_versamento_lotto VARCHAR(35),
	cod_anno_tributario VARCHAR(35),
	cod_bundlekey VARCHAR(256),
	dati_allegati TEXT,
	incasso VARCHAR(1),
	anomalie TEXT,
	iuv_versamento VARCHAR(35),
	numero_avviso VARCHAR(35),
	ack BOOLEAN NOT NULL,
	anomalo BOOLEAN NOT NULL,
	divisione VARCHAR(35),
	direzione VARCHAR(35),
	id_sessione VARCHAR(35),
	data_pagamento TIMESTAMP,
	importo_pagato DOUBLE PRECISION NOT NULL,
	importo_incassato DOUBLE PRECISION NOT NULL,
	stato_pagamento VARCHAR(35) NOT NULL,
	iuv_pagamento VARCHAR(35),
	src_iuv VARCHAR(35),
	src_debitore_identificativo VARCHAR(35) NOT NULL,
	cod_rata VARCHAR(35),
	tipo VARCHAR(35) NOT NULL,
	data_notifica_avviso TIMESTAMP,
	avviso_notificato BOOLEAN,
	avv_mail_data_prom_scadenza TIMESTAMP,
	avv_mail_prom_scad_notificato BOOLEAN,
	avv_app_io_data_prom_scadenza TIMESTAMP,
	avv_app_io_prom_scad_notificat BOOLEAN,
	proprieta TEXT,
	data_ultima_modifica_aca TIMESTAMP,
	data_ultima_comunicazione_aca TIMESTAMP,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_versamenti') NOT NULL,
	id_tipo_versamento_dominio BIGINT NOT NULL,
	id_tipo_versamento BIGINT NOT NULL,
	id_dominio BIGINT NOT NULL,
	id_uo BIGINT,
	id_applicazione BIGINT NOT NULL,
	id_documento BIGINT,
	-- unique constraints
	CONSTRAINT unique_versamenti_1 UNIQUE (cod_versamento_ente,id_applicazione),
	-- fk/pk keys constraints
	CONSTRAINT fk_vrs_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_versamenti PRIMARY KEY (id)
);

CREATE INDEX idx_vrs_id_pendenza ON versamenti (cod_versamento_ente,id_applicazione);
CREATE INDEX idx_vrs_data_creaz ON versamenti (data_creazione DESC);
CREATE INDEX idx_vrs_stato_vrs ON versamenti (stato_versamento);
CREATE INDEX idx_vrs_deb_identificativo ON versamenti (src_debitore_identificativo);
CREATE INDEX idx_vrs_iuv ON versamenti (src_iuv);
CREATE INDEX idx_vrs_auth ON versamenti (id_dominio,id_tipo_versamento,id_uo);
CREATE INDEX idx_vrs_prom_avviso ON versamenti (avviso_notificato,data_notifica_avviso DESC);
CREATE INDEX idx_vrs_avv_mail_prom_scad ON versamenti (avv_mail_prom_scad_notificato,avv_mail_data_prom_scadenza DESC);
CREATE INDEX idx_vrs_avv_io_prom_scad ON versamenti (avv_app_io_prom_scad_notificat,avv_app_io_data_prom_scadenza DESC);
CREATE INDEX idx_vrs_iuv_dominio ON versamenti (iuv_versamento,id_dominio);
CREATE INDEX idx_vrs_sped_aca ON versamenti (data_ultima_modifica_aca DESC,data_ultima_comunicazione_aca DESC);

CREATE TABLE singoli_versamenti
(
	cod_singolo_versamento_ente VARCHAR(70) NOT NULL,
	stato_singolo_versamento VARCHAR(35) NOT NULL,
	importo_singolo_versamento DOUBLE PRECISION NOT NULL,
	tipo_bollo VARCHAR(2),
	hash_documento VARCHAR(70),
	provincia_residenza VARCHAR(2),
	tipo_contabilita VARCHAR(1),
	codice_contabilita VARCHAR(255),
	descrizione VARCHAR(256),
	dati_allegati TEXT,
	indice_dati INT NOT NULL,
	descrizione_causale_rpt VARCHAR(140),
	contabilita TEXT,
	metadata TEXT,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_singoli_versamenti') NOT NULL,
	id_versamento BIGINT NOT NULL,
	id_tributo BIGINT,
	id_iban_accredito BIGINT,
	id_iban_appoggio BIGINT,
	id_dominio BIGINT,
	-- fk/pk keys constraints
	CONSTRAINT fk_sng_id_versamento FOREIGN KEY (id_versamento) REFERENCES versamenti(id),
	CONSTRAINT pk_singoli_versamenti PRIMARY KEY (id)
);
