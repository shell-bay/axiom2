#!/bin/bash
# Fixed Gradle Wrapper for Android Studio / GitHub Actions
# This ensures the correct Gradle version is used regardless of the system default.

set -e
set -x

# Check if gradle-wrapper.jar exists
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "gradle-wrapper.jar not found. Please ensure the gradle/wrapper folder is committed."
    exit 1
fi

# Execute the wrapper jar using the provided properties file
java -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
