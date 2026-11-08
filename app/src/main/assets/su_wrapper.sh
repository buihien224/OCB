#!/system/bin/sh
if [ "$1" = "-c" ] && [ -n "$2" ]; then
  printf '%s\n' "$2" | nc -U /data/local/tmp/su.sock
else
  printf '%s\n' "${1:-id}" | nc -U /data/local/tmp/su.sock
fi
