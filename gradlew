#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
APP_NAME="Gradle"

if [ "$APP_HOME" = "" ] ; then
    APP_HOME="."
fi

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

JAVACMD="java"

exec "$JAVACMD" $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
