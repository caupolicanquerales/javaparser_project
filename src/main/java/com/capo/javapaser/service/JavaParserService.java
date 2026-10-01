package com.capo.javapaser.service;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import tools.jackson.databind.ObjectMapper;

@Service
public class JavaParserService {

	// matches "groupId:artifactId:type[:classifier]:version:scope" lines from `mvn dependency:tree` output
	private static final Pattern DEPENDENCY_LINE_PATTERN =
			Pattern.compile("([\\w.-]+):([\\w.-]+):[\\w.-]+:(?:[\\w.-]+:)?([\\w.-]+):[\\w.-]+");

	// matches the <localRepository> override in a Maven settings.xml
	private static final Pattern LOCAL_REPOSITORY_PATTERN =
			Pattern.compile("<localRepository>(.*?)</localRepository>", Pattern.DOTALL);

	private final ObjectMapper objectMapper;
	
	public JavaParserService(ObjectMapper objectMapper) {
		this.objectMapper= objectMapper;
	}
	
	public void runJavaParser(String... args) throws Exception {

        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(config);

        Path sourceDir = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);

        List<Map<String, Object>> components = new ArrayList<>();

        Files.walk(sourceDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(path -> parseJavaFile(path.toFile(), components));

        writeReport(outputPath, components);
        System.out.println("AST report successfully generated at: " + outputPath);

        Path resolvedOutputPath = resolvedReportPath(outputPath);
        List<Map<String, Object>> resolvedComponents = runSymbolSolverReport(sourceDir);
        writeReport(resolvedOutputPath, resolvedComponents);
        System.out.println("Resolved AST report successfully generated at: " + resolvedOutputPath);
    }

    private void parseJavaFile(File file, List<Map<String, Object>> components) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                Map<String, Object> classData = new LinkedHashMap<>();
                classData.put("className", clazz.getNameAsString());
                classData.put("packageName", cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""));
                classData.put("isInterface", clazz.isInterface());
                classData.put("annotations", clazz.getAnnotations().stream().map(a -> a.getNameAsString()).toList());
                classData.put("javadoc", clazz.getJavadocComment().map(j -> j.parse().toText().trim()).orElse(""));
                components.add(classData);
            });
        } catch (Exception e) {
            System.err.println("Failed to parse: " + file.getPath());
        }
    }

    /**
     * Builds a symbol-solver-backed AST report where types are resolved to their fully
     * qualified names using a CombinedTypeSolver (reflection + project sources + dependency jars).
     */
    private List<Map<String, Object>> runSymbolSolverReport(Path sourceDir) throws IOException {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(sourceDir));

        List<JarTypeSolver> jarTypeSolvers = new ArrayList<>();
        for (Path jar : resolveDependencyJars(projectRootFrom(sourceDir))) {
            try {
                JarTypeSolver jarTypeSolver = new JarTypeSolver(jar);
                typeSolver.add(jarTypeSolver);
                jarTypeSolvers.add(jarTypeSolver);
            } catch (IOException e) {
                System.err.println("Skipping unresolved dependency jar: " + jar + " (" + e.getMessage() + ")");
            }
        }

        try {
            ParserConfiguration resolverConfig = new ParserConfiguration();
            resolverConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
            resolverConfig.setSymbolResolver(new JavaSymbolSolver(typeSolver));
            JavaParser resolvingParser = new JavaParser(resolverConfig);

            List<Map<String, Object>> components = new ArrayList<>();
            Files.walk(sourceDir)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> parseJavaFileWithSymbols(resolvingParser, path.toFile(), components));
            return components;
        } finally {
            // release the jars' underlying zip file handles (javaparser-symbol-solver-core 3.26.1 doesn't
            // implement Closeable on JarTypeSolver, so this is a defensive no-op on that version)
            jarTypeSolvers.forEach(this::closeQuietly);
        }
    }

    private void closeQuietly(JarTypeSolver jarTypeSolver) {
        if (jarTypeSolver instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                System.err.println("Failed to release jar handle: " + e.getMessage());
            }
        }
    }

    private void parseJavaFileWithSymbols(JavaParser resolvingParser, File file, List<Map<String, Object>> components) {
        try {
            ParseResult<CompilationUnit> result = resolvingParser.parse(file);
            CompilationUnit cu = result.getResult().orElse(null);
            if (cu == null) {
                System.err.println("Failed to parse: " + file.getPath());
                return;
            }

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                Map<String, Object> classData = new LinkedHashMap<>();
                classData.put("className", clazz.getNameAsString());
                classData.put("packageName", cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""));
                classData.put("isInterface", clazz.isInterface());
                classData.put("annotations", clazz.getAnnotations().stream().map(a -> a.getNameAsString()).toList());
                classData.put("javadoc", clazz.getJavadocComment().map(j -> j.parse().toText().trim()).orElse(""));
                classData.put("qualifiedName", resolveQualifiedName(clazz, cu));
                classData.put("extendedTypes", clazz.getExtendedTypes().stream().map(this::resolveType).toList());
                classData.put("implementedTypes", clazz.getImplementedTypes().stream().map(this::resolveType).toList());
                classData.put("fields", extractFields(clazz));
                classData.put("methods", extractMethods(clazz));
                components.add(classData);
            });
        } catch (Exception e) {
            System.err.println("Failed to resolve symbols in: " + file.getPath() + " (" + e.getMessage() + ")");
        }
    }

    private String resolveQualifiedName(ClassOrInterfaceDeclaration clazz, CompilationUnit cu) {
        try {
            return clazz.resolve().getQualifiedName();
        } catch (Exception e) {
            return cu.getPackageDeclaration().map(p -> p.getNameAsString() + "." + clazz.getNameAsString())
                    .orElse(clazz.getNameAsString());
        }
    }

    private String resolveType(Type type) {
        try {
            return type.resolve().describe();
        } catch (Exception e) {
            return type.asString();
        }
    }

    /** Field declarations with resolved types, access modifiers, and annotations (e.g. @Autowired, @Value). */
    private List<Map<String, Object>> extractFields(ClassOrInterfaceDeclaration clazz) {
        List<Map<String, Object>> fields = new ArrayList<>();
        clazz.getFields().forEach(field -> {
            List<String> modifiers = field.getModifiers().stream().map(m -> m.getKeyword().asString()).toList();
            List<String> annotations = field.getAnnotations().stream().map(a -> a.getNameAsString()).toList();
            field.getVariables().forEach(variable -> {
                Map<String, Object> fieldData = new LinkedHashMap<>();
                fieldData.put("name", variable.getNameAsString());
                fieldData.put("type", resolveType(variable.getType()));
                fieldData.put("modifiers", modifiers);
                fieldData.put("annotations", annotations);
                fields.add(fieldData);
            });
        });
        return fields;
    }

    /** Method contracts (signature, annotations, thrown/caught exceptions) plus the call graph of each body. */
    private List<Map<String, Object>> extractMethods(ClassOrInterfaceDeclaration clazz) {
        List<Map<String, Object>> methods = new ArrayList<>();
        clazz.getMethods().forEach(method -> {
            Map<String, Object> methodData = new LinkedHashMap<>();
            methodData.put("name", method.getNameAsString());
            methodData.put("returnType", resolveType(method.getType()));
            methodData.put("modifiers", method.getModifiers().stream().map(m -> m.getKeyword().asString()).toList());
            methodData.put("annotations", method.getAnnotations().stream().map(a -> a.getNameAsString()).toList());
            methodData.put("parameters", method.getParameters().stream().map(this::describeParameter).toList());
            methodData.put("declaredExceptions", method.getThrownExceptions().stream().map(this::resolveType).toList());
            methodData.put("caughtExceptions", extractCaughtExceptions(method));
            methodData.put("methodCalls", extractMethodCalls(method));
            methods.add(methodData);
        });
        return methods;
    }

    private Map<String, Object> describeParameter(Parameter parameter) {
        Map<String, Object> parameterData = new LinkedHashMap<>();
        parameterData.put("name", parameter.getNameAsString());
        parameterData.put("type", resolveType(parameter.getType()));
        return parameterData;
    }

    /** Exceptions caught inside the method body, as opposed to those declared in its `throws` clause. */
    private List<String> extractCaughtExceptions(MethodDeclaration method) {
        return method.findAll(CatchClause.class).stream()
                .flatMap(catchClause -> splitExceptionTypes(catchClause.getParameter().getType()).stream())
                .toList();
    }

    /** Splits a multi-catch (`catch (IOException | SQLException e)`) into individually resolved entries. */
    private List<String> splitExceptionTypes(Type type) {
        if (type instanceof UnionType unionType) {
            return unionType.getElements().stream().map(this::resolveType).toList();
        }
        return List.of(resolveType(type));
    }

    /** Method invocations inside the body, resolved to `declaringType.method(paramTypes)` to build a call graph. */
    private List<String> extractMethodCalls(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .map(this::describeMethodCall)
                .toList();
    }

    private String describeMethodCall(MethodCallExpr methodCallExpr) {
        try {
            return methodCallExpr.resolve().getQualifiedSignature();
        } catch (Exception e) {
            return describeUnresolvedMethodCall(methodCallExpr);
        }
    }

    /**
     * Best-effort fallback for calls the symbol solver can't fully resolve (e.g. overloaded library
     * methods javassist can't disambiguate, or same-class/static-imported helper methods): qualify the
     * scope's own resolved type, match a static import, or qualify with the enclosing class before
     * giving up on a bare name like "logger.info" or "fromBookDTO".
     */
    private String describeUnresolvedMethodCall(MethodCallExpr methodCallExpr) {
        String methodName = methodCallExpr.getNameAsString();
        Optional<Expression> scope = methodCallExpr.getScope();

        if (scope.isPresent()) {
            String scopeType = resolveExpressionType(scope.get());
            return (scopeType != null ? scopeType : scope.get().toString()) + "." + methodName;
        }

        return resolveStaticImport(methodCallExpr, methodName)
                .orElseGet(() -> {
                    String enclosingType = enclosingTypeName(methodCallExpr);
                    return enclosingType.isEmpty() ? methodName : enclosingType + "." + methodName;
                });
    }

    private String resolveExpressionType(Expression expression) {
        try {
            return expression.calculateResolvedType().describe();
        } catch (Exception e) {
            return null;
        }
    }

    /** Matches a scope-less call against the file's static imports (single-member or wildcard). */
    private Optional<String> resolveStaticImport(Node node, String methodName) {
        return node.findCompilationUnit().flatMap(cu -> cu.getImports().stream()
                .filter(ImportDeclaration::isStatic)
                .filter(imp -> imp.isAsterisk() || methodName.equals(imp.getName().getIdentifier()))
                .map(imp -> imp.isAsterisk()
                        ? imp.getName().asString() + "." + methodName
                        : imp.getName().getQualifier().map(q -> q.asString() + "." + methodName).orElse(methodName))
                .findFirst());
    }

    private String enclosingTypeName(Node node) {
        return node.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(clazz -> {
                    try {
                        return clazz.resolve().getQualifiedName();
                    } catch (Exception e) {
                        return clazz.getNameAsString();
                    }
                })
                .orElse("");
    }

    /** Walks up from a `<root>/src/main/java` source directory to the project root. */
    private Path projectRootFrom(Path sourceDir) {
        Path root = sourceDir;
        for (String expectedSegment : new String[] {"java", "main", "src"}) {
            if (root.getParent() != null && expectedSegment.equals(String.valueOf(root.getFileName()))) {
                root = root.getParent();
            }
        }
        return root;
    }

    /** Resolves dependency jar paths from the project's `dependency-tree.txt` via the local Maven repository. */
    private List<Path> resolveDependencyJars(Path projectRoot) throws IOException {
        List<Path> jars = new ArrayList<>();
        Path dependencyTreeFile = findDependencyTreeFile(projectRoot);
        if (dependencyTreeFile == null) {
            return jars;
        }

        Path localRepo = resolveLocalRepository();
        for (String line : Files.readAllLines(dependencyTreeFile)) {
            Matcher matcher = DEPENDENCY_LINE_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String groupId = matcher.group(1);
            String artifactId = matcher.group(2);
            String version = matcher.group(3);

            Path jarPath = localRepo.resolve(groupId.replace('.', File.separatorChar))
                    .resolve(artifactId)
                    .resolve(version)
                    .resolve(artifactId + "-" + version + ".jar");

            if (Files.exists(jarPath)) {
                jars.add(jarPath);
            } else {
                System.err.println("Dependency jar not found, type resolution may be incomplete: " + jarPath);
            }
        }
        return jars;
    }

    /**
     * Resolves the local Maven repository location: honors the `maven.repo.local` system property
     * first, then a `<localRepository>` override in `~/.m2/settings.xml`, falling back to the
     * conventional `~/.m2/repository`. A hardcoded default alone silently misses every dependency
     * jar (and therefore type resolution accuracy) on machines with a relocated repository.
     */
    private Path resolveLocalRepository() {
        String override = System.getProperty("maven.repo.local");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }

        Path userHome = Paths.get(System.getProperty("user.home"));
        Path settingsFile = userHome.resolve(".m2").resolve("settings.xml");
        if (Files.exists(settingsFile)) {
            try {
                Matcher matcher = LOCAL_REPOSITORY_PATTERN.matcher(Files.readString(settingsFile));
                if (matcher.find()) {
                    return Paths.get(matcher.group(1).trim());
                }
            } catch (IOException e) {
                System.err.println("Failed to read Maven settings.xml, using default local repository: " + e.getMessage());
            }
        }

        return userHome.resolve(".m2").resolve("repository");
    }

    /**
     * Looks for `dependency-tree.txt` starting at the given directory and walking up to its parents,
     * stopping at the enclosing Git/Maven multi-module root so a submodule's build still finds the
     * tree file generated at the aggregator root.
     */
    private Path findDependencyTreeFile(Path startDir) {
        Path current = startDir;
        while (current != null) {
            Path candidate = current.resolve("dependency-tree.txt");
            if (Files.exists(candidate)) {
                return candidate;
            }
            if (Files.exists(current.resolve(".git"))) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }

    private Path resolvedReportPath(Path outputPath) {
        String fileName = outputPath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String resolvedName = dotIndex >= 0
                ? fileName.substring(0, dotIndex) + "-resolved" + fileName.substring(dotIndex)
                : fileName + "-resolved";
        Path parent = outputPath.getParent();
        return parent != null ? parent.resolve(resolvedName) : Paths.get(resolvedName);
    }

    private void writeReport(Path outputPath, Object payload) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), payload);
    }
}
