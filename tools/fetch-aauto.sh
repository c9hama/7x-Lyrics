#!/bin/sh
# Fetches the unreleased Android Auto SDK (aauto.aar) used for the legacy projection entry.
# It isn't open source, so it's kept out of git; this pulls the copy Fermata Auto ships.
set -e
cd "$(dirname "$0")/.."
mkdir -p app/libs
curl -fL -o app/libs/aauto.aar \
  https://github.com/AndreyPavlenko/Fermata/raw/master/fermata/lib/auto/aauto.aar
echo "Saved app/libs/aauto.aar"
