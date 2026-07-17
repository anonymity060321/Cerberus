use crate::CryptoError;
use base64::{engine::general_purpose, Engine as _};
use digest::Digest;
use p256::ecdsa::{signature::Signer, Signature, SigningKey, VerifyingKey};
use p256::elliptic_curve::rand_core::OsRng;
use p256::elliptic_curve::sec1::ToEncodedPoint;
use rand::{thread_rng, RngCore};
use serde::Deserialize;
use serde_cbor::Value as CborValue;
use serde_json::json;
use sha2::Sha256;
use std::collections::BTreeMap;

const FLAG_USER_PRESENT: u8 = 0x01;
const FLAG_USER_VERIFIED: u8 = 0x04;
const FLAG_ATTESTED_CREDENTIAL_DATA: u8 = 0x40;
const ES256_ALGORITHM: i64 = -7;

#[derive(uniffi::Record)]
pub struct PasskeyCreationResult {
    pub credential_id: String,
    pub rp_id: String,
    pub user_id: String,
    pub username: String,
    pub display_name: String,
    pub private_key: String,
    pub response_json: String,
}

#[derive(Deserialize)]
struct RelyingParty {
    id: String,
}

#[derive(Deserialize)]
struct PasskeyUser {
    id: String,
    name: String,
    #[serde(rename = "displayName")]
    display_name: String,
}

#[derive(Deserialize)]
struct PublicKeyParameter {
    alg: i64,
}

#[derive(Deserialize)]
struct PasskeyCreationOptions {
    challenge: String,
    rp: RelyingParty,
    user: PasskeyUser,
    #[serde(rename = "pubKeyCredParams")]
    public_key_parameters: Vec<PublicKeyParameter>,
}

#[derive(Deserialize)]
struct CredentialDescriptor {
    id: String,
}

#[derive(Deserialize)]
struct PasskeyRequestOptions {
    challenge: String,
    #[serde(rename = "rpId")]
    rp_id: String,
    #[serde(rename = "allowCredentials", default)]
    allow_credentials: Vec<CredentialDescriptor>,
}

/// Creates a device-local ES256 passkey and its WebAuthn registration response.
///
/// The returned private key must be stored only inside encrypted application
/// storage. It is intentionally kept out of Cerberus account backups.
#[uniffi::export]
pub fn create_passkey(
    request_json: String,
    origin: String,
    package_name: String,
) -> Result<PasskeyCreationResult, CryptoError> {
    let options: PasskeyCreationOptions =
        serde_json::from_str(&request_json).map_err(|_| CryptoError::InvalidData)?;

    if options.rp.id.is_empty()
        || options.user.name.is_empty()
        || origin.is_empty()
        || package_name.is_empty()
        || !options
            .public_key_parameters
            .iter()
            .any(|parameter| parameter.alg == ES256_ALGORITHM)
    {
        return Err(CryptoError::InvalidParameter);
    }

    let user_id = decode_base64_url(&options.user.id)?;
    let challenge = decode_base64_url(&options.challenge)?;
    if user_id.is_empty() || challenge.is_empty() {
        return Err(CryptoError::InvalidData);
    }

    let mut credential_id = [0u8; 32];
    thread_rng().fill_bytes(&mut credential_id);

    let signing_key = SigningKey::random(&mut OsRng);
    let verifying_key = signing_key.verifying_key();
    let cose_public_key = encode_cose_public_key(verifying_key)?;

    let client_data_json = build_client_data(
        "webauthn.create",
        &options.challenge,
        &origin,
        &package_name,
    )?;
    let authenticator_data = build_creation_authenticator_data(
        &options.rp.id,
        &credential_id,
        &cose_public_key,
    );
    let attestation_object = encode_none_attestation(&authenticator_data)?;

    let encoded_credential_id = encode_base64_url(&credential_id);
    let response_json = json!({
        "id": encoded_credential_id,
        "rawId": encoded_credential_id,
        "type": "public-key",
        "authenticatorAttachment": "platform",
        "response": {
            "clientDataJSON": encode_base64_url(client_data_json.as_bytes()),
            "attestationObject": encode_base64_url(&attestation_object),
            "transports": ["internal"]
        }
    })
    .to_string();

    Ok(PasskeyCreationResult {
        credential_id: encoded_credential_id,
        rp_id: options.rp.id,
        user_id: encode_base64_url(&user_id),
        username: options.user.name,
        display_name: options.user.display_name,
        private_key: general_purpose::STANDARD.encode(signing_key.to_bytes()),
        response_json,
    })
}

/// Signs a WebAuthn authentication challenge with a previously stored passkey.
#[uniffi::export]
pub fn get_passkey_response(
    request_json: String,
    origin: String,
    package_name: String,
    expected_rp_id: String,
    credential_id: String,
    user_id: String,
    private_key: String,
) -> Result<String, CryptoError> {
    let options: PasskeyRequestOptions =
        serde_json::from_str(&request_json).map_err(|_| CryptoError::InvalidData)?;

    if origin.is_empty()
        || package_name.is_empty()
        || expected_rp_id.is_empty()
        || options.rp_id != expected_rp_id
    {
        return Err(CryptoError::InvalidParameter);
    }
    if decode_base64_url(&options.challenge)?.is_empty() {
        return Err(CryptoError::InvalidData);
    }
    if !options.allow_credentials.is_empty()
        && !options
            .allow_credentials
            .iter()
            .any(|descriptor| descriptor.id == credential_id)
    {
        return Err(CryptoError::InvalidData);
    }

    let credential_id_bytes = decode_base64_url(&credential_id)?;
    let user_id_bytes = decode_base64_url(&user_id)?;
    let private_key_bytes = general_purpose::STANDARD
        .decode(private_key)
        .map_err(|_| CryptoError::InvalidData)?;
    let signing_key =
        SigningKey::from_slice(&private_key_bytes).map_err(|_| CryptoError::InvalidKey)?;

    let client_data_json = build_client_data(
        "webauthn.get",
        &options.challenge,
        &origin,
        &package_name,
    )?;
    let authenticator_data = build_assertion_authenticator_data(&expected_rp_id);

    let client_data_hash = Sha256::digest(client_data_json.as_bytes());
    let mut signed_data = Vec::with_capacity(authenticator_data.len() + client_data_hash.len());
    signed_data.extend_from_slice(&authenticator_data);
    signed_data.extend_from_slice(&client_data_hash);
    let signature: Signature = signing_key.sign(&signed_data);

    Ok(json!({
        "id": encode_base64_url(&credential_id_bytes),
        "rawId": encode_base64_url(&credential_id_bytes),
        "type": "public-key",
        "authenticatorAttachment": "platform",
        "response": {
            "clientDataJSON": encode_base64_url(client_data_json.as_bytes()),
            "authenticatorData": encode_base64_url(&authenticator_data),
            "signature": encode_base64_url(signature.to_der().as_bytes()),
            "userHandle": encode_base64_url(&user_id_bytes)
        }
    })
    .to_string())
}

fn build_client_data(
    ceremony_type: &str,
    challenge: &str,
    origin: &str,
    package_name: &str,
) -> Result<String, CryptoError> {
    let value = json!({
        "type": ceremony_type,
        "challenge": challenge,
        "origin": origin,
        "crossOrigin": false,
        "androidPackageName": package_name
    });
    serde_json::to_string(&value).map_err(|_| CryptoError::SerializationError)
}

fn build_creation_authenticator_data(
    rp_id: &str,
    credential_id: &[u8],
    cose_public_key: &[u8],
) -> Vec<u8> {
    let mut data = build_authenticator_data_header(
        rp_id,
        FLAG_USER_PRESENT | FLAG_USER_VERIFIED | FLAG_ATTESTED_CREDENTIAL_DATA,
    );
    data.extend_from_slice(&[0u8; 16]); // AAGUID for a self/none attestation.
    data.extend_from_slice(&(credential_id.len() as u16).to_be_bytes());
    data.extend_from_slice(credential_id);
    data.extend_from_slice(cose_public_key);
    data
}

fn build_assertion_authenticator_data(rp_id: &str) -> Vec<u8> {
    build_authenticator_data_header(rp_id, FLAG_USER_PRESENT | FLAG_USER_VERIFIED)
}

fn build_authenticator_data_header(rp_id: &str, flags: u8) -> Vec<u8> {
    let rp_id_hash = Sha256::digest(rp_id.as_bytes());
    let mut data = Vec::with_capacity(37);
    data.extend_from_slice(&rp_id_hash);
    data.push(flags);
    data.extend_from_slice(&0u32.to_be_bytes()); // Signature counter not supported.
    data
}

fn encode_cose_public_key(verifying_key: &VerifyingKey) -> Result<Vec<u8>, CryptoError> {
    let point = verifying_key.to_encoded_point(false);
    let x = point.x().ok_or(CryptoError::InvalidKey)?;
    let y = point.y().ok_or(CryptoError::InvalidKey)?;

    let mut cose_key = BTreeMap::new();
    cose_key.insert(CborValue::Integer(1), CborValue::Integer(2)); // kty: EC2
    cose_key.insert(CborValue::Integer(3), CborValue::Integer(-7)); // alg: ES256
    cose_key.insert(CborValue::Integer(-1), CborValue::Integer(1)); // crv: P-256
    cose_key.insert(CborValue::Integer(-2), CborValue::Bytes(x.to_vec()));
    cose_key.insert(CborValue::Integer(-3), CborValue::Bytes(y.to_vec()));

    serde_cbor::to_vec(&CborValue::Map(cose_key)).map_err(|_| CryptoError::SerializationError)
}

fn encode_none_attestation(authenticator_data: &[u8]) -> Result<Vec<u8>, CryptoError> {
    let mut attestation = BTreeMap::new();
    attestation.insert(
        CborValue::Text("fmt".to_owned()),
        CborValue::Text("none".to_owned()),
    );
    attestation.insert(
        CborValue::Text("attStmt".to_owned()),
        CborValue::Map(BTreeMap::new()),
    );
    attestation.insert(
        CborValue::Text("authData".to_owned()),
        CborValue::Bytes(authenticator_data.to_vec()),
    );

    serde_cbor::to_vec(&CborValue::Map(attestation))
        .map_err(|_| CryptoError::SerializationError)
}

fn encode_base64_url(bytes: &[u8]) -> String {
    general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn decode_base64_url(value: &str) -> Result<Vec<u8>, CryptoError> {
    general_purpose::URL_SAFE_NO_PAD
        .decode(value)
        .or_else(|_| general_purpose::URL_SAFE.decode(value))
        .map_err(|_| CryptoError::InvalidData)
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::signature::Verifier;

    fn creation_request() -> String {
        json!({
            "challenge": encode_base64_url(b"registration challenge"),
            "rp": {"id": "telegram.org", "name": "Telegram"},
            "user": {
                "id": encode_base64_url(b"telegram-user-1"),
                "name": "+10000000000",
                "displayName": "Telegram User"
            },
            "pubKeyCredParams": [{"type": "public-key", "alg": -7}]
        })
        .to_string()
    }

    #[test]
    fn creates_none_attestation_and_signs_assertion() {
        let origin = "android:apk-key-hash:test-origin".to_owned();
        let package_name = "org.telegram.messenger".to_owned();
        let created = create_passkey(
            creation_request(),
            origin.clone(),
            package_name.clone(),
        )
            .expect("passkey creation succeeds");
        assert_eq!(created.rp_id, "telegram.org");

        let creation_json: serde_json::Value =
            serde_json::from_str(&created.response_json).expect("valid creation JSON");
        let attestation_bytes = decode_base64_url(
            creation_json["response"]["attestationObject"]
                .as_str()
                .expect("attestation object"),
        )
        .expect("valid attestation base64url");
        let attestation: CborValue =
            serde_cbor::from_slice(&attestation_bytes).expect("valid attestation CBOR");
        assert!(matches!(attestation, CborValue::Map(_)));

        let get_request = json!({
            "challenge": encode_base64_url(b"authentication challenge"),
            "rpId": "telegram.org",
            "allowCredentials": [{"type": "public-key", "id": created.credential_id.clone()}]
        })
        .to_string();
        let response = get_passkey_response(
            get_request,
            origin,
            package_name,
            created.rp_id.clone(),
            created.credential_id.clone(),
            created.user_id.clone(),
            created.private_key.clone(),
        )
        .expect("assertion succeeds");
        let response_json: serde_json::Value =
            serde_json::from_str(&response).expect("valid assertion JSON");
        let auth_data = decode_base64_url(
            response_json["response"]["authenticatorData"]
                .as_str()
                .expect("authenticator data"),
        )
        .expect("valid authenticator data");
        let client_data = decode_base64_url(
            response_json["response"]["clientDataJSON"]
                .as_str()
                .expect("client data"),
        )
        .expect("valid client data");
        let signature_bytes = decode_base64_url(
            response_json["response"]["signature"]
                .as_str()
                .expect("signature"),
        )
        .expect("valid signature");

        let mut signed_data = auth_data;
        signed_data.extend_from_slice(&Sha256::digest(&client_data));
        let private_key = general_purpose::STANDARD
            .decode(created.private_key)
            .expect("valid private key");
        let signing_key = SigningKey::from_slice(&private_key).expect("valid signing key");
        let signature = Signature::from_der(&signature_bytes).expect("DER signature");
        signing_key
            .verifying_key()
            .verify(&signed_data, &signature)
            .expect("signature verifies");
    }
}
