@echo off
NET SESSION >nul 2>&1
IF NOT %ERRORLEVEL% EQU 0 GOTO NONADMIN
IF NOT EXIST pom.xml GOTO WRONGFOLDER
rmdir /S /Q node_modules
rmdir /S /Q target
nvm use 15.11.0
cmd /c npm install
cmd /c gulp
cmd /c mvn package -DskipTests -U -e
GOTO END
:WRONGFOLDER
echo Must be run in plugin folder
GOTO END
:NONADMIN
echo Admin Privs required for this script
:END
pause