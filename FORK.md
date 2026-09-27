# Modified version of zpa-cli

This repository (`paddi35/zpa-cli`) is a modified fork of
[felipebz/zpa-cli](https://github.com/felipebz/zpa-cli) by Felipe Zorzo. It is distributed under
the same license, the [GNU Lesser General Public License v3.0](LICENSE).

Modifications by Patrick Völkel, since 2026-09-25 (the full list is the git history on top of
the upstream `main` branch):

- Issues on lines with a NOSONAR comment are suppressed.
- Daemon mode (`--daemon`): repeated analyses in one JVM over a line-based JSON protocol on stdin/stdout. The
  temporary directory with the relocated plugin JARs is now deleted at the end of every run, and the jar manifest
  carries `Implementation-Version`.
- The daemon loads the plugins once and reuses them across requests until a plugin JAR is added, replaced or removed.
- Quick fixes offered by the rules are exported as an optional `quickFixes` field per issue in the `json` and
  `sq-generic-issue-import` formats; the `console` format marks such issues with `[quick fix available]`.
- Standard input (`--files - --stdin-filename <path>`) can be analyzed with the project context, as an overlay of the
  project file `<path>` (previously only with `--syntax-only`).
- A UTF-8 byte order mark at the start of a file or of standard input is dropped when the source is read, like
  SonarQube does (previously a parsing error at 1:0); columns on the first line are counted without it.
- `--context-overlay <path> <file>` (repeatable) uses the content of other unsaved files as project context only.
- `--fix` applies the quick fixes to the analyzed files (in up to `--fix-max-rounds` rounds, keeping encoding, line
  separators and byte order mark) and reports the remaining issues; `--fix-dry-run` prints a unified diff instead.
- The jar contains `META-INF/zpa-cli-capabilities.properties`, listing the fork features for clients.
- CI: Docker Hub publishing is skipped outside `felipebz/zpa-cli`, and the fork builds against the ZPA fork
  (`paddi35/zpa`, published to mavenLocal as `4.1.1-local-SNAPSHOT`) instead of the upstream snapshot. The stable
  release pipeline after the `release-please` draft PR (Maven Central deployment, signing, the GitHub release,
  Docker Hub promotion) only runs in `felipebz/zpa-cli` - the fork has none of the required secrets and never
  publishes a release under the upstream's coordinates. `release-please` itself still runs in the fork.
- `--output-format sarif` produces a SARIF 2.1.0 log (one run, with a `rules` array and one `result` per issue),
  consumable by tools such as GitHub code scanning's `upload-sarif` action.

Local builds use the ZPA version given by `-PzpaVersion` (for example a `-local-SNAPSHOT` build of
the `paddi35/zpa` fork) and are not official zpa-cli releases.
