#!/usr/bin/env bash
# PreToolUse(Bash): make sure the Docker daemon is up before the URL shortener is started.
# Only acts on commands that start the app (spring-boot:run or docker compose up).
input=$(cat)
echo "$input" | grep -q -E 'spring-boot:run|docker compose up|docker-compose up' || exit 0

docker info >/dev/null 2>&1 && exit 0

echo "Docker is not running; starting Docker Desktop..." >&2
open -a Docker 2>/dev/null || { echo "Could not launch Docker Desktop. Start it and retry." >&2; exit 2; }

for _ in $(seq 1 60); do
  docker info >/dev/null 2>&1 && { echo "Docker is up." >&2; exit 0; }
  sleep 2
done
echo "Docker did not become ready within 120s. Start it manually and retry." >&2
exit 2
