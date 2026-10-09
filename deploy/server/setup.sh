#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu 24.04 VM (Oracle Cloud Ampere A1, or AWS EC2 t4g) for
# deploy/server/compose.yaml. Safe to run more than once. In order:
#   1. opens ports 80/443 in the VM's own firewall, if the image blocks them (Oracle's does)
#   2. adds a 2 GB swap file if the machine has under 2 GB of memory (AWS t4g.micro)
#   3. installs Docker from Ubuntu's own repositories
set -euo pipefail

sudo apt-get update -y

# Oracle's Ubuntu images ship an iptables policy that rejects everything except SSH, on top of the
# cloud-level security list; AWS's don't, and need nothing here. Where there is a REJECT rule,
# insert ACCEPT rules for 80/443 *above* it (appended after it they'd never match) and save them
# so they survive a reboot. This runs before Docker is installed on purpose: saving after Docker
# has started would freeze Docker's own chains into the saved rules and restore stale copies at
# every boot.
if sudo iptables -L INPUT -n | awk '$1 == "REJECT" { found = 1 } END { exit !found }'; then
  echo "==> Opening ports 80 and 443 in the VM firewall"
  # Noninteractive: iptables-persistent otherwise stops to ask whether to save the current rules.
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y netfilter-persistent iptables-persistent
  insert_above_reject() {
    local reject_line
    reject_line=$(sudo iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')
    sudo iptables -I INPUT "${reject_line:-1}" "$@"
  }
  for port in 80 443; do
    if ! sudo iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; then
      insert_above_reject -p tcp --dport "$port" -m state --state NEW -j ACCEPT
    fi
  done
  if ! sudo iptables -C INPUT -p udp --dport 443 -j ACCEPT 2>/dev/null; then
    insert_above_reject -p udp --dport 443 -j ACCEPT
  fi
  sudo netfilter-persistent save
else
  echo "==> VM firewall already allows inbound traffic (no REJECT rule) -- nothing to open"
fi

# Java plus Caddy fit in 1 GB, but with little to spare; swap turns a memory spike into slowness
# instead of the kernel killing the app. Skipped when there's already swap or 2 GB+ of memory.
mem_mb=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
if [ "$mem_mb" -lt 2000 ] && [ -z "$(swapon --noheadings --show)" ]; then
  echo "==> ${mem_mb} MB of memory: adding a 2 GB swap file"
  sudo fallocate -l 2G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
else
  echo "==> ${mem_mb} MB of memory$( [ -n "$(swapon --noheadings --show)" ] && echo ', swap already on') -- no swap file needed"
fi

echo "==> Installing Docker and the compose plugin"
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2
sudo systemctl enable --now docker
# Lets this user run docker without sudo -- takes effect at the next login.
sudo usermod -aG docker "$USER"

echo
echo "Done. Log out and back in (so the docker group applies), then continue with README.md:"
echo "  cp .env.example .env && nano .env && docker compose up -d"
