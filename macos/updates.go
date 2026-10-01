package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const releasesAPI = "https://api.github.com/repos/ImOstrovskiy/untether/releases/latest"

// release is the part of GitHub's release JSON we use.
type release struct {
	Tag    string `json:"tag_name"`
	Page   string `json:"html_url"`
	Assets []struct {
		Name string `json:"name"`
		URL  string `json:"browser_download_url"`
	} `json:"assets"`
}

func (r *release) version() string { return strings.TrimPrefix(r.Tag, "v") }

func (r *release) asset(suffix string) string {
	for _, a := range r.Assets {
		if strings.HasSuffix(a.Name, suffix) {
			return a.URL
		}
	}
	return ""
}

func latestRelease() (*release, error) {
	req, err := http.NewRequest(http.MethodGet, releasesAPI, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "Untether/"+version)
	resp, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub: %s", resp.Status)
	}
	var r release
	return &r, json.NewDecoder(resp.Body).Decode(&r)
}

// newer reports whether release version v is newer than current. Both must be plain x.y.z:
// development builds ("0.2.3-dev", "dev") are never offered an update.
func newer(v, current string) bool {
	a, okA := semver(v)
	b, okB := semver(current)
	if !okA || !okB {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return a[i] > b[i]
		}
	}
	return false
}

func semver(s string) ([3]int, bool) {
	var v [3]int
	parts := strings.Split(s, ".")
	if len(parts) != 3 {
		return v, false
	}
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return v, false
		}
		v[i] = n
	}
	return v, true
}

// watchUpdates looks for a new release at start and every 12 hours.
func (a *app) watchUpdates() {
	for {
		r, err := latestRelease()
		switch {
		case err != nil:
			slog.Info("update check failed", "err", err)
		case newer(r.version(), version) && r.asset(".dmg") != "":
			slog.Info("update available", "version", r.version())
			a.mu.Lock()
			a.update = r
			a.mu.Unlock()
			a.render()
		}
		time.Sleep(12 * time.Hour)
	}
}

// installUpdate puts the new release's app in place of this one and starts it.
func (a *app) installUpdate() {
	a.mu.Lock()
	r := a.update
	a.mu.Unlock()
	if r == nil {
		return
	}
	a.setBusy("@updating")
	bundle, err := appBundle()
	if err == nil {
		err = replaceApp(r.asset(".dmg"), bundle)
	}
	a.setBusy("")
	if err != nil {
		a.setNote("Update: " + err.Error())
		return
	}
	slog.Info("updated, restarting", "version", r.version())
	// The new bundle can only start once this process is gone: macOS would just activate it.
	_ = exec.Command("/bin/sh", "-c", `sleep 1; open "$1"`, "sh", bundle).Start()
	uiQuit()
}

// appBundle is the running app's bundle.
func appBundle() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	exe, _ = filepath.EvalSymlinks(exe)
	bundle := filepath.Dir(filepath.Dir(filepath.Dir(exe))) // …/Untether.app/Contents/MacOS/untether
	if filepath.Ext(bundle) != ".app" {
		return "", errors.New("not running from an app bundle")
	}
	return bundle, nil
}

// replaceApp downloads the DMG at url and swaps its Untether.app for bundle, after checking that
// the new app is signed by the same identity as bundle.
func replaceApp(url, bundle string) error {
	tmp, err := os.MkdirTemp("", "untether-update")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	dmg := filepath.Join(tmp, "update.dmg")
	if err := download(url, dmg); err != nil {
		return err
	}
	mnt := filepath.Join(tmp, "mnt")
	if out, err := exec.Command("hdiutil", "attach", "-quiet", "-nobrowse", "-readonly", "-mountpoint", mnt, dmg).CombinedOutput(); err != nil {
		return fmt.Errorf("mount: %s", strings.TrimSpace(string(out)))
	}
	defer exec.Command("hdiutil", "detach", "-quiet", "-force", mnt).Run()

	// The new app must meet this one's designated requirement: same bundle id, same signing certificate.
	out, err := exec.Command("codesign", "-d", "-r-", bundle).CombinedOutput()
	req, ok := strings.CutPrefix(lastLine(string(out)), "designated => ")
	if err != nil || !ok {
		return errors.New("cannot read this app's signature")
	}
	next, old := bundle+".new", bundle+".old"
	_ = os.RemoveAll(next)
	_ = os.RemoveAll(old)
	if out, err := exec.Command("ditto", filepath.Join(mnt, "Untether.app"), next).CombinedOutput(); err != nil {
		return fmt.Errorf("copy: %s", strings.TrimSpace(string(out)))
	}
	if err := exec.Command("codesign", "--verify", "--deep", "--strict", "-R", "="+req, next).Run(); err != nil {
		_ = os.RemoveAll(next)
		return errors.New("the downloaded app is not signed like this one")
	}
	if err := os.Rename(bundle, old); err != nil {
		_ = os.RemoveAll(next)
		return err
	}
	if err := os.Rename(next, bundle); err != nil {
		_ = os.Rename(old, bundle)
		return err
	}
	_ = os.RemoveAll(old)
	return nil
}

func download(url, path string) error {
	resp, err := (&http.Client{Timeout: 5 * time.Minute}).Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download: %s", resp.Status)
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return lines[len(lines)-1]
}
