# Explicitly provisions the database from local dev YAML and applies reviewed migrations.
$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$pom = Join-Path $backend 'pom.xml'
$classpathFile = Join-Path $backend 'target/database-runtime-classpath.txt'
$config = Join-Path $backend 'src/main/resources/application-dev.yml'
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
    throw 'Copy backend/src/main/resources/application-dev.yml.example to application-dev.yml and fill in local values.'
}
& mvn -B -ntp -f $pom dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve database tool classpath.' }
$classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
& java -cp $classpath (Join-Path $PSScriptRoot 'InitializeDatabase.java') (Join-Path $backend 'src/main/resources/db/migration') $config
if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed; no automatic cleanup is performed.' }
