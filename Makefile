# Uses a user-level JDK 21 when JAVA_HOME is not set (see README).
JAVA_HOME ?= $(HOME)/.local/share/jdks/temurin-21
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: check test clean

check test:
	./gradlew check --no-daemon

clean:
	./gradlew clean --no-daemon
