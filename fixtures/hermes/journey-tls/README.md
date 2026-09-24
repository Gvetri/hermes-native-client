# Journey test TLS assets

These files are repository-owned test fixtures for the deterministic emulator
journeys only.

- `journey-gateway.p12` — PKCS12 keystore holding the journey Gateway server
  key pair, signed by the journey test CA, with SANs for `localhost`,
  `127.0.0.1`, and `10.0.2.2` (the emulator's host loopback alias).
- `journey-ca.pem` — the journey test CA certificate. CI installs it into the
  emulator's system trust store so the app validates the loopback Gateway
  exactly as it would a real HTTPS endpoint.

Both files are synthetic test material:

- The private key and keystore password (`journey-fixture`) are test-only and
  must never protect production traffic.
- No production client, Gateway, or provider ever trusts this CA.
- Rotating or regenerating these assets changes the journey trust boundary
  and requires re-running the journey suite plus compatibility review.

Regenerate with (any keytool supporting PKCS12):

```text
keytool -genkeypair -alias journey-ca -keyalg RSA -keysize 2048 \
  -validity 3650 -dname "CN=Hermes Journey Test CA,O=hermes-native-client" \
  -ext "BC:c" -keystore ca.p12 -storetype PKCS12 -storepass <ca-password>
keytool -exportcert -alias journey-ca -keystore ca.p12 -storetype PKCS12 \
  -storepass <ca-password> -rfc -file journey-ca.pem
keytool -genkeypair -alias journey-gateway -keyalg RSA -keysize 2048 \
  -validity 3650 -dname "CN=journey-gateway.test,O=hermes-native-client" \
  -ext "SAN=dns:localhost,ip:127.0.0.1,ip:10.0.2.2" \
  -keystore server.p12 -storetype PKCS12 -storepass journey-fixture
# Sign the server certificate with the CA, then import the CA and the signed
# certificate chain back into server.p12 as journey-gateway.p12.
```
