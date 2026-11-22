# Zalava Home Assistant module

An independently released Zalava provider module for Home Assistant.

## Build and verify

The module resolves the released public Zalava API and contract kit from
`https://zalava.github.io/zalava-maven/` by default:

```bash
./gradlew check
```

The test suite loads the built JAR through the released contract kit. It is
module-repository acceptance; installation, authorization and persistence are
verified by the Zalava host.

## Home Assistant actions

`home_assistant_actions` reads the live services catalog, including parameter
descriptions when Home Assistant provides them. `home_assistant_execute_action`
is explicitly side-effecting and accepts `domain`, `action`, optional `entityId`,
`serviceData` and `returnResponse`. For example:

```json
{"domain":"light","action":"turn_on","entityId":"light.room","serviceData":{"brightness":180}}
```

Execution rechecks the live catalog and target state. Targeted actions require
one explicit entity ID; wildcard, area/device targets and target overrides in
service data are rejected. Parameters must be described by discovery; required
fields and known number/boolean/text selector types are validated. Home Assistant
remains responsible for full integration-specific validation and target support.
Legacy catalogs without field descriptions support only empty service data.
Discovery does not prove that a particular device supports an action.

Requests use only the configured trusted endpoint, bearer-secret reference,
no redirects, a 64 KiB request cap and the configured response/deadline bounds.
Set `returnResponse` only for actions supporting or requiring response data.
Results report API acceptance, without exposing the potentially unrelated HA
changed-state list or claiming physical verification. Raw HA error bodies are
suppressed. Failed POSTs can have an uncertain outcome: observe state before
retrying. The module never automatically retries an action.

Zalava owns actor/group/action/target permissions, approvals and risk controls
for indirect or sensitive actions. Deploy action tools only behind those host
controls; module contract tests do not establish household acceptance.

## Release

The reviewed alpha version in `module-metadata.yaml` is the source of truth.
A release tag must match that version. The release workflow publishes the JAR
and its SHA-256 sidecar as immutable GitHub Release assets.

## Catalog discovery and release indexes

The Zalava catalog locates this source repository and `releases/index.yaml`.
Versions, artifacts, digests, source commits, permissions and compatibility belong
to this module's release index. Do not update the catalog for each release.

The release-index workflow verifies every indexed artifact against its anonymous
GitHub Release download and immutable source tag. On a newly published release it
generates a reviewed index-update PR; merge that PR to expose the release through
catalog discovery. Enable Actions to create pull requests in repository settings.
The proposing workflow verifies the exact generated index. Maintainers must run
the normal module gate before approving it. The workflow never merges.

For manual generation, install `scripts/requirements.txt`, then run
`python scripts/release_index.py --artifact <published-jar> --tag <immutable-tag>
--revision <full-tag-commit> --repository https://github.com/Zalava/<repository>`.
Existing version entries cannot be rewritten. JSON syntax in `index.yaml` is valid
YAML and is accepted by the host's strict release-index loader.

The index workflow is called directly by the release workflow after publishing,
so GitHub token event suppression does not prevent index maintenance. Every
indexed source tag must belong to public `main`. Index updates remain reviewed
PRs; repository Actions permissions must allow their creation, and automation
never merges them. GitHub may require approval before running bot-created PR CI.
