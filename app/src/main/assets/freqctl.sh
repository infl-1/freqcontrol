#!/system/bin/sh
# Freq Control root helper.
#  - applies per-cluster CPU min/max frequency through cpufreq sysfs (policy0/2/5/7 on SM8650)
#  - runs the thermal guard: when the SoC or battery gets too hot it restores the original
#    limits and raises fcl_guard_tripped=1 so the SDHMS hook releases its DVFS limits as well
# Usage: freqctl.sh start | stop     (the app and /data/adb/service.d call "start")

DIR=${FC_DIR:-/data/adb/freqctl}
CPUF=${FC_CPUF:-/sys/devices/system/cpu/cpufreq}
THERM=${FC_THERM:-/sys/class/thermal}
BATT=${FC_BATT:-/sys/class/power_supply/battery/temp}
INTERVAL=${FC_INTERVAL:-3}
CONF=$DIR/config
PIDF=$DIR/run.pid

put() { settings put global "$1" "$2" >/dev/null 2>&1; }

case "$1" in
  stop)
    if [ -f "$PIDF" ]; then kill "$(cat "$PIDF")" 2>/dev/null; rm -f "$PIDF"; fi
    exit 0 ;;
  run) ;;
  *)
    if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then exit 0; fi
    if command -v setsid >/dev/null 2>&1; then
      setsid nohup sh "$0" run >/dev/null 2>&1 </dev/null &
    else
      nohup sh "$0" run >/dev/null 2>&1 </dev/null &
    fi
    exit 0 ;;
esac

# ---- daemon ---------------------------------------------------------------------------
mkdir -p "$DIR"
if [ -f "$PIDF" ]; then
  old=$(cat "$PIDF")
  if [ "$old" != "$$" ] && kill -0 "$old" 2>/dev/null; then exit 0; fi
fi
echo $$ > "$PIDF"
trap 'rm -f "$PIDF"; exit 0' TERM INT

up=$(cut -d. -f1 /proc/uptime 2>/dev/null)
up=${up:-9999}
if [ "$up" -lt 180 ]; then
  # fresh boot: forget saved originals, wait for the system and the settings provider
  rm -f "$DIR"/orig_*
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  sleep 25
fi

POLICIES=""
for d in "$CPUF"/policy*; do
  [ -d "$d" ] && POLICIES="$POLICIES ${d##*policy}"
done

BOOT=$(settings get global boot_count 2>/dev/null)
case "$BOOT" in ''|*[!0-9]*) BOOT=-1 ;; esac

# publish the frequency table of every cluster so the app can draw its slider
for p in $POLICIES; do
  d=$CPUF/policy$p
  tbl=""
  if [ -r "$d/scaling_available_frequencies" ]; then
    tbl=$(tr ' ' '\n' < "$d/scaling_available_frequencies" | grep -E '^[0-9]+$' | sort -n | tr '\n' ',' | sed 's/,$//')
  fi
  if [ -z "$tbl" ]; then
    a=""; b=""
    read a < "$d/cpuinfo_min_freq"
    read b < "$d/cpuinfo_max_freq"
    tbl="$a,$b"
  fi
  lo=${tbl%%,*}
  hi=${tbl##*,}
  eval "LO_$p=$lo; HI_$p=$hi"
  put "fcl_cpu${p}_table" "$BOOT:$tbl"
done

# SoC thermal zones (cpu / gpu); battery temperature is read separately
ZONES=""
for z in "$THERM"/thermal_zone*; do
  [ -r "$z/type" ] || continue
  t=""
  read t < "$z/type"
  case "$t" in cpu*|gpu*) ZONES="$ZONES $z/temp" ;; esac
done

SOC=0
BT=0
read_temps() {
  m=0
  for f in $ZONES; do
    [ -r "$f" ] || continue
    v=""
    read v < "$f"
    case "$v" in ''|*[!0-9]*) continue ;; esac
    [ "$v" -gt "$m" ] && m=$v
  done
  SOC=$((m / 1000))
  b=""
  [ -r "$BATT" ] && read b < "$BATT"
  case "$b" in ''|*[!0-9]*) b=0 ;; esac
  BT=$((b / 10))
}

set_freq() { # policy min max
  d=$CPUF/policy$1
  cmin=""; cmax=""
  read cmin < "$d/scaling_min_freq"
  read cmax < "$d/scaling_max_freq"
  if [ "$cmin" != "$2" ] || [ "$cmax" != "$3" ]; then
    # max first so a higher min is accepted, then min, then max again for a lower max
    echo "$3" > "$d/scaling_max_freq"
    echo "$2" > "$d/scaling_min_freq"
    echo "$3" > "$d/scaling_max_freq"
  fi
}

capture_orig() {
  [ -f "$DIR/orig_$1" ] && return
  a=""; b=""
  read a < "$CPUF/policy$1/scaling_min_freq"
  read b < "$CPUF/policy$1/scaling_max_freq"
  echo "$a $b" > "$DIR/orig_$1"
}

restore() {
  if [ -f "$DIR/orig_$1" ]; then
    read a b < "$DIR/orig_$1"
  else
    eval "a=\$LO_$1; b=\$HI_$1"
  fi
  set_freq "$1" "$a" "$b"
}

apply_policy() {
  p=$1
  [ -d "$CPUF/policy$p" ] || return
  eval "en=\${CPU${p}_EN:-0}; mn=\${CPU${p}_MIN:-0}; mx=\${CPU${p}_MAX:-0}; ap=\${AP_$p:-0}"
  if [ "$en" = 1 ] && [ "$mn" -gt 0 ] && [ "$mx" -ge "$mn" ]; then
    if [ "$TRIPPED" = 1 ]; then
      if [ "$ap" = 1 ]; then restore "$p"; eval "AP_$p=0"; fi
      st=guard_tripped
    else
      capture_orig "$p"
      set_freq "$p" "$mn" "$mx"
      eval "AP_$p=1"
      st=active
    fi
  else
    if [ "$ap" = 1 ]; then restore "$p"; eval "AP_$p=0"; fi
    st=disabled
  fi
  eval "last=\${ST_$p:-}"
  if [ "$last" != "$st" ]; then
    put "fcl_cpu${p}_status" "$st"
    eval "ST_$p=$st"
  fi
}

TRIPPED=0
put fcl_guard_tripped 0

while true; do
  [ -f "$CONF" ] && . "$CONF"
  read_temps
  gs=${GUARD_SOC:-85}
  gb=${GUARD_BATT:-45}
  if [ "${GUARD_EN:-1}" = 1 ]; then
    if [ "$TRIPPED" = 0 ]; then
      if [ "$SOC" -ge "$gs" ] || [ "$BT" -ge "$gb" ]; then
        TRIPPED=1
        put fcl_guard_tripped 1
      fi
    else
      if [ "$SOC" -le $((gs - 5)) ] && [ "$BT" -le $((gb - 2)) ]; then
        TRIPPED=0
        put fcl_guard_tripped 0
      fi
    fi
  elif [ "$TRIPPED" = 1 ]; then
    TRIPPED=0
    put fcl_guard_tripped 0
  fi
  for p in $POLICIES; do apply_policy "$p"; done
  sleep "$INTERVAL"
done
