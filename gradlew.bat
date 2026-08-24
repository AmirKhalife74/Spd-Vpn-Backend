@rem SPDX-License-Identifier: Apache-2.0
@echo off
setlocal
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME goto findJavaFromJavaHome
set JAVACMD=java.exe
%JAVACMD% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo Java was not found. Set JAVA_HOME or add java to PATH. 1>&2
exit /b 1

:findJavaFromJavaHome
set JAVACMD=%JAVA_HOME%\bin\java.exe
if exist "%JAVACMD%" goto execute
echo JAVA_HOME points to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1

:execute
"%JAVACMD%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
