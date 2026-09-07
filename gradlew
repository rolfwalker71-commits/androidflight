#!/bin/sh

#
# Gradle start up script for POSIX generated for FlightBuddy.
#

##############################################################################
# Resolve APP_HOME
##############################################################################
app_path=$0
while [ -h "$app_path" ]; do
    APP_HOME=${app_path%"${app_path##*/}"}
    ls=$(ls -ld "$app_path")
    link=${ls#*' -> '}
    case $link in
      /*) app_path=$link ;;
      *)  app_path=$APP_HOME$link ;;
    esac
done
APP_HOME=$(cd "${app_path%"${app_path##*/}"}." >/dev/null && pwd -P) || exit

APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD=maximum

warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }

cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN*) cygwin=true ;;
  Darwin*) darwin=true ;;
  MSYS*|MINGW*) msys=true ;;
  NONSTOP*) nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# If JAVA_HOME is unset, use gitignored gradle-local.properties, then
# gradle.properties, then common local JDK 17 locations.
read_java_home_prop() {
    _file=$1
    [ -f "$_file" ] || return 1
    _value=$(grep -E '^[[:space:]]*org\.gradle\.java\.home=' "$_file" | tail -n 1 | cut -d= -f2- | tr -d '\r')
    _value=$(printf '%s' "$_value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    [ -n "$_value" ] || return 1
    case $_value in
      "~/"*) _value=$HOME/${_value#~/} ;;
      "~") _value=$HOME ;;
      /*) ;;
      *) _value=$APP_HOME/$_value ;;
    esac
    if [ -x "$_value/bin/java" ]; then
        printf '%s' "$_value"
        return 0
    fi
    return 1
}

# Drop an unusable JAVA_HOME (Windows path, missing JDK) instead of dying.
if [ -n "$JAVA_HOME" ] && [ ! -x "$JAVA_HOME/bin/java" ] && [ ! -x "$JAVA_HOME/jre/sh/java" ]; then
    JAVA_HOME=
fi

if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(read_java_home_prop "$APP_HOME/gradle-local.properties") ||
        JAVA_HOME=$(read_java_home_prop "$APP_HOME/gradle.properties") ||
        true
    if [ -z "$JAVA_HOME" ] && [ -x "$HOME/.jdks/jdk-17/bin/java" ]; then
        JAVA_HOME=$HOME/.jdks/jdk-17
    fi
    if [ -z "$JAVA_HOME" ] && [ -x "$APP_HOME/.jdk/bin/java" ]; then
        JAVA_HOME=$APP_HOME/.jdk
    fi
    [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi

if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ]; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1; then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
    fi
fi

if ! "$cygwin" && ! "$darwin" && ! "$nonstop"; then
    case $MAX_FD in
      max*) MAX_FD=$(ulimit -H -n) || warn "Could not query maximum file descriptor limit" ;;
    esac
    case $MAX_FD in
      '' | soft) : ;;
      *) ulimit -n "$MAX_FD" || warn "Could not set maximum file descriptor limit to $MAX_FD" ;;
    esac
fi

if "$cygwin" || "$msys"; then
    APP_HOME=$(cygpath --path --mixed "$APP_HOME")
    CLASSPATH=$(cygpath --path --mixed "$CLASSPATH")
    JAVACMD=$(cygpath --unix "$JAVACMD")
fi

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -classpath "$CLASSPATH" \
        org.gradle.wrapper.GradleWrapperMain \
        "$@"

eval "set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \"\$@\""

exec "$JAVACMD" "$@"
