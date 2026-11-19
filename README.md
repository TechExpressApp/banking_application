# Banking Application

Java 21 / Spring Boot 3.5 backend with a browser UI for moving money between customer accounts.
Data lives in a **Google Sheet** (one tab each for Accounts, Customers, Beneficiaries and Transactions).
If you haven't set up a sheet, it uses local CSV files instead.

## Run

```bash
mvn spring-boot:run            # or: mvn package && java -jar target/banking-application-1.0.0.jar
```
Open http://localhost:8080

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

## Use a Google Sheet as the database

1. In Google Cloud Console, create a project, **enable the Google Sheets API**, create a **Service Account**
   and download its JSON key as `credentials.json` in the project root.
2. Create an empty Google Sheet. **Share** it with the service account's e-mail (`...@...iam.gserviceaccount.com`) as **Editor**.
3. Copy the spreadsheet ID from its URL: `https://docs.google.com/spreadsheets/d/<THIS_PART>/edit`.
4. Run:
   ```bash
   BANKING_STORAGE=sheets BANKING_SHEET_ID=<id> BANKING_SHEET_CREDENTIALS=./credentials.json mvn spring-boot:run
   ```
   On the first start the app creates the `Accounts`, `Customers`, `Beneficiaries` and `Transactions` tabs and fills in the 10 sample accounts.
   Every registration, beneficiary change and transfer is written to the sheet right away.

> The app loads the sheet into memory when it starts. If you edit the sheet by hand while the app is running, restart the app to pick up your edits.
> Otherwise the app's next write overwrites them.

## REST API

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
  store/    TableStore, CsvTableStore, GoogleSheetsTableStore, StoreConfig
  model/    Customer, Account, Beneficiary, Transaction
  repo/     BankRepository (in-memory cache that writes every change to the store, seeds the sample data)
  service/  BankService (register, login, beneficiaries, transfers), SessionService
  web/      BankController (REST), DTOs, ApiExceptionHandler
src/main/resources/static/   index.html, app.js, styles.css (the UI)
```
Tests: `mvn test`
