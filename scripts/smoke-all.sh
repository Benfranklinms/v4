#!/bin/bash
# Runs the smoke test on this build and on the latest published APK (arm64, via the emulator's ARM translation).
bash scripts/smoke.sh "" ci; A=$?
curl -sL -o /tmp/published.apk "https://github.com/${GITHUB_REPOSITORY}/releases/latest/download/v4-0.1.0.apk" && bash scripts/smoke.sh /tmp/published.apk published; B=$?
echo "ci=$A published=$B"
exit $A
