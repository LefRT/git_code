@echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
"%JAVA_HOME%/bin/java.exe" -Dorg.gradle.appname="ZuoYou" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
