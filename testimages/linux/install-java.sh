#!/bin/bash
set -euxo pipefail

echo "AGENT_IMAGE value is: $AGENT_IMAGE"
if [ -z "$AGENT_IMAGE" ]; then
  echo "AGENT_IMAGE variable is not set"
  exit 1
fi

sudo apt-get update && sudo apt-get install openjdk-21-jre-headless -y && java -version

if [[ "$AGENT_IMAGE" == *non-standard-java ]]; then
  sudo mv /usr/bin/java /usr/bin/non-standard-java
  /usr/bin/non-standard-java -version
fi