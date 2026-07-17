// rust/src/lib.rs
use aes_gcm::{
    aead::{Aead, KeyInit as AesKeyInit},
    Aes256Gcm, Nonce
};
use hmac::Hmac;
use digest::{Mac, KeyInit as MacKeyInit};
use pbkdf2::pbkdf2;
use sha1::Sha1;
use sha2::{Sha256, Sha512};
use rand::{RngCore, thread_rng};
use base64::{Engine as _, engine::general_purpose};
use thiserror::Error;
use data_encoding::BASE32_NOPAD;
use serde::{Serialize, Deserialize};

mod passkey;
pub use passkey::{
    create_passkey,
    finish_passkey_response,
    prepare_passkey_assertion,
    PasskeyAssertionPreparation,
    PasskeyCreationResult,
};

// 初始化 UniFFI
uniffi::setup_scaffolding!();

// 建议增加到 600,000 次以符合现代安全标准 (OWASP)
const ITERATION_COUNT: u32 = 600_000;
const KEY_LENGTH: usize = 32;
const IV_LENGTH: usize = 12;
const BACKUP_SALT_LENGTH: usize = 16;
const MASTER_SALT_LENGTH: usize = 32;
const BACKUP_VERSION: u8 = 1;

#[derive(Error, uniffi::Error, Debug)]
pub enum CryptoError {
    #[error("Encryption failed")]
    EncryptionFailed,
    #[error("Decryption failed")]
    DecryptionFailed,
    #[error("Invalid key")]
    InvalidKey,
    #[error("Invalid data")]
    InvalidData,
    #[error("Invalid parameter")]
    InvalidParameter,
    #[error("Unsupported backup version")]
    UnsupportedVersion,
    #[error("TOTP generation failed")]
    TotpFailed,
    #[error("Serialization failed")]
    SerializationError,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy)]
pub enum OtpHashAlgorithm {
    Sha1,
    Sha256,
    Sha512,
}

#[derive(uniffi::Enum, Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum OtpType {
    #[default]
    Totp,
    Steam,
}

#[derive(uniffi::Record, Serialize, Deserialize)]
pub struct Account {
    pub id: i32,
    pub name: String,
    pub username: String,
    pub password: String,
    pub icon_initial: String,
    pub secret_key: String,
    pub algorithm: OtpHashAlgorithm,
    pub has_otp: bool,
    #[serde(default)]
    pub otp_type: OtpType,
}

#[derive(uniffi::Record)]
pub struct MasterPasswordData {
    pub hash: String,
    pub salt: String,
}

// --- 账号列表序列化 (JSON) ---

#[uniffi::export]
pub fn accounts_to_json(accounts: Vec<Account>) -> Result<String, CryptoError> {
    serde_json::to_string(&accounts).map_err(|_| CryptoError::SerializationError)
}

#[uniffi::export]
pub fn json_to_accounts(json: String) -> Result<Vec<Account>, CryptoError> {
    serde_json::from_str(&json).map_err(|_| CryptoError::SerializationError)
}

// --- 备份加密逻辑 ---

#[uniffi::export]
pub fn encrypt_backup(data: String, password: String) -> Result<String, CryptoError> {
    let mut salt = [0u8; BACKUP_SALT_LENGTH];
    let mut iv = [0u8; IV_LENGTH];
    thread_rng().fill_bytes(&mut salt);
    thread_rng().fill_bytes(&mut iv);

    let key = derive_backup_key(&password, &salt)?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| CryptoError::InvalidKey)?;
    let nonce = Nonce::from_slice(&iv);

    let ciphertext = cipher
        .encrypt(nonce, data.as_bytes())
        .map_err(|_| CryptoError::EncryptionFailed)?;

    let mut combined = Vec::with_capacity(1 + BACKUP_SALT_LENGTH + IV_LENGTH + ciphertext.len());
    combined.push(BACKUP_VERSION);
    combined.extend_from_slice(&salt);
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&ciphertext);

    Ok(general_purpose::STANDARD.encode(combined))
}

#[uniffi::export]
pub fn decrypt_backup(encrypted_base64: String, password: String) -> Result<String, CryptoError> {
    let combined = general_purpose::STANDARD
        .decode(encrypted_base64)
        .map_err(|_| CryptoError::InvalidData)?;

    // 支持旧格式（无版本前缀）与新格式（首字节为版本号）
    let (salt, iv, ciphertext) = if combined.len() >= 1 + BACKUP_SALT_LENGTH + IV_LENGTH + 1 {
        let version = combined[0];
        if version != BACKUP_VERSION {
            return Err(CryptoError::UnsupportedVersion);
        }
        let salt_start = 1;
        let iv_start = salt_start + BACKUP_SALT_LENGTH;
        let cipher_start = iv_start + IV_LENGTH;
        (&combined[salt_start..iv_start], &combined[iv_start..cipher_start], &combined[cipher_start..])
    } else {
        if combined.len() < BACKUP_SALT_LENGTH + IV_LENGTH + 1 {
            return Err(CryptoError::InvalidData);
        }
        let salt_start = 0;
        let iv_start = salt_start + BACKUP_SALT_LENGTH;
        let cipher_start = iv_start + IV_LENGTH;
        (&combined[salt_start..iv_start], &combined[iv_start..cipher_start], &combined[cipher_start..])
    };

    let key = derive_backup_key(&password, salt)?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| CryptoError::InvalidKey)?;
    let nonce = Nonce::from_slice(&iv);

    let decrypted_bytes = cipher
        .decrypt(nonce, ciphertext)
        .map_err(|_| CryptoError::DecryptionFailed)?;

    String::from_utf8(decrypted_bytes).map_err(|_| CryptoError::InvalidData)
}

// --- 主密码逻辑 ---

#[uniffi::export]
pub fn hash_master_password(password: String) -> MasterPasswordData {
    let mut salt = [0u8; MASTER_SALT_LENGTH];
    thread_rng().fill_bytes(&mut salt);
    let hash = derive_master_key(&password, &salt).expect("static salt length guarantees success");
    MasterPasswordData {
        hash: hex::encode(hash),
        salt: hex::encode(salt),
    }
}

#[uniffi::export]
pub fn verify_master_password(password: String, stored_hash_hex: String, salt_hex: String) -> bool {
    let salt = match hex::decode(salt_hex) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let current_hash = match derive_master_key(&password, &salt) {
        Ok(h) => h,
        Err(_) => return false,
    };
    hex::encode(current_hash) == stored_hash_hex
}

// --- TOTP 生成逻辑 ---

#[uniffi::export]
pub fn generate_totp(secret: String, algo: OtpHashAlgorithm, digits: u32, period: u64) -> Result<String, CryptoError> {
    if !(6..=8).contains(&digits) {
        return Err(CryptoError::InvalidParameter);
    }
    if period == 0 {
        return Err(CryptoError::InvalidParameter);
    }

    let clean_secret = secret.replace(" ", "").to_uppercase();
    let secret_bytes = BASE32_NOPAD.decode(clean_secret.as_bytes()).map_err(|_| CryptoError::InvalidData)?;

    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| CryptoError::TotpFailed)?
        .as_secs();
    let counter = timestamp / period;

    let hmac_result = match algo {
        OtpHashAlgorithm::Sha1 => compute_hmac::<Hmac<Sha1>>(&secret_bytes, counter),
        OtpHashAlgorithm::Sha256 => compute_hmac::<Hmac<Sha256>>(&secret_bytes, counter),
        OtpHashAlgorithm::Sha512 => compute_hmac::<Hmac<Sha512>>(&secret_bytes, counter),
    };

    let offset = (hmac_result[hmac_result.len() - 1] & 0xf) as usize;
    let modulus = 10u32.checked_pow(digits).ok_or(CryptoError::InvalidParameter)?;
    let code = ((hmac_result[offset] as u32 & 0x7f) << 24 |
                (hmac_result[offset + 1] as u32 & 0xff) << 16 |
                (hmac_result[offset + 2] as u32 & 0xff) << 8 |
                (hmac_result[offset + 3] as u32 & 0xff)) % modulus;

    Ok(format!("{:0>width$}", code, width = digits as usize))
}

const STEAM_GUARD_ALPHABET: &[u8] = b"23456789BCDFGHJKMNPQRTVWXY";

/// Generate the five-character code used by Steam Guard for account login.
///
/// Steam shared secrets are Base64-encoded, unlike regular TOTP secrets,
/// which are Base32-encoded. The caller is responsible for keeping the
/// device clock synchronized because this offline implementation deliberately
/// does not contact Steam's time endpoint.
#[uniffi::export]
pub fn generate_steam_guard_code(shared_secret: String) -> Result<String, CryptoError> {
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| CryptoError::TotpFailed)?
        .as_secs();

    generate_steam_guard_code_at(&shared_secret, timestamp)
}

fn generate_steam_guard_code_at(shared_secret: &str, timestamp: u64) -> Result<String, CryptoError> {
    let clean_secret: String = shared_secret
        .chars()
        .filter(|character| !character.is_whitespace())
        .collect();

    if clean_secret.is_empty() {
        return Err(CryptoError::InvalidData);
    }

    let secret_bytes = general_purpose::STANDARD
        .decode(&clean_secret)
        .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(&clean_secret))
        .map_err(|_| CryptoError::InvalidData)?;

    let hmac_result = compute_hmac::<Hmac<Sha1>>(&secret_bytes, timestamp / 30);
    let offset = (hmac_result[hmac_result.len() - 1] & 0x0f) as usize;
    let mut code_point = u32::from_be_bytes([
        hmac_result[offset],
        hmac_result[offset + 1],
        hmac_result[offset + 2],
        hmac_result[offset + 3],
    ]) & 0x7fff_ffff;

    let mut code = String::with_capacity(5);
    for _ in 0..5 {
        let index = (code_point % STEAM_GUARD_ALPHABET.len() as u32) as usize;
        code.push(STEAM_GUARD_ALPHABET[index] as char);
        code_point /= STEAM_GUARD_ALPHABET.len() as u32;
    }

    Ok(code)
}

fn compute_hmac<D: Mac + MacKeyInit>(key: &[u8], counter: u64) -> Vec<u8> {
    let mut mac = <D as MacKeyInit>::new_from_slice(key).expect("HMAC should accept any key size");
    mac.update(&counter.to_be_bytes());
    mac.finalize().into_bytes().to_vec()
}

// --- 辅助函数 ---

fn derive_key(password: &str, salt: &[u8]) -> [u8; KEY_LENGTH] {
    let mut key = [0u8; KEY_LENGTH];
    let _ = pbkdf2::<Hmac<Sha256>>(
        password.as_bytes(),
        salt,
        ITERATION_COUNT,
        &mut key
    );
    key
}

fn derive_backup_key(password: &str, salt: &[u8]) -> Result<[u8; KEY_LENGTH], CryptoError> {
    if salt.len() != BACKUP_SALT_LENGTH {
        return Err(CryptoError::InvalidData);
    }
    Ok(derive_key(password, salt))
}

fn derive_master_key(password: &str, salt: &[u8]) -> Result<[u8; KEY_LENGTH], CryptoError> {
    if salt.len() != MASTER_SALT_LENGTH {
        return Err(CryptoError::InvalidData);
    }
    Ok(derive_key(password, salt))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn steam_guard_matches_known_vector() {
        // RFC 4226 test secret encoded as Base64. At timestamp 59 the
        // underlying HMAC-SHA1 value is independently known and the Steam
        // alphabet conversion yields PV9M4.
        let code = generate_steam_guard_code_at(
            "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
            59,
        )
        .expect("valid Steam shared secret");

        assert_eq!(code, "PV9M4");
    }

    #[test]
    fn steam_guard_accepts_unpadded_and_spaced_base64() {
        let padded = generate_steam_guard_code_at(
            "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
            59,
        )
        .expect("valid padded secret");
        let unpadded = generate_steam_guard_code_at(
            "MTIzNDU2Nzg5 MDEyMzQ1Njc4OTA",
            59,
        )
        .expect("valid unpadded secret");

        assert_eq!(padded, unpadded);
    }

    #[test]
    fn steam_guard_rejects_invalid_secret() {
        assert!(matches!(
            generate_steam_guard_code_at("not-base64!", 59),
            Err(CryptoError::InvalidData)
        ));
    }

    #[test]
    fn legacy_account_json_defaults_to_totp() {
        let json = r#"[{"id":1,"name":"Example","username":"user","password":"password","icon_initial":"E","secret_key":"JBSWY3DPEHPK3PXP","algorithm":"Sha1","has_otp":true}]"#;
        let accounts = json_to_accounts(json.to_owned()).expect("legacy JSON remains readable");

        assert_eq!(accounts[0].otp_type, OtpType::Totp);
    }
}
