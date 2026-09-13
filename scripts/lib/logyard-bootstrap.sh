#!/usr/bin/env bash

# Confine retry policy to the pinned bootstrap's checksum-verified GET downloads.
logyard_bootstrap_run() (
  curl() {
    command curl --retry 2 --retry-all-errors --retry-max-time 120 \
      --connect-timeout 15 --max-time 60 "$@"
  }
  export -f curl
  "$@"
)
