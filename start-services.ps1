# =========================================================================
#  RevPay - Distributed Payment System Startup Orchestrator
# =========================================================================

Clear-Host
$ErrorActionPreference = "Stop"

Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host "         REVPAY - DISTRIBUTED PAYMENT SYSTEM ORCHESTRATOR         " -ForegroundColor Cyan
Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host ""

# -- Step 1: Ensure Docker Desktop is Running ------------------------------
Write-Host "[1/4] Checking Docker Status..." -ForegroundColor Yellow

$dockerRunning = $false
try {
    & docker ps > $null 2>&1
    if ($LASTEXITCODE -eq 0) { $dockerRunning = $true }
} catch {}

if (-not $dockerRunning) {
    Write-Host "Docker is not running. Attempting to start Docker Desktop..." -ForegroundColor Yellow

    # Try common install locations for Docker Desktop on Windows
    $dockerDesktopPaths = @(
        "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe",
        "$env:LOCALAPPDATA\Programs\Docker\Docker\Docker Desktop.exe"
    )
    $launched = $false
    foreach ($path in $dockerDesktopPaths) {
        if (Test-Path $path) {
            Start-Process $path
            $launched = $true
            break
        }
    }

    if (-not $launched) {
        Write-Host "Could not find Docker Desktop. Please start it manually and re-run." -ForegroundColor Red
        Read-Host "Press ENTER to exit..."
        Exit 1
    }

    Write-Host "Waiting for Docker daemon to be ready (max 90s)..." -ForegroundColor Cyan
    $timeout = 90
    $elapsed = 0
    while ($elapsed -lt $timeout) {
        Start-Sleep -Seconds 3
        $elapsed += 3
        try {
            & docker ps > $null 2>&1
            if ($LASTEXITCODE -eq 0) {
                $dockerRunning = $true
                break
            }
        } catch {}
        Write-Host "  Still waiting... ($elapsed/$timeout s)" -ForegroundColor Gray
    }

    if (-not $dockerRunning) {
        Write-Host "Docker did not start within $timeout seconds. Please start it manually." -ForegroundColor Red
        Read-Host "Press ENTER to exit..."
        Exit 1
    }
}

Write-Host "Docker is running!" -ForegroundColor Green
Write-Host ""

# -- Step 2: Spin Up Infrastructure ----------------------------------------
Write-Host "[2/4] Starting Infrastructure Containers..." -ForegroundColor Yellow
& docker-compose up postgres redis zookeeper kafka kafka-ui -d

if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to start docker-compose infrastructure." -ForegroundColor Red
    Read-Host "Press ENTER to exit..."
    Exit 1
}
Write-Host "Infrastructure containers are online!" -ForegroundColor Green
Write-Host "Kafka UI: http://localhost:8090" -ForegroundColor Gray
Write-Host ""

# -- Step 3: Maven Build Option --------------------------------------------
Write-Host "[3/4] Maven Build Step" -ForegroundColor Yellow
$choice = Read-Host "Would you like to build/compile the microservices first? (y/n)"
if ($choice -eq "y" -or $choice -eq "Y") {
    Write-Host "Building project and skipping tests... Please wait." -ForegroundColor Cyan
    & .\mvnw.cmd clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Maven build failed." -ForegroundColor Red
        Read-Host "Press ENTER to exit..."
        Exit 1
    }
    Write-Host "Build completed successfully!" -ForegroundColor Green
} else {
    Write-Host "Starting with existing JARs..." -ForegroundColor Gray
}
Write-Host ""

# -- Step 4: Start Microservices -------------------------------------------
Write-Host "[4/4] Launching Microservices in separate windows..." -ForegroundColor Yellow

$services = @(
    @{ Name = "User Service";        Jar = "user-service/target/user-service-1.0.0-SNAPSHOT.jar";        Port = 8081; Color = "Cyan" },
    @{ Name = "Wallet Service";      Jar = "wallet-service/target/wallet-service-1.0.0-SNAPSHOT.jar";    Port = 8082; Color = "Green" },
    @{ Name = "Transaction Service"; Jar = "transaction-service/target/transaction-service-1.0.0-SNAPSHOT.jar"; Port = 8083; Color = "Magenta" },
    @{ Name = "Notification Service";Jar = "notification-service/target/notification-service-1.0.0-SNAPSHOT.jar";Port = 8084; Color = "Yellow" },
    @{ Name = "API Gateway";         Jar = "api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar";         Port = 8080; Color = "Blue" }
)

foreach ($service in $services) {
    $name = $service.Name
    $jarPath = $service.Jar
    $port = $service.Port
    $color = $service.Color

    if (-not (Test-Path $jarPath)) {
        Write-Host "Warning: Could not find JAR for $name at '$jarPath'." -ForegroundColor Red
        Continue
    }

    Write-Host "Launching $name on port $port..." -ForegroundColor $color
    
    $command = "`$Host.UI.RawUI.WindowTitle='RevPay - $name (Port $port)'; java -jar $jarPath"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $command
    
    Start-Sleep -Seconds 3
}

Write-Host ""
Write-Host "=====================================================================" -ForegroundColor Green
Write-Host "ALL SERVICES DISPATCHED SUCCESSFULLY!" -ForegroundColor Green
Write-Host "=====================================================================" -ForegroundColor Green
Write-Host "Logs are streaming in each of the opened PowerShell windows." -ForegroundColor Gray
Write-Host "API Gateway Port: http://localhost:8080" -ForegroundColor Gray
Write-Host "=====================================================================" -ForegroundColor Green
Write-Host ""
Read-Host "Press ENTER to complete..."
