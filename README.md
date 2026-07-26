<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay Common Pendenze

[![CI/CD Pipeline](https://github.com/link-it/govpay-common-pendenze/actions/workflows/maven.yml/badge.svg)](https://github.com/link-it/govpay-common-pendenze/actions/workflows/maven.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-common-pendenze&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-common-pendenze)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-common-pendenze&metric=coverage)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-common-pendenze)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://raw.githubusercontent.com/link-it/govpay-common-pendenze/main/LICENSE)

Libreria comune di gestione delle pendenze, condivisa tra i moduli GovPay.

## Installazione

```xml
<dependency>
    <groupId>org.gov4j.govpay</groupId>
    <artifactId>govpay-common-pendenze</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Gli SNAPSHOT sono pubblicati sul repository snapshot del Central Portal:

```xml
<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

## Build

```bash
# Build completo con tutti i check (test, coverage, OWASP)
mvn clean install

# Build veloce, senza OWASP Dependency-Check
mvn clean install -Dowasp.phase=none

# Test con report di coverage
mvn clean test
# Report: target/site/jacoco/index.html
```

Requisiti: JDK 21, Maven 3.9+.

## CI/CD

Validazione e pubblicazione sono automatizzate con GitHub Actions: build, test,
coverage, OWASP Dependency-Check, SonarCloud, analisi delle licenze, OSV scan e SBOM
CycloneDX su ogni push e PR; pubblicazione degli SNAPSHOT su Maven Central dai push su
`main` e delle release dai tag `X.Y.Z`.

Dettagli, secrets richiesti e troubleshooting: [.github/PIPELINE.md](.github/PIPELINE.md).

## Licenza

Distribuito con licenza [GNU GPL v3](LICENSE).
