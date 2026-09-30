APP := dist/PixelHotspot.app
# Stable identity from scripts/make-signing-identity.sh if present, else ad-hoc.
SIGN_ID := $(shell security find-identity -p codesigning 2>/dev/null | grep -q "Pixel Hotspot Dev" && echo "Pixel Hotspot Dev" || echo -)

.PHONY: app test install clean

app:
	CGO_ENABLED=1 go build -o dist/pixel-hotspot .
	rm -rf $(APP) && mkdir -p $(APP)/Contents/MacOS
	cp Info.plist $(APP)/Contents/
	cp dist/pixel-hotspot $(APP)/Contents/MacOS/
	codesign --force --sign "$(SIGN_ID)" $(APP)

test:
	go test ./...

install: app
	rm -rf /Applications/PixelHotspot.app && cp -R $(APP) /Applications/

clean:
	rm -rf dist
