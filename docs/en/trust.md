# Supply-Chain Trust

> English page · Chinese source: [../trust.md](../trust.md).

A one-page operator's guide to verifying a sureai release artifact: who signed it,
what it contains, how it was built, and whether it has known vulnerabilities. Every
layer is independently checkable.

## The five-layer trust chain

| Layer | Answers | How |
|---|---|---|
| ① GPG signature | **Who signed** the artifact | `maven-gpg-plugin` (`-Prelease`); public key on a keyserver |
| ② SBOM | **What dependencies** it ships | CycloneDX BOM from the `sure-ai-all` aggregate, attached to the GitHub Release |
| ③ SLSA provenance | **Which commit / workflow built it** | GitHub artifact attestation (Sigstore keyless), SLSA Build L3 reference level |
| ④ CVE scan | **Known vulnerabilities in deps** | OWASP dependency-check against NVD, profile `-Psecurity`, gate at CVSS ≥ 7 |
| ⑤ Fuzz robustness | **Do parsers survive malformed input** | fixed-seed JUnit FuzzTest, zero new deps, runs with `mvn verify` |

Layers ①②③ are **release-time trust** (frozen with the version, verifiable offline);
④⑤ are **development-time gates** that run on every PR. All trust mechanisms are
build-time plugins/tests — the only third-party runtime dependency stays `sure-core`.

## Quick commands

| Goal | Command |
|---|---|
| Full build (tests + gates) | `mvn -B verify` |
| Generate aggregate SBOM | `mvn -B -pl sure-ai-all -am package -DskipTests -Dgpg.skip=true` → `sure-ai-all/target/bom.{json,xml}` |
| Validate SBOM schema | `cyclonedx validate --input-file sure-ai-all/target/bom.json --input-format json --input-version v1_6 --fail-on-errors` |
| Match CVEs against the SBOM | `grype sbom:sure-ai-all/target/bom.json` |
| Verify an artifact's SLSA provenance | `gh attestation verify sure-ai-all/target/bom.json --repo TASure/sureai` |
| Run one fuzz suite | `mvn -B -pl sure-ai-core test -Dtest=JsonParserFuzzTest` |

> SLSA attestation and the CI-attached SBOM require a GitHub Actions runner with OIDC;
> they cannot be generated locally. Locally you can only **verify** already-published
> artifacts with `gh attestation verify`.

## Related

- Release process and secrets: [../RELEASING.md](../RELEASING.md)
- Security vulnerability reporting: [../../SECURITY.md](../../SECURITY.md)
- Contributing and test conventions: [../../CONTRIBUTING.md](../../CONTRIBUTING.md)
