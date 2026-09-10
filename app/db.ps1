<#
    Shortcuts for the project's local Postgres.

    Usage:
        .\db.ps1 up      # start the database and wait until it is healthy
        .\db.ps1 psql    # open a psql session (run it in two terminals for the lab)
        .\db.ps1 status  # container state
        .\db.ps1 logs    # follow the Postgres log
        .\db.ps1 down    # stop the database, KEEPING the data
        .\db.ps1 reset   # stop the database and DROP the volume (empty database)
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'reset', 'psql', 'logs', 'status')]
    [string]$Command = 'up'
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    switch ($Command) {
        'up' {
            docker compose up -d --wait
            docker compose ps
        }
        'down' {
            docker compose down
        }
        'reset' {
            docker compose down -v
            docker compose up -d --wait
            docker compose ps
        }
        'psql' {
            docker compose exec postgres psql -U stock -d stock
        }
        'logs' {
            docker compose logs -f postgres
        }
        'status' {
            docker compose ps
        }
    }
}
finally {
    Pop-Location
}
