#!/bin/sh
# Gradle wrapper is intentionally not used by the GitHub Actions workflow.
# The workflow provisions Gradle 8.13 with gradle/actions/setup-gradle.
echo "Use the GitHub Actions workflow, or generate a standard Gradle wrapper in Android Studio." >&2
exit 1
