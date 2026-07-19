GRADLE := ./gradlew
APK_DEBUG := app/build/outputs/apk/debug/app-debug.apk

.PHONY: help format lint test screenshots-verify screenshots-record coverage \
	build build-release check install run clean

help:
	@echo "Common targets:"
	@echo "  make format             - auto-fix Kotlin style (ktlintFormat)"
	@echo "  make lint               - check Kotlin style (ktlintCheck)"
	@echo "  make test               - run unit tests"
	@echo "  make screenshots-verify - check Roborazzi screenshots against baseline"
	@echo "  make screenshots-record - re-record Roborazzi baseline after an intentional UI change"
	@echo "  make coverage           - generate Kover HTML coverage report (app/build/reports/kover/htmlDebug/)"
	@echo "  make build              - assemble debug APK"
	@echo "  make build-release      - assemble signed release APK (needs RELEASE_KEYSTORE_* env vars)"
	@echo "  make check              - format + lint + test (what CI runs, roughly)"
	@echo "  make install            - install the debug APK on a connected device/emulator (needs adb)"
	@echo "  make run                - install + launch the debug build (needs adb)"
	@echo "  make clean              - ./gradlew clean"

format:
	$(GRADLE) ktlintFormat

lint:
	$(GRADLE) ktlintCheck

test:
	$(GRADLE) test

screenshots-verify:
	$(GRADLE) verifyRoborazziDebug

screenshots-record:
	$(GRADLE) recordRoborazziDebug

coverage:
	$(GRADLE) koverHtmlReportDebug

build:
	$(GRADLE) assembleDebug

build-release:
	$(GRADLE) assembleRelease

check: format lint test

install: build
	adb install -r $(APK_DEBUG)

run: install
	adb shell am start -n com.urlradiodroid/.ui.MainActivity

clean:
	$(GRADLE) clean
