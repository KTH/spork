package se.kth.spork.spoon

import se.kth.spork.spoon.printer.SourceExtractor
import se.kth.spork.spoon.printer.SporkPrettyPrinter
import se.kth.spork.util.LazyLogger
import spoon.Launcher
import spoon.compiler.Environment
import spoon.reflect.CtModel
import spoon.reflect.declaration.CtElement
import spoon.reflect.declaration.CtImport
import spoon.reflect.declaration.CtModule
import spoon.reflect.declaration.CtType
import spoon.support.compiler.FileSystemFile
import spoon.support.compiler.VirtualFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * A class for dealing with parsing.
 *
 * @author Simon Larsén
 */
object Parser {
    const val IMPORT_STATEMENTS = "spork_import_statements"
    const val COMPILATION_UNIT_COMMENT = "spork_cu_comment"
    private val LOGGER = LazyLogger(Parser::class.java)

    /**
     * Parse a Java file to a Spoon tree. Any import statements in the file are attached to the returned module's
     * metadata with the [Parser.IMPORT_STATEMENTS] key. The imports are sorted in ascending lexicographical
     * order.
     *
     * @param javaFile Path to a Java file.
     * @return The root module of the Spoon tree.
     */
    fun parse(javaFile: Path): CtModule {
        return parse { launcher: Launcher -> launcher.addInputResource(FileSystemFile(javaFile.toFile())) }
    }

    /**
     * Parse the contents of a single Java file.
     *
     * @param javaFileContents The contents of a single Java file.
     * @param excludeComments Whether or not to exclude comments when parsing.
     * @return The root module of the Spoon tree.
     */
    @JvmOverloads
    fun parse(javaFileContents: String, excludeComments: Boolean = false): CtModule {
        return parse { launcher: Launcher ->
            if (excludeComments) {
                launcher.environment.setCommentEnabled(false)
            }
            launcher.addInputResource(VirtualFile(javaFileContents))
        }
    }

    fun setSporkEnvironment(env: Environment, tabulationSize: Int, useTabs: Boolean) {
        env.tabulationSize = tabulationSize
        env.useTabulations(useTabs)
        env.setPrettyPrinterCreator { SporkPrettyPrinter(env) }
        env.noClasspath = true
        env.encoding = StandardCharsets.UTF_8 // TODO detect instead of setting unconditionally
    }

    private fun parse(configureLauncher: (Launcher) -> Unit): CtModule {
        val launcher = Launcher()
        configureLauncher(launcher)
        val model = launcher.buildModel()
        val indentationGuess = SourceExtractor.guessIndentation(model)
        val indentationType = if (indentationGuess.second) "tabs" else "spaces"
        LOGGER.info { "Using indentation: " + indentationGuess.first + " " + indentationType }
        setSporkEnvironment(launcher.environment, indentationGuess.first, indentationGuess.second)
        val module = model.unnamedModule
        module.putMetadata<CtElement>(COMPILATION_UNIT_COMMENT, getCuComment(module))

        // TODO preserve order of import statements
        val imports = parseImportStatements(model).toList().sortedBy(CtImport::prettyprint)
        module.putMetadata<CtElement>(IMPORT_STATEMENTS, imports)
        return module
    }

    // FIXME This is an ugly workaround for merging compliation unit comments
    private fun getCuComment(module: CtModule) =
        module.factory.CompilationUnit().map.values.firstOrNull()?.comments?.firstOrNull()?.rawContent ?: ""

    /**
     * Parse unique import statements from all types of the given model.
     *
     * Obviously, as a set is returned, the order of the statements is not preserved.
     *
     * @param model A model.
     * @return A list of import statements.
     */
    fun parseImportStatements(model: CtModel): Set<CtImport> =
        model.allTypes.flatMap(Parser::parseImportStatements).toSet()

    /**
     * Parse import statements from the given type. Note that all types in a single file will get the same
     * import statements attached to them, so there is no need to parse imports from multiple types in a single
     * file.
     *
     * The order of the import statements is preserved.
     *
     * @param type A Java type.
     * @return A list of import statements.
     */
    fun parseImportStatements(type: CtType<*>): List<CtImport> =
        type.factory.CompilationUnit().getOrCreate(type).imports

    /**
     * Read the contents of a file.
     *
     * @param path Path to a file.
     * @return The contents of the file.
     */
    fun read(path: Path): String {
        return try {
            java.lang.String.join("\n", Files.readAllLines(path, StandardCharsets.UTF_8))
        } catch (e: IOException) {
            e.printStackTrace()
            throw RuntimeException("Error reading from $path")
        }
    }
}
