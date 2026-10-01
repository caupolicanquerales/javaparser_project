@echo off
setlocal

if "%JAVA_HOME%"=="" (
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.2.13-hotspot"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Building javapaser with JAVA_HOME: %JAVA_HOME%
call mvnw.cmd clean package -DskipTests

echo JAR generated at: target\javapaser-0.0.1-SNAPSHOT.jar
endlocal