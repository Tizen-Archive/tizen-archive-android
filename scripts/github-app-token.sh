#!/usr/bin/env bash
# Prints a short-lived (1 hour) installation access token of a GitHub App for one repository, so that
# `gh` acts as the app (releases show up as "<app-slug>[bot]").
#
#   GITHUB_APP_CLIENT_ID=Iv23... GITHUB_APP_KEY=~/.config/tizen-companion-installer/github-app.pem \
#     GH_TOKEN=$(scripts/github-app-token.sh [owner/repo]) gh release create ...
#
# With --jwt only the app JWT (valid for 10 minutes) is printed.
set -euo pipefail

mode=token
if [ "${1:-}" = "--jwt" ]; then
    mode=jwt
    shift
fi
repo=${1:-Tizen-Archive/tizen-companion-installer}
client_id=${GITHUB_APP_CLIENT_ID:?set GITHUB_APP_CLIENT_ID (the client ID on the app settings page)}
key=${GITHUB_APP_KEY:?set GITHUB_APP_KEY (path to the private key .pem)}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
# iat is 60 seconds in the past to tolerate clock drift; GitHub accepts at most 10 minutes of lifetime
payload=$(printf '{"iat":%d,"exp":%d,"iss":"%s"}' "$((now - 60))" "$((now + 540))" "$client_id" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key" | b64url)
jwt="$header.$payload.$signature"

if [ "$mode" = jwt ]; then
    echo "$jwt"
    exit 0
fi

api() {
    curl -fsS -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" "$@"
}

installation=$(api "https://api.github.com/repos/$repo/installation" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["id"])')
body=$(printf '{"repositories":["%s"],"permissions":{"contents":"write"}}' "${repo#*/}")
api -X POST -d "$body" "https://api.github.com/app/installations/$installation/access_tokens" \
    | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["token"])'
