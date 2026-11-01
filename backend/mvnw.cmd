@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM ----------------------------------------------------------------------------

@IF "%__MVNW_ARG0_NAME__%"=="" (SET "__MVNW_ARG0_NAME__=%~nx0")
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_SAVE_ERRORLEVEL__=
@SET __MVNW_SAVE_ERRORLEVEL_RESET__=

@SETLOCAL

@SET DIRNAME=%~dp0
@IF "%DIRNAME%" == "" @SET DIRNAME=.
@SET APP_BASE_NAME=%~n0
@SET APP_HOME=%DIRNAME%

FOR %%i IN ("%APP_HOME%") DO @SET APP_HOME=%%~fi

SET "JVM_CONFIG_MAVEN_PROPS="
SET "MAVEN_OPTS=%MAVEN_OPTS%"
SET "MAVEN_DEBUG_OPTS="
SET "MAVEN_CONFIG="

FOR %%i IN ("%APP_HOME%") DO (
    SET MAVEN_PROJECTBASEDIR=%%~fi
)

@SET WRAPPER_JAR="%APP_HOME%\.mvn\wrapper\maven-wrapper.jar"
@SET WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

@SET DOWNLOAD_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"

@FOR /F "usebackq tokens=1,2 delims==" %%A IN ("%APP_HOME%\.mvn\wrapper\maven-wrapper.properties") DO (
    @IF "%%A"=="wrapperUrl" @SET DOWNLOAD_URL=%%B
)

@IF NOT EXIST %WRAPPER_JAR% (
    @IF "%MVNW_VERBOSE%" == "true" (
        @echo Downloading Maven Wrapper jar from: %DOWNLOAD_URL%
    )
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('%DOWNLOAD_URL%', '%APP_HOME%\.mvn\wrapper\maven-wrapper.jar')"
    @IF NOT EXIST %WRAPPER_JAR% (
        @echo Error: Could not download Maven Wrapper JAR from %DOWNLOAD_URL%
        @exit /B 1
    )
)

@SET MAVEN_JAVA_EXE="%JAVA_HOME%\bin\java.exe"
@IF NOT EXIST %MAVEN_JAVA_EXE% (
    @IF "%JAVA_HOME%"=="" (
        @SET MAVEN_JAVA_EXE=java
    ) ELSE (
        @echo Error: JAVA_HOME is not defined correctly.
        @echo   Cannot find %MAVEN_JAVA_EXE%
        @goto error
    )
)

@SET MAVEN_CMD_LINE_ARGS=%*

@%MAVEN_JAVA_EXE% %JVM_CONFIG_MAVEN_PROPS% %MAVEN_OPTS% %MAVEN_DEBUG_OPTS% ^
  -classpath %WRAPPER_JAR% ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  %WRAPPER_LAUNCHER% %MAVEN_CONFIG% %*

@IF %ERRORLEVEL% NEQ 0 goto error
@goto end

:error
@SET ERROR_CODE=%ERRORLEVEL%

:end
@ENDLOCAL & @SET ERROR_CODE=%ERROR_CODE%

@CMD /C EXIT /B %ERROR_CODE%
