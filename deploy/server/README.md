# Deploying to a Linux VM (Oracle Cloud or AWS)

One small VM runs the app and Caddy (automatic HTTPS). Your PC pulls a full backup every night.
The files here aren't tied to a provider; only the first steps — getting a VM with ports 80 and
443 open — differ, so pick your host below, then carry on from **Prepare the VM**.

```
 browser ──https──▶ Caddy :443 ──▶ app :4000 ──▶ /data (SQLite, Docker volume)
                     (VM)            (VM, not reachable from outside)
 your PC ──nightly, https──▶ /api/backup ──▶ Documents\SpendingAnalyzerBackups\*.json
```

| Host | What it costs | The catch |
|---|---|---|
| **Oracle Cloud Always Free** | $0, indefinitely, as long as the account is never upgraded | An idle VM may be reclaimed — the nightly backup makes that survivable |
| **AWS Free plan** | $0 — paid from the new-account credits (up to $200); the account can't be charged | The account **closes** after 6 months or when the credits run out, taking the VM and its disk with it. Before then, move to Oracle or upgrade to a paid plan |

Either way: **the database lives only on the VM.** The nightly backup to your PC is what you'd
rebuild from, so set it up (step 6) the same day you deploy.

---

## Option A — Oracle Cloud Always Free

> **Two Oracle catches.**
> - **Idle VMs can be reclaimed.** Oracle may reclaim an Always Free VM whose CPU, network and (on
>   Arm) memory all stay under 20% for 7 days — which a personal app easily does.
> - **"Out of host capacity"** when creating the Arm VM means the free pool is full in that
>   availability domain right now. Try another availability domain, or again later.

**A1. Account.** Sign up at <https://www.oracle.com/cloud/free/>. Pick your **home region**
carefully — Always Free resources only exist there and it can't be changed. A card is needed to
verify identity; it isn't charged unless you upgrade. **Don't upgrade the account.**

**A2. VM.** **Compute → Instances → Create instance**:

| Setting | Value |
|---|---|
| Image | **Canonical Ubuntu 24.04** |
| Shape | **Ampere → VM.Standard.A1.Flex**, 1 OCPU, 6 GB memory (inside the free 2 OCPU / 12 GB) |
| Networking | New VCN with a **public subnet**; **assign a public IPv4 address** |
| SSH keys | **Generate a key pair for me** and download the private key |

The SSH user is **`ubuntu`**.

**A3. Open ports 80 and 443.** **Networking → Virtual cloud networks → (your VCN) → Security →
Default security list → Add ingress rules**, twice: source `0.0.0.0/0`, TCP, destination port
`80`; and the same for `443`. (Oracle's Ubuntu image also blocks them in the VM's own firewall —
`setup.sh` opens that side.)

---

## Option B — AWS Free plan (EC2)

> **The AWS catch: the Free plan ends.** The account closes 6 months after it was opened or when
> the credits run out, whichever is first — the VM and its disk go with it. Watch the dates under
> **Billing → Credits**; before the end, either restore your newest backup onto an Oracle VM
> (Option A) or upgrade the account to a paid plan (from then on it's billed normally).

**B1. Region.** Pick one near you in the console's top-right corner and stay in it — the VM, its
IP and its firewall all live in that region.

**B2. VM.** **EC2 → Instances → Launch instances**:

| Setting | Value |
|---|---|
| Name | `spending-analyzer` |
| AMI | **Ubuntu Server 24.04 LTS**, architecture **64-bit (Arm)** |
| Instance type | **t4g.micro** (2 vCPU, 1 GB — on the Free plan list; `setup.sh` adds swap so 1 GB is enough). `t4g.small` (2 GB) also qualifies but spends credits about twice as fast |
| Key pair | **Create new key pair**, type ED25519, format `.pem`; download it |
| Network settings | **Create security group**; tick **Allow SSH traffic from My IP**, **Allow HTTPS traffic from the internet** and **Allow HTTP traffic from the internet** |
| Storage | **20 GiB gp3** |

The SSH user is **`ubuntu`**.

**B3. Give it a fixed address.** **EC2 → Elastic IPs → Allocate Elastic IP address**, then
**Actions → Associate**, and pick the instance. Without this the public IP changes whenever the
instance stops, and `SITE_ADDRESS` and the backup task would both point at a stale address. (A
public IPv4 address costs about the same either way, from credits; an Elastic IP left
*unassociated* still costs, so release it if you delete the instance.)

The security group from B2 already opens 80 and 443; the VM's own firewall is open by default.

---

## 4. Prepare the VM

From your PC (PowerShell), using the key you downloaded and the VM's public IP:

```powershell
ssh -i C:\path\to\your-key ubuntu@YOUR.PUBLIC.IP
```

Then on the VM:

```bash
git clone https://github.com/Lucifer0798/spending-analyzer.git
cd spending-analyzer/deploy/server
bash setup.sh
exit
```

`setup.sh` installs Docker from Ubuntu's repositories, adds a 2 GB swap file on machines with less
than 2 GB of memory (the AWS t4g.micro), and opens 80/443 in the VM's own firewall where an image
blocks them (Oracle's). Log out and back in afterwards so Docker works without `sudo`.

## 5. Configure and start

SSH in again, then:

```bash
cd ~/spending-analyzer/deploy/server
cp .env.example .env
nano .env
```

Set:

- `APP_PASSWORD` — a long password; it's what you sign in with.
- `SITE_ADDRESS` — your public IP with dashes plus `.sslip.io`, e.g. `3-91-12-34.sslip.io`
  (no sign-up needed; [sslip.io](https://sslip.io) resolves it to that IP). Or a free
  [DuckDNS](https://www.duckdns.org) name / your own domain pointed at the IP.

Start it:

```bash
docker compose up -d
docker compose logs -f caddy   # watch for "certificate obtained successfully", then Ctrl+C
```

Open `https://YOUR-SITE-ADDRESS` and sign in. If the certificate step fails, it's almost always the
ports (step A3 / the B2 security group) or a typo in `SITE_ADDRESS`. The first start on a
t4g.micro takes a minute or so — Java is slow to start on a small machine.

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
cd ~/spending-analyzer && git pull          # only matters if deploy/server files changed
cd deploy/server && docker compose pull && docker compose up -d
```

Your data lives in the `spending-data` volume and survives updates and restarts. Database
migrations run automatically on startup.

## Moving to a new VM

Oracle reclaimed the VM, the AWS Free plan is ending, or you're just switching hosts: create the new
VM (your host's steps, then 4–5; a new IP means a new `SITE_ADDRESS`), sign in, and restore the
newest file from `Documents\SpendingAnalyzerBackups`. Then point the backup task at the new
address: re-run `Register-BackupTask.ps1 -Url https://NEW-ADDRESS` (the saved password still works
as long as `APP_PASSWORD` is the same).
