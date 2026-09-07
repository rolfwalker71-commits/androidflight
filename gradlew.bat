@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Only keep JAVA_HOME when bin\java.exe exists. A WSL path from
@rem gradle-local.properties (e.g. /home/rolf/.jdks/jdk-17) is not usable here.
if defined JAVA_HOME set JAVA_HOME=%JAVA_HOME:"=%
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto findJavaFromJavaHome
set JAVA_HOME=

if exist "%APP_HOME%\gradle-local.properties" for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /c:"org.gradle.java.home=" "%APP_HOME%\gradle-local.properties"`) do set JAVA_HOME=%%B
if defined JAVA_HOME set JAVA_HOME=%JAVA_HOME:"=%
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto findJavaFromJavaHome
set JAVA_HOME=

for /d %%D in ("%USERPROFILE%\.jdks\jbr-*") do (
    if exist "%%~fD\bin\java.exe" (
        set "JAVA_HOME=%%~fD"
        goto findJavaFromJavaHome
    )
)

if exist "%USERPROFILE%\.jdks\jbr-21.0.11\bin\java.exe" (
    set "JAVA_HOME=%USERPROFILE%\.jdks\jbr-21.0.11"
    goto findJavaFromJavaHome
)

if exist "%USERPROFILE%\.jdks\jdk-17\bin\java.exe" (
    set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-17"
    goto findJavaFromJavaHome
)

if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    goto findJavaFromJavaHome
)

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%\bin\java.exe

if exist "%JAVA_EXE%" goto execute

echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
goto fail

:execute
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal
