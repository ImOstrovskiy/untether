APP := dist/Untether.app
# Stable identity from scripts/make-signing-identity.sh if present, else ad-hoc.
SIGN_ID := $(shell security find-identity -p codesigning 2>/dev/null | grep -q "Untether Dev" && echo "Untether Dev" || echo -)

.PHONY: app test install clean

app:
	CGO_ENABLED=1 go build -o dist/untether .
	rm -rf $(APP) && mkdir -p $(APP)/Contents/MacOS
	cp Info.plist $(APP)/Contents/
	cp dist/untether $(APP)/Contents/MacOS/
	codesign --force --sign "$(SIGN_ID)" $(APP)

test:
	go test ./...

install: app
	rm -rf /Applications/Untether.app && cp -R $(APP) /Applications/

clean:
	rm -rf dist
