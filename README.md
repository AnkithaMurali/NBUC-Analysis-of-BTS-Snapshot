# NBUC-Analysis-of-BTS-Snapshot

# SETUP

## INSTALLATION OF GRAYLOG AND ITS DEPENDENCIES 

### 1. Update System

sudo dnf update -y
sudo dnf install wget curl gnupg2 -y


### 2. Install Java 17 (OpenJDK)

Graylog 6+ requires Java 17.
sudo dnf install java-17-openjdk java-17-openjdk-devel -y
java -version


### 3. Install MongoDB 6.0

Create the repo file:
cat <<EOF | sudo tee /etc/yum.repos.d/mongodb-org-6.0.repo
[mongodb-org-6.0]
name=MongoDB Repository
baseurl=https://repo.mongodb.org/yum/redhat/9/mongodb-org/6.0/x86_64/
gpgcheck=1
enabled=1
gpgkey=https://www.mongodb.org/static/pgp/server-6.0.asc
EOF
Then install MongoDB:
sudo dnf install -y mongodb-org
sudo systemctl enable mongod --now

### 4. Install OpenSearch 2.x (as Elasticsearch is deprecated)

Add OpenSearch repo:
sudo rpm --import https://artifacts.opensearch.org/publickeys/opensearch.pgp
cat <<EOF | sudo tee /etc/yum.repos.d/opensearch.repo
[opensearch]
name=OpenSearch repository
baseurl=https://artifacts.opensearch.org/releases/bundle/opensearch/2.x/yum/
gpgcheck=1
gpgkey=https://artifacts.opensearch.org/publickeys/opensearch.pgp
enabled=1
autorefresh=1
type=rpm-md
EOF
Install and start:
sudo dnf install opensearch -y
Graylog 6+ Installation on CentOS 9
sudo systemctl enable opensearch.service --now


### 5. Install Graylog 6.x

Download the repo and install:
sudo rpm -Uvh
https://packages.graylog2.org/repo/packages/graylog-6.0-repository_latest.rpm
sudo dnf install graylog-server -y
Set the root password secret and hash in /etc/graylog/server/server.conf
Generate password hash:
echo -n yourpassword | sha256sum
Start Graylog:
sudo systemctl daemon-reexec
sudo systemctl enable graylog-server --now
Graylog will be available on: http://<your-ip>:9000



# Usage

After setting up the environment, here’s how to actually use the system to process BTS snapshots and interact with Graylog.
### 1. Start the Graylog Stack
If using Docker:
docker compose up -d

This will start:
MongoDB
OpenSearch
Graylog
Graylog UI will be available at:
http://localhost:9000

### 2. Log In to Graylog
Default credentials:
Username: admin
Password: the one corresponding to your SHA256 hash
Once logged in, complete the initial CA setup (only on first run).

### 3. Install Your Custom Snapshot Plugin
If not already installed:
sudo cp target/graylog-plugin-hello-1.0.0.jar /usr/share/graylog-server/plugin/
sudo systemctl restart graylog-server

If using Docker, mount the plugin directory in docker-compose.yml:
volumes:
  - ./plugins:/usr/share/graylog/plugin

Then restart:
docker compose restart graylog


### 4. Upload BTS Snapshot Files
You can process snapshot files in two ways:
a) Through Graylog Inputs
Go to System → Inputs
Choose input type (e.g., GELF UDP/TCP or FileBeat)
Start input
Send your snapshot log data to Graylog
If your custom plugin extracts snapshot content automatically, simply place snapshot files in the configured directory.

### 5. View Processed Snapshot Data
Navigate to:
Search → Logs
Apply filters, time range, fields
Visualize snapshot statistics
You can also create:
Dashboards
Alerts
Pipelines
Data transformations

### 6. Verify Plugin Execution
To confirm your plugin is running:
Docker
docker logs graylog -f

Systemd
sudo journalctl -u graylog-server -f

You should see:
Hello World Plugin Module Loaded!
(or your plugin-specific log messages)

### 7. Stopping the Environment
For Docker:
docker compose down

For system installation:
sudo systemctl stop graylog-server
sudo systemctl stop opensearch
sudo systemctl stop mongod

