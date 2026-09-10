# app

The project code and the local development database.

## Starting the database

```powershell
.\db.ps1 up
```
## Connecting

```powershell
.\db.ps1 psql
```

The experiment scripts need two sessions at the same time. Open two terminals and run `.\db.ps1 psql` in each. They are independent connections, each with its own transaction.

To confirm they really are two, run `SELECT pg_backend_pid();` in each session. The numbers have to differ.

## Other useful commands

| Command            | What it does                                 |
| ------------------ | -------------------------------------------- |
| `.\db.ps1 status`  | container state                              |
| `.\db.ps1 logs`    | follow the Postgres log                      |
| `.\db.ps1 down`    | stop the database, keeping the data          |
| `.\db.ps1 reset`   | stop the database and drop the volume        |

## Connection

| | |
| --- | --- |
| host | `localhost` |
| port | `5432` |
| database | `stock` |
| user | `stock` |
| password | `stock` |

This is just a local lab, so credentials doesn't matter. Just don't reuse the compose file for anything exposed to a network.

## Running the tests

```powershell
.\mvnw.cmd test
```

The tests do not use the database above. Each one starts its own Postgres through Testcontainers, with clean state.

## Notes

No lab tables are created automatically. The application schema comes from Flyway. exercise tables you create by hand in psql because typing the DDL is part of the point.