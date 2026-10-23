# Runs inside the self-extracting installer: unpacks the app (with Notes+ Setup) into a temporary
# folder that outlives the installer's own, and starts Setup. Setup removes that folder when done.
$dest = Join-Path $env:TEMP ('NotesPlusSetup-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dest | Out-Null
$zip = Join-Path $PSScriptRoot 'payload.zip'
& tar.exe -xf $zip -C $dest 2>$null
$setup = Join-Path $dest 'Notes+\Notes+ Setup.exe'
if (-not (Test-Path -LiteralPath $setup)) { Expand-Archive -LiteralPath $zip -DestinationPath $dest -Force }
Start-Process -FilePath $setup
