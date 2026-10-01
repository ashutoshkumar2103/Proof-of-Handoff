# Runs the HandOffly backend from the repo root, selecting JDK 25 automatically.
# Defaults to MySQL so a plain run never silently uses H2.
#
#   .\run-backend.ps1                # MySQL (create the DB/user first — see README)
#   .\run-backend.ps1 -Profile h2    # H2 only, when you don't want to run MySQL
#
param(
    [string]$Profile = "mysql"
)

function Find-Jdk25 {
    # 1) Current JAVA_HOME, if it already points at a JDK 25 install.
    if ($env:JAVA_HOME -and $env:JAVA_HOME -like "*jdk-25*" -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        return $env:JAVA_HOME
    }
    # 2) Well-known install location.
    if (Test-Path "C:\Program Files\Java\jdk-25\bin\java.exe") {
        return "C:\Program Files\Java\jdk-25"
    }
    # 3) Any jdk-25* under the common Java folders.
    foreach ($root in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Softwares")) {
        if (Test-Path $root) {
            $hit = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -like "jdk-25*" -and (Test-Path "$($_.FullName)\bin\java.exe") } |
                   Select-Object -First 1
            if ($hit) { return $hit.FullName }
        }
    }
    return $null
}

$jdk = Find-Jdk25
if (-not $jdk) {
    Write-Host "JDK 25 not found. Install it, or set JAVA_HOME to a JDK 25 and retry." -ForegroundColor Red
    exit 1
}

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Write-Host "Using JAVA_HOME = $jdk" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Normalize: anything that isn't explicitly "h2" runs against MySQL.
$activeProfile = if ($Profile -eq "h2") { "h2" } else { "mysql" }
Write-Host "Starting backend (profile '$activeProfile') on http://localhost:8080 ..." -ForegroundColor Cyan
& mvn -f "$scriptDir\backend\pom.xml" spring-boot:run "-Dspring-boot.run.profiles=$activeProfile"
