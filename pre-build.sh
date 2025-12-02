#!/bin/bash

DEFAULT_SABRE_IMAGE="linagora/esn-sabre:sabre-4_1_5-21-11-2025-rc1"

if [ -n "$1" ]; then
  echo "Using custom SABRE_IMAGE: $1"
  if ! docker image inspect $1 >/dev/null 2>&1; then
      docker pull $1
  fi
  docker tag $1 sabre-test
else
  echo "Using default SABRE_IMAGE"
  docker pull $DEFAULT_SABRE_IMAGE
  docker tag $DEFAULT_SABRE_IMAGE sabre-test
fi
