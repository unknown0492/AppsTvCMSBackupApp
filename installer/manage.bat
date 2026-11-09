@echo off
rem ==========================================================================
rem  AppsTV CMS Backup - Backup Manager
rem  Run a backup, view or change the configuration, check or change the
rem  Windows scheduled task, and open the log. Lives in C:\AppsTvCMSBackupApp
rem  (installed by install.bat, with a desktop shortcut).
rem ==========================================================================
setlocal EnableExtensions DisableDelayedExpansion
title AppsTV CMS Backup - Manager

rem Folder holding this file, the jar, config and logs (no trailing backslash)
set "APP=%~dp0"
set "APP=%APP:~0,-1%"
rem Application jar
set "JAR=%APP%\AppsTvCMSBackup.jar"
rem Scheduled task name (must match AppsTvCMSBackup.TASK_NAME)
set "TASK=AppsTV CMS Backup"
rem Temporary files
set "TMPF=%TEMP%\appstv_manage_%RANDOM%"

rem ---------------------------------------------------------------- admin (needed for the scheduled task and for writing here)
net session >nul 2>&1
if not errorlevel 1 goto :isadmin
echo Requesting administrator rights...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs" >nul 2>&1
if not errorlevel 1 exit /b 0
echo Could not get administrator rights. Right-click manage.bat and choose "Run as administrator".
pause
exit /b 1
:isadmin
cd /d "%APP%"

if exist "%JAR%" goto :jarok
echo ERROR: %JAR% not found. Run install.bat again.
pause
exit /b 1
:jarok

rem ---------------------------------------------------------------- Java chosen by install.bat, else the one on PATH
set "JAVA_EXE="
if exist "%APP%\java_path.txt" set /p JAVA_EXE=<"%APP%\java_path.txt"
if defined JAVA_EXE if not exist "%JAVA_EXE%" set "JAVA_EXE="
if not defined JAVA_EXE for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%p"
if defined JAVA_EXE goto :menu
echo ERROR: Java was not found. Run install.bat again - it installs Java automatically.
pause
exit /b 1

rem ==========================================================================
:menu
cls
echo ==================================================================
echo   AppsTV CMS Backup - Manager            %APP%
echo ==================================================================
echo.
echo    1. Run backup now          (database once a day, CMS when due)
echo    2. Run FULL backup now     (database and CMS, even if done today)
echo.
echo    3. View configuration and backup status
echo    4. Change configuration    (all questions; Enter keeps a value)
echo    5. Change backup thumb drive / set up a new thumb drive
echo    6. Change daily backup time
echo.
echo    7. Scheduled task status
echo    8. Re-create scheduled task
echo    9. Open the log file
echo.
echo    0. Exit
echo.
set "CH="
set /p "CH=  Choose an option and press Enter: "
if "%CH%"=="1" goto :runnormal
if "%CH%"=="2" goto :runforce
if "%CH%"=="3" goto :view
if "%CH%"=="4" goto :configall
if "%CH%"=="5" goto :configdrive
if "%CH%"=="6" goto :configtime
if "%CH%"=="7" goto :taskstatus
if "%CH%"=="8" goto :taskcreate
if "%CH%"=="9" goto :log
if "%CH%"=="0" exit /b 0
goto :menu

rem ---------------------------------------------------------------- 1 / 2: run
:runnormal
echo.
"%JAVA_EXE%" -jar "%JAR%"
call :result %errorlevel%
goto :back

:runforce
echo.
echo Running a full backup. A large CMS folder can take several minutes...
echo.
"%JAVA_EXE%" -jar "%JAR%" --force
call :result %errorlevel%
goto :back

rem ---------------------------------------------------------------- 3: view
:view
"%JAVA_EXE%" -jar "%JAR%" --show-config
goto :back

rem ---------------------------------------------------------------- 4 / 5 / 6: configure
:configall
"%JAVA_EXE%" -jar "%JAR%" --configure
if errorlevel 1 goto :back
call :installtask
goto :back

:configdrive
"%JAVA_EXE%" -jar "%JAR%" --configure drive
goto :back

:configtime
"%JAVA_EXE%" -jar "%JAR%" --configure schedule
if errorlevel 1 goto :back
call :installtask
goto :back

rem ---------------------------------------------------------------- 7: task status
:taskstatus
echo.
echo ---- Scheduled task "%TASK%" ----
schtasks /Query /TN "%TASK%" >nul 2>&1
if not errorlevel 1 goto :taskexists
echo   NOT INSTALLED - backups will NOT run automatically.
echo.
choice /c YN /m "  Create the scheduled task now"
if errorlevel 2 goto :back
call :installtask
goto :back
:taskexists
schtasks /Query /TN "%TASK%" /FO LIST /V > "%TMPF%_task.txt" 2>nul
type "%TMPF%_task.txt" | findstr /i /c:"Status:" /c:"Next Run Time:" /c:"Last Run Time:" /c:"Last Result:" /c:"Run As User:" /c:"Start Time:"
if errorlevel 1 type "%TMPF%_task.txt"
rem Interpret the last result (the app's exit code)
set "LR="
for /f "tokens=2 delims=:" %%r in ('type "%TMPF%_task.txt" ^| findstr /i /c:"Last Result:"') do if not defined LR set "LR=%%r"
if defined LR set "LR=%LR: =%"
echo.
if "%LR%"=="0" echo   Last run: OK
if "%LR%"=="1" echo   Last run: completed with WARNINGS - open the log (option 9)
if "%LR%"=="2" echo   Last run: a backup FAILED - open the log (option 9)
if "%LR%"=="3" echo   Last run: skipped, another backup was still running
if "%LR%"=="267011" echo   Last run: the task has not run yet
if "%LR%"=="267009" echo   The backup is running right now
rem Make sure the task runs THIS installation
schtasks /Query /TN "%TASK%" /XML > "%TMPF%_task.xml" 2>nul
type "%TMPF%_task.xml" | "%SystemRoot%\System32\find.exe" /i "%JAR%" >nul
if errorlevel 1 (
    echo   WARNING: the task does not point to this folder - use option 8 to fix it.
) else (
    echo   The task runs this installation.
)
goto :back

rem ---------------------------------------------------------------- 8: re-create task
:taskcreate
call :installtask
goto :back

rem ---------------------------------------------------------------- 9: log
:log
set "LOGF="
for /f "delims=" %%f in ('dir /b /o-n "%APP%\logs\backup_*.log" 2^>nul') do if not defined LOGF set "LOGF=%APP%\logs\%%f"
if not defined LOGF (
    echo.
    echo   No log file yet - the backup has not run.
    goto :back
)
start "" notepad "%LOGF%"
goto :menu

rem ---------------------------------------------------------------- shared
:back
echo.
pause
goto :menu

:installtask
rem Creates / replaces the scheduled task, then checks it really exists and runs this jar
echo.
schtasks /Query /TN "%TASK%" >nul 2>&1
if errorlevel 1 (echo Creating the scheduled task...) else (echo Updating the scheduled task...)
"%JAVA_EXE%" -jar "%JAR%" --install-task
schtasks /Query /TN "%TASK%" /XML > "%TMPF%_task.xml" 2>nul
if errorlevel 1 goto :installfail
type "%TMPF%_task.xml" | "%SystemRoot%\System32\find.exe" /i "%JAR%" >nul
if errorlevel 1 goto :installfail
echo   OK: scheduled task is in place.
schtasks /Query /TN "%TASK%" /FO LIST > "%TMPF%_task.txt" 2>nul
type "%TMPF%_task.txt" | findstr /i /c:"Next Run Time"
goto :eof
:installfail
echo   ERROR: the scheduled task could not be created. Read the messages above.
goto :eof

:result
rem %1 = exit code of the app
echo.
if "%~1"=="0" echo   RESULT: OK
if "%~1"=="1" echo   RESULT: completed with WARNINGS - read the WARN lines above
if "%~1"=="2" echo   RESULT: a backup FAILED - read the ERROR lines above
if "%~1"=="3" echo   RESULT: another backup is already running - try again later
goto :eof
