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
* `--context-overlay <path> <file>` (fork, repeatable): Uses the content of `<file>` for the project file `<path>` in the project context, without analyzing it (e.g. other unsaved editor buffers). See [Context overlays](#context-overlays-fork).
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
* `--context-overlay` changes the content of project files in the project context only, see below.

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

### Context overlays (fork)

`--context-overlay <path> <file>` replaces the content of the project file `<path>` with the content of `<file>` in
the project context, e.g. for editor buffers with unsaved changes besides the one analyzed from stdin. The option takes
two values and can be repeated, once per file:

* `<path>` is the project file, relative to `--sources` or absolute, and must be inside `--sources` with one of the
  `--extensions`. If it does not exist on disk (a new, unsaved file), it is added to the project context.
* `<file>` holds the content, typically a temporary file written by the client (relative paths resolve against the
  working directory). It is read as UTF-8 like the source files; a leading byte order mark is dropped.
* The overlays are project context only: they are used for the project declaration index but never analyzed or
  reported. Only the targets are analyzed: `--files -` (stdin, see above) or `--files <path>...` on disk.
* Two separate values instead of a `<path>=<file>` syntax, so no separator can clash with characters in paths
  (Windows drive letters, `=` in file names).
* Exit code 2 (before stdin is read) when `--context-overlay` is combined with `--syntax-only` or used without
  `--files`, when `<path>` is outside `--sources` or has an unsupported extension, when `<path>` is given twice or is
  also a target (the `--stdin-filename` file or a `--files` entry), when `<file>` does not exist or is not a file, or
  when a value is missing or empty.
* Nothing is written to `--sources`.

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

### Fix mode (fork)

`--fix` applies the quick fixes to the analyzed files, e.g. in a CI job or a pre-commit hook:

```sh
zpa-cli --sources . --fix                                   # fix all project files
zpa-cli --sources . --files src/packages/customer.pkb --fix # fix one file
zpa-cli --sources . --fix-dry-run > fixes.diff              # show the changes, write nothing
```

* The analysis runs as usual (`--sources`, `--files`, `--config`, `--extensions`, `--context-overlay`, NOSONAR, ...).
  Then, per file, the first (preferred) quick fix of every issue is chosen in document order; a quick fix that
  overlaps one chosen before is skipped. Quick fixes starting at the same position are chosen by the severity of
  their issue (most severe first): for `x <> NULL`, `ComparisonWithNull`'s `IS NOT NULL` wins over
  `InequalityUsage`'s `!=`. This is the same choice as "fix all" in zpa-for-vscode.
* The fixed files are analyzed again, which finds skipped quick fixes and issues revealed by a fix. This repeats up
  to `--fix-max-rounds` rounds (default `3`), analyzing only the files changed in the round before, and stops early
  when nothing changes. A round whose fixes make a file unparsable is not applied to that file.
* The report (`--output-format`, `--output-file`, `--fail-on`) shows the issues that **remain** after the fixes, so a
  CI job can fail on what is left. A summary goes to stderr:
  ```
  Applied 4 quick fixes in 2 files (2 rounds)
    src/a.sql: 3 quick fixes
    src/b.sql: 1 quick fix
  ```
* Files are only written at the end and only if a quick fix changed them, each atomically (a temporary file in the
  same directory replaces the file). The encoding stays UTF-8, and the line separators and a leading byte order mark
  are kept. Files that are not valid UTF-8 are analyzed but not fixed; a file that changed on disk during the
  analysis is not written. If a file cannot be written, the exit code is `3`.
* `--fix-dry-run` does the same without writing: it prints a unified diff of every file that would change to stdout
  (`--- a/<path>` / `+++ b/<path>`, relative to `--sources`, usable with `git apply`) and reports the issues that
  would remain. With `--output-format json` it needs `--output-file`, since stdout carries the diff.
* Rejected with exit code `2`: `--fix` / `--fix-dry-run` with `--syntax-only` (no rule offers quick fixes) or with
  standard input (`--files -`; fix the file on disk instead), and `--fix-max-rounds` below 1 or without `--fix`.
* In [daemon mode](#daemon-mode-fork) `--fix` works the same way; the files are written before the response is sent.

### Exit codes:
* `0`: analysis completed without an enabled validation failure (threshold not exceeded)
* `1`: requested validation condition failed (findings met or exceeded the `--fail-on` threshold)
* `2`: invalid invocation, command-line arguments, or target file error (e.g. nonexistent target file, target outside `--sources`, stdin used without `--stdin-filename` in normal analysis)
* `3`: internal execution failure (with `--fix`, also: a fixed file could not be written)
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

The same, with the unsaved specification of the package as context (fork):
```sh
cat buffer.pkb | zpa-cli --sources . --files - --stdin-filename src/packages/customer.pkb \
  --context-overlay src/packages/customer.pks /tmp/customer-spec-buffer.pks
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
  [Analyzing stdin with the project context](#analyzing-stdin-with-the-project-context-fork). Other unsaved buffers can be
  passed as [context overlays](#context-overlays-fork) (`--context-overlay <path> <file>` in `args`, with the content
  in temporary files written by the client).
* Requests run sequentially. Each gets one response line
  `{"id": ..., "exitCode": <int>, "stdout": "...", "stderr": "..."}` with the usual [exit codes](#exit-codes) and
  everything the run wrote to stdout/stderr (including log output). Nothing else is written to stdout.
* A malformed request line gets a response with `exitCode` 2 and the error in `stderr` (`id` is `null` if it could not
  be read); the daemon keeps running.
* `{"type":"shutdown"}` or the end of stdin stops the daemon with exit code 0.

Relative paths (`--sources`, `--output-file`, `--config`, ...) resolve against the directory the daemon was started
in, not per request, so clients should pass absolute paths.

The plugins in the `plugins` folder are loaded by the first analysis request and reused by the following ones; the
rules, their configuration and the check instances are still set up per request. Before every analysis the daemon
compares the plugin JARs (file name, size and modification time) with the loaded ones: when a JAR was added, replaced
or removed, the plugins are unloaded and loaded again, so changes are picked up without restarting the daemon. The
loaded plugins are released when the daemon stops.

## Capabilities file (fork)

The zpa-cli jar (`lib/zpa-cli-<version>.jar`) contains `META-INF/zpa-cli-capabilities.properties`, so clients can
detect the fork features of an installation offline, without starting it:

```properties
daemon=1
stdin-project=1
quick-fixes=1
context-overlays=1
fix=1
```

* `daemon`: [daemon mode](#daemon-mode-fork).
* `stdin-project`: [analyzing stdin with the project context](#analyzing-stdin-with-the-project-context-fork).
* `quick-fixes`: [quick fixes](#quick-fixes-fork) in the `json` and `sq-generic-issue-import` formats.
* `context-overlays`: [context overlays](#context-overlays-fork) (`--context-overlay <path> <file>`).
* `fix`: [fix mode](#fix-mode-fork) (`--fix`, `--fix-dry-run`, `--fix-max-rounds`).

This is a stable contract: there is one key per fork feature present in the build, and its value is the revision of the
feature (an integer, currently `1`, raised only for incompatible changes). Keys are not renamed or removed while the
feature exists. A missing key, or a missing file (as in the official releases), means the feature is absent.

## Contributing

Please read our [contributing guidelines](CONTRIBUTING.md) to see how you can contribute to this project.
