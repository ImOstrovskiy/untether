package main

import (
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fxamacker/cbor/v2"
	"tinygo.org/x/bluetooth"
)

var errNotConnected = errors.New("phone is not connected")

// Phone keeps a BLE connection to the phone while a pairing exists and runs pairing on request.
//
// tinygo's CoreBluetooth backend is not safe for concurrent GATT operations on one peripheral,
// so every read/write goes through mu. Its callbacks run on CoreBluetooth's queue and must not
// block, hence the goroutines in the notification handler.
type Phone struct {
	adapter *bluetooth.Adapter
	creds   func() *Pairing // current pairing, nil if none
	paired  func(Pairing)   // store a new pairing
	onState func(*State)    // nil = link lost
	pairReq chan chan error

	mu    sync.Mutex // GATT operations, chars and dev
	chars map[bluetooth.UUID]bluetooth.DeviceCharacteristic
	dev   *bluetooth.Device

	linkMu sync.Mutex // addr and lost; taken on CoreBluetooth's queue, so never held across GATT calls
	addr   bluetooth.Address
	lost   chan struct{}

	lastState atomic.Int64 // unix ms of the last state from the phone (it re-sends every 60 s)
}

const (
	silenceLimit    = 150 * time.Second // no state for this long on a live link: reconnect
	failuresToReset = 5                 // consecutive failed sessions before resetting the adapter
)

func NewPhone(adapter *bluetooth.Adapter, creds func() *Pairing, paired func(Pairing), onState func(*State)) *Phone {
	p := &Phone{adapter: adapter, creds: creds, paired: paired, onState: onState, pairReq: make(chan chan error, 1)}
	adapter.SetConnectHandler(func(d bluetooth.Device, connected bool) {
		if connected {
			return
		}
		p.linkMu.Lock()
		defer p.linkMu.Unlock()
		if p.lost != nil && d.Address == p.addr {
			close(p.lost)
			p.lost = nil
		}
	})
	return p
}

// Run never returns.
func (p *Phone) Run() {
	go p.watchdog()
	failures := 0
	for {
		var req chan error
		if p.creds() == nil {
			req = <-p.pairReq // nothing to connect to until the user pairs
		}
		start := time.Now()
		err := p.session(req)
		slog.Info("phone session ended", "err", err)
		p.onState(nil)
		// A session that lived a while was a good one; quick failures in a row point at a stuck stack.
		if time.Since(start) > time.Minute {
			failures = 0
		} else if failures++; failures >= failuresToReset {
			failures = 0
			slog.Warn("watchdog: resetting the Bluetooth adapter")
			if err := p.adapter.Reset(); err == nil {
				err = p.adapter.Enable()
				slog.Info("adapter re-enabled", "err", err)
			}
		}
		time.Sleep(2 * time.Second)
	}
}

// watchdog drops a link that stopped delivering state; Run then reconnects.
func (p *Phone) watchdog() {
	for range time.Tick(30 * time.Second) {
		p.mu.Lock()
		silent := p.dev != nil && time.Since(time.UnixMilli(p.lastState.Load())) > silenceLimit
		if silent {
			slog.Warn("watchdog: no state from the phone, reconnecting")
			_ = p.dev.Disconnect()
		}
		p.mu.Unlock()
	}
}

// Pair asks Run to read the pairing record. The phone must have its pairing window open.
func (p *Phone) Pair() error {
	reply := make(chan error, 1)
	select {
	case p.pairReq <- reply:
	default:
		return errors.New("pairing already in progress")
	}
	select {
	case err := <-reply:
		return err
	case <-time.After(90 * time.Second):
		return errors.New("timed out: is the phone nearby with its pairing window open?")
	}
}

func (p *Phone) session(req chan error) (err error) {
	defer func() {
		if req != nil && err != nil {
			req <- err
		}
	}()
	scanFor := time.Duration(0) // forever
	if req != nil {
		scanFor = 60 * time.Second
	}
	addr, err := p.scan(scanFor)
	if err != nil {
		return err
	}
	dev, err := p.adapter.Connect(addr, bluetooth.ConnectionParams{})
	if err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	defer dev.Disconnect()
	chars, err := discover(dev)
	if err != nil {
		return err
	}
	lost := make(chan struct{})
	p.linkMu.Lock()
	p.addr, p.lost = addr, lost
	p.linkMu.Unlock()
	p.lastState.Store(time.Now().UnixMilli()) // the watchdog's clock starts at connect
	p.mu.Lock()
	p.chars, p.dev = chars, &dev
	p.mu.Unlock()
	defer func() {
		p.linkMu.Lock()
		p.lost = nil
		p.linkMu.Unlock()
		p.mu.Lock()
		p.chars, p.dev = nil, nil
		p.mu.Unlock()
	}()
	slog.Info("phone connected", "addr", addr.String())

	for {
		if req != nil {
			pr, err := p.readPairing(chars[pairingUUID])
			if err != nil {
				return err
			}
			p.paired(pr)
			req <- nil
			req = nil
		}
		// Encrypted CCCD: the first subscription bonds (macOS and Android show a dialog).
		if err := chars[stateUUID].EnableNotifications(func(b []byte) { go p.handleState(b) }); err != nil {
			return fmt.Errorf("subscribe: %w", err)
		}
		go p.refresh()
		select {
		case <-lost:
			return errors.New("disconnected")
		case req = <-p.pairReq: // pair again over this link
		}
	}
}

func (p *Phone) scan(timeout time.Duration) (bluetooth.Address, error) {
	found := make(chan bluetooth.Address, 1)
	if timeout > 0 {
		t := time.AfterFunc(timeout, func() { p.adapter.StopScan() })
		defer t.Stop()
	}
	err := p.adapter.Scan(func(a *bluetooth.Adapter, r bluetooth.ScanResult) {
		// ponytail: first phone advertising the service wins; pin the peripheral if two phones ever meet.
		if r.HasServiceUUID(serviceUUID) {
			select {
			case found <- r.Address:
			default:
			}
			a.StopScan()
		}
	})
	if err != nil {
		return bluetooth.Address{}, fmt.Errorf("scan: %w", err)
	}
	select {
	case addr := <-found:
		return addr, nil
	default:
		return bluetooth.Address{}, errors.New("phone not found")
	}
}

func discover(dev bluetooth.Device) (map[bluetooth.UUID]bluetooth.DeviceCharacteristic, error) {
	svcs, err := dev.DiscoverServices([]bluetooth.UUID{serviceUUID})
	if err != nil || len(svcs) == 0 {
		return nil, fmt.Errorf("service not found: %v", err)
	}
	list, err := svcs[0].DiscoverCharacteristics(nil)
	if err != nil {
		return nil, fmt.Errorf("characteristics: %w", err)
	}
	chars := map[bluetooth.UUID]bluetooth.DeviceCharacteristic{}
	for _, c := range list {
		chars[c.UUID()] = c
	}
	for _, u := range []bluetooth.UUID{nonceUUID, commandUUID, stateUUID, pairingUUID} {
		if _, ok := chars[u]; !ok {
			return nil, fmt.Errorf("characteristic %s missing", u)
		}
	}
	return chars, nil
}

// readPairing retries for a minute: the first read waits for the bonding dialog (tinygo gives up
// after 10 s), and the phone answers 0x83 until the user opens the pairing window.
func (p *Phone) readPairing(ch bluetooth.DeviceCharacteristic) (Pairing, error) {
	deadline := time.Now().Add(60 * time.Second)
	for {
		var pr Pairing
		b, err := p.read(ch)
		if err == nil {
			if err = cbor.Unmarshal(b, &pr); err == nil && len(pr.Key) != 32 {
				err = errors.New("bad pairing record")
			}
			if err == nil {
				return pr, nil
			}
		}
		if time.Now().After(deadline) {
			return Pairing{}, fmt.Errorf("pairing: %w", err)
		}
		slog.Info("pairing read failed, retrying", "err", err)
		time.Sleep(time.Second)
	}
}

func (p *Phone) read(ch bluetooth.DeviceCharacteristic) ([]byte, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	buf := make([]byte, 512)
	n, err := ch.Read(buf)
	if err != nil {
		p.dropIfStale(err)
		return nil, err
	}
	return buf[:min(n, len(buf))], nil
}

// dropIfStale disconnects after a GATT timeout. It happens when the phone app restarted while the
// link stayed up: its GATT server is new, our characteristic handles are not, and nothing answers.
// Reconnecting rediscovers the service. Needs p.mu held.
func (p *Phone) dropIfStale(err error) {
	if p.dev != nil && strings.Contains(err.Error(), "timeout") {
		slog.Warn("GATT timeout, reconnecting", "err", err)
		_ = p.dev.Disconnect()
	}
}

func (p *Phone) handleState(b []byte) {
	if len(b) == 0 { // too long for a notification: read it
		p.refresh()
		return
	}
	var st State
	if err := cbor.Unmarshal(b, &st); err != nil {
		slog.Warn("bad state", "err", err)
		return
	}
	p.lastState.Store(time.Now().UnixMilli())
	p.onState(&st)
}

func (p *Phone) refresh() {
	p.mu.Lock()
	ch, ok := p.chars[stateUUID]
	p.mu.Unlock()
	if !ok {
		return
	}
	if b, err := p.read(ch); err == nil && len(b) > 0 {
		p.handleState(b)
	}
}

// Command reads a fresh nonce and writes a signed command.
func (p *Phone) Command(op byte, key, arg []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.chars == nil {
		return errNotConnected
	}
	nonce, err := p.readNonce()
	if err != nil {
		return err
	}
	if _, err := p.chars[commandUUID].Write(sign(key, op, nonce, arg)); err != nil {
		p.dropIfStale(err)
		return fmt.Errorf("phone rejected the command: %w", err)
	}
	return nil
}

// readNonce needs p.mu held.
func (p *Phone) readNonce() ([]byte, error) {
	buf := make([]byte, 64)
	n, err := p.chars[nonceUUID].Read(buf)
	if err != nil {
		p.dropIfStale(err)
	}
	if err != nil || n != 16 {
		return nil, fmt.Errorf("read nonce: n=%d err=%v", n, err)
	}
	return buf[:16], nil
}
