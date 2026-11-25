# Explicitly provisions DB_NAME and applies reviewed migrations; never run without authorization.
# Credentials must already be in process environment variables. No arguments carry passwords.
$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$pom = Join-Path $backend 'pom.xml'
$classpathFile = Join-Path $backend 'target/database-runtime-classpath.txt'
foreach ($name in @('DB_USERNAME', 'DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Set $name in this process before initialization."
    }
}
& mvn -B -ntp -f $pom dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve database tool classpath.' }
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
& java -cp $classpath (Join-Path $PSScriptRoot 'InitializeDatabase.java') (Join-Path $backend 'src/main/resources/db/migration')
if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed; no automatic cleanup is performed.' }
