#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu VM (Oracle Cloud Ampere A1, Ubuntu 24.04) for
# deploy/oracle/compose.yaml: installs Docker from Ubuntu's own repositories and opens ports
# 80 and 443 in the VM's firewall. Safe to run more than once.
set -euo pipefail

echo "==> Installing Docker and the compose plugin"
sudo apt-get update -y
# Noninteractive: iptables-persistent otherwise stops to ask whether to save the current rules.
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2 netfilter-persistent iptables-persistent
sudo systemctl enable --now docker
# Lets this user run docker without sudo -- takes effect at the next login.
sudo usermod -aG docker "$USER"

# Oracle's Ubuntu images ship an iptables policy that rejects everything except SSH, on top of
# the cloud-level security list. Insert ACCEPT rules for 80/443 *above* that REJECT rule
# (appending them after it would never match), then save so they survive a reboot.
echo "==> Opening ports 80 and 443 in the VM firewall"
for port in 80 443; do
  if ! sudo iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; then
    reject_line=$(sudo iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')
    sudo iptables -I INPUT "${reject_line:-1}" -p tcp --dport "$port" -m state --state NEW -j ACCEPT
  fi
done
if ! sudo iptables -C INPUT -p udp --dport 443 -j ACCEPT 2>/dev/null; then
  reject_line=$(sudo iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')
  sudo iptables -I INPUT "${reject_line:-1}" -p udp --dport 443 -j ACCEPT
fi
sudo netfilter-persistent save

echo
echo "Done. Log out and back in (so the docker group applies), then continue with README.md:"
echo "  cp .env.example .env && nano .env && docker compose up -d"
