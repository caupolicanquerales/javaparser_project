@echo off
setlocal enabledelayedexpansion

:: 1. Validate Command-Line Parameters
set "TARGET_DIR=%~1"
set "JAR_INPUT=%~2"

if "%TARGET_DIR%"=="" (
    echo [ERROR] Target directory parameter is missing.
    echo Usage: generate-reports.bat ^<target-project-dir^> ^<path-to-javaparser-jar-or-dir^>
    exit /b 1
)

if "%JAR_INPUT%"=="" (
    echo [ERROR] JavaParser JAR path parameter is missing.
    echo Usage: generate-reports.bat ^<target-project-dir^> ^<path-to-javaparser-jar-or-dir^>
    exit /b 1
)

:: 2. Resolve Absolute Paths
for %%I in ("%TARGET_DIR%") do set "ABS_TARGET_DIR=%%~fI"
for %%I in ("%JAR_INPUT%") do set "ABS_JAR_INPUT=%%~fI"

:: the built artifact is intentionally named "javapaser" (no 'r') per pom.xml artifactId
if exist "%ABS_JAR_INPUT%\javapaser-0.0.1-SNAPSHOT.jar" (
    set "PARSER_JAR=%ABS_JAR_INPUT%\javapaser-0.0.1-SNAPSHOT.jar"
) else if exist "%ABS_JAR_INPUT%" (
    set "PARSER_JAR=%ABS_JAR_INPUT%"
) else (
    echo [ERROR] Specified JavaParser JAR or directory does not exist: "%ABS_JAR_INPUT%"
    exit /b 1
)

if not exist "%ABS_TARGET_DIR%\src\main\java" (
    echo [ERROR] Source directory does not exist: "%ABS_TARGET_DIR%\src\main\java"
    exit /b 1
)

echo ========================================================
echo  Target Project: "%ABS_TARGET_DIR%"
echo  Parser Location: "%PARSER_JAR%"
echo ========================================================

:: 3. Export Maven Dependency Tree
echo [1/2] Exporting Maven dependency tree...
cd /d "%ABS_TARGET_DIR%"

if exist "%ABS_TARGET_DIR%\mvnw.cmd" (
    call "%ABS_TARGET_DIR%\mvnw.cmd" dependency:tree -DoutputFile="%ABS_TARGET_DIR%\dependency-tree.txt"
) else (
    call mvn dependency:tree -DoutputFile="%ABS_TARGET_DIR%\dependency-tree.txt"
)

if !ERRORLEVEL! NEQ 0 (
    echo [ERROR] Maven dependency:tree execution failed.
    exit /b !ERRORLEVEL!
)

:: 4. Run JavaParser Extractor
echo [2/2] Running JavaParser Extractor...
java -jar "%PARSER_JAR%" "%ABS_TARGET_DIR%\src\main\java" "%ABS_TARGET_DIR%\business-ast-report.json"
if !ERRORLEVEL! NEQ 0 (
    echo [ERROR] JavaParser execution failed.
    exit /b !ERRORLEVEL!
)

echo ========================================================
echo  SUCCESS: Diagnostic reports created in target root:
echo   - %ABS_TARGET_DIR%\dependency-tree.txt
echo   - %ABS_TARGET_DIR%\business-ast-report.json
echo   - %ABS_TARGET_DIR%\business-ast-report-resolved.json
echo ========================================================
endlocal