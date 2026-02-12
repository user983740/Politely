#!/bin/bash

BACKEND_PID=""
CONTINUOUS_PID=""

cleanup() {
  echo "[dev-local] Shutting down..."
  if [ -n "$CONTINUOUS_PID" ]; then
    kill "$CONTINUOUS_PID" 2>/dev/null
    wait "$CONTINUOUS_PID" 2>/dev/null
  fi
  if [ -n "$BACKEND_PID" ]; then
    kill "$BACKEND_PID" 2>/dev/null
    wait "$BACKEND_PID" 2>/dev/null
  fi
}

trap cleanup EXIT INT TERM

# Check if backend is running on port 8080
if nc -z localhost 8080 2>/dev/null; then
  echo "[dev-local] Backend already running on port 8080"
else
  echo "[dev-local] Starting backend server (with auto-reload)..."

  # 1) bootRun with DevTools (auto-restarts when classes change)
  (cd backend && ./gradlew bootRun) &
  BACKEND_PID=$!

  # 2) Continuous build (recompiles on source change → triggers DevTools restart)
  (cd backend && ./gradlew classes --continuous -x test --quiet) &
  CONTINUOUS_PID=$!

  echo "[dev-local] Waiting for backend to start..."
  for i in $(seq 1 30); do
    if nc -z localhost 8080 2>/dev/null; then
      echo "[dev-local] Backend started! (auto-reload enabled)"
      break
    fi
    sleep 2
  done

  if ! nc -z localhost 8080 2>/dev/null; then
    echo "[dev-local] Backend failed to start within 60 seconds"
    exit 1
  fi
fi

npx vite --mode localdev
