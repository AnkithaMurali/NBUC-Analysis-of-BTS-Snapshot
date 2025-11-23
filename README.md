# NBUC-Analysis-of-BTS-Snapshot

# SETUP

# INSTALLATION OF GRAYLOG AND ITS DEPENDENCIES 

# 1. Update System

sudo dnf update -y
sudo dnf install wget curl gnupg2 -y


# 2. Install Java 17 (OpenJDK)

Graylog 6+ requires Java 17.
sudo dnf install java-17-openjdk java-17-openjdk-devel -y
java -version



# 3. Install MongoDB 6.0

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

# 4. Install OpenSearch 2.x (as Elasticsearch is deprecated)

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


# 5. Install Graylog 6.x

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
