#!/bin/bash
#
# Runs the same RMI-IIOP load against one GlassFish install with different
# ORB builds swapped in, and appends one RESULT line per run to $OUT.
#
#   compare.sh <rounds> <config>...
#
# Configs (jars are looked up in $JARS):
#   A    GlassFish as released: its ORB, its orb-iiop, 1 KB fragments
#   B    ORB master without these changes, released orb-iiop, 1 KB fragments
#   B64  as B with 64 KB fragments, buffers as large as a fragment
#   C    ORB and orb-iiop with these changes, 1 KB fragments (the default)
#   C64  as C with 64 KB fragments, buffers starting at 1 KB
#
# Rounds interleave the configs so that drift on the machine hits all of them.
#
# Environment: GF (glassfish8 dir), JARS, CLIENT (load client jar), RUN_JAVA
# (JDK used to run), OUT, JFR_DIR, and optionally W, D, TH.
set -u

MOD=$GF/glassfish/modules
W=${W:-15}; D=${D:-45}; TH=${TH:-8}
export JAVA_HOME=$RUN_JAVA AS_JAVA=$RUN_JAVA
mkdir -p "$JFR_DIR"

install() {   # orb internal-api orb-iiop fragment-size
    "$GF/bin/asadmin" stop-domain >/dev/null 2>&1
    cp "$JARS/$1" "$MOD/glassfish-corba-orb.jar"
    cp "$JARS/$2" "$MOD/glassfish-corba-internal-api.jar"
    cp "$JARS/$3" "$MOD/orb-iiop.jar"
    rm -rf "$GF/glassfish/domains/domain1/osgi-cache"
    "$GF/bin/asadmin" start-domain >/dev/null || return 1
    "$GF/bin/asadmin" set configs.config.server-config.iiop-service.orb.message-fragment-size=$4 >/dev/null
    "$GF/bin/asadmin" restart-domain >/dev/null
    sleep 5
}

server_pid() {
    for p in $(pgrep -f 'GlassFishMain.*domain1'); do
        [ "$(readlink /proc/$p/exe)" = "$RUN_JAVA/bin/java" ] && echo $p
    done | head -1
}

run() {   # config round scenario client-properties
    local label=$1-r$2-$3
    "$RUN_JAVA/bin/jcmd" "$(server_pid)" JFR.start name=$label settings=profile \
        delay=${W}s duration=${D}s filename="$JFR_DIR/server-$label.jfr" >/dev/null 2>&1
    VMARGS="-Dscenario=$3 -Dthreads=$TH -Dwarmup=$W -Dseconds=$D $4" \
        "$GF/glassfish/bin/appclient" -client "$CLIENT" 2>&1 \
        | grep -E 'RESULT' | sed "s/^/config=$1 round=$2 /" | tee -a "$OUT"
    sleep 3
}

rounds=$1; shift
for r in $(seq 1 "$rounds"); do
    for c in "$@"; do
        client=""
        case $c in
            A)   install orb-released.jar internal-api-released.jar orb-iiop-released.jar 1024 ;;
            B)   install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar 1024 ;;
            B64) install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar 65536
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=65536 -Dcom.sun.corba.ee.giop.ORBBufferSize=65536" ;;
            C)   install orb-patched.jar  internal-api-patched.jar  orb-iiop-patched.jar  1024 ;;
            C64) install orb-patched.jar  internal-api-patched.jar  orb-iiop-patched.jar  65536
                 client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=65536 -Dcom.sun.corba.ee.giop.ORBBufferSize=1024" ;;
            *)   echo "unknown config $c"; exit 1 ;;
        esac || { echo "config $c did not start"; exit 1; }
        for sc in small graph large; do
            run "$c" "$r" "$sc" "$client"
        done
    done
done
"$GF/bin/asadmin" stop-domain >/dev/null 2>&1
