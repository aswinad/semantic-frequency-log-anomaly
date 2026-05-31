# Contributing

This repository is intended to be a reproducible research artifact for the semantic-frequency log anomaly detection paper.

## Local Setup

Use JDK 17 or newer. The project intentionally compiles with Java release 17 so the research artifact is easier to run across developer machines and CI environments.

```bash
cp .env_example .env
mvn test
```

Unit tests use deterministic synthetic embeddings and do not require Docker OpenSearch.

## OpenSearch Integration Tests

Start Docker OpenSearch and set:

```text
OPENSEARCH_INTEGRATION_ENABLED=true
OPENSEARCH_URL=http://localhost:9200
```

Then run:

```bash
mvn verify
```

Integration tests are named `*IT.java` and are run by Maven Failsafe.

## Paper Demo

```bash
mvn exec:java
```

The demo recreates the configured OpenSearch index, seeds synthetic logs, and prints the paper comparison table.
When `REPORT_EXCEL_ENABLED=true`, it also writes a paper metrics workbook under the configured report directory.

## Development Notes

- Keep deterministic embeddings as the default path for reproducible paper results.
- Add real embedding models behind `EmbeddingProvider` as optional validation experiments.
- Keep classification deterministic; LLMs may explain structured outputs but should not own anomaly classification.
