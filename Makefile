ADB ?= adb
APP_APK := app/build/outputs/apk/debug/app-debug.apk
APK ?= $(APP_APK)

.PHONY: build install-all install-debug devices clean _install

build:
	./gradlew :app:assembleDebug

devices:
	$(ADB) devices -l

install-all: build
	@$(MAKE) APK="$(APK)" _install

install-debug: install-all

_install:
	@set -e; \
	devices="$$( $(ADB) devices | awk 'NR > 1 && $$2 == "device" { print $$1 }' )"; \
	if [ -z "$$devices" ]; then \
		echo "No authorized Android devices found."; \
		exit 1; \
	fi; \
	for device in $$devices; do \
		echo "Installing $(APK) on $$device..."; \
		$(ADB) -s "$$device" install -r "$(APK)"; \
	done

clean:
	./gradlew clean
