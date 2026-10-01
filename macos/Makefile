VERSION ?= 0.2.0-dev
APP := dist/Untether.app
DMG := dist/Untether-$(VERSION).dmg
# Stable identity if present (scripts/make-signing-identity.sh locally, "Untether Release" in CI), else ad-hoc.
SIGN_ID ?= $(shell security find-identity -p codesigning 2>/dev/null | grep -q "Untether Dev" && echo "Untether Dev" || echo -)

.PHONY: app dmg test install clean

app:
	CGO_ENABLED=1 go build -ldflags "-s -w -X main.version=$(VERSION)" -o dist/untether .
	rm -rf $(APP) && mkdir -p $(APP)/Contents/MacOS $(APP)/Contents/Resources
	cp Info.plist $(APP)/Contents/
	cp AppIcon.icns $(APP)/Contents/Resources/
	plutil -replace CFBundleShortVersionString -string "$(VERSION)" $(APP)/Contents/Info.plist
	cp dist/untether $(APP)/Contents/MacOS/
	codesign --force --timestamp=none --sign "$(SIGN_ID)" $(APP)

# Drag-to-install disk image: the app next to a link to /Applications. The image itself stays
# unsigned: Gatekeeper rejects a self-signed one outright, while an unsigned one just mounts.
dmg: app
	rm -rf dist/dmg && mkdir -p dist/dmg
	cp -R $(APP) dist/dmg/ && ln -s /Applications dist/dmg/Applications
	hdiutil create -quiet -volname Untether -srcfolder dist/dmg -ov -format UDZO $(DMG)
	rm -rf dist/dmg

test:
	go test ./...

install: app
	rm -rf /Applications/Untether.app && cp -R $(APP) /Applications/

clean:
	rm -rf dist
