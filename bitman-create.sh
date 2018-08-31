#!/usr/bin/env bash

set -o xtrace
set -o errexit
set -o nounset
set -o pipefail

curl -F "amount=1" -F "sender=Joe%20Sixpack" -F "recipient=Jane%20Random" -F "routingnr=123456" -F "accountnr=123456" -F "attachment=$(uuidgen -r)" http://bitman.lignab.com/tokens

echo "+OK"
