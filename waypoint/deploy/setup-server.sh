#!/usr/bin/env bash
# Sets up a Wanees (ونيس) production server on a fresh Ubuntu 22.04/24.04 VPS.
#
#   cd alnaitool-app/waypoint
#   sudo ./deploy/setup-server.sh                  # free HTTPS address <ip>.sslip.io
#   sudo ./deploy/setup-server.sh api.example.com  # your own domain (DNS A record → server IP)
#
# Safe to re-run: existing secrets in .env are kept; services are updated in place.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ $EUID -ne 0 ]]; then
  echo "Please run with sudo: sudo $0 $*" >&2
  exit 1
fi

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# --- 1. Docker ---------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  say "Installing Docker"
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker >/dev/null 2>&1 || true

# --- 2. Memory: OSRM preprocessing needs ~3 GB; add swap on small servers -----
mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
if (( mem_kb < 3900000 )) && ! swapon --show | grep -q '/swapfile'; then
  say "Adding 3 GB swap (server has less than 4 GB RAM)"
  fallocate -l 3G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# --- 3. Firewall: only SSH, HTTP and HTTPS -----------------------------------
if command -v ufw >/dev/null 2>&1; then
  say "Configuring firewall"
  ufw allow OpenSSH >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw allow 443/udp >/dev/null
  ufw --force enable >/dev/null
fi

# --- 4. Configuration (.env) -------------------------------------------------
set_env() {
  local key=$1 value=$2
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
}

if [[ ! -f .env ]]; then
  say "Creating .env with random secrets"
  cp .env.example .env
  set_env JWT_SECRET "$(openssl rand -hex 32)"
  db_pass=$(openssl rand -hex 24)
  set_env POSTGRES_PASSWORD "$db_pass"
  set_env DATABASE_URL "postgres://wanees:${db_pass}@localhost:5432/wanees?sslmode=disable"
fi
chmod 600 .env

domain=${1:-}
if [[ -z "$domain" ]]; then
  domain=$(grep -E '^DOMAIN=' .env | cut -d= -f2- || true)
fi
if [[ -z "$domain" ]]; then
  ip=$(curl -fsS4 https://api.ipify.org || curl -fsS4 https://ifconfig.me)
  domain="${ip//./-}.sslip.io"
fi
set_env DOMAIN "$domain"
set_env APP_ENV production
set_env TRUST_PROXY true

# --- 5. Start --------------------------------------------------------------------
say "Building and starting Wanees (first start downloads Syria's road map: a few minutes)"
mkdir -p backups
compose=(docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml)
"${compose[@]}" up -d --build

say "Waiting for https://${domain} to answer"
for _ in $(seq 1 90); do
  if curl -fsS "https://${domain}/healthz" >/dev/null 2>&1; then
    printf '\n\033[1;32m✔ ونيس يعمل! Wanees is running.\033[0m\n'
    printf '\n  Server address for the app / عنوان الخادم في التطبيق:\n\n      https://%s\n\n' "$domain"
    printf '  Logs:   %s logs -f api\n' "${compose[*]}"
    printf '  Backups: %s/backups (daily)\n\n' "$(pwd)"
    exit 0
  fi
  sleep 10
done

echo "The server did not answer on https://${domain} within 15 minutes." >&2
echo "Check: ${compose[*]} ps   and   ${compose[*]} logs --tail=100 caddy api osrm-prepare" >&2
exit 1
