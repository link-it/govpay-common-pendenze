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
--
-- id/sequenza/trigger seguono lo stesso pattern gia' in uso nei patch del
-- core per una nuova tabella con chiave generata (vedi
-- src/main/resources/db/sql/oracle/patch/3.9.sql, tabella jppa_config).
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_debitori MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 INCREMENT BY 1 CACHE 2 NOCYCLE;

CREATE TABLE debitori
(
	tipo VARCHAR2(1) NOT NULL,
	identificativo VARCHAR2(35) NOT NULL,
	anagrafica VARCHAR2(70),
	indirizzo VARCHAR2(70),
	civico VARCHAR2(16),
	cap VARCHAR2(16),
	localita VARCHAR2(35),
	provincia VARCHAR2(35),
	nazione VARCHAR2(2),
	email VARCHAR2(256),
	cellulare VARCHAR2(35),
	-- fk/pk columns
	id NUMBER NOT NULL,
	-- fk/pk keys constraints
	CONSTRAINT pk_debitori PRIMARY KEY (id)
);

CREATE TRIGGER trg_debitori
BEFORE
insert on debitori
for each row
begin
   IF (:new.id IS NULL) THEN
      SELECT seq_debitori.nextval INTO :new.id
                FROM DUAL;
   END IF;
end;
/

CREATE INDEX idx_deb_identificativo ON debitori (identificativo);

CREATE SEQUENCE seq_pd_debitori MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 INCREMENT BY 1 CACHE 2 NOCYCLE;

CREATE TABLE pd_debitori
(
	posizione NUMBER NOT NULL,
	notifica_send_payed NUMBER DEFAULT 0 NOT NULL,
	notifica_send_nav VARCHAR2(18),
	-- fk/pk columns
	id NUMBER NOT NULL,
	id_documento NUMBER NOT NULL,
	id_debitore NUMBER NOT NULL,
	-- unique constraints
	CONSTRAINT unique_pd_debitori_1 UNIQUE (id_documento, id_debitore),
	-- fk/pk keys constraints
	CONSTRAINT fk_pdd_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT fk_pdd_id_debitore FOREIGN KEY (id_debitore) REFERENCES debitori(id),
	CONSTRAINT pk_pd_debitori PRIMARY KEY (id)
);

CREATE TRIGGER trg_pd_debitori
BEFORE
insert on pd_debitori
for each row
begin
   IF (:new.id IS NULL) THEN
      SELECT seq_pd_debitori.nextval INTO :new.id
                FROM DUAL;
   END IF;
end;
/

CREATE INDEX idx_pdd_id_debitore ON pd_debitori (id_debitore);
