#!/usr/bin/env bash

set -o xtrace
set -o errexit
set -o nounset
set -o pipefail

curl http://bitman.lignab.com/tokens

echo "+OK"
