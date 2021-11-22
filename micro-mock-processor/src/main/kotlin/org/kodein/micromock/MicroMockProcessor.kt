package org.kodein.micromock

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.*


class MicroMockProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private companion object {
        val mockerTypeName = ClassName("org.kodein.micromock", "Mocker")
        val builtins = mapOf(
            "kotlin.Boolean" to ("%L" to "false"),
            "kotlin.Byte" to ("%L" to "0"),
            "kotlin.Short" to ("%L" to "0"),
            "kotlin.Int" to ("%L" to "0"),
            "kotlin.Long" to ("%L" to "0L"),
            "kotlin.Float" to ("%L" to "0f"),
            "kotlin.Double" to ("%L" to "0.0"),
            "kotlin.String" to ("%L" to "\"\""),
            "kotlin.collections.List" to ("%M()" to MemberName("kotlin.collections", "emptyList")),
            "kotlin.collections.Set" to ("%M()" to MemberName("kotlin.collections", "emptySet")),
            "kotlin.collections.Map" to ("%M()" to MemberName("kotlin.collections", "emptyMap")),
        )
    }

    private fun Location.asString() = when (this) {
        is FileLocation -> "${this.filePath}:${this.lineNumber}"
        NonExistLocation -> "Unnown location"
    }

    @OptIn(KotlinPoetKspPreview::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val toInject = HashMap<KSClassDeclaration, ArrayList<Pair<String, KSPropertyDeclaration>>>()
        val toMock = HashMap<KSClassDeclaration, HashSet<KSFile>>()
        val toFake = HashMap<KSClassDeclaration, HashSet<KSFile>>()

        fun addMock(type: KSType, files: Iterable<KSFile>, loc: Location) {
            if (type.isFunctionType) return
            val decl = type.declaration
            if (decl !is KSClassDeclaration || decl.classKind != ClassKind.INTERFACE) error("${loc.asString()}: Cannot generate mock for non interface $decl")
            toMock.getOrPut(decl) { HashSet() } .addAll(files)
        }

        fun addFake(type: KSType, files: Iterable<KSFile>, loc: Location) {
            val decl = type.declaration
            if (
                decl !is KSClassDeclaration || decl.isAbstract() ||
                decl.classKind !in arrayOf(ClassKind.CLASS, ClassKind.ENUM_CLASS)
            ) error("${loc.asString()}: Cannot generate fake for non concrete class $decl")
            toFake.getOrPut(decl) { HashSet() } .addAll(files)
        }

        fun lookUpFields(annotationName: String, add: (KSType, Iterable<KSFile>, Location) -> Unit) {
            val symbols = resolver.getSymbolsWithAnnotation(annotationName)
            symbols.forEach { symbol ->
                val prop = when (symbol) {
                    is KSPropertySetter -> symbol.receiver
                    is KSPropertyDeclaration -> {
                        if (!symbol.isMutable) error("${symbol.location.asString()}: $symbol is immutable but is annotated with @${annotationName.split(".").last()}")
                        symbol
                    }
                    else -> error("${symbol.location.asString()}: $symbol is not a property nor a property setter but is annotated with @${annotationName.split(".").last()} (is ${symbol::class.simpleName})")
                }
                val cls = prop.parentDeclaration as? KSClassDeclaration ?: error("${symbol.location.asString()}: Cannot generate injector for $prop as it is not inside a class")
                toInject.getOrPut(cls) { ArrayList() } .add(annotationName to prop)
                add(prop.type.resolve(), listOf(cls.containingFile!!), symbol.location)
            }
        }

        fun lookUpUses(annotationName: String, add: (KSType, Iterable<KSFile>, Location) -> Unit) {
            val uses = resolver.getSymbolsWithAnnotation(annotationName)
            uses.forEach { use ->
                val anno = use.annotations.first { it.annotationType.resolve().declaration.qualifiedName!!.asString() == annotationName }
                (anno.arguments.first().value as List<*>).forEach {
                    add((it as KSType), listOf(use.containingFile!!), use.location)
                }
            }
        }

        lookUpFields("org.kodein.micromock.Mock", ::addMock)
        lookUpFields("org.kodein.micromock.Fake", ::addFake)
        lookUpUses("org.kodein.micromock.UsesMocks", ::addMock)
        lookUpUses("org.kodein.micromock.UsesFakes", ::addFake)

        run {
            val toExplore = ArrayDeque(toFake.map { it.toPair() })
            while (toExplore.isNotEmpty()) {
                val (cls, files) = toExplore.removeFirst()
                cls.primaryConstructor?.parameters?.forEach { param ->
                    if (!param.hasDefault) {
                        val paramType = param.type.resolve()
                        val paramDecl = paramType.declaration
                        if (
                                paramType.nullability == Nullability.NOT_NULL
                            &&  paramDecl.qualifiedName!!.asString() !in builtins
                            &&  paramDecl !in toFake
                        ) {
                            addFake(paramType, files, param.location)
                            toExplore.add((paramDecl as KSClassDeclaration) to files)
                        }
                    }
                }
            }
        }

        toMock.forEach { (vItf, files) ->
            val mockClassName = "Mock${vItf.simpleName.asString()}"
            val gFile = FileSpec.builder(vItf.packageName.asString(), mockClassName)
            val gCls = TypeSpec.classBuilder(mockClassName)
                .addSuperinterface(
                    if (vItf.typeParameters.isEmpty()) vItf.toClassName()
                    else vItf.toClassName().parameterizedBy(vItf.typeParameters.map { it.toTypeVariableName() })
                )
                .addModifiers(KModifier.INTERNAL)
            vItf.typeParameters.forEach { vParam ->
                gCls.addTypeVariable(vParam.toTypeVariableName())
            }
            gCls.primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("mocker", mockerTypeName)
                        .build()
                )
            val mocker = PropertySpec.builder("mocker", mockerTypeName)
                .initializer("mocker")
                .addModifiers(KModifier.PRIVATE)
                .build()
            gCls.addProperty(mocker)
            vItf.getAllProperties()
                .filter { it.isAbstract() }
                .forEach { vProp ->
                    val typeParamResolver = vItf.typeParameters.toTypeParameterResolver()
                    val gProp = PropertySpec.builder(vProp.simpleName.asString(), vProp.type.toTypeName(typeParamResolver))
                        .addModifiers(KModifier.OVERRIDE)
                        .getter(
                            FunSpec.getterBuilder()
                                .addStatement("return this.%N.register(this, %S)", mocker, "get:${vProp.simpleName.asString()}")
                                .build()
                        )
                    if (vProp.isMutable) {
                        gProp.mutable(true)
                            .setter(
                                FunSpec.setterBuilder()
                                    .addParameter("value", vProp.type.toTypeName(typeParamResolver))
                                    .addStatement("return this.%N.register(this, %S, value)", mocker, "set:${vProp.simpleName.asString()}")
                                    .build()
                            )
                    }
                    gCls.addProperty(gProp.build())
                }
            vItf.getAllFunctions()
                .filter { it.isAbstract }
                .forEach { vFun ->
                    val gFun = FunSpec.builder(vFun.simpleName.asString())
                        .addModifiers(KModifier.OVERRIDE)
                    val typeParamResolver = vFun.typeParameters.toTypeParameterResolver(vItf.typeParameters.toTypeParameterResolver())
                    vFun.typeParameters.forEach { vParam ->
                        gFun.addTypeVariable(vParam.toTypeVariableName(typeParamResolver))
                    }
                    gFun.addModifiers((vFun.modifiers - Modifier.ABSTRACT).mapNotNull { it.toKModifier() })
                    gFun.returns(vFun.returnType!!.toTypeName(typeParamResolver))
                    vFun.parameters.forEach { vParam ->
                        gFun.addParameter(vParam.name!!.asString(), vParam.type.toTypeName(typeParamResolver))
                    }
                    val paramsDescription = vFun.parameters.joinToString { (it.type.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString() ?: "?" }
                    val paramsCall = if (vFun.parameters.isEmpty()) "" else vFun.parameters.joinToString(prefix = ", ") { it.name!!.asString() }
                    gFun.addStatement("return this.%N.register(this, %S$paramsCall)", mocker, "${vFun.simpleName.asString()}($paramsDescription)")
                    gCls.addFunction(gFun.build())
                }
            gFile.addType(gCls.build())
            gFile.build().writeTo(codeGenerator, Dependencies(false, *files.toTypedArray()))
        }

        toFake.forEach { (vCls, files) ->
            val mockFunName = "fake${vCls.simpleName.asString()}"
            val gFile = FileSpec.builder(vCls.packageName.asString(), mockFunName)
            val gFun = FunSpec.builder(mockFunName)
                .addModifiers(KModifier.INTERNAL)
            when (vCls.classKind) {
                ClassKind.CLASS -> {
                    val vCstr = vCls.primaryConstructor
                    if (vCstr == null) {
                        gFun.addStatement("return %T()", vCls.toClassName())
                    } else {
                        val args = ArrayList<Pair<String, Any>>()
                        vCstr.parameters.forEach { vParam ->
                            if (!vParam.hasDefault) {
                                val vParamType = vParam.type.resolve()
                                if (vParamType.nullability != Nullability.NOT_NULL) {
                                    args.add("${vParam.name!!.asString()} = %L" to "null")
                                } else {
                                    val vParamDecl = vParamType.declaration
                                    val builtIn = builtins[vParamDecl.qualifiedName!!.asString()]
                                    if (builtIn != null) {
                                        val (template, value) = builtIn
                                        args.add("${vParam.name!!.asString()} = $template" to value)
                                    } else {
                                        args.add("${vParam.name!!.asString()} = %M()" to MemberName(vParamDecl.packageName.asString(), "fake${vParamDecl.simpleName.asString()}"))
                                    }
                                }
                            }
                        }
                        gFun.addStatement("return %T(${args.joinToString { it.first }})", *(listOf(vCls.toClassName()) + args.map { it.second }).toTypedArray())
                    }
                }
                ClassKind.ENUM_CLASS -> {
                    val firstEntry = vCls.declarations.filterIsInstance<KSClassDeclaration>().firstOrNull { it.classKind == ClassKind.ENUM_ENTRY }
                        ?: error("${vCls.location.asString()}: Cannot fake empty enum class ${vCls.qualifiedName!!.asString()}")
                    gFun.addStatement("return %T.%L", vCls.toClassName(), firstEntry.simpleName.asString())
                }
                else -> error("${vCls.location.asString()}: Cannot process ${vCls.classKind}")
            }
            gFile.addFunction(gFun.build())
            gFile.build().writeTo(codeGenerator, Dependencies(false, *files.toTypedArray()))
        }

        toInject.forEach { (vCls, vProps) ->
            val gFile = FileSpec.builder(vCls.packageName.asString(), "${vCls.simpleName.asString()}_injectMocks")
            val gFun = FunSpec.builder("injectMocks")
                .addModifiers(KModifier.INTERNAL)
                .receiver(vCls.toClassName())
                .addParameter("mocker", mockerTypeName)
            vProps.forEach { (anno, vProp) ->
                when {
                    anno == "org.kodein.micromock.Mock" -> {
                        val vType = vProp.type.resolve()
                        if (vType.isFunctionType) {
                            logger.warn(vType.arguments.joinToString())
                            val argCount = vType.arguments.size - 1
                            val args =
                                if (argCount == 0) ""
                                else vType.arguments.take(argCount).joinToString(prefix = ", ") { "\"${it.type!!.resolve().declaration.qualifiedName!!.asString()}\"" }
                            gFun.addStatement(
                                "this.%N = %M(%N$args)",
                                vProp.simpleName.asString(),
                                MemberName("org.kodein.micromock", "mockFunction$argCount"),
                                "mocker"
                            )
                        } else {
                            val vDecl = vType.declaration
                            gFun.addStatement(
                                "this.%N = %T(%N)",
                                vProp.simpleName.asString(),
                                ClassName(vDecl.packageName.asString(), "Mock${vDecl.simpleName.asString()}"),
                                "mocker"
                            )
                        }
                    }
                    anno == "org.kodein.micromock.Fake" -> {
                        gFun.addStatement(
                            "this.%N = %M()",
                            vProp.simpleName.asString(),
                            vProp.type.resolve().declaration.let { MemberName(it.packageName.asString(), "fake${it.simpleName.asString()}") }
                        )
                    }
                }
            }
            gFile.addFunction(gFun.build())
            gFile.build().writeTo(codeGenerator, Dependencies(false, vCls.containingFile!!))
        }

        return emptyList()
    }
}
