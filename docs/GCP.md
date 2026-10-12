# Run Version Server on a GCP VM

A Compute Engine VM can run the same container as a local Docker host. `rycbar1948/version-server` on Docker Hub is the Python image built from this checkout.

Tokens issued here keep working on the VM when the VM uses the same `jwt.crt` and `jwt.key`.

## 1. Use the VM named `version-server`

Keep a single Compute Engine instance named `version-server`. Each run looks that name up in one zone and starts it. The instance is created only when that name is missing.

Use Ubuntu with at least 4 GB of RAM. The container limit is 3 GB. Pick one zone and keep using it. The examples below use `us-central1-a`.

Allow HTTP to instances tagged `version-server`. Create the firewall rule only when that rule name is missing. Add `tcp:8443` to the rule when the VM should serve HTTPS.

```bash
ZONE=us-central1-a
INSTANCE=version-server

if ! gcloud compute firewall-rules describe version-server-http >/dev/null 2>&1; then
  gcloud compute firewall-rules create version-server-http \
    --allow=tcp:8080 --target-tags=version-server
fi

STATUS="$(gcloud compute instances describe "$INSTANCE" --zone="$ZONE" --format='value(status)' 2>/dev/null || true)"
if [[ -z "$STATUS" ]]; then
  gcloud compute instances create "$INSTANCE" \
    --zone="$ZONE" \
    --machine-type=e2-standard-2 \
    --image-family=ubuntu-2404-lts \
    --image-project=ubuntu-os-cloud \
    --tags=version-server \
    --boot-disk-size=30GB
elif [[ "$STATUS" != "RUNNING" ]]; then
  gcloud compute instances start "$INSTANCE" --zone="$ZONE"
fi
```

Install Docker and the Compose plugin on that VM, and add your user to the `docker` group. Reach it by the same name:

```bash
gcloud compute ssh "$INSTANCE" --zone="$ZONE"
```

## 2. Put this checkout on the VM

Commit and push this working tree, then on `version-server`:

```bash
git clone https://github.com/RunYouCleverBoy/VersionServer.git
cd VersionServer
```

## 3. Copy the signing certificate and current data

On the machine that is already running the container:

```bash
docker cp version-server:/var/lib/version-server/jwt/jwt.crt .
docker cp version-server:/var/lib/version-server/jwt/jwt.key .
docker cp version-server:/var/lib/version-server/store.json .
docker cp version-server:/var/lib/version-server/files .
```

Copy those files to the existing `version-server` instance (`gcloud compute scp --zone="$ZONE"`), then on that instance:

```bash
sudo mkdir -p /var/lib/version-server/jwt /var/lib/version-server/files
sudo cp jwt.crt jwt.key /var/lib/version-server/jwt/
sudo cp store.json /var/lib/version-server/
sudo cp -a files/. /var/lib/version-server/files/
sudo chmod 600 /var/lib/version-server/jwt/jwt.key
```

`store.json` and `files/` carry the current users and published artifacts. Skip them when the VM should start empty.

## 4. Write `.env` before the first start

Create `.env` in the checkout. Compose still requires `VERSION_SERVER_JWT_SECRET`. Signing uses the certificate when both certificate paths are set.

```bash
VERSION_SERVER_DATA_DIR=/var/lib/version-server
VERSION_SERVER_JWT_SECRET=<the long random value from the source machine's .env>
VERSION_SERVER_JWT_CERT=/var/lib/version-server/jwt/jwt.crt
VERSION_SERVER_JWT_KEY=/var/lib/version-server/jwt/jwt.key
VERSION_SERVER_PORT=8080
VERSION_SERVER_UPLOAD_MAX_BYTES=1073741824
VERSION_SERVER_HTTPS_PORT=8443
```

Leave the TLS variables empty to serve HTTP. The certificate paths are paths inside the container. The host directory `/var/lib/version-server` is mounted at that same path.

Set both `VERSION_SERVER_JWT_CERT` and `VERSION_SERVER_JWT_KEY` before the first start. When both are empty, `scripts/update.sh` creates a new certificate in the data directory, and tokens signed by the copied certificate are rejected.

```bash
chmod 600 .env
```

## 5. Start

```bash
bash scripts/update.sh
```

The UI is `http://<external-ip>:8080/`.

A copied `store.json` keeps the current users. A missing `store.json` creates `admin` / `admin-pass`. Change that password before port 8080 is open to the internet.

## HTTPS

Create a TLS certificate on the VM and point `.env` at it. Do not commit the certificate or copy it into the image.

```bash
openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \
  -keyout /var/lib/version-server/tls/server.key \
  -out /var/lib/version-server/tls/server.crt \
  -subj "/CN=<external-ip-or-dns-name>" \
  -addext "subjectAltName=DNS:<dns-name>,IP:<external-ip>"
chmod 600 /var/lib/version-server/tls/server.key
```

```bash
VERSION_SERVER_TLS_CERT=/var/lib/version-server/tls/server.crt
VERSION_SERVER_TLS_KEY=/var/lib/version-server/tls/server.key
```

Add `tcp:8443` to the existing `version-server-http` rule, then run `bash scripts/update.sh` again. HTTPS is `https://<external-ip>:8443/`. HTTP on port 8080 stays up.
