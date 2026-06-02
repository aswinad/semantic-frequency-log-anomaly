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
DATASET_MODE=synthetic
DATASET_ACTION=evaluate
OPENSEARCH_URL=http://localhost:9200
OPENSEARCH_INDEX=log-anomaly-synthetic
OPENSEARCH_INTEGRATION_ENABLED=false
EMBEDDING_PROVIDER=deterministic-synthetic-v1
OPENAI_API_KEY=
OPEN_API_KEY=
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=1536
OPENSTACK_LOGHUB_DIR=data/loghub/openstack
OPENSTACK_INDEX=log-anomaly-openstack
OPENSTACK_RECREATE_INDEX=false
OPENSTACK_EMBEDDING_CACHE=target/openstack-embedding-cache.jsonl
OPENSTACK_BATCH_SIZE=64
OPENSTACK_INDEX_BATCH_SIZE=1000
BGL_LOGHUB_FILE=data/loghub/bgl/BGL.log
BGL_INDEX=log-anomaly-bgl
BGL_RECREATE_INDEX=false
BGL_EMBEDDING_CACHE=target/bgl-embedding-cache.jsonl
BGL_BATCH_SIZE=64
BGL_INDEX_BATCH_SIZE=1000
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

The app uses dataset/action flags so one Maven command can run multiple workflows:

```bash
# Current synthetic paper demo
DATASET_MODE=synthetic DATASET_ACTION=evaluate mvn exec:java

# One-time OpenStack embedding and index creation
DATASET_MODE=openstack DATASET_ACTION=index EMBEDDING_PROVIDER=openai mvn exec:java

# Repeatable OpenStack evaluation against the existing index
DATASET_MODE=openstack DATASET_ACTION=evaluate EMBEDDING_PROVIDER=openai mvn exec:java

# One-time BGL embedding and index creation
DATASET_MODE=bgl DATASET_ACTION=index EMBEDDING_PROVIDER=openai mvn exec:java

# Repeatable BGL evaluation against the existing index
DATASET_MODE=bgl DATASET_ACTION=evaluate EMBEDDING_PROVIDER=openai mvn exec:java
```

OpenStack indexing is the expensive step because it creates embeddings and saves documents into `OPENSTACK_INDEX`. OpenStack evaluation is designed to be repeated without recreating the index or re-embedding the whole dataset.

BGL follows the same pattern but uses two derived indexes to avoid storing one repeated vector per log event. `BGL_INDEX` is a base name:

```text
${BGL_INDEX}-templates  # one vector document per unique normalized template
${BGL_INDEX}-events     # one lightweight document per BGL log row, no embedding field
```

For the default `BGL_INDEX=log-anomaly-bgl`, the actual indexes are `log-anomaly-bgl-templates` and `log-anomaly-bgl-events`. Existing cached embeddings in `BGL_EMBEDDING_CACHE` are reused. If you created the old single-index `log-anomaly-bgl` before this split, it is obsolete and can be deleted manually:

```bash
curl -X DELETE localhost:9200/log-anomaly-bgl
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

## Dataset Strategy

The implemented dataset today is the synthetic benchmark. Public LogHub datasets are planned validation tracks so the same semantic-frequency thesis can be tested against real operational logs without mixing controlled and external results.

| Dataset | Purpose | Label Type | Window Strategy | Embedding Recommendation | Paper Role |
| --- | --- | --- | --- | --- | --- |
| Synthetic Benchmark | Controlled reproducible paper demo | Scenario-level expected class | `.env` defaults: `5 min / 55 min` | Deterministic embeddings | Proves exact pattern misses paraphrases while semantic frequency catches them |
| OpenStack LogHub | External validation on OpenStack logs | VM IDs listed in `anomaly_labels.txt` | Remap normal files to baseline and abnormal file to test period | `text-embedding-3-small` with template caching | Real-log validation after the synthetic benchmark |
| BGL LogHub | Stronger public validation on dense system logs | Per-line label: `-` normal, other values anomaly | Original timestamps with sliding `15 min / 24 hr` windows | `text-embedding-3-small` with template caching | Main public dataset candidate because labels and timestamps align well |
| HDFS LogHub | Optional future robustness check | Block/session-level labels | Session-aware windows, not simple line-level windows | `text-embedding-3-small` with template caching | Later validation, less ideal for the first paper experiment |

### Synthetic Benchmark

The synthetic benchmark uses generated historical logs and scenario probes from the Java codebase. It keeps deterministic embeddings as the default so the semantic neighborhoods, spike counts, and Excel report are exactly reproducible.

Recommended default:

```text
EXPERIMENT_SHORT_WINDOW_MINUTES=5
EXPERIMENT_BASELINE_WINDOW_MINUTES=55
EMBEDDING_PROVIDER=deterministic-synthetic-v1
```

Paper role: this is the controlled proof that exact-pattern counting undercounts paraphrased incidents, bounded top-K retrieval is only examples, and threshold-based semantic frequency captures related operational prevalence.

### OpenStack LogHub

OpenStack should be used as an external validation dataset after the synthetic benchmark. Use:

```text
openstack_normal1.log
openstack_normal2.log
openstack_abnormal.log
anomaly_labels.txt
```

The two normal files should act as historical baseline data. The abnormal file should act as the test or incident period. The VM instance IDs in `anomaly_labels.txt` are the ground-truth anomaly identifiers; the entire abnormal file should not be treated as anomalous.

Because the files are separated by dataset construction, timestamps need to be normalized before OpenSearch range-window evaluation. The raw OpenStack timestamp should be preserved as `originalTimestamp`, while the OpenSearch `timestamp` field should store the normalized experiment timestamp used by semantic-frequency queries. A practical validation setup is:

```text
openstack_normal1.log + openstack_normal2.log -> 2026-01-01T00:00:00Z to 2026-01-01T23:45:00Z
openstack_abnormal.log                         -> 2026-01-01T23:45:00Z to 2026-01-02T00:00:00Z
```

Recommended window range:

```text
short window:    15 minutes
baseline window: 24 hours
```

Paper role: OpenStack gives real operational logs and VM-level anomaly labels, but it should be reported separately from the synthetic benchmark.

### BGL LogHub

BGL is the strongest public validation candidate for this paper because it has dense system logs, real timestamps, and per-line labels. In `BGL.log`, the first column is the label:

```text
-        normal
APPREAD  anomaly/alert type
KERNDTLB anomaly/alert type
```

Use the original BGL timestamps. For each evaluated log at time `T`, query OpenSearch over sliding windows before `T`:

```text
short window:    T - 15 minutes to T
baseline window: T - 24 hours to T - 15 minutes
```

Paper role: BGL can test whether semantic-frequency spike detection works on real, line-labeled operational data. It is a better first public validation target than HDFS for this specific thesis.

### HDFS LogHub

HDFS is useful but less direct for this paper because its anomaly labels are typically block/session-level rather than line-level. That means the experiment must group logs by block ID or session before classification, which is a different evaluation shape from the line-level semantic-frequency benchmark.

Paper role: optional later robustness validation, not the first public dataset target.

## Time Window Strategy

For each evaluated log at time `T`, the short window measures current activity and the baseline window estimates historical expected activity. The framework compares recent semantic prevalence against the historical semantic baseline:

```text
expected_short_count = baseline_count * (short_window_duration / baseline_window_duration)
spike_ratio = short_count / expected_short_count
```

Recommended defaults:

| Experiment | Short Window | Baseline Window |
| --- | ---: | ---: |
| Synthetic | 5 minutes | 55 minutes |
| OpenStack | 5-15 minutes | 1-24 hours |
| BGL | 15 minutes | 24 hours |
| Sensitivity check A | 5 minutes | 6 hours |
| Sensitivity check B | 15 minutes | 24 hours |
| Sensitivity check C | 60 minutes | 7 days |

The paper should report public dataset results separately from synthetic results unless the tables clearly label the dataset source.

## Embedding Guidance

Deterministic embeddings remain the default for reproducible synthetic experiments. For public dataset validation, `text-embedding-3-small` is the recommended first real embedding model because it is cost-effective and sufficient for grouping semantically related log messages.

For large public datasets, cache embeddings by normalized message or parsed template when possible. For example, repeated raw lines that normalize to the same template should reuse one embedding instead of calling the embedding API for every duplicate line.

The generated Excel workbook structure should remain the same across dataset modes:

```text
Overall Performance
Semantic Cluster Detection
Operational Spike Detection
Ablation Study
Charts
Scenario Results
Top-K Examples
```

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
