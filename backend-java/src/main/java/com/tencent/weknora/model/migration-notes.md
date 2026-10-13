# Native model reads, catalog and remote DB mutations — partial migration

The compatibility bridge must continue to own **all `/api/v1/models` traffic**,
including with `weknora.bridge.native-routes=true`. These native endpoints operate
when the bridge is disabled. Models have not been added to the native allowlist.

## Implemented endpoints

- `GET /api/v1/models` and `GET /api/v1/models/{id}` support all existing categories,
  including remote KnowledgeQA/VLLM. Visibility is tenant-owned or globally builtin,
  excluding deleted rows; builtin visibility cannot expose foreign ordinary rows.
  The actual Go list handler ignores type/source/pagination queries; Java does too.
  Lists include inactive rows; detail returns Go's 500 messages for downloading,
  failed and abnormal states, or 404 for missing/foreign/deleted ordinary rows.
- `GET /providers`, `GET` and `POST /catalog/resolve` expose provider metadata,
  typed catalog entries, actual pure ModelSpec/capability resolution and preview
  URLs. Azure, Aliyun, Volcengine and WeKnora Cloud endpoint hooks are translated,
  including Go's raw query concatenation. No model request is made.
- `POST /api/v1/models` with source exactly `remote` validates typed parameters,
  catalog and URL policy, then creates an active tenant-owned DB row. Go performs
  no connectivity probe for remote creation; Java follows that behavior. Every
  other source returns 503 **before insertion**, because its Go behavior requires
  the actual asynchronous Ollama download worker.
- `PUT /{id}` implements Go's replacement semantics, including absent source/type/
  description overwriting existing values, optional display-name update, API key/
  app secret and parameter-size preservation, fallback app ID/interface type, and
  vendor-aware secret-extra preservation. Existing rows must be active. Builtin
  edits require system administration, preserve original tenant/builtin ownership
  and clear `managed_by` to preserve a runtime override.
- `PUT /{id}/credentials` and `DELETE /{id}/credentials/{field}` encrypt nonempty
  secret edits, treat empty PUT strings as unchanged, clear explicitly/idempotently,
  return only presence metadata, and return 204 for DELETE. Empty PUT requires an
  active row (inactive =>404); actual edits can target inactive rows. Builtin edits
  require system administration and clear declarative ownership.
- `DELETE /{id}` forbids builtin deletion, checks every seven KB/six Agent binding
  plus both memory model pins (even when memory is disabled), and soft-deletes
  only unreferenced ordinary rows. Active current-tenant references block deletion;
  deleted/foreign references do not. 400/code 2300 details return identification only,
  DB name/id order, at most 50 KB/Agent entries and untruncated totals.

Reads require JWT viewer membership; create/delete require workspace admin;
update/credential routes implement Go admin-or-system-admin guards. System admin
bypasses workspace role only on those declared routes. **API key model routes
remain default-denied**; `manage_models` policies are not migrated.

## Projection and secrets

Responses follow `dto.NewModelResponse`, not persisted entity serialization.
They never include `managed_by`, `deleted_at`, `api_key` or `app_secret`. Ordinary
Viewer/Contributor callers hide base URL, extra config, headers and spec. Builtins
additionally hide app ID and credential metadata except for system administrators;
Admin/Owner membership alone does not bypass builtin redaction. Extra keys declared
secret/password by any current provider are globally redacted even after a provider
switch; legacy vendor detection uses the current runtime URL rules.

Shared security `GoSecretCodec` uses Go `enc:v1:` + raw URL base64 of nonce12 /
ciphertext / tag and `SYSTEM_AES_KEY` of exactly 32 UTF-8 bytes. Lenient reads
report bad ciphertext/missing/rotated keys unconfigured. Writes encrypt API key and
app secret when a valid key exists, otherwise preserve Go legacy plaintext behavior.
Go does not encrypt secret extras; Java preserves that storage behavior and redaction.

## Runtime catalog

`BuiltinCatalogData` snapshots actual 27 Go provider definitions, complete normalized
catalog, seven protocol settings defaults and reflected schemas. Pure Java resolution
implements exact/alias/glob typed matching, generic unknown models, ModelSpec overrides,
URL protocol inference, first-party OpenAI Responses selection, reasoning/capabilities,
legacy thinking control and strict compatibility merging. Embedding/rerank/ASR pure
resolution is supported; model execution is not.

The actual Go runtime loads **JSON**, `MODELS_CONFIG` or `config/models.json`.
Registry YAML is provider build input. Java applies deployment JSON then the existing
**global singleton** `model_catalog_configs` DB overlay. There is no tenant-specific
catalog overlay in this Go implementation; Java does not invent one. Generations
compile transactionally and keep the last valid generation. Deployment environment
expansion, routing patches, model upserts/overrides, strict console secret/routing/
duplicate/collision checks, and safe deployment SVG files are implemented.

Java checks console versions on model requests, at most every 5 seconds once a valid
version has applied; it does not run Go's background poller. Invalid versions cache
until version changes; DB/schema failures retain the last valid generation/baseline.
Invalid deployment config keeps builtins. Go omits capabilities for a stored row that
cannot resolve; Java does the same. The old whole-list remote-chat 503 restriction
has been **removed**: remote rows are readable and resolve real catalog capabilities.

## URL policy and remaining limitations

`ModelURLPolicy` reads DB `ssrf.whitelist`, falls back to ENV and merges ENV-only
`SSRF_WHITELIST_EXTRA`. It covers exact/wildcard apex+suffix/CIDR whitelist, whitelist-
only refusal before DNS, Go's DNS-resolved CIDR whitelist behavior, direct/obfuscated
IP refusal, every Go ipclass restricted/translated range, all DNS answer checks,
restricted hostname/suffix/port and operator guidance. Unlisted URL validation makes
real DNS lookups; deterministic tests inject the resolver.

This validates **input only**, not outbound transports, redirects or dial-time pinning.
Java URI parsing rejects some Go-accepted spellings; malformed URI/type-binding errors
are not byte-identical to Go. Typed parameter parsing covers declared lowercase names;
Go's case-insensitive field binding and malformed persisted-JSON edge cases are not
fully migrated. Therefore this write slice remains partial. PostgreSQL JSON writes
explicitly cast JSONB, but full PostgreSQL/MySQL execution has not been exercised.
Deletion scans projected current-tenant references rather than Go's SQL JSON count/
filter query; schema/query failures fail closed rather than assuming no references.

Inference, connectivity/probe/debug, Ollama jobs/downloads/status, model copy, WeKnora
Cloud activation/status, catalog management writes and other subresources remain Go.
Generic native subpaths return 503. No fake probe/download success is returned.

## Validation

This command passes **18 JUnit tests** (SQLite lifecycle plus pure contracts/policy):

```sh
cd backend-java
mvn -q -Dtest=ModelProjectionTest,ModelIntegrationTest,RuntimeCatalogTest,CatalogOverlaySecurityTest,ModelMutationTest,ModelURLPolicyTest test
```

Fixtures/generators are in `src/test/java/com/tencent/weknora/model/`:

- `fixture.go`: **131** cases using production `ModelParameters.Scan`,
  `dto.NewModelResponse`, Go JSON marshal. Current Go has no `Model.MarshalJSON`;
  redaction lives in that DTO.
- `runtime_fixture.go fixtures`: **740** actual `runtime.Resolve` results including
  all settings/spec/capabilities/errors; `data` regenerates Java metadata/schema.
- `catalog_http_fixture.go`: **82** actual ListModelProviders/ResolveModelCatalog
  handler outputs across viewer/admin roles.
- `mutation_fixture.go`: **14** actual Create/Update handler cases with a deterministic
  service stub, real typed DTO and `ModelParameters.Value` JSON. Service authorization/
  DB behavior is independently covered by production-migration SQLite/MockMvc tests.

Coverage includes role/tenant/builtin visibility, AES at rest, replacement/secret
preservation, credential no-op/clear/inactive states, system builtin overrides,
all reference bindings/limits/totals, soft deletion and reference isolation,
last-good overlay reload, SVG/console restrictions and URL/DNS policy. Generators run
from the repository root; HTTP and mutation generators take output JSON filenames
because Go logging shares stdout. All fixture credentials are synthetic test data.

## Production SQLite BLOB parameters

`ModelClientFactory` now decodes JDBC `byte[]` parameters directly as JSON. Go's actual model repository writes `ModelParameters.Value()` as a BLOB in SQLite; using Java `byte[].toString()` previously rejected these persisted models before any embedding request. The existing string and null/error paths and strict encrypted-secret rejection are preserved.

`model/client/model_blob_fixture.go` creates models using the production Go repository and calls the real tenant model service/client against loopback HTTP. Six BLOB scenes compare vectors and exact request payload/header projections, including an owner-tenant context. `ModelParametersBlobGoParityTest` runs the Java factory against real SQLite BLOB rows, plus malformed JSON and encrypted-secret rejection without HTTP. The focused three-class Maven run passed 11 methods with zero failures/errors/skips (`/tmp/weknora-java-model-blob-checkpoint.log`). Agent standalone regression also replayed existing 12 SQLite/model cases and 236 Ollama SDK wire scenes; those iterations are not new JUnit method counts.

Reproduce with `python3 backend-java/scripts/generate-model-blob.py --output /tmp/go-model-blob.json --compare-to backend-java/src/test/java/com/tencent/weknora/model/client/go-model-blob.json`. Root replay passed (`/tmp/weknora-model-blob-root-replay.log`). PostgreSQL has not been exercised by this focused fix.
