// Copyright 2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class AuthHandlerForTesting implements AuthHandler {
    private final NKey nkey;

    public AuthHandlerForTesting(NKey nkey) {
        this.nkey = nkey;
    }

    public AuthHandlerForTesting() throws Exception {
        this.nkey = NKey.createUser(null);
    }

    public NKey getNKey() {
        return this.nkey;
    }

    public char[] getID() {
        try {
            return this.nkey.getPublicKey();
        } catch (GeneralSecurityException|IOException|NullPointerException ex) {
            return null;
        }
    }

    public byte[] sign(byte[] nonce) {
        try {
            return this.nkey.sign(nonce);
        } catch (GeneralSecurityException|IOException|NullPointerException ex) {
            return null;
        }
    }

    public char[] getJWT() {
        return null;
    }
}