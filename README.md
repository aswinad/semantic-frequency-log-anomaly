# Semantic Frequency Log Anomaly Detection

Java/Maven project for experimenting with semantic-frequency log anomaly detection on synthetic, BGL, and OpenStack-style workloads.

The repository focuses on one operational distinction:

- top-K semantic retrieval finds representative examples
- semantic frequency estimation measures how prevalent a related failure family is in a time window

The codebase combines semantic similarity, temporal counting, and deterministic classification so anomaly labels do not depend on LLM output.

## What the project includes

- synthetic benchmark with deterministic embeddings for reproducible local runs
- OpenSearch-backed evaluation workflows for synthetic, BGL, and OpenStack datasets
- deterministic hybrid anomaly classification
- workbook generation for synthetic metrics, public-dataset evaluation, and metadata analysis
- unit tests for parsing, temporal logic, evaluation behavior, and workbook output

## Requirements

- Java 17+
- Maven 3.9+
- OpenSearch 2.x for indexing/evaluation workflows that use vector search

`mvn test` is the default verification path and does not require OpenSearch when integration testing is disabled.

## Quick start

Copy the sample configuration:

```bash
cp .env_example .env
```

Run the default test suite:

```bash
mvn test
```

Run the default synthetic evaluation:

```bash
DATASET_MODE=synthetic DATASET_ACTION=evaluate mvn exec:java
```

## Common workflows

Synthetic benchmark:

```bash
DATASET_MODE=synthetic DATASET_ACTION=evaluate mvn exec:java
DATASET_MODE=synthetic DATASET_ACTION=analyze_existing mvn exec:java
```

OpenStack:

```bash
DATASET_MODE=openstack DATASET_ACTION=index EMBEDDING_PROVIDER=openai mvn exec:java
DATASET_MODE=openstack DATASET_ACTION=evaluate EMBEDDING_PROVIDER=openai mvn exec:java
```

BGL:

```bash
DATASET_MODE=bgl DATASET_ACTION=index EMBEDDING_PROVIDER=openai mvn exec:java
DATASET_MODE=bgl DATASET_ACTION=evaluate EMBEDDING_PROVIDER=openai mvn exec:java
DATASET_MODE=bgl DATASET_ACTION=evaluate_ablation EMBEDDING_PROVIDER=openai mvn exec:java
DATASET_MODE=bgl DATASET_ACTION=analyze_existing EMBEDDING_PROVIDER=openai mvn exec:java
```

When `REPORT_EXCEL_ENABLED=true`, evaluation workflows write workbook outputs to the configured report directory.

## OpenSearch setup

Example local OpenSearch run:

```bash
docker run --name opensearch-dev \
  -p 9200:9200 -p 9600:9600 \
  -e discovery.type=single-node \
  -e plugins.security.disabled=true \
  -e OPENSEARCH_INITIAL_ADMIN_PASSWORD='Admin123!' \
  opensearchproject/opensearch:2
```

To run integration-style verification with OpenSearch enabled:

```bash
OPENSEARCH_INTEGRATION_ENABLED=true mvn verify
```

## Project structure

```text
src/main/java/com/loganomaly
  app/          workflow entry points
  config/       .env parsing and runtime configuration
  core/         anomaly classes and deterministic classification logic
  embedding/    embedding providers and embedding cache support
  experiment/   synthetic dataset, analyzers, and scenario types
  loghub/       BGL and OpenStack parsing/normalization
  opensearch/   OpenSearch repositories and document models
  report/       workbook writers and evaluation summaries

src/test/java/com/loganomaly
  ...           unit and workflow tests
```

## Configuration

The main runtime settings live in `.env`. Shell environment variables can override them for CI or one-off runs.

Important areas in `.env_example`:

- dataset mode and action
- OpenSearch connection
- embedding provider selection
- OpenStack dataset settings
- BGL dataset settings
- experiment thresholds and windows
- report output settings

## Reproducibility notes

- synthetic runs default to `deterministic-synthetic-v1` embeddings for stable local behavior
- OpenAI embeddings are supported for public-dataset workflows through `EMBEDDING_PROVIDER=openai`
- the hybrid classifier remains deterministic; language models, when used, are downstream explanation aids rather than classification owners

## License

MIT
