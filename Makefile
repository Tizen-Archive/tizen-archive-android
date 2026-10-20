# Uses a user-level JDK 21 when JAVA_HOME is not set (see README).
JAVA_HOME ?= $(HOME)/.local/share/jdks/temurin-21
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

ANDROID_HOME ?= $(HOME)/.local/share/android-sdk
export ANDROID_HOME

.PHONY: check test clean reproducible

check test:
	./gradlew check --no-daemon

clean:
	./gradlew clean --no-daemon

# Builds the release APK twice from clean clones of HEAD and compares them (commit your changes first).
reproducible:
	scripts/check-reproducible.sh
