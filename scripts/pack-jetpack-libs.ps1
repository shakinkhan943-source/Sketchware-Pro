# Builds a Jetpack dependency ZIP for Sketchware Pro's shared store
# (.sketchware/libs/JetpackLibs). The store derives dependency edges, DEX and the
# resource package name itself, so this script only has to deliver one directory per
# artifact containing the contents of its AAR, plus a maven-coordinate file (which makes
# group/artifact/version exact and lets a newer runtime replace the app's built-in copy).
#
#   .\scripts\pack-jetpack-libs.ps1 -From .\compose-libs          # a folder of *.aar / *.jar
#   .\scripts\pack-jetpack-libs.ps1 -Coordinate androidx.compose.ui:ui:1.7.8 ...
#   .\scripts\pack-jetpack-libs.ps1 -From .\compose-libs -Out jetpack-libs.zip
#
# With -Coordinate the script downloads from Google Maven and then Maven Central. Use
# Gradle to decide *which* artifacts belong in the set (it resolves transitives for you);
# this script only normalizes them into the store layout.

param(
    [string[]]$Coordinate = @(),
    [string]$From = "",
    [string]$Out = "jetpack-libs.zip",
    [string]$Stage = ".\.jetpack-stage",
    [string[]]$Mirror = @(
        "https://dl.google.com/dl/android/maven2",
        "https://repo1.maven.org/maven2"
    )
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ArtifactId([string]$groupId, [string]$artifactId) {
    # androidx.compose.ui:ui-android  ->  androidx_compose_ui_ui_android
    # Same spelling Sketchware's own ids use, so an existing ZIP can be repacked unchanged.
    return ("{0}_{1}" -f $groupId, $artifactId).Replace(".", "_").Replace("-", "_")
}

function Copy-DirectoryContents([string]$source, [string]$target) {
    if (-not (Test-Path $source)) { return }
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Get-ChildItem -Path $source -Recurse | ForEach-Object {
        if ($_.PSIsContainer) { return }
        $relative = $_.FullName.Substring($source.Length).TrimStart('\', '/')
        $destination = Join-Path $target $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
        Copy-Item $_.FullName $destination -Force
    }
}

function Import-Archive([string]$archive, [string]$artifactDirectory) {
    # An AAR is already a ZIP of the layout the store wants; a JAR becomes classes.jar.
    New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
    $extension = [System.IO.Path]::GetExtension($archive).ToLowerInvariant()
    if ($extension -eq ".aar") {
        $expanded = Join-Path $Stage ("expand-" + [System.IO.Path]::GetRandomFileName())
        [System.IO.Compression.ZipFile]::ExtractToDirectory($archive, $expanded)
        foreach ($name in @("classes.jar", "AndroidManifest.xml", "proguard.txt")) {
            $found = Join-Path $expanded $name
            if (Test-Path $found) { Copy-Item $found (Join-Path $artifactDirectory $name) -Force }
        }
        # AARs carry their R-class resources and, occasionally, extra JARs.
        Copy-DirectoryContents (Join-Path $expanded "res") (Join-Path $artifactDirectory "res")
        Copy-DirectoryContents (Join-Path $expanded "assets") (Join-Path $artifactDirectory "assets")
        Copy-DirectoryContents (Join-Path $expanded "libs") (Join-Path $artifactDirectory "libs")
        if (-not (Test-Path (Join-Path $artifactDirectory "classes.jar"))) {
            # Some artifacts ship only libs/*.jar; merge them so classes.jar always exists.
            $extra = Get-ChildItem -Path (Join-Path $artifactDirectory "libs") -Filter *.jar -ErrorAction SilentlyContinue
            if ($extra) { Copy-Item $extra[0].FullName (Join-Path $artifactDirectory "classes.jar") -Force }
        }
        Remove-Item $expanded -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Copy-Item $archive (Join-Path $artifactDirectory "classes.jar") -Force
    }
    # classes.dex is intentionally absent: Sketchware generates it on the device with D8.
}

function Resolve-Remote([string]$coordinate) {
    $parts = $coordinate.Split(":")
    if ($parts.Length -ne 3) { throw "Expected groupId:artifactId:version, got $coordinate" }
    $groupId, $artifactId, $version = $parts
    $base = ("{0}/{1}/{2}/{3}/{2}-{3}" -f $Mirror[0], ($groupId.Replace(".", "/")), $artifactId, $version)
    foreach ($mirror in $Mirror) {
        $root = "{0}/{1}/{2}/{3}/{2}-{3}" -f $mirror.TrimEnd('/'), ($groupId.Replace(".", "/")), $artifactId, $version
        foreach ($extension in @("aar", "jar")) {
            $url = "$root.$extension"
            try {
                $target = Join-Path $Stage ("download." + $extension)
                Invoke-WebRequest -Uri $url -OutFile $target -UseBasicParsing -ErrorAction Stop
                return @{ Path = $target; GroupId = $groupId; ArtifactId = $artifactId; Version = $version }
            } catch {
                Remove-Item $target -Force -ErrorAction SilentlyContinue
            }
        }
    }
    throw "Not found on the configured mirrors: $coordinate"
}

if (-not $Coordinate -and -not $From) {
    throw "Pass -From <folder of .aar/.jar> or at least one -Coordinate group:artifact:version"
}

if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

$imported = @()

function Get-MavenIdentity([string]$path) {
    # Recovers groupId:artifactId:version from either the file name (Maven layout:
    # ui-android-1.7.8.aar) or, when the file sits in a Gradle/Maven cache, from the
    # neighbouring .pom. A folder of loose AARs can still be packed - the identity only
    # enables the newer-runtime override, it is not required to build.
    $item = Get-Item $path
    $stem = $item.BaseName
    $artifact = $stem
    $version = "0"
    if ($stem -match '^(.*)-(\d.*)$') { $artifact = $matches[1]; $version = $matches[2] }

    $groupId = ""
    $pom = Join-Path $item.DirectoryName ($stem + ".pom")
    if (Test-Path $pom) {
        try {
            [xml]$xml = Get-Content $pom -Raw
            $groupId = $xml.project.groupId
            if (-not $groupId) { $groupId = $xml.project.parent.groupId }
            if ($xml.project.artifactId) { $artifact = $xml.project.artifactId }
            if ($xml.project.version) { $version = $xml.project.version }
        } catch { }
    }
    if (-not $groupId -and $item.DirectoryName -match '[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\][^/\\]+$') {
        # .../<groupId>/<artifactId>/<version>/<sha1>/<file>: Gradle's module cache layout.
        $groupId = $matches[1]
        $artifact = $matches[2]
        $version = $matches[3]
    }
    return [pscustomobject]@{ GroupId = $groupId; ArtifactId = $artifact; Version = $version }
}

if ($From) {
    Get-ChildItem -Path $From -Recurse -Include *.aar, *.jar | ForEach-Object {
        $identity = Get-MavenIdentity $_.FullName
        $id = if ($identity.GroupId) {
            Get-ArtifactId $identity.GroupId $identity.ArtifactId
        } else {
            $identity.ArtifactId.Replace(".", "_").Replace("-", "_")
        }
        $directory = Join-Path $Stage $id
        Import-Archive $_.FullName $directory
        if ($identity.GroupId) {
            Set-Content -Path (Join-Path $directory "maven-coordinate") `
                -Value ("{0}:{1}:{2}" -f $identity.GroupId, $identity.ArtifactId, $identity.Version) -NoNewline
        }
        $imported += [pscustomobject]@{ Id = $id; Coordinate = $(if ($identity.GroupId) {
            "{0}:{1}:{2}" -f $identity.GroupId, $identity.ArtifactId, $identity.Version } else { "unknown" }) }
    }
}

foreach ($value in $Coordinate) {
    $resolved = Resolve-Remote $value
    $id = Get-ArtifactId $resolved.GroupId $resolved.ArtifactId
    $directory = Join-Path $Stage $id
    Import-Archive $resolved.Path $directory
    Set-Content -Path (Join-Path $directory "maven-coordinate") `
        -Value ("{0}:{1}:{2}" -f $resolved.GroupId, $resolved.ArtifactId, $resolved.Version) -NoNewline
    $imported += [pscustomobject]@{ Id = $id; Coordinate = $value }
}

if ($imported.Count -eq 0) { throw "No .aar/.jar found in $From" }

if (Test-Path $Out) { Remove-Item $Out -Force }
$compression = [System.IO.Compression.ZipFile]::CreateFromDirectory($Stage, (Join-Path (Get-Location) $Out))
$compression.Dispose()

Write-Host ""
Write-Host "Packed $($imported.Count) artifacts into $Out ($([math]::Round((Get-Item $Out).Length / 1MB, 1)) MB)"
Write-Host "Each entry is <id>/classes.jar plus res/, AndroidManifest.xml, proguard.txt, maven-coordinate."
Write-Host "No JSON: Sketchware reads the dependency graph from the class files when it installs this."
Write-Host ""
$imported | ForEach-Object { Write-Host ("  {0}" -f $_.Id) }
