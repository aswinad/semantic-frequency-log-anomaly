# Log Anomaly Detection Test Project

Java/Maven project for the semantic-frequency log anomaly detection framework described in the consolidated paper.

The main simulation flow now does this:

1. Recreates an OpenSearch KNN index.
2. Inserts synthetic historical logs for exact repeated errors, paraphrased failure families, known semantic spikes, novel semantic events, compound anomalies, and operational noise.
3. Runs incoming probe logs through OpenSearch top-K retrieval for representative examples.
4. Runs exact-pattern counts as the traditional baseline.
5. Runs threshold-based semantic-frequency counts across short and baseline time windows.
6. Applies deterministic hybrid classification from semantic familiarity and semantic-frequency deviation.
7. Prints a comparison table: `Exact Pattern | Top-K Only | Semantic Frequency | Hybrid`.
8. Optionally writes an Excel workbook with paper-ready metric tables.

## What is covered

- Exact repeated errors are handled by both pattern counts and semantic frequency.
- Paraphrased database failure families are aggregated by semantic frequency.
- Novel semantic events classify as rare anomalies.
- Known semantic spikes classify as surge anomalies.
- Novel spikes classify as critical anomalies.
- Spike detection uses the paper formula:

```text
Expected = LongTermCount * ShortWindowDuration / LongWindowDuration
SpikeRatio = ActualCount / Expected
```

- Hybrid scoring combines semantic novelty and temporal abnormality.
- Signal separation tests protect against treating bounded top-K neighbor counts as historical frequency.
- OpenSearch integration tests validate Docker-backed vector indexing, top-K retrieval, exact-pattern counts, semantic-frequency counts, and end-to-end scenario classification.
- V1 uses deterministic synthetic embeddings through an `EmbeddingProvider` interface; real embedding providers can be added later.

## Requirements

- Java 17 or higher. The Maven build compiles with `--release 17` for broad open-source compatibility.
- Maven 3.9 or higher.
- Docker OpenSearch 2.x for integration tests and the paper demo.

## Run unit tests

```bash
mvn test
```

## Project Structure

```text
src/main/java/com/loganomaly
  app/          CLI/demo entry point
  config/       .env and experiment configuration
  core/         deterministic anomaly classification model
  embedding/    embedding provider abstraction and deterministic provider
  experiment/   synthetic dataset, probes, analyzer, scenario results
  opensearch/   OpenSearch repository and vector/count queries
  reasoning/    signal-separation/conflation reasoning examples
src/test/java/com/loganomaly
  *Test.java    unit tests
  *IT.java      Docker OpenSearch integration tests
```

## Configure `.env`

Copy the example file:

```bash
cp .env_example .env
```

The harness reads configuration from `.env`. Shell environment variables can override `.env` values for CI or one-off local runs.

Important values:

```text
OPENSEARCH_URL=http://localhost:9200
OPENSEARCH_INDEX=log-anomaly-synthetic
OPENSEARCH_INTEGRATION_ENABLED=false
EMBEDDING_PROVIDER=deterministic-synthetic-v1
EXPERIMENT_TOP_K=5
EXPERIMENT_SIMILARITY_THRESHOLD=0.85
EXPERIMENT_SHORT_WINDOW_MINUTES=5
EXPERIMENT_BASELINE_WINDOW_MINUTES=55
REPORT_EXCEL_ENABLED=true
REPORT_OUTPUT_DIR=reports
REPORT_FILE_PREFIX=semantic-frequency-paper-test
LLM_EVALUATION_MODE=placeholder
```

Keep `OPENSEARCH_INTEGRATION_ENABLED=false` for ordinary `mvn test` runs when Docker OpenSearch is not running. Set it to `true` when you want the Docker-backed OpenSearch tests.

## Run with Docker OpenSearch

Start OpenSearch locally, for example:

```bash
docker run --name opensearch-dev \
  -p 9200:9200 -p 9600:9600 \
  -e discovery.type=single-node \
  -e plugins.security.disabled=true \
  -e OPENSEARCH_INITIAL_ADMIN_PASSWORD='Admin123!' \
  opensearchproject/opensearch:2
```

Then run:

```bash
mvn verify
```

The OpenSearch integration tests run under Maven Failsafe and only when `.env` has:

```text
OPENSEARCH_INTEGRATION_ENABLED=true
```

## Populate OpenSearch and print paper scenario results

With Docker OpenSearch running and `.env` configured:

```bash
mvn exec:java
```

This recreates the default index:

```text
log-anomaly-synthetic
```

To use another index:

```bash
OPENSEARCH_INDEX=my-log-anomaly-index mvn exec:java
```

The simulator prints output like:

```text
Scenario                         Exact Pattern            Top-K Only         Semantic Frequency             Hybrid                     Pass
--------------------------------------------------------------------------------------------------------------------------------------------------
B. Paraphrased Failure Family    8/2 exp=0.2 ratio=44.0 SPIKE 5 examples     39/16 exp=1.5 ratio=26.8 SPIKE SURGE_ANOMALY/SURGE_ANOMALY PASS
```

When `REPORT_EXCEL_ENABLED=true`, the run also writes:

```text
reports/semantic-frequency-paper-test-<timestamp>.xlsx
```

The workbook includes overall performance, semantic cluster detection, operational spike detection, ablation study, scenario-level results, top-K examples, LLM evaluation placeholders, and method notes.

If your Docker OpenSearch has security enabled:

```text
OPENSEARCH_URL=https://localhost:9200
OPENSEARCH_USERNAME=admin
OPENSEARCH_PASSWORD=Admin123!
```

The OpenSearch integration tests are skipped unless `OPENSEARCH_INTEGRATION_ENABLED=true`.

## Research Artifact Intention

This codebase is intended to generate reproducible test results for the associated paper, regardless of venue. The default deterministic embedding provider is deliberate: it keeps semantic neighborhoods stable so the paper scenarios can be reproduced exactly. Real embedding providers should be added behind `EmbeddingProvider` later as validation experiments, not as the default reproducible experiment path.

## Citation

This repository includes [CITATION.cff](CITATION.cff) as a placeholder for the final paper citation and repository URL. Update it before publishing the open-source release.
