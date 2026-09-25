#!/bin/bash
#
# Runs the RMI-IIOP fidelity checks against one GlassFish install with
# different ORB builds swapped in, and writes one transcript per run.
#
#   fidelity.sh <config>...
#
# Configs, as in compare.sh (jars are looked up in $JARS):
#   A    GlassFish as released: its ORB, its orb-iiop
#   B    ORB master without these changes
#   C    ORB and orb-iiop with these changes
#
# Each config is run at every fragment size in $FRAGMENTS, because the
# encoding a payload takes depends on whether it fits in one fragment. The
# small sizes are the point: at 1 KB nearly every case here is written across
# fragments, which is where a buffer that is reused rather than reallocated
# has to be got right.
#
# The client decides pass or fail for the cases whose answer is not in doubt.
# This script adds the other half: the transcripts of B and C must match, so a
# change of behaviour is caught even where nobody wrote down what the
# behaviour should be.
#
# Environment: GF (glassfish8 dir), JARS, CLIENT (fidelity client jar),
# RUN_JAVA (JDK used to run), OUT_DIR.
set -u

MOD=$GF/glassfish/modules
FRAGMENTS=${FRAGMENTS:-1024 65536}
BEANS=${BEANS:-GreeterBean}
export JAVA_HOME=$RUN_JAVA AS_JAVA=$RUN_JAVA
mkdir -p "$OUT_DIR"

failed=0

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

run() {   # config fragment-size bean client-properties
    local label=$1-f$2-$3
    local log="$OUT_DIR/$label.log"
    VMARGS="-Dbean=$3 $4" "$GF/glassfish/bin/appclient" -client "$CLIENT" > "$log" 2>&1
    local status=$?
    echo "--- $label"
    grep -E '^(ok|note|FAIL|FIDELITY)' "$log" || {
        echo "the client produced no checks; it said:"
        grep -viE '^\s*$' "$log" | grep -iE 'exception|error|caused by' | head -15
    }
    if [ $status -ne 0 ] || ! grep -q '^FIDELITY OK' "$log"; then
        failed=1
    fi
    sleep 2
}

for c in "$@"; do
    for f in $FRAGMENTS; do
        client="-Dcom.sun.corba.ee.giop.ORBFragmentSize=$f"
        case $c in
            A) install orb-released.jar internal-api-released.jar orb-iiop-released.jar "$f" ;;
            B) install orb-master.jar   internal-api-master.jar   orb-iiop-released.jar "$f" ;;
            C) install orb-patched.jar  internal-api-patched.jar  orb-iiop-patched.jar  "$f" ;;
            *) echo "unknown config $c"; exit 1 ;;
        esac || { echo "config $c did not start"; exit 1; }
        for bean in $BEANS; do
            run "$c" "$f" "$bean" "$client"
        done
    done
done
"$GF/bin/asadmin" stop-domain >/dev/null 2>&1

# The unchanged ORB and the changed one have to agree, including on the cases
# the client only reports. Comparing the check lines rather than whole logs
# keeps timings and paths out of it.
for f in $FRAGMENTS; do
    for bean in $BEANS; do
        b="$OUT_DIR/B-f$f-$bean.log"
        c="$OUT_DIR/C-f$f-$bean.log"
        if [ -f "$b" ] && [ -f "$c" ]; then
            if diff <(grep -E '^(ok|note|FAIL)' "$b") <(grep -E '^(ok|note|FAIL)' "$c") > "$OUT_DIR/diff-f$f-$bean.txt"; then
                echo "SAME master and patched agree at $f byte fragments ($bean)"
            else
                echo "DIFFERENT master and patched disagree at $f byte fragments ($bean):"
                cat "$OUT_DIR/diff-f$f-$bean.txt"
                failed=1
            fi
        fi
    done
done

exit $failed
