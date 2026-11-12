param(
    [string]$Device = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir
$SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"

# Help Java CLI tools (sdkmanager, Gradle) use Windows certs, system proxies & IPv6 when available
$ProxyUri = [System.Net.WebRequest]::GetSystemWebProxy().GetProxy([Uri]"https://dl.google.com")
$ProxyArgs = ""
if ($ProxyUri -and $ProxyUri.Host -and ($ProxyUri.Host -ne "dl.google.com")) {
    Write-Host "    Detected Windows HTTP proxy: $($ProxyUri.Host):$($ProxyUri.Port)" -ForegroundColor Gray
    $ProxyArgs = " -Dhttp.proxyHost=$($ProxyUri.Host) -Dhttp.proxyPort=$($ProxyUri.Port) -Dhttps.proxyHost=$($ProxyUri.Host) -Dhttps.proxyPort=$($ProxyUri.Port)"
}
$StackFlag = "-Djava.net.preferIPv6Addresses=true"
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT $StackFlag -Djava.net.useSystemProxies=true$ProxyArgs"

function Test-UrlExists {
    param([string]$Url)
    try {
        $resp = Invoke-WebRequest -Uri $Url -Method Get -UseBasicParsing -TimeoutSec 12
        if ($resp.StatusCode -lt 200 -or $resp.StatusCode -ge 400) { return $false }
        $body = $resp.Content
        if ($body -is [byte[]]) {
            $body = [System.Text.Encoding]::UTF8.GetString($body)
        }
        $ok = ($body -match '<project|<metadata')
        if ($ok) {
            Write-Host "    [200 XML] $Url ($($body.Length) bytes)" -ForegroundColor DarkGray
        }
        return $ok
    } catch {
        return $false
    }
}

function Get-StableMavenCandidates {
    param([string]$MetadataUrl)
    try {
        [xml]$xml = (Invoke-WebRequest -Uri $MetadataUrl -UseBasicParsing -TimeoutSec 15).Content
        $stable = @($xml.metadata.versioning.versions.version) |
            Where-Object { $_ -and ($_ -notmatch 'alpha|beta|rc|dev|snapshot|M\d') } |
            Sort-Object {
                $parts = ($_ -split '\.') | ForEach-Object { [int]($_ -replace '[^0-9]', '') }
                while ($parts.Count -lt 4) { $parts += 0 }
                "{0:D6}.{1:D6}.{2:D6}.{3:D6}" -f $parts[0], $parts[1], $parts[2], $parts[3]
            }
        [array]::Reverse($stable)
        return $stable
    } catch {
        return @()
    }
}

function Get-LatestStableMavenVersion {
    param(
        [string]$MetadataUrl,
        [string]$MarkerPomBaseUrl = $null,
        [string]$MarkerArtifactId = $null,
        [string]$CorePomBaseUrl = $null,
        [string]$CoreArtifactId = $null
    )
    $candidates = Get-StableMavenCandidates -MetadataUrl $MetadataUrl
    if ($candidates.Count -eq 0) { return $null }
    foreach ($cand in $candidates) {
        if ($MarkerPomBaseUrl -and $MarkerArtifactId) {
            $markerPom = "$MarkerPomBaseUrl/$cand/$MarkerArtifactId-$cand.pom"
            if (-not (Test-UrlExists $markerPom)) { continue }
            if ($CorePomBaseUrl -and $CoreArtifactId) {
                $corePom = "$CorePomBaseUrl/$cand/$CoreArtifactId-$cand.pom"
                if (-not (Test-UrlExists $corePom)) { continue }
            }
            return $cand
        } else {
            return $cand
        }
    }
    return $null
}

function Get-ArchiveUrlFromRepoXml {
    param(
        [xml]$RepoXml,
        [string]$XPathQuery
    )
    $nodes = @($RepoXml.SelectNodes($XPathQuery))
    if ($nodes.Count -eq 0) { return $null }
    foreach ($pkg in $nodes) {
        $winArchive = $pkg.archives.archive | Where-Object { $_.'host-os' -eq "windows" } | Select-Object -First 1
        if ($winArchive) {
            $chosenArchive = $winArchive
        } else {
            $chosenArchive = $pkg.archives.archive | Select-Object -First 1
        }
        $url = $chosenArchive.complete.url
        if ($url) {
            if ($url -match '^https?://') { return $url }
            return "https://dl.google.com/android/repository/$url"
        }
    }
    return $null
}

function Install-SdkPackageDirect {
    param(
        [xml]$RepoXml,
        [string]$PkgPath,
        [string]$DestDir
    )
    $url = Get-ArchiveUrlFromRepoXml -RepoXml $RepoXml -XPathQuery "//remotePackage[@path='$PkgPath']"
    if (-not $url) { throw "Package $PkgPath not found in repository2-3.xml" }
    $safeName = $PkgPath -replace '[^a-zA-Z0-9]', '_'
    $zip = Join-Path $env:TEMP "sdk_$safeName.zip"
    $ext = Join-Path $env:TEMP "sdk_ext_$safeName"
    if (Test-Path $ext) { Remove-Item $ext -Recurse -Force }
    Write-Host "    Downloading $url..."
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $ext -Force
    Remove-Item $zip -Force
    $inner = Get-ChildItem $ext -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Path (Split-Path $DestDir) -Force | Out-Null
    if (Test-Path $DestDir) { Remove-Item $DestDir -Recurse -Force }
    if ($inner) {
        Move-Item -Path $inner.FullName -Destination $DestDir
    } else {
        Move-Item -Path $ext -Destination $DestDir
    }
    if (Test-Path $ext) { Remove-Item $ext -Recurse -Force }
}

if (-not $SkipBuild) {
Write-Host "==> 1. Verifying live plugin & dependency versions on Google Maven / Maven Central..." -ForegroundColor Cyan
$TomlPath = Join-Path $ProjectDir "gradle\libs.versions.toml"
$TomlText = Get-Content $TomlPath -Raw

$LiveAgp = Get-LatestStableMavenVersion `
    -MetadataUrl "https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml" `
    -MarkerPomBaseUrl "https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin" `
    -MarkerArtifactId "com.android.application.gradle.plugin" `
    -CorePomBaseUrl "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle" `
    -CoreArtifactId "gradle"

if (-not $LiveAgp) {
    throw "Cannot reach Google Maven (dl.google.com). Check your internet/VPN/firewall and retry."
}
Write-Host "    Verified AGP on Google Maven: $LiveAgp" -ForegroundColor Gray
if ($TomlText -match '(?m)^agp = "([^"]+)"' -and $Matches[1] -ne $LiveAgp) {
    Write-Host "    Syncing agp -> $LiveAgp" -ForegroundColor Yellow
    $TomlText = $TomlText -replace '(?m)^agp = "[^"]+"', "agp = `"$LiveAgp`""
}

$Checks = @(
    @{ Key = "kotlin"; Url = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/plugin/compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/maven-metadata.xml" },
    @{ Key = "okhttp"; Url = "https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml" },
    @{ Key = "kotlinxSerialization"; Url = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml" },
    @{ Key = "coil3"; Url = "https://repo.maven.apache.org/maven2/io/coil-kt/coil3/coil-compose/maven-metadata.xml" },
    @{ Key = "composeBom"; Url = "https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml" }
)

foreach ($item in $Checks) {
    $liveVer = Get-LatestStableMavenVersion -MetadataUrl $item.Url
    if (-not $liveVer) { continue }
    $key = $item.Key
    if ($TomlText -match "(?m)^$key = `"([^`"]+)`"") {
        $curr = $Matches[1]
        Write-Host "    $key`: current=$curr, latest=$liveVer" -ForegroundColor Gray
        if ($curr -ne $liveVer) {
            Write-Host "    Syncing $key -> $liveVer" -ForegroundColor Yellow
            $TomlText = $TomlText -replace "(?m)^$key = `"[^`"]+`"", "$key = `"$liveVer`""
        }
    }
}
Set-Content -Path $TomlPath -Value $TomlText -Encoding UTF8

Write-Host "    Fetching official Android SDK repository manifest..." -ForegroundColor Gray
$RepoXml = $null
foreach ($repoUrl in @(
    "https://dl.google.com/android/repository/repository2-4.xml",
    "https://dl.google.com/android/repository/repository2-3.xml"
)) {
    try {
        [xml]$candidate = (Invoke-WebRequest -Uri $repoUrl -UseBasicParsing -TimeoutSec 20).Content
        if ($candidate) {
            $RepoXml = $candidate
            Write-Host "    Loaded manifest: $repoUrl" -ForegroundColor Gray
            break
        }
    } catch { }
}
if (-not $RepoXml) {
    throw "Could not download Android SDK repository manifest from dl.google.com."
}

$AllPlatformPaths = @($RepoXml.SelectNodes("//remotePackage[starts-with(@path, 'platforms;android-')]") |
    ForEach-Object { $_.GetAttribute("path") } |
    Sort-Object -Unique)
$RecentPlatforms = @($AllPlatformPaths | Where-Object { $_ -match '^platforms;android-(3[5-9]|4\d|CANARY|canary)' })
Write-Host ("    Available recent SDK platforms: " + ($RecentPlatforms -join ", ")) -ForegroundColor Gray

$NumericPlatforms = @($AllPlatformPaths | Where-Object { $_ -match '^platforms;android-(\d+)(?:\.(\d+))?$' } |
    Sort-Object {
        $null = $_ -match '^platforms;android-(\d+)(?:\.(\d+))?$'
        $maj = [int]$Matches[1]
        $min = 0
        if ($Matches[2]) { $min = [int]$Matches[2] }
        "{0:D4}.{1:D4}" -f $maj, $min
    })
if ($NumericPlatforms.Count -gt 0) {
    $BestPlatformPkg = $NumericPlatforms[-1]
    $TargetPlatform = $BestPlatformPkg -replace 'platforms;', ''
} else {
    $TargetPlatform = "android-36"
}
$null = $TargetPlatform -match '^android-(\d+)(?:\.(\d+))?$'
$TargetApiInt = [int]$Matches[1]
$TargetMinorInt = 0
if ($Matches[2]) { $TargetMinorInt = [int]$Matches[2] }

$BtNodes = @($RepoXml.SelectNodes("//remotePackage[starts-with(@path, 'build-tools;')]")) |
    Where-Object { ($null -eq $_.channelRef) -or ($_.channelRef.ref -eq 'channel-0') }
$BestBtPath = ($BtNodes | ForEach-Object { $_.GetAttribute("path") } |
    Where-Object { $_ -notmatch 'rc|alpha|beta' } |
    Sort-Object {
        $ver = $_ -replace 'build-tools;', ''
        $p = ($ver -split '\.') | ForEach-Object { [int]($_ -replace '[^0-9]', '') }
        while ($p.Count -lt 3) { $p += 0 }
        "{0:D4}.{1:D4}.{2:D4}" -f $p[0], $p[1], $p[2]
    } | Select-Object -Last 1)
if (-not $BestBtPath) { $BestBtPath = "build-tools;$TargetApiInt.0.0" }
$BestBtVer = $BestBtPath -replace '^build-tools;', ''
Write-Host "    Using highest stable SDK platform: $TargetPlatform (Build-Tools $BestBtVer)" -ForegroundColor Gray

$AppGradlePath = Join-Path $ProjectDir "app\build.gradle.kts"
$AppGradle = Get-Content $AppGradlePath -Raw
$AppGradle = $AppGradle -replace 'buildToolsVersion = "[^"]+"', "buildToolsVersion = `"$BestBtVer`""
$AppGradle = $AppGradle -replace 'version = release\(\d+\)', "version = release($TargetApiInt)"
$AppGradle = $AppGradle -replace 'minorApiLevel = \d+', "minorApiLevel = $TargetMinorInt"
$AppGradle = $AppGradle -replace 'targetSdk = \d+', "targetSdk = $TargetApiInt"
Set-Content -Path $AppGradlePath -Value $AppGradle -Encoding UTF8

Write-Host "==> 2. Setting up Android SDK ($TargetPlatform)..." -ForegroundColor Cyan
$SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path $SdkRoot)) {
    New-Item -ItemType Directory -Path $SdkRoot -Force | Out-Null
}
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

$EscapedSdk = $SdkRoot.Replace("\", "\\")
Set-Content -Path (Join-Path $ProjectDir "local.properties") -Value "sdk.dir=$EscapedSdk" -Encoding ASCII

$PlatformDir = Join-Path $SdkRoot "platforms\$TargetPlatform"
$PlatformJar = Join-Path $PlatformDir "android.jar"
$HasPlatformXml = Test-Path (Join-Path $PlatformDir "package.xml")
$HasBtXml = Test-Path (Join-Path $SdkRoot "build-tools\$BestBtVer\package.xml")

if (-not ((Test-Path $PlatformJar) -and $HasPlatformXml -and $HasBtXml)) {
    $SdkManagerBat = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    $AndroidCliExe = Join-Path $SdkRoot "cmdline-tools\latest\bin\android.exe"
    if (-not ((Test-Path $AndroidCliExe) -or (Test-Path $SdkManagerBat))) {
        $ZipUrl = Get-ArchiveUrlFromRepoXml -RepoXml $RepoXml -XPathQuery "//remotePackage[@path='cmdline-tools;latest']"
        if (-not $ZipUrl) {
            $allCmd = @($RepoXml.SelectNodes("//remotePackage[starts-with(@path, 'cmdline-tools;')]"))
            $bestNode = $allCmd | Sort-Object {
                $maj = 0; if ($_.revision.major) { $maj = [int]$_.revision.major }
                $min = 0; if ($_.revision.minor) { $min = [int]$_.revision.minor }
                $mic = 0; if ($_.revision.micro) { $mic = [int]$_.revision.micro }
                "{0:D4}.{1:D4}.{2:D4}" -f $maj, $min, $mic
            } | Select-Object -Last 1
            $winArc = $bestNode.archives.archive | Where-Object { $_.'host-os' -eq "windows" } | Select-Object -First 1
            if (-not $winArc) { $winArc = $bestNode.archives.archive | Select-Object -First 1 }
            $u = $winArc.complete.url
            if ($u -match '^https?://') {
                $ZipUrl = $u
            } else {
                $ZipUrl = "https://dl.google.com/android/repository/$u"
            }
        }
        $TmpZip = Join-Path $env:TEMP "android-cmdline-tools.zip"
        $TmpExtract = Join-Path $env:TEMP "android-cmdline-extract"
        if (Test-Path $TmpExtract) { Remove-Item $TmpExtract -Recurse -Force }
        Write-Host "    Downloading Command-Line Tools: $ZipUrl"
        Invoke-WebRequest -Uri $ZipUrl -OutFile $TmpZip -UseBasicParsing
        Expand-Archive -Path $TmpZip -DestinationPath $TmpExtract -Force
        Remove-Item $TmpZip -Force

        $ExtractedRoot = Get-ChildItem $TmpExtract -Directory | Select-Object -First 1
        if (-not $ExtractedRoot) { throw "Unexpected archive layout in $ZipUrl" }

        $LatestDest = Join-Path $SdkRoot "cmdline-tools\latest"
        New-Item -ItemType Directory -Path (Split-Path $LatestDest) -Force | Out-Null
        if (Test-Path $LatestDest) { Remove-Item $LatestDest -Recurse -Force }
        Move-Item -Path $ExtractedRoot.FullName -Destination $LatestDest
        Remove-Item $TmpExtract -Recurse -Force
    }

    if ((Test-Path $PlatformDir) -and (-not $HasPlatformXml)) {
        Write-Host "    Removing unregistered $TargetPlatform (missing package.xml) so Android CLI can install & register it cleanly..." -ForegroundColor Yellow
        Remove-Item $PlatformDir -Recurse -Force
    }

    $PkgsToInstall = @()
    if (-not (Test-Path (Join-Path $SdkRoot "platform-tools\package.xml"))) { $PkgsToInstall += "platform-tools" }
    if (-not ((Test-Path $PlatformJar) -and $HasPlatformXml)) { $PkgsToInstall += "platforms/$TargetPlatform" }
    if (-not $HasBtXml) { $PkgsToInstall += "build-tools/$BestBtVer" }

    Write-Host "    Installing missing SDK packages via Android CLI: $($PkgsToInstall -join ', ')..."
    $YesInput = ("y`n" * 30)
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    if (Test-Path $AndroidCliExe) {
        $YesInput | & $AndroidCliExe --sdk="$SdkRoot" sdk install --no-downgrade @PkgsToInstall
    } elseif (Test-Path $SdkManagerBat) {
        $YesInput | & $SdkManagerBat --sdk_root="$SdkRoot" @PkgsToInstall
    } else {
        throw "Neither android.exe nor sdkmanager.bat found under $SdkRoot\cmdline-tools\latest\bin"
    }
    $SdkMgrExit = $LASTEXITCODE
    $ErrorActionPreference = $prevPref

    if ($SdkMgrExit -ne 0 -or (-not (Test-Path $PlatformJar)) -or (-not (Test-Path (Join-Path $PlatformDir "package.xml")))) {
        Write-Host "    Android CLI did not finish cleanly; installing packages via PowerShell Invoke-WebRequest..." -ForegroundColor Yellow
        Install-SdkPackageDirect -RepoXml $RepoXml -PkgPath "platform-tools" -DestDir (Join-Path $SdkRoot "platform-tools")
        Install-SdkPackageDirect -RepoXml $RepoXml -PkgPath "platforms;$TargetPlatform" -DestDir $PlatformDir
        Install-SdkPackageDirect -RepoXml $RepoXml -PkgPath $BestBtPath -DestDir (Join-Path $SdkRoot "build-tools\$BestBtVer")
    }
}

Write-Host "==> 3. Ensuring Gradle 9.6.0 Wrapper..." -ForegroundColor Cyan
$GradlewBat = Join-Path $ProjectDir "gradlew.bat"
$WrapperJar = Join-Path $ProjectDir "gradle\wrapper\gradle-wrapper.jar"
if (-not ((Test-Path $GradlewBat) -and (Test-Path $WrapperJar))) {
    $BootstrapDir = Join-Path $env:USERPROFILE ".gradle\vesper-bootstrap"
    $GradleBin = Join-Path $BootstrapDir "gradle-9.6.0\bin\gradle.bat"
    if (-not (Test-Path $GradleBin)) {
        New-Item -ItemType Directory -Path $BootstrapDir -Force | Out-Null
        $ZipPath = Join-Path $BootstrapDir "gradle-9.6.0-bin.zip"
        Write-Host "    Downloading Gradle 9.6.0..."
        Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-9.6.0-bin.zip" -OutFile $ZipPath
        Expand-Archive -Path $ZipPath -DestinationPath $BootstrapDir -Force
        Remove-Item $ZipPath -Force
    }
    $TmpWrapDir = Join-Path $BootstrapDir "wrapper-tmp"
    if (Test-Path $TmpWrapDir) { Remove-Item $TmpWrapDir -Recurse -Force }
    New-Item -ItemType Directory -Path $TmpWrapDir -Force | Out-Null
    Set-Content -Path (Join-Path $TmpWrapDir "settings.gradle.kts") -Value 'rootProject.name = "tmp"' -Encoding UTF8
    Push-Location $TmpWrapDir
    try {
        & $GradleBin wrapper --gradle-version 9.6.0
        if ($LASTEXITCODE -ne 0) { throw "Failed to bootstrap Gradle wrapper." }
    } finally {
        Pop-Location
    }
    Copy-Item (Join-Path $TmpWrapDir "gradlew.bat") $GradlewBat -Force
    Copy-Item (Join-Path $TmpWrapDir "gradlew") (Join-Path $ProjectDir "gradlew") -Force
    Copy-Item (Join-Path $TmpWrapDir "gradle\wrapper\gradle-wrapper.jar") $WrapperJar -Force
}

Write-Host "==> 4. Diagnosing Java/Gradle HTTP transport & building Debug APK..." -ForegroundColor Cyan
$AgpPomUrl = "https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/$LiveAgp/com.android.application.gradle.plugin-$LiveAgp.pom"
$MavenPomUrl = "https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/5.5.1/okhttp-5.5.1.pom"

Write-Host "    [PS/.NET] DNS for dl.google.com: $((([System.Net.Dns]::GetHostAddresses('dl.google.com')) | ForEach-Object { $_.IPAddressToString }) -join ', ')" -ForegroundColor DarkGray
$InetReg = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
$DefProxy = [System.Net.Http.HttpClient]::DefaultProxy.GetProxy([Uri]$AgpPomUrl)
$EnvProxies = @("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY") | ForEach-Object {
    $v = [Environment]::GetEnvironmentVariable($_)
    if ($v) { "$_=<set>" }
}
Write-Host ("    [Proxy] WinINET ProxyEnable=$($InetReg.ProxyEnable), Server=$($InetReg.ProxyServer), PAC=$([bool]$InetReg.AutoConfigURL), HttpClientDefaultProxyHost=$($DefProxy.Host):$($DefProxy.Port), Env=($($EnvProxies -join ', '))") -ForegroundColor DarkGray
try {
    $psResp = Invoke-WebRequest -Uri $AgpPomUrl -Method Get -UseBasicParsing -TimeoutSec 12
    Write-Host "    [PS/.NET] GET $AgpPomUrl -> HTTP $($psResp.StatusCode) ($($psResp.RawContentLength) bytes, Server=$($psResp.Headers['Server']), Alt-Svc=$($psResp.Headers['Alt-Svc']))" -ForegroundColor DarkGray
} catch {
    Write-Host "    [PS/.NET] Failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

$CurlCmd = Get-Command "curl.exe" -ErrorAction SilentlyContinue
if ($CurlCmd) {
    $prevCurlPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    Write-Host ("    [curl -4] " + (& $CurlCmd.Source -4 -s -o NUL -w "HTTP %{http_code} ip=%{remote_ip} http=%{http_version} size=%{size_download}" --max-time 8 $AgpPomUrl 2>&1)) -ForegroundColor DarkGray
    Write-Host ("    [curl -6] " + (& $CurlCmd.Source -6 -s -o NUL -w "HTTP %{http_code} ip=%{remote_ip} http=%{http_version} size=%{size_download}" --max-time 8 $AgpPomUrl 2>&1)) -ForegroundColor DarkGray
    Write-Host ("    [curl --noproxy * -4] " + (& $CurlCmd.Source --noproxy "*" -4 -s -o NUL -w "HTTP %{http_code} ip=%{remote_ip} size=%{size_download}" --max-time 8 $AgpPomUrl 2>&1)) -ForegroundColor DarkGray
    $ErrorActionPreference = $prevCurlPref
}

$ProbeJavaPath = Join-Path $env:TEMP "VesperHttpProbe.java"
$ProbeJavaCode = @'
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

public class VesperHttpProbe {
    public static void main(String[] args) throws Exception {
        String agpUrl = args[0];
        String mavenUrl = args[1];
        String label = args[2];
        System.out.println("  --- Java Probe [" + label + "] ---");
        try {
            InetAddress[] addrs = InetAddress.getAllByName("dl.google.com");
            System.out.println("    DNS dl.google.com: " + Arrays.toString(addrs));
        } catch (Exception e) {
            System.out.println("    DNS failed: " + e);
        }
        String gradleUa = "Gradle/9.6.0 (Windows 11;10.0;amd64)";
        testHttpClient(agpUrl, HttpClient.Version.HTTP_2, null, false, "HttpClient H2 default");
        testHttpClient(agpUrl, HttpClient.Version.HTTP_1_1, null, false, "HttpClient H1.1 default");
        testHttpClient(agpUrl, HttpClient.Version.HTTP_1_1, gradleUa, false, "HttpClient H1.1 Gradle-UA");
        testHttpClient(agpUrl, HttpClient.Version.HTTP_1_1, gradleUa, true, "HttpClient H1.1 Gradle-UA + max-age=0");
        testUrlConn(agpUrl, null, false, "HttpURLConnection default");
        testUrlConn(agpUrl, gradleUa, true, "HttpURLConnection Gradle-UA + max-age=0");
        testHttpClient(mavenUrl, HttpClient.Version.HTTP_1_1, gradleUa, true, "MavenCentral okhttp POM");
    }

    private static void testHttpClient(String url, HttpClient.Version ver, String ua, boolean noCache, String tag) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .version(ver)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .GET();
            if (ua != null) b.header("User-Agent", ua);
            if (noCache) b.header("Cache-Control", "max-age=0");
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String snippet = r.body() == null ? "" : r.body().replaceAll("\\s+", " ");
            if (snippet.length() > 400) snippet = snippet.substring(0, 400);
            String srv = r.headers().firstValue("server").orElse("-");
            String ct = r.headers().firstValue("content-type").orElse("-");
            System.out.println("    [" + tag + "] -> HTTP " + r.statusCode() + " (" + r.version() + ", Server=" + srv + ", CT=" + ct + ") body=" + snippet);
        } catch (Exception e) {
            System.out.println("    [" + tag + "] -> ERR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void testUrlConn(String url, String ua, boolean noCache, String tag) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            if (noCache) conn.setRequestProperty("Cache-Control", "max-age=0");
            int code = conn.getResponseCode();
            InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (stream != null) {
                byte[] buf = stream.readNBytes(512);
                body = new String(buf, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
                if (body.length() > 400) body = body.substring(0, 400);
            }
            System.out.println("    [" + tag + "] -> HTTP " + code + " (" + conn.getResponseMessage() + ", Server=" + conn.getHeaderField("Server") + ") body=" + body);
        } catch (Exception e) {
            System.out.println("    [" + tag + "] -> ERR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
'@
Set-Content -Path $ProbeJavaPath -Value $ProbeJavaCode -Encoding UTF8

$SavedJavaToolOpts = $env:JAVA_TOOL_OPTIONS
$prevPrefProbe = $ErrorActionPreference
$ErrorActionPreference = "Continue"

$env:JAVA_TOOL_OPTIONS = $null
$ProbeCleanOut = @(& java $ProbeJavaPath $AgpPomUrl $MavenPomUrl "CleanJVM (dual-stack)" 2>&1 | ForEach-Object { "$_" })
$ProbeCleanOut | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }

$env:JAVA_TOOL_OPTIONS = "-Djava.net.preferIPv6Addresses=true -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
$ProbeIPv6Out = @(& java $ProbeJavaPath $AgpPomUrl $MavenPomUrl "PreferIPv6 + WinRoot" 2>&1 | ForEach-Object { "$_" })
$ProbeIPv6Out | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }

$env:JAVA_TOOL_OPTIONS = $SavedJavaToolOpts
$ProbeWinOut = @(& java $ProbeJavaPath $AgpPomUrl $MavenPomUrl "WinFlags (IPv4+SystemProxies+WinRoot)" 2>&1 | ForEach-Object { "$_" })
$ProbeWinOut | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }

$ErrorActionPreference = $prevPrefProbe
Remove-Item $ProbeJavaPath -ErrorAction SilentlyContinue

# Decide optimal JVM options and cache flags based on Java probe
$CleanH1Ok = [bool]($ProbeCleanOut | Select-String "H1\.1 Gradle-UA\] -> HTTP 200|HttpURLConnection default\] -> HTTP 200")
$IPv6H1Ok  = [bool]($ProbeIPv6Out  | Select-String "H1\.1 Gradle-UA\] -> HTTP 200|HttpURLConnection default\] -> HTTP 200")
$WinH1Ok   = [bool]($ProbeWinOut   | Select-String "H1\.1 Gradle-UA\] -> HTTP 200|HttpURLConnection default\] -> HTTP 200")
$MaxAgeOk  = [bool](($ProbeCleanOut + $ProbeIPv6Out + $ProbeWinOut) | Select-String "max-age=0\] -> HTTP 200")

$LogPath = Join-Path $ProjectDir "gradle-build.log"
$ExtraGradleArgs = @("--no-daemon", "--debug")
if ($MaxAgeOk -or (-not ($CleanH1Ok -or $IPv6H1Ok -or $WinH1Ok))) {
    $ExtraGradleArgs += "--refresh-dependencies"
} elseif (-not (Test-Path (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"))) {
    Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2") -Directory -Filter "metadata-*" -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}
if ($IPv6H1Ok) {
    Write-Host "    Java probe passed with PreferIPv6! Configuring Gradle for IPv6..." -ForegroundColor Green
    $env:JAVA_TOOL_OPTIONS = "-Djava.net.preferIPv6Addresses=true -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
    $ExtraGradleArgs += "-Djava.net.preferIPv6Addresses=true"
} elseif ($CleanH1Ok -and (-not $WinH1Ok)) {
    Write-Host "    Java probe passed in CleanJVM mode! Using clean dual-stack JVM defaults for Gradle." -ForegroundColor Green
    $env:JAVA_TOOL_OPTIONS = $null
} elseif ($WinH1Ok) {
    Write-Host "    Java probe passed with WinFlags; keeping WINDOWS-ROOT + system proxy flags." -ForegroundColor Green
    if ($ProxyUri -and $ProxyUri.Host -and ($ProxyUri.Host -ne "dl.google.com")) {
        $ExtraGradleArgs += "-Dhttps.proxyHost=$($ProxyUri.Host)"
        $ExtraGradleArgs += "-Dhttps.proxyPort=$($ProxyUri.Port)"
        $ExtraGradleArgs += "-Dhttp.proxyHost=$($ProxyUri.Host)"
        $ExtraGradleArgs += "-Dhttp.proxyPort=$($ProxyUri.Port)"
    }
} else {
    Write-Host "    Warning: Java HTTP/1.1 did not receive 200 in any mode; attempting Gradle build anyway to capture full logs..." -ForegroundColor Yellow
}

$prevPrefGradle = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $GradlewBat --stop | Out-Null
& $GradlewBat testDebugUnitTest assembleDebug @ExtraGradleArgs *> $LogPath
$GradleExit = $LASTEXITCODE
$ErrorActionPreference = $prevPrefGradle

if ($GradleExit -ne 0) {
    Write-Host "    Gradle failed! Error/failure summary from gradle-build.log:" -ForegroundColor Red
    $errLines = @(Select-String -Path $LogPath -Pattern "\[ERROR\]|FAILURE:|What went wrong:|e:\s+file:|>\s+Task\s+:|Execution failed|FAILED|Exception:")
    if ($errLines.Count -eq 0) {
        $errLines = @(Select-String -Path $LogPath -Pattern "Resource missing|Could not")
    }
    $errLines |
        Select-Object -Last 40 |
        ForEach-Object { Write-Host ("    " + $_.Line) -ForegroundColor Yellow }
    throw "Gradle build failed. Full log saved to: $LogPath"
}
} # end if (-not $SkipBuild)

$ApkPath = Join-Path $ProjectDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $ApkPath)) {
    throw "APK build did not produce $ApkPath"
}
Write-Host "==> APK ready: $ApkPath" -ForegroundColor Green

Write-Host "==> 5. Checking for connected Android device via adb (USB / Wi-Fi)..." -ForegroundColor Cyan
$AdbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($AdbCmd) {
    $AdbExe = $AdbCmd.Source
} elseif (Test-Path (Join-Path $SdkRoot "platform-tools\adb.exe")) {
    $AdbExe = Join-Path $SdkRoot "platform-tools\adb.exe"
} else {
    $AdbExe = $null
}

if ($AdbExe) {
    $prevAdbPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"

    if ($Device) {
        Write-Host "    Connecting to Wi-Fi ADB target $Device..." -ForegroundColor Cyan
        & $AdbExe connect $Device
    }

    $Devices = @((& $AdbExe devices) | Select-String '^\S+\s+device$')
    if ($Devices.Count -eq 0) {
        $mdnsOut = @(& $AdbExe mdns services 2>$null | ForEach-Object { "$_" })
        $tlsTargets = @($mdnsOut | Where-Object { $_ -match '_adb-tls-connect\._tcp' } | ForEach-Object {
            if ($_ -match '(\d{1,3}(?:\.\d{1,3}){3}:\d+)') { $Matches[1] }
        } | Select-Object -Unique)
        foreach ($t in $tlsTargets) {
            Write-Host "    Discovered paired Wireless Debugging endpoint $t via mDNS; connecting..." -ForegroundColor Cyan
            & $AdbExe connect $t
        }
        if ($tlsTargets.Count -gt 0) {
            $Devices = @((& $AdbExe devices) | Select-String '^\S+\s+device$')
        }
    }

    if ($Devices.Count -gt 0) {
        foreach ($d in $Devices) {
            $serial = ($d.Line -split '\s+')[0]
            Write-Host "    Installing Vesper onto $serial..." -ForegroundColor Cyan
            & $AdbExe -s $serial install -r $ApkPath
            if ($LASTEXITCODE -eq 0) {
                Write-Host "==> Installed Vesper on $serial! Open Vesper on your phone and add your Gemini API key in Settings." -ForegroundColor Green
            }
        }
    } else {
        Write-Host "    No ready ADB device in 'adb devices' output:" -ForegroundColor Yellow
        (& $AdbExe devices -l 2>&1) | ForEach-Object { Write-Host ("      " + $_) -ForegroundColor DarkGray }
        Write-Host "    If your Android phone uses Wireless Debugging (note: some VPN tunnels can block mDNS auto-discovery):" -ForegroundColor Yellow
        Write-Host "      1) On phone: Settings -> Developer options -> Wireless debugging -> read IP address & port" -ForegroundColor White
        Write-Host "      2) Connect & install without rebuilding:" -ForegroundColor White
        Write-Host "         .\setup-and-install.ps1 -SkipBuild -Device <PHONE_IP>:<PORT>" -ForegroundColor Cyan
        Write-Host "      (If this PC hasn't paired before, run: adb pair <PHONE_IP>:<PAIR_PORT> first)" -ForegroundColor DarkGray
    }
    $ErrorActionPreference = $prevAdbPref
} else {
    Write-Host "    adb not found. Install this APK onto your Android phone:" -ForegroundColor Yellow
    Write-Host "    $ApkPath" -ForegroundColor White
}
