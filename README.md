# Cerberus 🛡️

**Cerberus** 是一款专注于极致隐私和本地安全的 Android **全能凭据管理器**。

它不仅是一个强大的身份验证器 (TOTP)，更是一个离线的账号密码保险箱。我们坚信：**您的数字资产不应离开您的物理设备。**

---

## ✨ 核心特性

- 🛡️ **双重存储能力**：
  - **身份验证器 (TOTP)**：支持 SHA1/SHA256/SHA512 算法，生成实时 2FA 校验码。
  - **Steam Guard**：从已有的 `shared_secret` 离线生成 Steam 登录五字符验证码。
  - **密码管理器**：离线存储账号密码，内置基于硬件熵池的**强密码生成器**。
- 🔑 **Android Passkey Provider**：在 Android 15 及以上作为系统 Passkey 凭据提供程序，为 Telegram 等支持 Credential Manager 的应用保存和提供设备本地 Passkey。
- 🌐 **本地优先**：凭据不会上传；网络仅用于用户主动检查更新，以及 Passkey 流程中读取 RP 域名公开的 Digital Asset Links 以验证调用应用。
- 🔐 **硬件级加密**：利用 Android Keystore 系统和硬件安全模块对数据进行透明加密。
- 📂 **加密备份 (.cerb)**：
  - 支持导出高度安全的离线备份文件。
  - **二次加密**：采用 AES-256-GCM 算法，结合用户自定义的备份密码（经 PBKDF2 强化）。
- 🧬 **生物识别**：集成指纹及面部识别，实现秒级安全解锁。
- ⌨️ **主密码保护**：所有敏感数据的读取与修改均受主密码校验，防止物理层面的未授权访问。
- 🎨 **Modern UI**：基于 Jetpack Compose 和 Material 3 设计，支持动态配色与**预测性返回手势**。

---

## 🚀 快速上手

> 当前版本要求 Android 15（API 35）或更高版本。

### 添加记录
1. 点击主界面右下角的 `+` 按钮。
2. 输入服务名称、账号和密码（或点击图标生成强密码）。
3. 如需 2FA 支持，开启“启用双重验证”并输入 Base32 密钥。
4. 如果只需要 Steam 登录验证码，将验证码类型改为 `Steam Guard`，并输入已有验证器的 Base64 `shared_secret`。这不是 Steam 密码、救援码或恢复码。

### 在 Telegram 中使用 Passkey
1. 打开 Cerberus 的「设置」→「Passkey 凭据提供程序」，在 Android 系统页面启用 Cerberus。
2. 在 Telegram 官方 Android 客户端中打开「设置」→「隐私和安全」→「Passkeys」，新增 Passkey 并选择 Cerberus。
3. 新设备登录 Telegram 时选择 Passkey，再用锁屏 PIN、密码或生物识别授权签名。

> Telegram 的 RP ID 是 `telegram.org`，因此必须使用与该域名通过 Digital Asset Links 关联的官方 Telegram 客户端；第三方 Telegram 客户端会被服务端拒绝。

> 小米 HyperOS 3 未实现标准 Credential Provider 启用入口。Cerberus 会通过仅用于兼容的 Autofill Service 出现在系统服务列表中；选择 Cerberus 可能替换当前默认密码自动填充服务，而兼容层本身不会读取、保存或填充普通密码。

### 备份与恢复
- **导出**：进入“设置” -> “导出加密备份”，设置备份密码并保存 `.cerb` 文件。
- **恢复**：在新设备上安装后，通过“导入加密备份”并输入正确的备份密码即可找回所有数据。

---

## 🛠️ 技术栈

- **语言**: [Kotlin](https://kotlinlang.org/)
- **UI 框架**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **底层加密**: [Rust](https://www.rust-lang.org/) (通过 UniFFI 集成，处理核心加解密逻辑)
- **安全库**: 
    - `androidx.security:security-crypto` (硬件级加密存储)
    - `androidx.biometric:biometric` (标准生物识别)
- **算法支持**: 
    - TOTP/HOTP 算法支持。
    - AES-256-GCM (备份文件加密)。

---

## ⚠️ 免责声明

1. **主密码唯一性**：Cerberus 不设云端找回机制。**遗忘主密码 = 永久失去对所有令牌和密码的访问权限**。
2. **本地存储**：账号数据不会自动同步，备份需由用户手动完成。设备本地 Passkey 不包含在 `.cerb` 备份中。
3. **风险自担**：本应用作为开源工具提供，开发者不对因设备故障、误操作或遗忘密码导致的数据丢失承担任何责任。

---

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 授权。

---

**Cerberus** - *Guard your digital identity like a beast.* 🐺
