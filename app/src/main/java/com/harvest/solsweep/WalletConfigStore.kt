package com.harvest.solsweep

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class SweeperConfig(
    val rpcUrls: List<String>,
    val sourceSecretKey: String,
    val destAddress: String,
    val pollIntervalMs: Int,
    val feeReserveLamports: Long,
    val proxy: ProxyConfig = ProxyConfig(ProxyType.NONE, "", 0, "", ""),
    val faucetEnabled: Boolean = false,
    val faucetIntervalMs: Long = 60_000,
    val faucetAmountLamports: Long = 1_000_000_000 // 1 SOL - devnet's usual per-request cap
)

/** All of this - especially the secret key - lives only in Android-Keystore-backed
 * EncryptedSharedPreferences, never plain SharedPreferences, never logged. */
object WalletConfigStore {
    private const val FILE = "sol_sweeper_secure_config"

    private const val KEY_RPC_URLS = "rpc_urls"
    private const val KEY_SECRET = "source_secret_key"
    private const val KEY_DEST = "dest_address"
    private const val KEY_POLL_MS = "poll_interval_ms"
    private const val KEY_FEE_RESERVE = "fee_reserve_lamports"
    private const val KEY_PROXY_TYPE = "proxy_type"
    private const val KEY_PROXY_HOST = "proxy_host"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_PROXY_USER = "proxy_username"
    private const val KEY_PROXY_PASS = "proxy_password"
    private const val KEY_FAUCET_ENABLED = "faucet_enabled"
    private const val KEY_FAUCET_INTERVAL_MS = "faucet_interval_ms"
    private const val KEY_FAUCET_AMOUNT_LAMPORTS = "faucet_amount_lamports"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(context: Context, config: SweeperConfig) {
        prefs(context).edit()
            .putString(KEY_RPC_URLS, config.rpcUrls.joinToString("\n"))
            .putString(KEY_SECRET, config.sourceSecretKey)
            .putString(KEY_DEST, config.destAddress)
            .putInt(KEY_POLL_MS, config.pollIntervalMs)
            .putLong(KEY_FEE_RESERVE, config.feeReserveLamports)
            .putString(KEY_PROXY_TYPE, config.proxy.type.name)
            .putString(KEY_PROXY_HOST, config.proxy.host)
            .putInt(KEY_PROXY_PORT, config.proxy.port)
            .putString(KEY_PROXY_USER, config.proxy.username)
            .putString(KEY_PROXY_PASS, config.proxy.password)
            .putBoolean(KEY_FAUCET_ENABLED, config.faucetEnabled)
            .putLong(KEY_FAUCET_INTERVAL_MS, config.faucetIntervalMs)
            .putLong(KEY_FAUCET_AMOUNT_LAMPORTS, config.faucetAmountLamports)
            .apply()
    }

    fun load(context: Context): SweeperConfig? {
        val p = prefs(context)
        val secret = p.getString(KEY_SECRET, null) ?: return null
        val dest = p.getString(KEY_DEST, null) ?: return null
        val rpcRaw = p.getString(KEY_RPC_URLS, "") ?: ""
        val rpcUrls = rpcRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (rpcUrls.isEmpty()) return null
        val proxyType = try {
            ProxyType.valueOf(p.getString(KEY_PROXY_TYPE, ProxyType.NONE.name) ?: ProxyType.NONE.name)
        } catch (_: Exception) {
            ProxyType.NONE
        }
        return SweeperConfig(
            rpcUrls = rpcUrls,
            sourceSecretKey = secret,
            destAddress = dest,
            pollIntervalMs = p.getInt(KEY_POLL_MS, 400),
            feeReserveLamports = p.getLong(KEY_FEE_RESERVE, 5000),
            proxy = ProxyConfig(
                type = proxyType,
                host = p.getString(KEY_PROXY_HOST, "") ?: "",
                port = p.getInt(KEY_PROXY_PORT, 0),
                username = p.getString(KEY_PROXY_USER, "") ?: "",
                password = p.getString(KEY_PROXY_PASS, "") ?: ""
            ),
            faucetEnabled = p.getBoolean(KEY_FAUCET_ENABLED, false),
            faucetIntervalMs = p.getLong(KEY_FAUCET_INTERVAL_MS, 60_000),
            faucetAmountLamports = p.getLong(KEY_FAUCET_AMOUNT_LAMPORTS, 1_000_000_000)
        )
    }
}
