[CmdletBinding()]
param(
    [string]$NdkPath = "",
    [string]$SourcePath = "",
    [string]$OutputDir = "",
    [switch]$NoInstall,
    [switch]$KeepStaging
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$defaultSourceDateEpoch = 1779534332
if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
    $env:SOURCE_DATE_EPOCH = $defaultSourceDateEpoch.ToString()
}
$parsedSourceDateEpoch = 0L
if (![long]::TryParse($env:SOURCE_DATE_EPOCH, [ref]$parsedSourceDateEpoch) -or $parsedSourceDateEpoch -lt 0) {
    throw "SOURCE_DATE_EPOCH must be an unsigned Unix timestamp."
}
$env:TZ = "UTC"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($SourcePath)) {
    $SourcePath = Join-Path $repo "third_party\kernelpatch"
}
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $repo "userspace\ksud\bin\aarch64"
}
if ([string]::IsNullOrWhiteSpace($NdkPath)) {
    $NdkPath = $env:ANDROID_NDK_HOME
    if ([string]::IsNullOrWhiteSpace($NdkPath)) {
        $NdkPath = $env:ANDROID_NDK_ROOT
    }
}

if ([string]::IsNullOrWhiteSpace($NdkPath)) {
    throw "Set ANDROID_NDK_HOME/ANDROID_NDK_ROOT or pass -NdkPath."
}
$SourcePath = (Resolve-Path $SourcePath).Path
$NdkPath = (Resolve-Path $NdkPath).Path

$hostTag = "windows-x86_64"
$llvmBin = Join-Path $NdkPath "toolchains\llvm\prebuilt\$hostTag\bin"
$clang = Join-Path $llvmBin "clang.exe"
$strip = Join-Path $llvmBin "llvm-strip.exe"
$make = Join-Path $NdkPath "prebuilt\$hostTag\bin\make.exe"
if (!(Test-Path $make)) {
    $makeCommand = Get-Command make.exe -ErrorAction SilentlyContinue
    if ($null -eq $makeCommand) {
        throw "GNU make was not found in the NDK or PATH."
    }
    $make = $makeCommand.Source
}
if (!(Test-Path $clang)) {
    throw "Android clang was not found: $clang"
}

$stage = Join-Path $repo "out\kernelpatch-build"
$stage = [IO.Path]::GetFullPath($stage)
$expectedStage = [IO.Path]::GetFullPath((Join-Path $repo "out\kernelpatch-build"))
if ($stage -ne $expectedStage -or !$stage.StartsWith($repo + [IO.Path]::DirectorySeparatorChar)) {
    throw "Build staging path is outside the workspace."
}
$kernelStage = Join-Path $stage "kernel"
$toolsStage = Join-Path $stage "tools"
$sysrootLib = Join-Path $NdkPath "toolchains\llvm\prebuilt\$hostTag\sysroot\usr\lib\aarch64-linux-android"
$kpimg = Join-Path $kernelStage "kpimg"
$kptools = Join-Path $toolsStage "kptools"
$jobs = [Math]::Max(1, [Environment]::ProcessorCount)

function Invoke-Make([string[]]$Arguments) {
    & $make @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "make failed with exit code $LASTEXITCODE"
    }
}

function Read-U16([byte[]]$Bytes, [int]$Offset) {
    return [BitConverter]::ToUInt16($Bytes, $Offset)
}

function Read-U64([byte[]]$Bytes, [int]$Offset) {
    return [BitConverter]::ToUInt64($Bytes, $Offset)
}

function Validate-Outputs {
    if (!(Test-Path $kpimg)) {
        throw "kpimg was not produced."
    }
    $kpBytes = [IO.File]::ReadAllBytes($kpimg)
    if ($kpBytes.Length -lt 6 -or [Text.Encoding]::ASCII.GetString($kpBytes, 0, 6) -ne "KP1158") {
        throw "kpimg does not start with the expected KP1158 header."
    }

    if (!(Test-Path $kptools)) {
        throw "kptools was not produced."
    }
    $toolBytes = [IO.File]::ReadAllBytes($kptools)
    if ($toolBytes.Length -lt 64 -or $toolBytes[0] -ne 0x7f -or
        $toolBytes[1] -ne 0x45 -or $toolBytes[2] -ne 0x4c -or $toolBytes[3] -ne 0x46 -or
        $toolBytes[4] -ne 2 -or $toolBytes[5] -ne 1 -or
        (Read-U16 $toolBytes 16) -notin 2, 3 -or (Read-U16 $toolBytes 18) -ne 183) {
        throw "kptools is not a little-endian AArch64 ELF64 executable."
    }
    $phoff = Read-U64 $toolBytes 32
    $phentsize = Read-U16 $toolBytes 54
    $phnum = Read-U16 $toolBytes 56
    if ($phnum -gt 0 -and ($phentsize -lt 56 -or $phoff + $phentsize * $phnum -gt $toolBytes.Length)) {
        throw "kptools has a malformed ELF program header table."
    }
    for ($index = 0; $index -lt $phnum; $index++) {
        $offset = [int]($phoff + $index * $phentsize)
        if ([BitConverter]::ToUInt32($toolBytes, $offset) -eq 3) {
            throw "kptools contains PT_INTERP and is not statically linked."
        }
    }
}

try {
    if (Test-Path $stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    New-Item -ItemType Directory -Force $stage | Out-Null

    $copyArgs = @(
        $SourcePath, $stage, "/E", "/COPY:DAT", "/DCOPY:T", "/R:1", "/W:1",
        "/NFL", "/NDL", "/NJH", "/NJS", "/NP",
        "/XD", ".git", "tools\build", "tools\build-android", "tools\build-linux",
        "/XF", "*.o", "*.elf", "kpimg", "kpimg-test", "kptools", "user_event.c", "userd.c"
    )
    & robocopy @copyArgs | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "robocopy failed with exit code $LASTEXITCODE"
    }

    Copy-Item (Join-Path $kernelStage "include\preset.h") (Join-Path $toolsStage "preset.h") -Force
    Remove-Item -LiteralPath (Join-Path $kernelStage "patch\common\user_event.c") -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $kernelStage "patch\android\userd.c") -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $stage "patch\encrypt") -Force -ErrorAction SilentlyContinue

    Write-Host "[kpimg] building KPM-only early boot runtime"
    Invoke-Make @("-C", $kernelStage, "TARGET_COMPILE=$llvmBin", "CC=$clang --target=aarch64-linux-android35", "ANDROID=1", "-j$jobs")

    Write-Host "[kptools] building Android AArch64 image patcher"
    Invoke-Make @(
        "-C", $toolsStage,
        "CC=$clang --target=aarch64-linux-android35",
        "LDFLAGS=-static -L$sysrootLib -lz",
        "-j$jobs"
    )

    if (Test-Path $strip) {
        & $strip --strip-unneeded $kptools
        if ($LASTEXITCODE -ne 0) {
            throw "llvm-strip failed with exit code $LASTEXITCODE"
        }
    }
    Validate-Outputs

    New-Item -ItemType Directory -Force $OutputDir | Out-Null
    if (!$NoInstall) {
        Copy-Item $kpimg (Join-Path $OutputDir "kpimg") -Force
        Copy-Item $kptools (Join-Path $OutputDir "kptools") -Force
    }

    $kpHash = (Get-FileHash $kpimg -Algorithm SHA256).Hash.ToLowerInvariant()
    $toolHash = (Get-FileHash $kptools -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host ("kpimg: {0} bytes sha256={1}" -f (Get-Item $kpimg).Length, $kpHash)
    Write-Host ("kptools: {0} bytes sha256={1}" -f (Get-Item $kptools).Length, $toolHash)
    if (!$NoInstall) {
        Write-Host "installed: $OutputDir\kpimg"
        Write-Host "installed: $OutputDir\kptools"
    }
}
finally {
    if (!$KeepStaging -and (Test-Path $stage)) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
