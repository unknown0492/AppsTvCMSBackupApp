@echo off
rem ==========================================================================
rem  AppsTV CMS Backup - installer (Windows 7 and later)
rem
rem  1. Gets administrator rights
rem  2. Checks Java (needs Java 8 update 141+ or newer); downloads and installs
rem     Eclipse Temurin 8 when missing or too old. For PCs without internet,
rem     put OpenJDK8U-jre_x64_windows_*.msi (or _x86-32_) next to this file.
rem  3. Copies the app to C:\AppsTvCMSBackupApp and verifies the copy
rem  4. Asks for the configuration, one value at a time (required values
rem     cannot be skipped; folders, MySQL login and drive are tested)
rem  5. Runs the first backup
rem  6. Checks / creates / re-checks the Windows scheduled task
rem  7. Puts an "AppsTV Backup Manager" shortcut on the desktop
rem ==========================================================================
setlocal EnableExtensions DisableDelayedExpansion
title AppsTV CMS Backup - Installer

rem Installation folder
set "APP=C:\AppsTvCMSBackupApp"
rem Scheduled task name (must match AppsTvCMSBackup.TASK_NAME)
set "TASK=AppsTV CMS Backup"
rem Application jar name
set "JAR=AppsTvCMSBackup.jar"
rem Folder this installer was started from (ends with a backslash)
set "SRC=%~dp0"
rem Temporary files
set "TMPF=%TEMP%\appstv_install_%RANDOM%"

rem ---------------------------------------------------------------- admin
net session >nul 2>&1
if not errorlevel 1 goto :isadmin
echo Requesting administrator rights...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs" >nul 2>&1
if not errorlevel 1 exit /b 0
echo.
echo Could not get administrator rights automatically.
echo Right-click install.bat and choose "Run as administrator".
pause
exit /b 1
:isadmin
cd /d "%SRC%"

echo.
echo ==================================================================
echo   AppsTV CMS Backup - Installation
echo ==================================================================
echo   Installs to: %APP%
echo.

rem ---------------------------------------------------------------- package check
set "MISSING="
if not exist "%SRC%%JAR%" set "MISSING=%JAR%"
if not exist "%SRC%lib\json-simple-1.1.1.jar" set "MISSING=lib\json-simple-1.1.1.jar"
if not exist "%SRC%manage.bat" set "MISSING=manage.bat"
if not defined MISSING goto :pkgok
echo ERROR: %MISSING% is missing next to install.bat.
echo If you opened a ZIP file, extract ALL files to a folder first, then run install.bat from there.
goto :fail
:pkgok

rem ---------------------------------------------------------------- step 1: Java
echo [1/6] Checking Java...
call :findjava
if defined JAVA_EXE goto :javaok
echo   Java 8 update 141 or newer was not found. Installing Eclipse Temurin Java 8...
call :installjava
if errorlevel 1 goto :fail
call :findjava
if defined JAVA_EXE goto :javaok
echo ERROR: Java was installed but could not be found. Restart the PC and run install.bat again.
goto :fail
:javaok
echo   Using Java %JV%: %JAVA_EXE%

rem ---------------------------------------------------------------- step 2: copy files
echo.
echo [2/6] Copying the application to %APP% ...
if /i "%SRC%"=="%APP%\" (
    echo   Installer is already running from %APP% - nothing to copy.
    goto :copied
)
rem Stop a running scheduled backup so the jar is not locked
schtasks /Query /TN "%TASK%" >nul 2>&1
if not errorlevel 1 schtasks /End /TN "%TASK%" >nul 2>&1
if not exist "%APP%\lib" mkdir "%APP%\lib"
if not exist "%APP%\lib" goto :copyfail
copy /y "%SRC%%JAR%" "%APP%\%JAR%" >nul
if errorlevel 1 goto :copyfail
copy /y "%SRC%lib\*.jar" "%APP%\lib\" >nul
if errorlevel 1 goto :copyfail
copy /y "%SRC%manage.bat" "%APP%\manage.bat" >nul
if errorlevel 1 goto :copyfail
if exist "%SRC%AppsTV_CMS_Backup_Setup_Guide.docx" copy /y "%SRC%AppsTV_CMS_Backup_Setup_Guide.docx" "%APP%\" >nul
rem Keep an existing config (re-install / upgrade); otherwise start from the shipped template
if not exist "%APP%\config" if exist "%SRC%config" copy /y "%SRC%config" "%APP%\config" >nul
rem Verify the copy byte for byte
fc /b "%SRC%%JAR%" "%APP%\%JAR%" >nul
if errorlevel 1 goto :copyfail
fc /b "%SRC%lib\json-simple-1.1.1.jar" "%APP%\lib\json-simple-1.1.1.jar" >nul
if errorlevel 1 goto :copyfail
echo   Files copied and verified.
:copied
rem Remember which Java to use (manage.bat reads this)
> "%APP%\java_path.txt" echo %JAVA_EXE%

rem ---------------------------------------------------------------- step 3: configuration
echo.
echo [3/6] Configuration
if not exist "%APP%\config" goto :wizard
"%JAVA_EXE%" -jar "%APP%\%JAR%" --show-config
if errorlevel 1 goto :wizard
echo.
choice /c KC /m "This PC is already configured. Press K to keep this configuration or C to change it"
if errorlevel 2 goto :wizard
goto :configured
:wizard
"%JAVA_EXE%" -jar "%APP%\%JAR%" --configure
if not errorlevel 1 goto :configured
echo.
echo The configuration must be completed before the backup can be installed.
choice /c RQ /m "Press R to retry or Q to quit"
if errorlevel 2 goto :fail
goto :wizard
:configured

rem ---------------------------------------------------------------- step 4: first backup
echo.
echo [4/6] First backup
choice /c YN /m "Run a full backup now? Recommended - it proves everything works (takes a few minutes)"
if errorlevel 2 goto :task
"%JAVA_EXE%" -jar "%APP%\%JAR%" --force
set "RC=%errorlevel%"
echo.
if "%RC%"=="0" echo   First backup completed successfully.
if "%RC%"=="1" echo   First backup completed with warnings - read the WARN lines above.
if "%RC%"=="2" echo   FIRST BACKUP FAILED - read the ERROR lines above. Fix the cause, then use the Backup Manager option 2.
if "%RC%"=="3" echo   Another backup was already running - skipped.
pause

rem ---------------------------------------------------------------- step 5: scheduled task
:task
echo.
echo [5/6] Windows scheduled task
schtasks /Query /TN "%TASK%" >nul 2>&1
if errorlevel 1 (
    echo   The task "%TASK%" does not exist yet - creating it now...
) else (
    echo   The task "%TASK%" already exists - updating it to use this installation...
)
"%JAVA_EXE%" -jar "%APP%\%JAR%" --install-task
call :checktask
if errorlevel 1 goto :taskfail
echo   OK: the task exists and runs %APP%\%JAR%
schtasks /Query /TN "%TASK%" /FO LIST > "%TMPF%_task.txt" 2>nul
type "%TMPF%_task.txt" | findstr /i /c:"Next Run Time" /c:"Status"

rem ---------------------------------------------------------------- step 6: shortcut + old version
echo.
echo [6/6] Finishing
set "LNK=%PUBLIC%\Desktop\AppsTV Backup Manager.lnk"
if not defined PUBLIC set "LNK=%USERPROFILE%\Desktop\AppsTV Backup Manager.lnk"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%LNK%'); $s.TargetPath='%APP%\manage.bat'; $s.WorkingDirectory='%APP%'; $s.Description='Run, configure and schedule the AppsTV CMS backup'; $s.Save()" >nul 2>&1
if exist "%LNK%" (echo   Desktop shortcut "AppsTV Backup Manager" created.) else (echo   Could not create the desktop shortcut - open %APP%\manage.bat instead.)
call :oldversion

echo.
echo ==================================================================
echo   Installation complete.
echo ==================================================================
echo   The backup now runs every day by itself, and 10 minutes after start-up.
echo   To run it manually, change settings or check the schedule, use the
echo   "AppsTV Backup Manager" shortcut on the desktop  (%APP%\manage.bat).
echo.
pause
exit /b 0

:taskfail
echo.
echo ERROR: the scheduled task could not be created or does not point to %APP%.
echo Read the messages above, then use the Backup Manager option 8 to try again.
goto :fail

:copyfail
echo.
echo ERROR: copying to %APP% failed. Close any open backup window or file from that folder and run install.bat again.
goto :fail

:fail
echo.
echo Installation NOT completed.
pause
exit /b 1


rem ==========================================================================
rem  Subroutines
rem ==========================================================================

:findjava
rem Sets JAVA_EXE / JV to the first Java that is 8u141+ (or 9+). Checks PATH, JAVA_HOME and the usual folders.
set "JAVA_EXE="
set "JV="
for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_EXE call :checkjava "%%p"
if defined JAVA_EXE goto :eof
if defined JAVA_HOME call :checkjava "%JAVA_HOME%\bin\java.exe"
if defined JAVA_EXE goto :eof
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Java\*" "%ProgramFiles(x86)%\Eclipse Adoptium\*" "%ProgramFiles(x86)%\Java\*") do if not defined JAVA_EXE call :checkjava "%%~d\bin\java.exe"
goto :eof

:checkjava
rem Tests one java.exe; sets JAVA_EXE and JV when it is good enough
if not exist "%~1" goto :eof
"%~1" -version > "%TMPF%_jv.txt" 2>&1
findstr /i /c:" version " "%TMPF%_jv.txt" > "%TMPF%_jv2.txt"
set "CJV="
for /f usebackq^ tokens^=2^ delims^=^" %%v in ("%TMPF%_jv2.txt") do if not defined CJV set "CJV=%%v"
if not defined CJV goto :eof
set "J1=0"
set "J2=0"
set "J4=0"
for /f "tokens=1-4 delims=._-+" %%a in ("%CJV%") do (
    set "J1=%%a"
    if not "%%b"=="" set "J2=%%b"
    if not "%%d"=="" set "J4=%%d"
)
set /a J1=J1+0 2>nul
set /a J2=J2+0 2>nul
set /a J4=J4+0 2>nul
rem Old scheme "1.8.0_321": major = second number, update = fourth. New scheme "11.0.2": major = first.
if %J1% GEQ 9 goto :javagood
if not %J1%==1 goto :eof
if %J2% GEQ 9 goto :javagood
if not %J2%==8 goto :javaold
if %J4% GEQ 141 goto :javagood
:javaold
echo   Found Java %CJV% at %~1 - too old (needs 8 update 141 or newer).
goto :eof
:javagood
set "JAVA_EXE=%~1"
set "JV=%CJV%"
goto :eof

:installjava
rem Installs Eclipse Temurin 8 JRE: bundled .msi first, otherwise downloads it
set "ARCH=x86"
set "ARCHPAT=x86-32"
if /i "%PROCESSOR_ARCHITECTURE%"=="AMD64" set "ARCH=x64"
if /i "%PROCESSOR_ARCHITEW6432%"=="AMD64" set "ARCH=x64"
if "%ARCH%"=="x64" set "ARCHPAT=x64"
set "MSI="
for %%f in ("%SRC%OpenJDK8U-jre_%ARCHPAT%_windows*.msi" "%SRC%OpenJDK8U-jdk_%ARCHPAT%_windows*.msi") do set "MSI=%%~ff"
if defined MSI (
    echo   Using the Java installer found next to install.bat
    goto :runmsi
)
set "URL=https://api.adoptium.net/v3/installer/latest/8/ga/windows/%ARCH%/jre/hotspot/normal/eclipse?project=jdk"
set "MSI=%TEMP%\temurin8_jre_%ARCH%.msi"
echo   Downloading Java 8 (%ARCH%, about 40 MB) - please wait...
if exist "%MSI%" del /f /q "%MSI%"
rem Method 1: curl (Windows 10 and later)
where curl >nul 2>&1
if not errorlevel 1 curl -L --fail --retry 2 --silent --show-error -o "%MSI%" "%URL%"
call :msiok && goto :runmsi
rem Method 2: PowerShell (enables TLS 1.2 where the system supports it)
powershell -NoProfile -ExecutionPolicy Bypass -Command "try{[Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor 3072}catch{}; (New-Object Net.WebClient).DownloadFile('%URL%','%MSI%')" >nul 2>&1
call :msiok && goto :runmsi
rem Method 3: certutil (uses Internet Explorer's connection settings)
certutil -urlcache -split -f "%URL%" "%MSI%" >nul 2>&1
certutil -urlcache "%URL%" delete >nul 2>&1
call :msiok && goto :runmsi
rem Method 4: BITS
bitsadmin /transfer AppsTvJava /download /priority foreground "%URL%" "%MSI%" >nul 2>&1
call :msiok && goto :runmsi
echo.
echo   ERROR: Java could not be downloaded (no internet, proxy, or old Windows 7 security settings).
echo   Do this instead:
echo     1. On any PC, open  https://adoptium.net/temurin/releases/?version=8
echo     2. Download: Windows, %ARCH%, JRE, .msi
echo     3. Copy the .msi file into the same folder as install.bat
echo     4. Run install.bat again
exit /b 1
:runmsi
echo   Installing Java - a progress window will appear...
msiexec /i "%MSI%" ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith,FeatureJavaHome /qb /norestart /l*v "%TMPF%_java_msi.log"
set "MRC=%errorlevel%"
if "%MRC%"=="3010" set "MRC=0"
if "%MRC%"=="0" (
    echo   Java installed.
    exit /b 0
)
echo   ERROR: the Java installer failed with code %MRC%. Details: %TMPF%_java_msi.log
exit /b 1

:msiok
rem Succeeds when the downloaded MSI exists and is plausibly complete (over 5 MB)
if not exist "%MSI%" exit /b 1
for %%A in ("%MSI%") do if %%~zA LSS 5000000 (
    del /f /q "%MSI%"
    exit /b 1
)
exit /b 0

:checktask
rem Succeeds when the task exists and runs the jar of this installation
schtasks /Query /TN "%TASK%" >nul 2>&1
if errorlevel 1 exit /b 1
schtasks /Query /TN "%TASK%" /XML > "%TMPF%_task.xml" 2>nul
type "%TMPF%_task.xml" | "%SystemRoot%\System32\find.exe" /i "%APP%\%JAR%" >nul
if errorlevel 1 exit /b 1
exit /b 0

:oldversion
rem Looks for other scheduled tasks that run an AppsTvCMSBackup jar (the old v1 app) and offers to delete them
schtasks /Query /FO CSV /V /NH > "%TMPF%_allv.csv" 2>nul
set "LASTOLD="
for /f "usebackq tokens=2 delims=," %%t in (`type "%TMPF%_allv.csv" ^| "%SystemRoot%\System32\find.exe" /i "AppsTvCMSBackup" ^| "%SystemRoot%\System32\find.exe" /v /i "\%TASK%"`) do call :offerdelete %%t
for %%s in ("%ProgramData%\Microsoft\Windows\Start Menu\Programs\Startup" "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup") do (
    if exist "%%~s" dir /b "%%~s" 2>nul | "%SystemRoot%\System32\find.exe" /i "backup" >nul && (
        echo   NOTE: a startup shortcut with "backup" in its name exists in %%~s
        echo         If it starts the OLD backup app, delete it.
    )
)
goto :eof

:offerdelete
rem %1 = quoted task name from schtasks CSV
if "%~1"=="" goto :eof
if /i "%~1"=="TaskName" goto :eof
rem A task with several triggers is listed once per trigger - ask only once
if /i "%~1"=="%LASTOLD%" goto :eof
set "LASTOLD=%~1"
echo   Found another task that runs the backup app: %~1
choice /c YN /m "  Is this the OLD backup app? Delete it"
if errorlevel 2 goto :eof
schtasks /Delete /TN "%~1" /F >nul 2>&1
if errorlevel 1 (echo   Could not delete it - remove it in Task Scheduler.) else (echo   Deleted.)
goto :eof
