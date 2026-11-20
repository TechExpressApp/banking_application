# Banking Application

Java 21 / Spring Boot 3.5 backend with a browser UI for moving money between customer accounts.
Data lives in a **PostgreSQL database on Supabase**. Without a database configured, the app uses a local
H2 database file in `data/`, so it runs with no setup.

## Run

```bash
mvn spring-boot:run            # or: mvn package && java -jar target/banking-application-1.0.0.jar
```
Open http://localhost:8080/banking-application/

On the first start the app creates **10 sample accounts** (`1000000001` to `1000000010`, customers `CUST001` to `CUST010`).
All sample customers log in with the password **`Password@123`**, for example `aarav.sharma@example.com`.
`sample-data/Accounts.csv` holds the same 10 accounts.

## UI tabs

| Tab | What it does |
|---|---|
| **Bank Transfer** | Pick any source account, any target account and an amount. Also lists every account and the recent transactions. |
| **Register** | Creates a customer and opens a new account (SAVINGS/CURRENT, optional opening deposit). |
| **Login** | Customer login (session token, 30 min idle timeout). |
| **My Accounts** | Your balances and transaction history. |
| **Beneficiaries** | Add a beneficiary by 10-digit account number (it must exist in this bank) and remove beneficiaries. |
| **Transfer to Beneficiary** | Send money from one of your own accounts to one of your beneficiaries. |

Transfer rules: the amount must be positive, have at most 2 decimals and be no more than 1,000,000. The source account needs enough funds.
Source and target must be different accounts. Customers can only send from their own accounts and only to their own beneficiaries.
Transfers are atomic: if saving fails, the balances are rolled back.

## Use Supabase as the database

1. Create a Supabase project (at https://supabase.com, or in Vercel under **Storage → Create Database → Supabase**).
2. Copy its connection string from **Project Settings → Database → Connection string** (URI).
   Use the **pooler** string; it works over IPv4.
3. Put it in the git-ignored `config/application-local.properties`:
   ```properties
   banking.database.url=postgresql://postgres.<ref>:<password>@aws-0-<region>.pooler.supabase.com:6543/postgres
   ```
   You can also set it as the `POSTGRES_URL` environment variable.
4. Start the app. It creates its tables in a separate `bank` schema, which is not exposed through Supabase's public web API.
   If the database is empty, it imports `data/*.csv` (the old file storage) if present; otherwise it creates the 10 sample accounts.

Every transfer runs in a database transaction that locks both accounts, so concurrent transfers can't overspend,
even across several server instances.

## Deploy to Vercel

See [docs/deploy-vercel.md](docs/deploy-vercel.md). The app runs as a Docker container on Vercel's Container runtime, with data in Supabase.

## REST API

All paths below are under the base path `/banking-application` (for example `/banking-application/api/accounts`).
Change it with the `BASE_PATH` environment variable.

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register` | none |
| POST | `/api/auth/login` → `{token}` | none |
| POST | `/api/auth/logout` | Bearer |
| GET | `/api/me` (profile + accounts) | Bearer |
| GET/POST | `/api/me/beneficiaries`, DELETE `/api/me/beneficiaries/{id}` | Bearer |
| POST | `/api/me/transfers` `{fromAccount, beneficiaryId, amount, note}` | Bearer |
| GET | `/api/me/transactions` | Bearer |
| GET | `/api/accounts`, `/api/transactions` | none |
| POST | `/api/transfers` `{fromAccount, toAccount, amount, note}` | none |

> ⚠️ This is a demo. The **Bank Transfer** tab and `/api/transfers` have no login, so anyone who can reach the server can move money between any accounts.
> Put staff/admin authentication in front of them before real use. Sessions are kept in memory and are lost on restart.

## Project layout

```
src/main/java/com/bank/
  db/       DatabaseConfig (Supabase/PostgreSQL or local H2), PostgresUrl
  store/    CsvTableStore (one-time import of the old CSV data)
src/main/resources/schema.sql  tables (bank schema)
  model/    Customer, Account, Beneficiary, Transaction
  repo/     BankRepository (in-memory cache that writes every change to the store, seeds the sample data)
  service/  BankService (register, login, beneficiaries, transfers), SessionService
  web/      BankController (REST), DTOs, ApiExceptionHandler
src/main/resources/static/   index.html, app.js, styles.css (the UI)
```
Tests: `mvn test`
