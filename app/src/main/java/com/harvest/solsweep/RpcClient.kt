package com.harvest.solsweep

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

enum class ProxyType { NONE, HTTP, SOCKS }

data class ProxyConfig(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String
) {
    fun toJavaProxy(): Proxy? {
        if (type == ProxyType.NONE || host.isBlank() || port <= 0) return null
        val kind = if (type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(kind, InetSocketAddress(host, port))
    }

    fun basicAuthHeader(): String? {
        if (type != ProxyType.HTTP || username.isBlank()) return null
        val raw = "$username:$password".toByteArray()
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}

/** Minimal JSON-RPC client for the 3 Solana RPC calls the sweeper needs. */
object RpcClient {

    private fun post(rpcUrl: String, method: String, params: JSONArray, timeoutMs: Int, proxy: ProxyConfig?): JSONObject {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }
        val javaProxy = proxy?.toJavaProxy()
        val connection = if (javaProxy != null) URL(rpcUrl).openConnection(javaProxy) else URL(rpcUrl).openConnection()
        val conn = (connection as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            proxy?.basicAuthHeader()?.let { setRequestProperty("Proxy-Authorization", it) }
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            val root = JSONObject(text)
            if (root.has("error")) {
                throw RuntimeException("rpc error: ${root.getJSONObject("error").optString("message")}")
            }
            return root
        } finally {
            conn.disconnect()
        }
    }

    /** lamports balance of a base58 pubkey. */
    fun getBalance(rpcUrl: String, pubkeyBase58: String, proxy: ProxyConfig? = null, timeoutMs: Int = 4000): Long {
        val params = JSONArray().put(pubkeyBase58)
        val root = post(rpcUrl, "getBalance", params, timeoutMs, proxy)
        return root.getJSONObject("result").getLong("value")
    }

    /** base58 blockhash, fresh for building a transaction. */
    fun getLatestBlockhash(rpcUrl: String, proxy: ProxyConfig? = null, timeoutMs: Int = 4000): String {
        val params = JSONArray().put(JSONObject().put("commitment", "processed"))
        val root = post(rpcUrl, "getLatestBlockhash", params, timeoutMs, proxy)
        return root.getJSONObject("result").getJSONObject("value").getString("blockhash")
    }

    /** Submits a base64-encoded signed transaction; returns the tx signature. */
    fun sendTransaction(rpcUrl: String, txBase64: String, proxy: ProxyConfig? = null, timeoutMs: Int = 6000): String {
        val opts = JSONObject().apply {
            put("skipPreflight", true)
            put("preflightCommitment", "processed")
            put("encoding", "base64")
            put("maxRetries", 0)
        }
        val params = JSONArray().put(txBase64).put(opts)
        val root = post(rpcUrl, "sendTransaction", params, timeoutMs, proxy)
        return root.getString("result")
    }

    /** Devnet/testnet only - the official no-captcha faucet built into those RPC nodes. Mainnet RPCs reject this. */
    fun requestAirdrop(rpcUrl: String, pubkeyBase58: String, lamports: Long, proxy: ProxyConfig? = null, timeoutMs: Int = 10000): String {
        val params = JSONArray().put(pubkeyBase58).put(lamports)
        val root = post(rpcUrl, "requestAirdrop", params, timeoutMs, proxy)
        return root.getString("result")
    }
}
