/**
 * The server's secret key and encryption of secret columns at rest ({@code *_enc} columns, see
 * {@code docs/data-model.md#secrets-and-ip-addresses-at-rest}). {@link ServerSecret} loads or
 * generates the key; {@link SecretCipher} encrypts and decrypts column values with AES-GCM.
 */
package io.siggi.togethertunes.secrets;
