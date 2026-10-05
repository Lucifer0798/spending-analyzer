# Deploying to Oracle Cloud (Always Free)

One small Arm VM runs the app and Caddy (automatic HTTPS). Your PC pulls a full backup every
night. Total cost: **$0**, as long as the Oracle account is never upgraded to Pay As You Go — a
Free Tier account can't be charged (Oracle reclaims non-free resources instead of billing them).

```
 browser ──https──▶ Caddy :443 ──▶ app :4000 ──▶ /data (SQLite, Docker volume)
                     (VM)            (VM, not reachable from outside)
 your PC ──nightly, https──▶ /api/backup ──▶ Documents\SpendingAnalyzerBackups\*.json
```

> **Know the two Oracle catches before you start.**
> - **Idle VMs can be reclaimed.** Oracle may reclaim an Always Free VM whose CPU, network and
>   (on Arm) memory all stay under 20% for 7 days — which a personal app easily does. The nightly
>   backup to your PC (step 6) is what makes this survivable: rebuild the VM, restore the newest
>   backup, done.
> - **"Out of host capacity"** when creating the Arm VM means Oracle's free pool is full in that
>   availability domain right now. Try another availability domain, or again later.

## 1. Create the Oracle account (you do this)

Sign up at <https://www.oracle.com/cloud/free/>. Pick your **home region** carefully — Always Free
resources only exist there and it can't be changed. A card is needed to verify identity; it isn't
charged unless you upgrade. **Don't upgrade the account.**

## 2. Create the VM

In the console: **Compute → Instances → Create instance**.

| Setting | Value |
|---|---|
| Image | **Canonical Ubuntu 24.04** |
| Shape | **Ampere → VM.Standard.A1.Flex**, 1 OCPU, 6 GB memory (well inside the free 2 OCPU / 12 GB; the app needs far less) |
| Networking | Create a new VCN with a **public subnet**, and **assign a public IPv4 address** |
| SSH keys | **Generate a key pair for me** and download the private key (or paste your own public key) |
| Boot volume | Default (≈47 GB) is fine — free storage is 200 GB in total |

Note the VM's **public IP address** once it's running.

## 3. Open ports 80 and 443 in Oracle's network

**Networking → Virtual cloud networks → (your VCN) → Security → Default security list → Add
ingress rules**, twice:

| Source CIDR | IP protocol | Destination port |
|---|---|---|
| `0.0.0.0/0` | TCP | `80` |
| `0.0.0.0/0` | TCP | `443` |

(Port 22 for SSH is already open by default.)

## 4. Prepare the VM

From your PC (PowerShell), using the key you downloaded:

```powershell
ssh -i C:\path\to\ssh-key.key ubuntu@YOUR.PUBLIC.IP
```

Then on the VM:

```bash
git clone https://github.com/Lucifer0798/spending-analyzer.git
cd spending-analyzer/deploy/oracle
bash setup.sh
exit
```

`setup.sh` installs Docker from Ubuntu's repositories and opens 80/443 in the VM's own firewall
(Oracle's Ubuntu images block everything but SSH there too). Log out and back in afterwards.

## 5. Configure and start

SSH in again, then:

```bash
cd ~/spending-analyzer/deploy/oracle
cp .env.example .env
nano .env
```

Set:

- `APP_PASSWORD` — a long password; it's what you sign in with.
- `SITE_ADDRESS` — your public IP with dashes plus `.sslip.io`, e.g. `129-146-12-34.sslip.io`
  (no sign-up needed; [sslip.io](https://sslip.io) resolves it to that IP). Or a free
  [DuckDNS](https://www.duckdns.org) name / your own domain pointed at the IP.

Start it:

```bash
docker compose up -d
docker compose logs -f caddy   # watch for "certificate obtained successfully", then Ctrl+C
```

Open `https://YOUR-SITE-ADDRESS` and sign in. If the certificate step fails, it's almost always
step 3 (the security list) or a typo in `SITE_ADDRESS`.

## 6. Nightly backups to your PC

On your Windows PC, from the repository's `deploy\backup` folder in PowerShell:

```powershell
# Once: store the app password, encrypted for your Windows user only (prompts for it)
.\Backup-SpendingAnalyzer.ps1 -Url https://YOUR-SITE-ADDRESS -SavePassword

# Once: check a backup works right now
.\Backup-SpendingAnalyzer.ps1 -Url https://YOUR-SITE-ADDRESS

# Once: schedule it nightly at 21:00 (runs at the next chance if the PC was off)
.\Register-BackupTask.ps1 -Url https://YOUR-SITE-ADDRESS -At 21:00
```

Backups land in `Documents\SpendingAnalyzerBackups` as `spending-analyzer-backup-YYYY-MM-DD.json`
(the newest 30 are kept), with each run's result in `backup.log` there. If PowerShell refuses to
run the scripts, allow local scripts once with
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

**To restore**: sign in → **Manage → Backup → Restore**, and pick a backup file. It replaces
everything in that instance with the file's contents.

## Updating to a new version

Every merge to `main` publishes a new image. On the VM:

```bash
cd ~/spending-analyzer && git pull          # only matters if deploy/oracle files changed
cd deploy/oracle && docker compose pull && docker compose up -d
```

Your data lives in the `spending-data` volume and survives updates and restarts. Database
migrations run automatically on startup.

## If Oracle reclaims the VM

Create a new VM (steps 2–5; the new public IP means a new `SITE_ADDRESS`), sign in, and restore
the newest file from `Documents\SpendingAnalyzerBackups`. Then point the backup task at the new
address: re-run `Register-BackupTask.ps1 -Url https://NEW-ADDRESS` (the saved password still works
as long as `APP_PASSWORD` is the same).
