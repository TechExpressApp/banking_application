# Deploying to Vercel

The app runs on Vercel's **Container runtime** (beta, all plans): Vercel builds the root `Dockerfile` and runs the
Spring Boot app as a container. Data lives in a **Supabase** PostgreSQL database.

What that means for this app:
- **Vercel stops the app after 5 minutes without traffic, and local files are lost then.** The image therefore refuses to start without a database (`BANKING_REQUIRE_EXTERNAL_DB=true`).
- **The first request after an idle period starts Java from scratch,** so expect a 5–15 second wait.
- **Vercel may run several instances at once.** The app copes with this:
  - transfers are database transactions that lock both accounts
  - logins are signed tokens (`BANKING_SESSION_SECRET`)
  - agent chats restart automatically if a different instance answers

## 1. Protect the site

In Vercel, open **Project → Settings → Deployment Protection**. Choose **Vercel Authentication** with **All Deployments** (free on every plan).
Only signed-in members of your Vercel team can then open the site.

Without this, anyone with the URL could move money (the Bank Transfer and Ops pages have no staff login) and use your Claude credit.

## 2. Supabase database

1. In the Vercel project, open **Storage → Create Database → Supabase**.
2. Pick a region close to the app (Washington, D.C. / `iad1` is Vercel's default) and connect it to the project for all environments.

Vercel then adds `POSTGRES_URL` (and related variables) to the project automatically. The app uses `POSTGRES_URL`.

**Move your existing data in (optional, once).** Before the first deploy, run the app locally against the new database:
```bash
npx vercel env pull .env.local            # downloads POSTGRES_URL etc. (git-ignored)
set -a; source .env.local; set +a
mvn spring-boot:run                       # imports data/*.csv into the empty database, then Ctrl+C
```
If the database is empty when the deployed app first starts, it creates the 10 sample accounts instead.

## 3. Other environment variables

In **Project → Settings → Environment Variables**:

| Name | Value |
|---|---|
| `BANKING_SESSION_SECRET` | A random string of 32+ characters. Generate one with `openssl rand -hex 32` |
| `ANTHROPIC_API_KEY` | Your Claude API key |
| `ANTHROPIC_WORKSPACE_ID` | Only if the key starts with `sk-ant-usr-`: the `wrkspc_…` ID |

## 4. Deploy

The project is connected to GitHub, so every push to `main` deploys. To deploy now from your machine, run `npx vercel deploy --prod`.

Check the result:
- Open the deployment URL, sign in with Vercel, then log in to the bank as `aarav.sharma@example.com` / `Password@123`.
- In Supabase, open **Table Editor** and pick the **`bank`** schema to see the data.
- Problems? See **Project → Logs** in Vercel. A missing `POSTGRES_URL` stops the app at startup with a clear error.

## Limitations

- **Agent chats and unconfirmed transfers** live in one instance's memory. They're lost when Vercel stops the app; the chat starts over, and the transfer card says it expired.
- **Logging out** ends the token on the instance that handled it. On other instances the token stays valid until it expires (2 hours).
