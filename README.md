# NBUC – Analysis of BTS Snapshot

This repository contains setup instructions and usage guidelines for installing Graylog, configuring dependencies, and running the BTS Snapshot Analysis plugin.

# Setup
### 1. Install Dependencies
sudo dnf update -y
sudo dnf install wget curl gnupg2 -y

### 2. Install Java 17
sudo dnf install java-17-openjdk java-17-openjdk-devel -y

### 3. Install MongoDB 6.0
sudo dnf install -y mongodb-org
sudo systemctl enable --now mongod

### 4. Install OpenSearch 2.x
sudo dnf install opensearch -y
sudo systemctl enable --now opensearch

### 5. Install Graylog 6.x
sudo rpm -Uvh https://packages.graylog2.org/repo/packages/graylog-6.0-repository_latest.rpm
sudo dnf install graylog-server -y

Set root password hash in:
/etc/graylog/server/server.conf


Start Graylog:

sudo systemctl daemon-reexec
sudo systemctl enable --now graylog-server


Graylog UI:
http://<server-ip>:9000

# Usage
### 1. Start the Stack (Docker Optional)
docker compose up -d

### 2. Log In
Username: admin
Password: <password you hashed>

### 3. Install Custom Plugin

System installation:

sudo cp target/graylog-plugin-hello-1.0.0.jar /usr/share/graylog-server/plugin/
sudo systemctl restart graylog-server


Docker (docker-compose.yml):

volumes:
  - ./plugins:/usr/share/graylog/plugin


Restart:
docker compose restart graylog

### 4. Upload & Process BTS Snapshot Files

You can process snapshot files through:
System → Inputs (GELF UDP/TCP, FileBeat, etc.)
Plugin-configured directory (automatic extraction)

### 5. View Parsed Snapshot Data

Navigate to:
Search → Logs

### 6. Verify Plugin Execution

Systemd:
sudo journalctl -u graylog-server -f

Docker:
docker logs graylog -f

### 7. Stop Services

System install:
sudo systemctl stop graylog-server opensearch mongod


Docker:
docker compose down
