# Contributing

Thanks for contributing to Semantic Frequency Log Anomaly Detection.

## Local setup

Use JDK 17 or newer.

```bash
cp .env_example .env
mvn test
```

The default test path uses deterministic synthetic embeddings and does not require OpenSearch.

## Verification

Run the default test suite before opening a change:

```bash
mvn test
```

If you want to exercise the OpenSearch-backed paths, start a local OpenSearch instance and run:

```bash
OPENSEARCH_INTEGRATION_ENABLED=true mvn verify
```

## Development guidelines

- keep the classification path deterministic
- treat semantic retrieval and semantic frequency as separate signals
- keep synthetic runs reproducible by preserving deterministic defaults unless a change explicitly targets another evaluation mode
- prefer small, test-backed changes over broad refactors

## Configuration notes

- `.env_example` is the source of truth for supported local configuration
- `.env` is local-only and should not be committed
- generated workbook outputs belong in the configured report directory and are not part of the tracked code surface
