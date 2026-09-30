package main

import (
	"encoding/json"
	"errors"

	"github.com/keybase/go-keychain"
)

const (
	kcService = "io.github.imostrovskiy.pixelhotspot"
	kcAccount = "pairing"
)

func loadPairing() (*Pairing, error) {
	data, err := keychain.GetGenericPassword(kcService, kcAccount, "", "")
	if err != nil || data == nil {
		return nil, err
	}
	var p Pairing
	if err := json.Unmarshal(data, &p); err != nil || len(p.Key) != 32 {
		return nil, errors.New("keychain item is corrupt")
	}
	return &p, nil
}

func savePairing(p Pairing) error {
	data, err := json.Marshal(p)
	if err != nil {
		return err
	}
	_ = forgetPairing()
	item := keychain.NewItem()
	item.SetSecClass(keychain.SecClassGenericPassword)
	item.SetService(kcService)
	item.SetAccount(kcAccount)
	item.SetLabel("Pixel Hotspot pairing")
	item.SetData(data)
	item.SetAccessible(keychain.AccessibleAfterFirstUnlockThisDeviceOnly)
	item.SetSynchronizable(keychain.SynchronizableNo)
	return keychain.AddItem(item)
}

func forgetPairing() error {
	err := keychain.DeleteGenericPasswordItem(kcService, kcAccount)
	if err == keychain.ErrorItemNotFound {
		return nil
	}
	return err
}
