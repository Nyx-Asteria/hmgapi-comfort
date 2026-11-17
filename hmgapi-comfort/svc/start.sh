#!/system/bin/sh
# Non-root starter script invoked by hmgapid once sys.boot_completed=1 (or when enabled live).
# Waits up to 60 s for ModApps.sync APK install/update and ActivityManager user-0 readiness.
i=0
while [ "$i" -lt 30 ]; do
    if [ -d /data/data/dev.lemoyne.comfort ] || /system/bin/pm path dev.lemoyne.comfort >/dev/null 2>&1; then
        out=$(/system/bin/am startservice --user 0 -n dev.lemoyne.comfort/.ComfortService 2>&1)
        case "$out" in
            *Error*|*Exception*|*not\ exist*) ;;
            *) exit 0 ;;
        esac
    fi
    i=$((i + 1))
    /system/bin/sleep 2
done
/system/bin/am startservice --user 0 -n dev.lemoyne.comfort/.ComfortService
