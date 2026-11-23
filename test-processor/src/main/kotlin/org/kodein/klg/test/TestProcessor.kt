package org.kodein.klg.test

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * The processor the plugin's tests run KSP with: for every class annotated `@klg.test.Generate`,
 * generates an object named after it with a `Generated` suffix - and reports an error instead for a
 * class whose name starts with `Broken`, so that a test can make KSP fail at will.
 */
class TestProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        for (symbol in resolver.getSymbolsWithAnnotation("klg.test.Generate")) {
            val decl = symbol as? KSClassDeclaration ?: continue
            val name = decl.simpleName.asString()
            if (name.startsWith("Broken")) {
                logger.error("'$name' is broken.", decl)
                continue
            }
            val packageName = decl.packageName.asString()
            val file = decl.containingFile ?: continue
            codeGenerator.createNewFile(Dependencies(false, file), packageName, "${name}Generated").writer().use {
                it.write("package $packageName\n\nobject ${name}Generated {\n    const val NAME: String = \"$name\"\n}\n")
            }
        }
        return emptyList()
    }
}

class TestProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        TestProcessor(environment.codeGenerator, environment.logger)
}
