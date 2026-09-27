package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.config.BaseRuleCategory
import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.config.RuleConfiguration
import br.com.felipezorzo.zpa.cli.config.RuleLevel
import br.com.felipezorzo.zpa.cli.exporters.ConsoleExporter
import br.com.felipezorzo.zpa.cli.exporters.GenericIssueFormatExporter
import br.com.felipezorzo.zpa.cli.exporters.IssueExporter
import br.com.felipezorzo.zpa.cli.exporters.JsonExporter
import br.com.felipezorzo.zpa.cli.fix.FixRunner
import br.com.felipezorzo.zpa.cli.plugin.PerRunPlugins
import br.com.felipezorzo.zpa.cli.plugin.PluginManager
import br.com.felipezorzo.zpa.cli.plugin.PluginProvider
import br.com.felipezorzo.zpa.cli.rules.CliActiveRules
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.zpa.CustomAnnotationBasedRulesDefinition
import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.ZpaRulesDefinition
import com.felipebz.zpa.api.checks.PlSqlVisitor
import com.felipebz.zpa.checks.ParsingErrorCheck
import com.felipebz.zpa.metadata.FormsMetadata
import com.felipebz.zpa.project.FileId
import com.felipebz.zpa.project.ProjectAnalysisContext
import com.felipebz.zpa.project.ProjectIndexPreparation
import com.felipebz.zpa.project.ProjectSource
import com.felipebz.zpa.project.ProjectSourceReader
import com.felipebz.zpa.rules.Repository
import com.felipebz.zpa.rules.RuleMetadataLoader
import com.felipebz.zpa.rules.ZpaChecks
import com.felipebz.zpa.squid.AstScanner
import com.felipebz.zpa.squid.ProgressReport
import com.felipebz.zpa.squid.ZpaIssue
import com.felipebz.zpa.utils.log.Loggers
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.LogManager
import java.util.stream.Collectors
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.system.measureTimeMillis

const val CONSOLE = "console"
const val GENERIC_ISSUE_FORMAT = "sq-generic-issue-import"
const val JSON = "json"
const val DEFAULT_FIX_MAX_ROUNDS = 3

/** Fixed files could not be written (exit code 3, after the report of the remaining issues). */
class FixWriteException(message: String) : Exception(message)

class Main(private val args: Arguments, private val plugins: PluginProvider = PerRunPlugins()) {

    val mapper = jacksonObjectMapper()

    fun run(): Int {
        javaClass.getResourceAsStream("/logging.properties").use {
            LogManager.getLogManager().readConfiguration(it)
        }

        val format = args.outputFormat.lowercase(Locale.ROOT)
        if (format != CONSOLE && format != GENERIC_ISSUE_FORMAT && format != JSON) {
            throw CliValidationException("Invalid output format: '${args.outputFormat}'. Supported formats: $CONSOLE, $GENERIC_ISSUE_FORMAT, $JSON")
        }

        val failOnThreshold = if (args.failOn != null) {
            FailOnThreshold.fromString(args.failOn!!)
        } else if (args.syntaxOnly) {
            FailOnThreshold.SYNTAX
        } else {
            FailOnThreshold.NONE
        }
        val config = loadConfigFile()

        val baseDir = File(args.sources).absoluteFile
        if (!baseDir.exists() || !baseDir.isDirectory) {
            throw CliValidationException("Sources folder does not exist or is not a directory: ${args.sources}")
        }
        val baseDirPath = baseDir.toPath().normalize()

        val extensions = args.extensions.split(',').map { it.trim().lowercase(Locale.ROOT) }
        val sourceSelection = SourceSelection(baseDirPath, extensions, StandardCharsets.UTF_8)

        val readsStdin = args.files.contains(SourceSelection.STDIN_TARGET)
        validateFixOptions(format, readsStdin)
        if (args.stdinFilename.isNotEmpty() && !readsStdin) {
            throw CliValidationException("--stdin-filename can only be used when reading from standard input ('--files -')")
        }
        // Project-aware stdin analysis: the input is an overlay for one project file. Validated before plugins load
        // and before anything is read from stdin.
        val stdinOverlayPath = if (readsStdin && !args.syntaxOnly) {
            sourceSelection.resolveStdinOverlayPath(args.files, args.stdinFilename)
        } else {
            null
        }
        // Unsaved content of other project files, used as project context only. Validated before plugins load and
        // before anything is read from stdin.
        val contextOverlays = if (args.contextOverlays.isEmpty()) {
            emptyList()
        } else {
            if (args.syntaxOnly) {
                throw CliValidationException("--context-overlay cannot be used with --syntax-only, which has no project context")
            }
            sourceSelection.resolveContextOverlays(args.contextOverlays, args.files, stdinOverlayPath)
        }

        val validationFailed = if (args.syntaxOnly) {
            analyze(null, format, failOnThreshold, config, sourceSelection, baseDirPath, stdinOverlayPath, contextOverlays)
        } else {
            plugins.withPlugins { pluginManager ->
                for (plugin in pluginManager.startedPlugins) {
                    LOG.info("Plugin '${plugin.descriptor.pluginId}@${plugin.descriptor.version}' loaded")
                }
                analyze(pluginManager, format, failOnThreshold, config, sourceSelection, baseDirPath, stdinOverlayPath, contextOverlays)
            }
        }

        return if (validationFailed) 1 else 0
    }

    /** Runs the analysis with the plugins of [pluginManager] (null in syntax-only mode); returns whether validation failed. */
    private fun analyze(
        pluginManager: PluginManager?,
        format: String,
        failOnThreshold: FailOnThreshold,
        config: ConfigFile,
        sourceSelection: SourceSelection,
        baseDirPath: Path,
        stdinOverlayPath: String?,
        contextOverlays: List<SourceSelection.ContextOverlay>,
    ): Boolean {
        var validationFailed = false
        val ellapsedTime = measureTimeMillis {
            val ruleMetadataLoader = RuleMetadataLoader()

            val checkList = mutableListOf<PlSqlVisitor>()

            if (args.syntaxOnly) {
                val repository = Repository("zpa")
                CustomAnnotationBasedRulesDefinition.load(
                    repository, "plsqlopen",
                    listOf(ParsingErrorCheck::class.java), ruleMetadataLoader
                )
                val activeRules = CliActiveRules(ConfigFile(base = BaseRuleCategory.DEFAULT))
                activeRules.addRepository(repository)
                val checks = ZpaChecks(activeRules, repository.key, ruleMetadataLoader)
                    .addAnnotatedChecks(listOf(ParsingErrorCheck::class.java))
                checkList.addAll(checks.all())
            } else {
                val activeRules = getActiveRules(config)

                val rulesDefinitions = listOf(
                    DefaultRulesDefinition(),
                    *pluginManager!!.getExtensions(ZpaRulesDefinition::class.java).toTypedArray()
                )

                val repositories = rulesDefinitions.map { rulesDefinition ->
                    val repository = Repository(rulesDefinition.repositoryKey())
                    CustomAnnotationBasedRulesDefinition.load(
                        repository, "plsqlopen",
                        rulesDefinition.checkClasses().toList(), ruleMetadataLoader
                    )

                    activeRules.addRepository(repository)
                    repository
                }

                try {
                    activeRules.validateConfiguration()
                } catch (e: IllegalArgumentException) {
                    throw CliValidationException("Invalid configuration: ${e.message}", e)
                }

                for ((rulesDefinition, repository) in rulesDefinitions.zip(repositories)) {
                    val checks = ZpaChecks(activeRules, repository.key, ruleMetadataLoader)
                        .addAnnotatedChecks(rulesDefinition.checkClasses().toList())

                    checkList.addAll(checks.all())
                }
            }

            val metadata = if (args.syntaxOnly) null else FormsMetadata.loadFromFile(args.formsMetadata)

            val rawIssues: List<ZpaIssue>
            val fixResult: FixRunner.Result?
            if (isFixMode) {
                // Targets are files on disk; the overlays (never a target) only change the project context.
                val projectSources = sourceSelection.discoverProjectSources()
                val targets = sourceSelection.resolveProjectTargets(projectSources = projectSources, requestedFiles = args.files)
                val runner = FixRunner(
                    maxRounds = args.fixMaxRounds ?: DEFAULT_FIX_MAX_ROUNDS,
                    dryRun = args.fixDryRun,
                    out = System.out,
                    err = System.err
                ) { roundTargets, fixedSources ->
                    val sources = projectSources.map { fixedSources[it.pathRelativeToBase] ?: it }
                    scan(
                        checkList, metadata, roundTargets,
                        prepareProjectAnalysisContext(sourceSelection.applyContextOverlays(sources, contextOverlays))
                    )
                }
                fixResult = runner.run(targets, baseDirPath)
                rawIssues = fixResult.issues
            } else {
                fixResult = null
                rawIssues = analyzeTargets(checkList, metadata, sourceSelection, stdinOverlayPath, contextOverlays)
            }

            val issues = IssueOrdering.sort(rawIssues)

            validationFailed = failOnThreshold.hasFailure(issues)

            val issueExporter: IssueExporter = when (format) {
                CONSOLE -> ConsoleExporter()
                GENERIC_ISSUE_FORMAT -> GenericIssueFormatExporter(args.outputFile)
                JSON -> JsonExporter(
                    outputFile = args.outputFile,
                    validationFailed = validationFailed,
                    threshold = failOnThreshold.cliName
                )
                else -> throw CliValidationException("Invalid output format: '${args.outputFormat}'")
            }

            issueExporter.export(issues)

            if (fixResult != null && fixResult.writeFailures.isNotEmpty()) {
                throw FixWriteException("Could not write the fixed content of: ${fixResult.writeFailures.joinToString(", ")}")
            }
        }

        LOG.info("Time elapsed: $ellapsedTime ms")
        return validationFailed
    }

    private val isFixMode: Boolean
        get() = args.fix || args.fixDryRun

    /** Validates `--fix`, `--fix-dry-run` and `--fix-max-rounds` before plugins load and before stdin is read. */
    private fun validateFixOptions(format: String, readsStdin: Boolean) {
        val maxRounds = args.fixMaxRounds
        if (maxRounds != null) {
            if (!isFixMode) {
                throw CliValidationException("--fix-max-rounds can only be used with --fix or --fix-dry-run")
            }
            if (maxRounds < 1) {
                throw CliValidationException("--fix-max-rounds must be at least 1")
            }
        }
        if (!isFixMode) {
            return
        }
        val option = if (args.fixDryRun) "--fix-dry-run" else "--fix"
        if (args.syntaxOnly) {
            throw CliValidationException("$option cannot be used with --syntax-only, which runs no rule with quick fixes")
        }
        if (readsStdin) {
            throw CliValidationException("$option cannot be used with standard input ('--files -'); only files on disk can be fixed")
        }
        if (args.fixDryRun && format == JSON && args.outputFile.isEmpty()) {
            throw CliValidationException("--fix-dry-run writes the diff to standard output; use --output-file for the json report")
        }
    }

    /** Resolves the targets and the project context of a normal analysis (no `--fix`) and analyzes them. */
    private fun analyzeTargets(
        checkList: List<PlSqlVisitor>,
        metadata: FormsMetadata?,
        sourceSelection: SourceSelection,
        stdinOverlayPath: String?,
        contextOverlays: List<SourceSelection.ContextOverlay>,
    ): List<ZpaIssue> {
        val targetFiles: List<InputFile>
        val projectAnalysisContext: ProjectAnalysisContext

        if (args.syntaxOnly) {
            projectAnalysisContext = ProjectAnalysisContext.NOT_PREPARED
            targetFiles = sourceSelection.resolveSyntaxOnlyTargets(
                requestedFiles = args.files,
                stdinFilename = args.stdinFilename
            )
        } else if (stdinOverlayPath != null) {
            val overlay = sourceSelection.applyStdinOverlay(
                projectSources = sourceSelection.applyContextOverlays(
                    sourceSelection.discoverProjectSources(), contextOverlays
                ),
                overlayPath = stdinOverlayPath,
                content = sourceSelection.readStdin()
            )
            targetFiles = listOf(overlay.target)
            projectAnalysisContext = prepareProjectAnalysisContext(overlay.projectSources)
        } else {
            val projectSources = sourceSelection.discoverProjectSources()
            // Targets are files on disk; the overlays (never a target) only change the project context.
            targetFiles = sourceSelection.resolveProjectTargets(
                projectSources = projectSources,
                requestedFiles = args.files
            )
            projectAnalysisContext = prepareProjectAnalysisContext(
                sourceSelection.applyContextOverlays(projectSources, contextOverlays)
            )
        }

        return scan(checkList, metadata, targetFiles, projectAnalysisContext)
    }

    /** Analyzes [targetFiles] and returns their issues, without the ones suppressed by NOSONAR. */
    private fun scan(
        checkList: List<PlSqlVisitor>,
        metadata: FormsMetadata?,
        targetFiles: List<InputFile>,
        projectAnalysisContext: ProjectAnalysisContext,
    ): List<ZpaIssue> {
        val scanner = AstScanner(
            checkList,
            metadata,
            true,
            StandardCharsets.UTF_8,
            projectAnalysisContext
        )

        // Started right before the guarded scan, so its thread is always stopped or cancelled.
        val progressReport = ProgressReport("Report about progress of code analyzer", TimeUnit.SECONDS.toMillis(10))
        progressReport.start(targetFiles.map { it.pathRelativeToBase }.toList())

        val rawIssues: List<ZpaIssue>
        var scanSucceeded = false
        try {
            rawIssues = targetFiles.parallelStream().flatMap { file ->
                val scannerResult = scanner.scanFile(file, fileId = FileId(file.pathRelativeToBase))
                progressReport.nextFile()
                NoSonarFilter.filter(scannerResult.issues, scannerResult.linesWithNoSonar).stream()
            }.collect(Collectors.toList())
            scanSucceeded = true
        } finally {
            if (scanSucceeded) {
                progressReport.stop()
            } else {
                progressReport.cancel()
            }
        }

        return rawIssues
    }

    private fun prepareProjectAnalysisContext(files: Collection<InputFile>): ProjectAnalysisContext =
        ProjectAnalysisContext.prepared(
            ProjectIndexPreparation().prepare(
                files.map { file ->
                    ProjectSource(FileId(file.pathRelativeToBase), ProjectSourceReader { file.contents() })
                }
            )
        )

    private fun loadConfigFile(): ConfigFile {
        if (args.configFile.isEmpty()) {
            return ConfigFile()
        }
        val configFile = File(args.configFile)
        if (!configFile.exists()) {
            throw CliValidationException(
                "Configuration file does not exist: ${args.configFile}",
                java.io.FileNotFoundException("Configuration file not found: ${configFile.absolutePath}")
            )
        }
        if (!configFile.isFile) {
            throw CliValidationException(
                "Configuration file is not a file: ${args.configFile}",
                IOException("Configuration path is not a regular file: ${configFile.absolutePath}")
            )
        }
        return try {
            mapper.readValue(configFile, ConfigFile::class.java)
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            throw CliValidationException("Failed to parse configuration file '${args.configFile}': ${e.message}", e)
        } catch (e: IOException) {
            throw CliValidationException("Failed to read configuration file '${args.configFile}': ${e.message}", e)
        }
    }

    private fun getActiveRules(config: ConfigFile): CliActiveRules {
        val activeRules = CliActiveRules(config)

        if (config.rules.isNotEmpty()) {
            activeRules.addRuleConfigurer { repo, rule, configuration ->
                var ruleConfig = config.rules["${repo.key}:${rule.key}"] ?: config.rules[rule.key]
                if (config.base == BaseRuleCategory.DEFAULT && rule.isActivatedByDefault) {
                    ruleConfig = ruleConfig ?: RuleConfiguration()
                }

                if (ruleConfig == null || ruleConfig.options.level == RuleLevel.OFF) {
                    return@addRuleConfigurer false
                }

                if (ruleConfig.options.level != RuleLevel.ON) {
                    configuration.severity = ruleConfig.options.level.toString()
                }
                configuration.parameters.putAll(ruleConfig.options.parameters)
                true
            }
        }
        return activeRules
    }

    companion object {
        val LOG = Loggers.getLogger(Main::class.java)
    }
}

fun execute(args: Array<String>, plugins: PluginProvider = PerRunPlugins()): Int {
    val arguments = Arguments()
    val cmd = JCommander.newBuilder()
        .addObject(arguments)
        .programName("zpa-cli")
        .build()
    return try {
        cmd.parse(*args)
        checkContextOverlayValues(args)
        if (arguments.help) {
            val sb = StringBuilder()
            cmd.usage(sb)
            println(sb.toString())
            return 0
        }
        Main(arguments, plugins).run()
    } catch (exception: ParameterException) {
        System.err.println(exception.message)
        val sb = StringBuilder()
        cmd.usage(sb)
        System.err.println(sb.toString())
        2
    } catch (exception: CliValidationException) {
        System.err.println(exception.message)
        2
    } catch (exception: FixWriteException) {
        System.err.println(exception.message)
        3
    } catch (exception: Exception) {
        System.err.println("Execution failed: ${exception.message}")
        exception.printStackTrace(System.err)
        3
    }
}

/**
 * JCommander drops empty arguments and accepts a trailing option with fewer values than its arity, which would shift the
 * `<path> <file>` pairs of `--context-overlay`; so both values are checked on the raw arguments.
 */
private fun checkContextOverlayValues(args: Array<String>) {
    for ((index, arg) in args.withIndex()) {
        if (arg == CONTEXT_OVERLAY_OPTION) {
            val values = args.drop(index + 1).take(2)
            if (values.size < 2 || values.any { it.isBlank() }) {
                throw CliValidationException("$CONTEXT_OVERLAY_OPTION expects two non-empty values: <path> <file>")
            }
        }
    }
}

fun main(args: Array<String>) {
    if (args.contains(DAEMON_FLAG)) {
        kotlin.system.exitProcess(Daemon.runOnStandardStreams(args))
    }
    val exitCode = execute(args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
