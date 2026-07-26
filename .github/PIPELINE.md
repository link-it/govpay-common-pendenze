# Pipeline di Validazione e Pubblicazione CI/CD

Questo progetto utilizza GitHub Actions per la validazione automatica del codice, la
pubblicazione degli SNAPSHOT e il rilascio delle versioni stabili su Maven Central.

La pipeline e' allineata a quella del progetto
[`link-it/govpay-common`](https://github.com/link-it/govpay-common).

## Workflow

| Workflow | File | Trigger |
|----------|------|---------|
| CI/CD Pipeline | `.github/workflows/maven.yml` | push su `main`, PR verso `main`, tag che iniziano con una cifra |
| Refresh OWASP DB | `.github/workflows/refresh-owasp-db.yml` | schedulato ogni giorno alle 03:00, `workflow_dispatch` |

## CI/CD Pipeline (`maven.yml`)

### Job: `build`

1. **Setup ambiente**
   - Checkout del codice (`fetch-depth: 0`)
   - Timezone Europe/Rome
   - JDK 21 (distribuzione Temurin) con cache Maven

2. **Security scanning**
   - Cache del database OWASP Dependency-Check (`.dependency-check/data`).
     Chiave: versione del plugin (letta dal POM) + data corrente; se la cache e' esatta
     (stessa giornata) l'update NVD viene skippato con `-DautoUpdate=false`
   - Verifica delle vulnerabilita' note (NVD + OSS Index)
   - Report di sicurezza in formato HTML e XML

3. **Build e test**
   - `mvn clean install`
   - Test unitari
   - Report di code coverage JaCoCo, con zip del report HTML

4. **Code quality**
   - Scansione SonarCloud (`projectKey=link-it_govpay-common-pendenze`, org `link-it`)
   - Le dipendenze vengono copiate in `target/dependency` per `sonar.java.libraries`

5. **License analysis**
   - Download delle licenze con `license-maven-plugin:aggregate-download-licenses`
     (esclusi gli scope `test`, `provided`, `system`, incluse le transitive)
   - Analisi di compatibilita' con `.github/workflows/scripts/analyze_licenses.py`
   - Eccezioni gestite in `.github/workflows/scripts/license-exceptions.json`

6. **Artifact**
   - `third-party-licenses`: report licenze
   - `govpay-common-pendenze`: JAR, `jacoco.xml`, `jacoco-html-report.zip`,
     `dependency-check-report.html`, `dependency-check-report.xml`

### Job: `osv-scan`

Esegue il workflow riusabile `google/osv-scanner-action` in parallelo al build.
Blocca (`fail-on-vuln`) solo su `main` e sui tag; sulle PR segnala senza bloccare.
Pubblica i risultati come SARIF (`osv-report.sarif`) nella sezione Security del repo.

### Job: `sbom`

Genera la SBOM CycloneDX (json + xml, schema 1.6) con
`cyclonedx-maven-plugin:makeAggregateBom` e la pubblica come artifact `sbom-report`
(retention 30 giorni).

### Job: `snapshot-on-main`

Attivo solo sui push di branch (`refs/heads/`), dopo `build`, `osv-scan` e `sbom`.

- Verifica la presenza di `CENTRAL_USERNAME` / `CENTRAL_TOKEN`
- Legge la versione dal POM
- Se la versione termina con `-SNAPSHOT`, esegue
  `mvn -B -DskipTests deploy -Dowasp.phase=none` verso `central-snapshots`
- Se la versione e' stabile, salta la pubblicazione

### Job: `github-release`

Attivo solo sui tag (`refs/tags/`), dopo `build`, `osv-scan` e `sbom`.

- Scarica gli artifact del build, i report licenze, il SARIF OSV e la SBOM
- Rinomina il JAR con la versione del tag
- Assembla `release-reports-<tag>.zip` (owasp, jacoco, osv, sbom, licenze,
  link al run della pipeline)
- Crea la GitHub Release con:
  - `govpay-common-pendenze-<tag>.jar`
  - `release-reports-<tag>.zip`

### Job: `release-on-tag`

Attivo solo sui tag, dopo `build`, `osv-scan` e `sbom`. Pubblica su Maven Central
tramite il Central Portal.

Controlli di sicurezza, in ordine — se uno fallisce il rilascio si interrompe:

1. Il tag deve rispettare il formato `X.Y.Z`
2. Devono essere presenti tutti i secret richiesti (Central + GPG)
3. La versione del POM non deve essere `-SNAPSHOT`
4. Il tag deve coincidere esattamente con la versione del POM

Deploy finale: `mvn -B -DskipTests deploy -Prelease -Dowasp.phase=none`
(profilo `release` ereditato dal BOM: sources, javadoc, firma GPG, central-publishing).

## Refresh OWASP DB (`refresh-owasp-db.yml`)

Mantiene caldo il database NVD in modo che il job `build` non debba scaricarlo.

- Preflight sull'endpoint NVD: se non risponde `200` il job fallisce senza toccare
  la cache esistente
- Installa la CLI Dependency-Check nella stessa versione del plugin dichiarata nel POM
- Esegue solo l'update del DB (`--updateonly`)
- Pannello di tuning nella sezione `env` del workflow:
  `NVD_MAX_RETRY_COUNT`, `NVD_API_DELAY`, `NVD_PREFLIGHT_TIMEOUT`

## Configurazione richiesta

### Variables (Settings -> Secrets and variables -> Actions -> Variables)

| Variable | Descrizione | Obbligatoria |
|----------|-------------|--------------|
| `CENTRAL_USERNAME` | Username del Central Portal Sonatype | Si (per publish) |
| `OSS_INDEX_USER` | Utenza OSS Index per l'analyzer OWASP | Consigliata |

### Secrets (Settings -> Secrets and variables -> Actions -> Secrets)

| Secret | Descrizione | Obbligatorio |
|--------|-------------|--------------|
| `CENTRAL_TOKEN` | Token del Central Portal Sonatype | Si (per publish) |
| `GPG_PRIVATE_KEY` | Chiave privata GPG per la firma degli artifact | Si (per release) |
| `GPG_PASSPHRASE` | Passphrase della chiave GPG | Si (per release) |
| `SONAR_TOKEN` | Token SonarCloud | Si |
| `NVD_API_KEY` | API key NVD (alza il rate limit di Dependency-Check) | Consigliato |
| `OSS_INDEX_PASSWORD` | Password/token OSS Index | Consigliato |
| `GH_TOKEN` | PAT per la creazione della GitHub Release | Si (per release) |

### Come ottenere i secrets

**NVD_API_KEY** — richiedila su https://nvd.nist.gov/developers/request-an-api-key,
arriva via email.

**SONAR_TOKEN** — SonarCloud -> Account -> Security -> genera token.

**CENTRAL_USERNAME / CENTRAL_TOKEN** — https://central.sonatype.com ->
View Account -> Generate User Token.

**GPG_PRIVATE_KEY / GPG_PASSPHRASE** — chiave pubblicata su un keyserver, esportata
in formato ASCII armored (`gpg --armor --export-secret-keys <keyid>`).

**GH_TOKEN** — GitHub Settings -> Developer settings -> Personal access tokens
(classic) con permessi `repo` e `write:packages`.

### SonarCloud

1. Importa il progetto su SonarCloud
2. Organizzazione: `link-it`
3. Project key: `link-it_govpay-common-pendenze`

## Trigger della pipeline

### Push su main
```bash
git push origin main
```
Esegue build, test, OWASP, Sonar, license analysis, OSV, SBOM e — se la versione del POM
e' SNAPSHOT — pubblica lo SNAPSHOT su Maven Central.

### Pull request verso main
Esegue build, test, OWASP, Sonar, license analysis, OSV, SBOM. Nessuna pubblicazione.

### Release
```bash
# 1. Porta il POM alla versione stabile
mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
git commit -am "Rilasciata versione 1.0.0"
git push origin main

# 2. Crea e pusha il tag (deve coincidere con la versione del POM)
git tag 1.0.0
git push origin 1.0.0
```
Esegue build completo, crea la GitHub Release con JAR e report, pubblica la release
firmata su Maven Central.

## Esecuzione locale

```bash
# Build completo con tutti i check
mvn clean install

# Solo build e test, senza OWASP
mvn clean install -Dowasp.phase=none

# Solo code coverage
mvn clean test
open target/site/jacoco/index.html

# Solo security check
mvn dependency-check:aggregate
open target/dependency-check-report.html

# License analysis
mvn org.codehaus.mojo:license-maven-plugin:2.4.0:aggregate-download-licenses \
  -DexcludeScopes=test,provided,system -DincludeTransitiveDependencies=true
python3 .github/workflows/scripts/analyze_licenses.py \
  --exceptions .github/workflows/scripts/license-exceptions.json

# SBOM CycloneDX
mvn org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputDirectory=sbom/cyclonedx -DoutputName=bom.cdx -DoutputFormat=all
```

## Plugin Maven

Versioni e configurazioni arrivano dal parent `org.gov4j.govpay:govpay-bom`
(`pluginManagement`); nel POM del progetto i plugin sono dichiarati senza versione.

### JaCoCo
- Versione: proprieta' `jacoco.version` del BOM
- Esecuzioni: `prepare-agent` + `report` (fase `test`)
- Report: `target/site/jacoco/`

### OWASP Dependency-Check
- Versione: proprieta' `owasp.plugin.version` del BOM
- Goal `aggregate` in fase `${owasp.phase}` (default `verify`)
- Formato: `ALL`; report in `target/dependency-check-report.*`
- Soglia CVSS: `owasp.failBuildOnCVSS` (default `11`, non blocca la build)
- Cache DB locale: `.dependency-check/data` (esclusa da Git)

### Override utili

```bash
# Disabilita completamente OWASP
mvn clean install -Dowasp.phase=none

# Blocca la build sulle vulnerabilita' HIGH
mvn clean verify -Dowasp.failBuildOnCVSS=7

# Disabilita l'auto-update del DB (utile quando NVD ha problemi)
mvn clean verify -Dowasp.autoUpdate=false
```

## License Analysis

Lo script `analyze_licenses.py` verifica:

- **Compatibilita' GPLv3**: tutte le licenze devono essere compatibili con GPLv3
- **Enterprise safety**: segnala licenze problematiche per uso enterprise
- **Licenze sconosciute**: dipendenze senza licenza o con licenza non riconosciuta

Licenze riconosciute: Apache 2.0 (tutte le varianti), MIT, BSD-2/3-Clause,
EPL-1.0/2.0, EDL-1.0, LGPL 2.1/3.0, MPL-2.0, GPL con eccezioni
(Classpath Exception, FOSS Exception).

### Eccezioni

`.github/workflows/scripts/license-exceptions.json`:

```json
{
  "exceptions": [
    {
      "groupId": "org.example",
      "artifactId": "some-artifact",
      "reason": "Spiegazione del motivo dell'eccezione",
      "exclude_from_reports": true
    }
  ]
}
```

- `groupId` / `artifactId`: supportano il wildcard `*`
- `reason`: obbligatorio
- `exclude_from_reports`: se `true`, la dipendenza e' esclusa dai report

### Report generati (`third-party-licenses/`)

- `license-summary.json`
- `license-artifacts-mapping.csv`
- `license-compatibility-report.html`
- `license-compatibility-report.md`

### Gestione delle licenze problematiche

1. **Licenza sconosciuta**: aggiorna il database licenze in `analyze_licenses.py`
2. **Licenza incompatibile**: sostituisci la dipendenza o aggiungi un'eccezione motivata
3. **Dipendenza senza licenza**: contatta il maintainer o evita la dipendenza

## Troubleshooting

### Il build fallisce per timeout OWASP
- Verifica che `NVD_API_KEY` sia configurato (alza il rate limit)
- Lancia manualmente il workflow `Refresh OWASP Dependency-Check DB` per ripopolare la cache
- Alza `owasp.nvdApiDelay` / `owasp.nvdMaxRetryCount`

### SonarCloud non riceve i dati
- Verifica `SONAR_TOKEN`
- Verifica organizzazione (`link-it`) e project key (`link-it_govpay-common-pendenze`)
- Verifica che `target/site/jacoco/jacoco.xml` sia stato generato

### La GitHub Release non viene creata
- Verifica i permessi di `GH_TOKEN`
- Il job si attiva solo sui tag: `git push origin <tag>`

### Il deploy su Maven Central fallisce
- `Missing secrets: ...`: configura variables/secrets Central e GPG
- `Tag does not match POM version`: allinea il tag alla versione del POM
- `Refusing to release a -SNAPSHOT version on tag`: porta il POM a una versione stabile

### Il parent BOM non viene risolto
Il parent e' una versione SNAPSHOT: assicurati che il repository
`central-snapshots` sia raggiungibile (e' dichiarato nel `pom.xml`) oppure installa
localmente il BOM con `mvn install` dal checkout di `govpay-bom`.

## Badge per il README

```markdown
[![CI/CD Pipeline](https://github.com/link-it/govpay-common-pendenze/actions/workflows/maven.yml/badge.svg)](https://github.com/link-it/govpay-common-pendenze/actions/workflows/maven.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-common-pendenze&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-common-pendenze)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-common-pendenze&metric=coverage)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-common-pendenze)
```
