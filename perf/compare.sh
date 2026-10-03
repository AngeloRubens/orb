#!/bin/bash
#
# Runs the same RMI-IIOP load against one GlassFish install with different
# ORB builds swapped in, and appends one RESULT line per run to $OUT.
#
#   compare.sh <rounds> <config>...
#
# Configs (jars are looked up in $JARS):
#   A    GlassFish as released: its ORB, its orb-iiop, 1 KB fragments
#   A8   as A with 8 KB fragments: only GlassFish's default changed
#   B    ORB master without these changes, released orb-iiop, 1 KB fragments
#   B8   as B with 8 KB fragments, buffers as large as a fragment
#   B8d  as B8 with orb-iiop starting messages in a 1 KB buffer (only that change)
#   Be   as B with orb-iiop resolving the EJB service once (only that change)
#   Bx0  as B with Exousia built from its 3.x branch on the fork
#   Bx   as B with Exousia built from the fork's perf/authorization-checks-3.x
#   B64  as B with 64 KB fragments, buffers as large as a fragment
#   C8   as C with 8 KB fragments, buffers starting at 1 KB
#   C    ORB and orb-iiop with these changes, 1 KB fragments (the default)
#   C64  as C with 64 KB fragments, buffers starting at 1 KB
#   Cnq  as C but with internal-api from master: every change except the work queue
#   Bq   as B but with internal-api with these changes: only the work queue
#   Bltq as B with the work queue as first rewritten, on LinkedTransferQueue
#   Cb   as C but with the ORB built from the workflow's baseline_ref: the
#        change under test against the stack under it, rather than master
#   Cnf  as C but without processing the next fragment inline
#   Cltq as C but with the work queue on LinkedTransferQueue
#   C64ltq as C64 but with the work queue on LinkedTransferQueue
#
# Rounds interleave the configs so that drift on the machine hits all of them.
#
# Client and server share the runner. With SERVER_CPUS and CLIENT_CPUS set
# (taskset CPU lists) each gets its own cores, so neither takes CPU from the
# other. Each RESULT line also gets the server's CPU use, read from /proc
# over a window inside the client's measurement: serverCores (cores busy on
# average) and serverUsPerCall (server CPU microseconds per call). That one
# depends far less on the machine than throughput does: it says how much
# work the server did, not how fast a shared machine let it go. Over the
# same window, from /proc/<pid>/io: the server's write and read system
# calls per call (serverWritesPerCall, serverReadsPerCall) and the bytes it
# wrote and read per call (serverBytesOutPerCall, serverBytesInPerCall).
#
# Environment: GF (glassfish8 dir), JARS, CLIENT (load client jar), RUN_JAVA
# (JDK used to run), OUT, JFR_DIR, and optionally W, D, TH, SERVER_CPUS,
# CLIENT_CPUS.
set -u

MOD=$GF/glassfish/modules
W=${W:-15}; D=${D:-45}; TH=${TH:-8}
# The CPU window starts CPU_SKIP seconds into the client's measurement, to
# leave room for the client to start, and ends CPU_TAIL seconds before it.
CPU_SKIP=10; CPU_TAIL=5
HZ=$(getconf CLK_TCK)
SCENARIOS=${SCENARIOS:-small graph large}
BEANS=${BEANS:-GreeterBean}
export JAVA_HOME=$RUN_JAVA AS_JAVA=$RUN_JAVA
mkdir -p "$JFR_DIR"

# The Exousia jar GlassFish ships, kept so that every configuration but Bx
# and Bx0 runs with it.
EXOUSIA_JAR=$(ls "$MOD" | grep -i '^exousia.*\.jar$' | head -1)
if [ -n "$EXOUSIA_JAR" ] && [ ! -f "$JARS/exousia-released.jar" ]; then
    cp "$MOD/$EXOUSIA_JAR" "$JARS/exousia-released.jar"
fi
FAILED=0

install() {   # orb internal-api orb-iiop fragment-size [exousia]
    # Checked before anything is stopped. A jar that was never built - a
    # configuration asked for without the workflow input that produces its
    # jar - would otherwise leave the previous configuration's jar in the
    # modules directory, and the run would measure that one again under this
    # configuration's name.
    local jar
    for jar in "$1" "$2" "$3"; do
        [ -f "$JARS/$jar" ] || { echo "$JARS/$jar was not built"; return 1; }
    done
    "$GF/bin/asadmin" stop-domain >/dev/null 2>&1
    cp "$JARS/$1" "$MOD/glassfish-corba-orb.jar" || return 1
    cp "$JARS/$2" "$MOD/glassfish-corba-internal-api.jar" || return 1
    cp "$JARS/$3" "$MOD/orb-iiop.jar" || return 1
    if [ -n "$EXOUSIA_JAR" ]; then
        cp "$JARS/${5:-exousia-released.jar}" "$MOD/$EXOUSIA_JAR" || return 1
    fi
    rm -rf "$GF/glassfish/domains/domain1/osgi-cache"
    "$GF/bin/asadmin" start-domain >/dev/null || return 1
    "$GF/bin/asadmin" set configs.config.server-config.iiop-service.orb.message-fragment-size=$4 >/dev/null
    "$GF/bin/asadmin" restart-domain >/dev/null
    sleep 5
}

server_pid() {
    "$RUN_JAVA/bin/jcmd" -l | awk '/GlassFishMain/ && /domain1/ {print $1; exit}'
}

cpu_ticks() {   # pid: user + system time so far, in clock ticks
    awk '{print $14 + $15}' "/proc/$1/stat"
}

io_counts() {   # pid: write calls, read calls, bytes written, bytes read so far
    awk '/^syscw:/ {w = $2} /^syscr:/ {r = $2} /^wchar:/ {wb = $2} /^rchar:/ {rb = $2}
         END {print w, r, wb, rb}' "/proc/$1/io"
}

run() {   # config round scenario client-properties bean
    local label=$1-r$2-$3-$5
    local pid; pid=$(server_pid)
    if [ -n "${SERVER_CPUS:-}" ]; then
        # Every thread the server has; the ones it starts later inherit it.
        taskset -a -p -c "$SERVER_CPUS" "$pid" >/dev/null || echo "server not pinned for $label"
    fi
    "$RUN_JAVA/bin/jcmd" "$pid" JFR.start name=$label settings=profile \
        delay=${W}s duration=${D}s filename="$JFR_DIR/server-$label.jfr" >/dev/null \
        || echo "JFR not started for $label"
    local log="$JFR_DIR/client-$label.log"
    local client=("$GF/glassfish/bin/appclient" -client "$CLIENT")
    if [ -n "${CLIENT_CPUS:-}" ]; then
        client=(taskset -c "$CLIENT_CPUS" "${client[@]}")
    fi
    VMARGS="-Dscenario=$3 -Dbean=$5 -Dthreads=$TH -Dwarmup=$W -Dseconds=$D $4" \
        "${client[@]}" > "$log" 2>&1 &
    local client_pid=$!
    local cpu=""
    if [ "$D" -gt $((CPU_SKIP + CPU_TAIL)) ]; then
        sleep $((W + CPU_SKIP))
        local t0 c0 t1 c1 io0 io1
        t0=$(date +%s.%N); c0=$(cpu_ticks "$pid"); io0=$(io_counts "$pid")
        sleep $((D - CPU_SKIP - CPU_TAIL))
        t1=$(date +%s.%N); c1=$(cpu_ticks "$pid"); io1=$(io_counts "$pid")
        # Seconds, cores busy, then per second: writes, reads, bytes out, bytes in.
        cpu=$(LC_ALL=C awk -v c="$((c1 - c0))" -v hz="$HZ" -v t0="$t0" -v t1="$t1" \
            -v a="$io0" -v b="$io1" 'BEGIN {
                split(a, x, " "); split(b, y, " "); s = t1 - t0
                printf "%.3f %.3f %.1f %.1f %.1f %.1f", s, c / hz / s,
                    (y[1] - x[1]) / s, (y[2] - x[2]) / s, (y[3] - x[3]) / s, (y[4] - x[4]) / s}')
    fi
    wait "$client_pid"
    # After the measurement: live log records, which a logging system stuck
    # before full service keeps in its startup queue.
    "$RUN_JAVA/bin/jcmd" "$pid" GC.class_histogram 2>/dev/null \
        | grep -E 'GlassFishLogRecord|StartupQueue|java.util.logging.LogRecord$' \
        | sed "s/^/$label /" >> "$JFR_DIR/histogram.txt"
    # Once: the logging status from inside the server, if the probe was built.
    if [ -n "${LOG_PROBE:-}" ] && [ ! -f "$JFR_DIR/logstatus.txt" ]; then
        "$LOG_PROBE_JAVA/bin/java" -cp "$LOG_PROBE" Attach "$pid" "$LOG_PROBE/logstatus-agent.jar" "$JFR_DIR/logstatus.txt" \
            || echo "log probe failed for $label"
    fi
    # Whether the debug messages of the per-call code reach the log at all.
    local server_log=$GF/glassfish/domains/domain1/logs/server.log
    echo "$label server.log lines=$(wc -l < "$server_log") getEjbDescriptor=$(grep -c 'getEjbDescriptor' "$server_log") FINE=$(grep -c 'FINE' "$server_log")" \
        >> "$JFR_DIR/histogram.txt"
    if grep -q 'RESULT' "$log"; then
        grep 'RESULT' "$log" | sed "s/^/config=$1 round=$2 /" | while read -r line; do
            if [ -n "$cpu" ]; then
                local tput=${line##*throughput=}; tput=${tput%%/s*}
                line="$line $(LC_ALL=C awk -v m="$cpu" -v t="$tput" 'BEGIN {
                    split(m, v, " "); if (t <= 0) t = 1
                    printf "serverCores=%.3f serverUsPerCall=%.1f serverWritesPerCall=%.2f serverReadsPerCall=%.2f serverBytesOutPerCall=%.0f serverBytesInPerCall=%.0f",
                        v[2], v[2] / t * 1e6, v[3] / t, v[4] / t, v[5] / t, v[6] / t}')"
            fi
            echo "$line"
        done | tee -a "$OUT"
    else
        echo "NO RESULT for $label; the client said:"
        FAILED=$((FAILED + 1))
        grep -vE '^\s*$' "$log" | grep -iE 'exception|error|caused by' | head -15
    fi
    sleep 3
}

rounds=$1; shift
for r in $(seq 1 "$rounds"); do
    for c in "$@"; do
        client=""
        case $c in
            A)   install orb-released.jar internal-api-released.jar orb-iiop-released.jar 1024 ;;
            A8)  install orb-released.jar internal-api-released.jar orb-iiop-released.jar 8192
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=8192 -Dcom.sun.corba.ee.giop.ORBBufferSize=8192" ;;
            B)   install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar 1024 ;;
            B64) install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar 65536
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=65536 -Dcom.sun.corba.ee.giop.ORBBufferSize=65536" ;;
            C)   install orb-patched.jar  internal-api-patched.jar  orb-iiop-patched.jar  1024 ;;
            C64) install orb-patched.jar  internal-api-patched.jar  orb-iiop-patched.jar  65536
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=65536 -Dcom.sun.corba.ee.giop.ORBBufferSize=1024" ;;
            Cnq) install orb-patched.jar  internal-api-master.jar   orb-iiop-patched.jar  1024 ;;
            Bq)  install orb-master.jar   internal-api-patched.jar  orb-iiop-released.jar 1024 ;;
            Bltq) install orb-master.jar  internal-api-ltq.jar      orb-iiop-released.jar 1024 ;;
            B8)  install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar 8192
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=8192 -Dcom.sun.corba.ee.giop.ORBBufferSize=8192" ;;
            Bx0) install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar 1024 exousia-3x.jar ;;
            Bx)  install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar 1024 exousia-patched.jar ;;
            Be)  install orb-master.jar   internal-api-master.jar   orb-iiop-ejbsvc.jar   1024 ;;
            B8d) install orb-master.jar   internal-api-master.jar   orb-iiop-buffer.jar   8192
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=8192 -Dcom.sun.corba.ee.giop.ORBBufferSize=1024" ;;
            C8)  install orb-patched.jar  internal-api-patched.jar  orb-iiop-patched.jar  8192
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=8192 -Dcom.sun.corba.ee.giop.ORBBufferSize=1024" ;;
            Cb)  install orb-baseline.jar internal-api-baseline.jar orb-iiop-patched.jar  1024 ;;
            Cnf) install orb-noinline.jar internal-api-patched.jar  orb-iiop-patched.jar  1024 ;;
            Cltq) install orb-patched.jar internal-api-ltq.jar      orb-iiop-patched.jar  1024 ;;
            C64ltq) install orb-patched.jar internal-api-ltq.jar    orb-iiop-patched.jar  65536
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=65536 -Dcom.sun.corba.ee.giop.ORBBufferSize=1024" ;;
            *)   echo "unknown config $c"; exit 1 ;;
        esac || { echo "config $c did not start"; exit 1; }
        for bean in $BEANS; do
            for sc in $SCENARIOS; do
                run "$c" "$r" "$sc" "$client" "$bean"
            done
        done
    done
done
"$GF/bin/asadmin" list-log-levels 2>/dev/null | grep -iE 'iiop|exousia|invocation|^org.glassfish |^\.|root' > "$JFR_DIR/log-levels.txt"
"$GF/bin/asadmin" stop-domain >/dev/null 2>&1
# A run that measured nothing must not look like a success.
if [ "$FAILED" -gt 0 ]; then
    echo "$FAILED measurements produced no result"
    exit 1
fi
