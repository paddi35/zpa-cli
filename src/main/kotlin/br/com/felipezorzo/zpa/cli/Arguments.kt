package br.com.felipezorzo.zpa.cli

import com.beust.jcommander.Parameter

class Arguments {
    @Parameter(names = ["--sources"], description = "Folder with files", required = true)
    var sources: String = ""

    @Parameter(names = ["--forms-metadata"], description = "Oracle Forms metadata file")
    var formsMetadata: String = ""

    @Parameter(names = ["--extensions"], description = "Extensions to analyze")
    var extensions: String = "sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps"

    @Parameter(names = ["--output-format"], description = "Format of the output file")
    var outputFormat: String = "console"

    @Parameter(names = ["--output-file"], description = "Output filename")
    var outputFile: String = ""

    @Parameter(names = ["--config"], description = "Config file")
    var configFile: String = ""

    @Parameter(names = ["--files"], description = "Files to analyze", variableArity = true)
    var files: List<String> = ArrayList()

    @Parameter(names = ["--syntax-only"], description = "Perform syntax validation only")
    var syntaxOnly: Boolean = false

    @Parameter(names = ["--stdin-filename"], description = "Virtual filename when reading from stdin")
    var stdinFilename: String = ""

    @Parameter(
        names = [CONTEXT_OVERLAY_OPTION],
        arity = 2,
        description = "<path> <file>: use the content of <file> for the project file <path> as project context only, e.g. an unsaved editor buffer (repeatable)"
    )
    var contextOverlays: List<String> = ArrayList()

    @Parameter(names = ["--fail-on"], description = "Failure threshold for validation exit code (none, any, syntax, blocker, critical, major, minor, info)")
    var failOn: String? = null

    @Parameter(names = ["--fix"], description = "Apply the quick fixes of the issues to the analyzed files; the report shows the remaining issues")
    var fix: Boolean = false

    @Parameter(names = ["--fix-dry-run"], description = "Like --fix, but print the changes as a unified diff to standard output instead of writing the files")
    var fixDryRun: Boolean = false

    @Parameter(names = ["--fix-max-rounds"], description = "Maximum number of fix rounds (fix, analyze the changed files again, ...) with --fix (default: 3)")
    var fixMaxRounds: Int? = null

    @Parameter(names = ["--help", "-h"], help = true, description = "Display help information")
    var help: Boolean = false
}

const val CONTEXT_OVERLAY_OPTION = "--context-overlay"
