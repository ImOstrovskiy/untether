APP := dist/PixelHotspot.app

.PHONY: app test install clean

app:
	CGO_ENABLED=1 go build -o dist/pixel-hotspot .
	rm -rf $(APP) && mkdir -p $(APP)/Contents/MacOS
	cp Info.plist $(APP)/Contents/
	cp dist/pixel-hotspot $(APP)/Contents/MacOS/
	codesign --force --sign - $(APP)

test:
	go test ./...

install: app
	rm -rf /Applications/PixelHotspot.app && cp -R $(APP) /Applications/

clean:
	rm -rf dist
