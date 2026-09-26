# ZPA CLI

[![Build](https://github.com/felipebz/zpa-cli/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/felipebz/zpa-cli/actions/workflows/build.yml)

This is a command-line interface to the [Z PL/SQL Analyzer](https://github.com/felipebz/zpa). It is a code analyzer for Oracle PL/SQL and Oracle Forms projects.

## Downloading

Official releases are available for download on the ["Releases" page](https://github.com/felipebz/zpa-cli/releases).

## Requirements

* Java 21 or newer

## Usage

Currently, the zpa-cli supports these options:

* `--sources`: **[required]** Path to the folder containing project files. Defines the complete project source/context set.
* `--files`: One or more files to analyze, separated by space (e.g. `--files a.pks b.pkb` or repeated `--files a.pks --files b.pkb`), or `-` to read from standard input. Relative paths are resolved relative to `--sources`. Absolute paths are accepted only when they resolve inside `--sources`. For normal analysis, every target must be a member of the discovered sources under `--sources`. When omitted, all supported files discovered under `--sources` are analyzed.
* `--syntax-only`: Validates PL/SQL syntax only. Bypasses normal coding rules, custom plugin loading, Forms metadata, and project semantic preparation. When used with explicit `--files`, only the specified targets are resolved and parsed without recursively discovering the rest of the project.
* `--stdin-filename`: Filename for source identity when reading from stdin (`--files -`), relative to `--sources` or absolute. Must reside inside `--sources` and have a supported extension. In normal analysis it is required and names the project file the input stands for (see [Analyzing stdin with the project context](#analyzing-stdin-with-the-project-context-fork)); with `--syntax-only` it defaults to `stdin.sql`.
* `--fail-on`: Failure threshold for the exit code (`none`, `any`, `syntax`, `blocker`, `critical`, `major`, `minor`, `info`). In normal analysis mode, the default is `none`. When `--syntax-only` is requested without `--fail-on`, the default threshold is `syntax`. An explicit `--fail-on none` overrides this default in syntax-only mode.
* `--forms-metadata`: Path to the Oracle Forms [metadata file](https://github.com/felipebz/zpa/wiki/Oracle-Forms-support).
* `--extensions`: File extensions to analyze, separated by comma. The default value is `sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps`.
* `--output-format`: Format of the output. Supported formats: `console`, `json`, `sq-generic-issue-import`. The default value is `console`.
* `--output-file`: Path to the output file. When specified with `json`, writes the report to the file without writing to stdout.
* `--config`: Path to the configuration file. The file format must comply with the [provided JSON schema](schema.json).
  You can refer to the example [zpa-config-example.json](zpa-config-example.json) for guidance. If the configuration
  file is not provided, only the rules marked as "activated by default" will be executed.

### Project context vs analysis targets

* `--sources` defines the complete project context. All discovered project files are used for project declaration index preparation and cross-file semantic resolution.
* `--files` optionally restricts the files that are actually scanned for diagnostics and reported. Filesystem targets must be a subset of discovered project sources (`filesystemTargets ⊆ discoveredProjectSources`).
* Standard input (`--files -`) is analyzed as an overlay of one project file, see below. With `--syntax-only` it is only parsed.

### Analyzing stdin with the project context (fork)

`--files - --stdin-filename <path>` without `--syntax-only` analyzes the content of standard input (e.g. an unsaved
editor buffer) as if the file `<path>` on disk had that content:

* The project declaration index is built from all files under `--sources`, with the stdin content in place of the disk
  content of `<path>`. If `<path>` does not exist yet (a new, unsaved file), it is added to the project.
* Only `<path>` is analyzed, from the stdin content; other project files are used as context but not reported.
* Issues are reported under `<path>` relative to `--sources`, exactly as for `--files <path>` (all output formats,
  NOSONAR filter, quick fixes, `--fail-on`). If the file exists on disk, the spelling found on disk is used.
* stdin is read as UTF-8, like the source files; a byte order mark is kept exactly as when reading a file.
* `--stdin-filename` is required, must be inside `--sources` and must have one of the `--extensions`. `-` cannot be
  combined with other `--files` entries (analyze other files in a separate run). Violations exit with code 2 before
  stdin is read.
* Nothing is written to `--sources`.

In [daemon mode](#daemon-mode-fork) the content is passed in the request's `"stdin"` field instead.

### Output formats:
* `console`: writes human-readable analysis results to standard output, grouped by file and sorted deterministically, including position, severity, rule key, and message.
* `json`: outputs a deterministic, schema-versioned machine-readable JSON document (`schemaVersion: 1`). Safe to pipe: stdout contains only JSON (when `--output-file` is not used), while logs and progress messages go to stderr.
  - `validation`: `{ "passed": boolean, "threshold": string }`. Authoritative validation outcome matching exit code 0 (`passed: true`) vs 1 (`passed: false`) against the configured `--fail-on` threshold. Diagnostics may exist when `validation.passed` is true (e.g. with `--fail-on none`).
  - `diagnostics`: list of findings sorted stably by normalized file, range, rule, and message:
    - `kind`: `"SYNTAX"` (parse/syntax diagnostics from `ParsingErrorCheck`) or `"RULE"` (standard coding rules).
    - `file`: normalized file path relative to `--sources`.
    - `range`: `{ "startLine": int?, "startColumn": int?, "endLine": int?, "endColumn": int? }` or `null` for file-level issues. Coordinates: `startLine` is 1-based, `startColumn` is 0-based, `endLine` is 1-based, `endColumn` is 0-based exclusive. Unavailable coordinates are represented as `null`.
    - `rule`: rule identifier (e.g. `zpa:ParsingError`, `zpa:PackageBodyParameterNocopy`).
    - `severity`: `"BLOCKER"`, `"CRITICAL"`, `"MAJOR"`, `"MINOR"`, `"INFO"`.
    - `message`: localized diagnostic message.
    - `quickFixes` (optional, fork): automatic corrections for the diagnostic, see [Quick fixes](#quick-fixes-fork). The field is omitted when the rule offers none.
* `sq-generic-issue-import`: generates a JSON file using SonarQube's ["Generic Issue Data" format](https://docs.sonarqube.org/latest/analysis/generic-issue/) that can be used in SonarCloud or in a SonarQube server.
  Issues with a quick fix additionally carry a `quickFixes` field (fork, see [Quick fixes](#quick-fixes-fork)); SonarQube ignores it.

### Quick fixes (fork)

Some rules (e.g. `InequalityUsage`, `ComparisonWithNull`) offer automatic corrections. The `json` and
`sq-generic-issue-import` formats export them per issue as an optional `quickFixes` array, which is omitted when the
issue has none, so reports without quick fixes are unchanged. The `console` format appends `[quick fix available]` to
such issues.

```json
"quickFixes": [
  {
    "message": "Change to \"IS NULL\"",
    "edits": [
      { "startLine": 7, "startColumn": 5, "endLine": 7, "endColumn": 12, "text": "" },
      { "startLine": 7, "startColumn": 13, "endLine": 7, "endColumn": 13, "text": " IS NULL" }
    ]
  }
]
```

* An issue may offer several alternative quick fixes; each one has a `message` and a non-empty list of `edits`.
* Each edit replaces a range of the file of the issue with `text`. The coordinates are the same as those of the issue
  location in the respective format (`primaryLocation.textRange` / `range`): lines are 1-based, columns are 0-based
  UTF-16 code units, and `endColumn` is exclusive. Unlike the issue location, all four coordinates are always present.
  An empty `text` deletes the range; an empty range inserts `text`.
* All edits of a quick fix refer to the original file and must be applied together (e.g. from the last to the first).
  They do not overlap. Quick fixes of different issues may overlap (for example `x <> NULL` gets one fix from
  `InequalityUsage` and one from `ComparisonWithNull`), so after applying one, the file should be analyzed again.
* The `json` `schemaVersion` stays `1`: the field is an additive, optional extension.

### Exit codes:
* `0`: analysis completed without an enabled validation failure (threshold not exceeded)
* `1`: requested validation condition failed (findings met or exceeded the `--fail-on` threshold)
* `2`: invalid invocation, command-line arguments, or target file error (e.g. nonexistent target file, target outside `--sources`, stdin used without `--stdin-filename` in normal analysis)
* `3`: internal execution failure
### Examples

Full project analysis:
```sh
zpa-cli --sources .
```

Focused human validation:
```sh
zpa-cli --sources . --files src/packages/customer.pkb
```

Focused machine validation:
```sh
zpa-cli --sources . --files src/packages/customer.pkb --output-format json
```

Syntax validation:
```sh
zpa-cli --sources . --files src/packages/customer.pkb --syntax-only
```

Stdin validation:
```sh
cat generated.sql | zpa-cli --sources . --files - --stdin-filename src/packages/customer.pkb --syntax-only
```

Analysis of an unsaved buffer with the project context (fork):
```sh
cat buffer.pkb | zpa-cli --sources . --files - --stdin-filename src/packages/customer.pkb
```

Running an analysis:

`./zpa-cli/bin/zpa-cli --sources . --output-file zpa-issues.json --output-format sq-generic-issue-import`

Then you can send the results to a SonarCloud or SonarQube server setting the `sonar.externalIssuesReportPaths` property:

```
sonar-scanner 
  -Dsonar.organization=$SONARCLOUD_ORGANIZATION \
  -Dsonar.projectKey=myproject \
  -Dsonar.sources=. \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.externalIssuesReportPaths=zpa-issues.json
```

Check the [demo project on SonarCloud](https://sonarcloud.io/project/issues?id=utPLSQL-zpa-demo&resolved=false)!

## Daemon mode (fork)

`zpa-cli --daemon` keeps one JVM running for many analyses (e.g. for an editor integration), avoiding the JVM
startup on every run. `--daemon` must be the only argument. The protocol is line-based JSON (UTF-8, one object per
`\n`-terminated line) on stdin/stdout:

* On start the daemon writes `{"type":"ready","protocol":1,"version":"<zpa-cli version>"}`.
* Each request line is `{"id": <string|number>, "args": ["--sources", "...", ...]}`, where `args` are exactly the
  arguments of a normal invocation. Optional `"stdin": "<text>"` is the content read by `--files -`; without it,
  standard input is empty for the analysis. With `--files - --stdin-filename <path>` (and no `--syntax-only`) this
  analyzes an unsaved buffer with the project context, see
  [Analyzing stdin with the project context](#analyzing-stdin-with-the-project-context-fork).
* Requests run sequentially. Each gets one response line
  `{"id": ..., "exitCode": <int>, "stdout": "...", "stderr": "..."}` with the usual [exit codes](#exit-codes) and
  everything the run wrote to stdout/stderr (including log output). Nothing else is written to stdout.
* A malformed request line gets a response with `exitCode` 2 and the error in `stderr` (`id` is `null` if it could not
  be read); the daemon keeps running.
* `{"type":"shutdown"}` or the end of stdin stops the daemon with exit code 0.

Relative paths (`--sources`, `--output-file`, `--config`, ...) resolve against the directory the daemon was started
in, not per request, so clients should pass absolute paths. Plugins are loaded per request, so plugins added to the
`plugins` folder are picked up without restarting the daemon.

## Capabilities file (fork)

The zpa-cli jar (`lib/zpa-cli-<version>.jar`) contains `META-INF/zpa-cli-capabilities.properties`, so clients can
detect the fork features of an installation offline, without starting it:

```properties
daemon=1
stdin-project=1
quick-fixes=1
```

* `daemon`: [daemon mode](#daemon-mode-fork).
* `stdin-project`: [analyzing stdin with the project context](#analyzing-stdin-with-the-project-context-fork).
* `quick-fixes`: [quick fixes](#quick-fixes-fork) in the `json` and `sq-generic-issue-import` formats.

This is a stable contract: there is one key per fork feature present in the build, and its value is the revision of the
feature (an integer, currently `1`, raised only for incompatible changes). Keys are not renamed or removed while the
feature exists. A missing key, or a missing file (as in the official releases), means the feature is absent.

## Contributing

Please read our [contributing guidelines](CONTRIBUTING.md) to see how you can contribute to this project.
