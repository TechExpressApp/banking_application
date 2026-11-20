# Deploying to Vercel

The app runs on Vercel's **Container runtime** (beta, all plans). Vercel builds the root `Dockerfile` and runs the Spring Boot app as a container.

What that means for this app:
- **Vercel stops the app after 5 minutes without traffic.** Local files are lost then, so bank data must live in a **Google Sheet**. The image defaults to `BANKING_STORAGE=sheets`.
- **The first request after an idle period starts Java from scratch,** so expect a 5–15 second wait.
- **Vercel may run several instances at once.** The app copes with this:
  - logins use signed tokens (`BANKING_SESSION_SECRET`)
  - each instance re-reads the Sheet before every change
  - agent chats restart automatically if a different instance answers

## 1. Google Sheet (once)

1. In https://console.cloud.google.com, create a project and **enable the Google Sheets API**.
2. Go to **IAM & Admin → Service Accounts → Create**, then on the new account choose **Keys → Add key → JSON**. A `.json` file downloads.
3. Create an empty Google Sheet. **Share** it as **Editor** with the service account's e-mail (`…@….iam.gserviceaccount.com`).
4. Copy the Sheet ID from its URL: `https://docs.google.com/spreadsheets/d/<SHEET_ID>/edit`.

On first start the app creates the tabs and fills in the 10 sample accounts.

## 2. Protect the site before the first deploy

Anyone with the URL could otherwise move money (the Bank Transfer and Ops Assistant pages have no staff login) and use your Claude credit.

1. In Vercel, open **Team Settings → Deployment Protection**.
2. Set the default for new projects to **Vercel Authentication** with **All Deployments**. This is free on every plan.

Only people logged in to Vercel with access to your team can then open the site. Invite others under **Team Settings → Members**.

(If the project already exists: **Project → Settings → Deployment Protection**, same choices.)

## 3. Create the Vercel project

1. Go to https://vercel.com/new and **import** the GitHub repo `TechExpressApp/banking_application`.
2. Vercel detects the `Dockerfile` and picks the **Container** preset. Leave the build settings as they are.
3. Open **Environment Variables** and add:

| Name | Value |
|---|---|
| `BANKING_SHEET_ID` | The Sheet ID from step 1 |
| `BANKING_SHEET_CREDENTIALS_JSON` | The **entire contents** of the downloaded service-account `.json` file |
| `BANKING_SESSION_SECRET` | A random string of 32+ characters. Generate one with `openssl rand -hex 32` |
| `ANTHROPIC_API_KEY` | Your Claude API key |
| `ANTHROPIC_WORKSPACE_ID` | Only if the key starts with `sk-ant-usr-`: the `wrkspc_…` ID |

4. Click **Deploy**. The first build takes a few minutes, because Maven downloads dependencies.

Every later `git push` to `main` deploys automatically.

## 4. Check it

- Open the deployment URL, log in with Vercel if asked, then log in to the bank as `aarav.sharma@example.com` / `Password@123`.
- Look in the Google Sheet: the `Accounts`, `Customers`, `Transactions`… tabs should have appeared.
- Problems? See **Project → Logs** in Vercel. A missing or wrong Sheet setting stops the app at startup with a clear error.

## Limitations

- **Sheet throughput.** Google Sheets is a demo-grade database: every change re-reads the Sheet, which takes about 1 second. Google allows about 300 reads per minute per project. For real traffic, move to a hosted database, for example Postgres from the Vercel Marketplace.
- **Two simultaneous changes on different instances can still collide** in the second or so between reading and writing the Sheet.
- **Agent chats and unconfirmed transfers** live in one instance's memory. They're lost when Vercel stops the app; the chat starts over, and the transfer card says it expired.
- **Logging out** ends the token on the instance that handled it. On other instances the token stays valid until it expires (2 hours).
