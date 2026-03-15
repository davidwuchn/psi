#!/usr/bin/env bash
# Run clj-kondo on staged Clojure files and fail if there are warnings or errors.
set -euo pipefail

if [[ $# -eq 0 ]]; then
  exit 0
fi

clj-kondo --cache false --lint "$@"
