package com.harvest.solsweep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class SweeperService : Service() {

    companion object {
        const val CHANNEL_ID = "sol_sweeper_channel"
        const val NOTIF_ID = 1
        val isRunning = AtomicBoolean(false)

        const val ACTION_STOP = "com.harvest.solsweep.ACTION_STOP"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val sweepLock = Mutex()
    private var cooldownUntilMs = 0L

    // kept fresh in the background so a sweep doesn't pay the extra RPC
    // round-trip for a blockhash at the moment funds actually arrive
    @Volatile private var cachedBlockhash: ByteArray? = null

    private var config: SweeperConfig? = null
    private var keypair: SolanaKeypair? = null
    private var destPubkeyBytes: ByteArray? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSweeper()
            return START_NOT_STICKY
        }

        val cfg = WalletConfigStore.load(this)
        if (cfg == null) {
            SweepLog.add("cannot start: no saved config")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            keypair = SolanaKeypair.parse(cfg.sourceSecretKey)
            destPubkeyBytes = Base58.decode(cfg.destAddress)
            require(destPubkeyBytes!!.size == 32) { "destination address is not a valid pubkey" }
        } catch (e: Exception) {
            SweepLog.add("cannot start: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        config = cfg

        startForeground(NOTIF_ID, buildNotification("watching …"))
        isRunning.set(true)
        SweepLog.add("service started · source=${keypair!!.publicKeyBase58} · dest=${cfg.destAddress} · ${cfg.rpcUrls.size} rpc(s)")

        scope.launch { blockhashRefreshLoop() }
        scope.launch { pollLoop() }
        if (cfg.faucetEnabled) {
            SweepLog.add("faucet auto-collect enabled · every ${cfg.faucetIntervalMs}ms · ${cfg.faucetAmountLamports} lamports/request (devnet/testnet RPC only - mainnet rejects this)")
            scope.launch { faucetLoop() }
        }

        return START_STICKY
    }

    private suspend fun faucetLoop() {
        val cfg = config ?: return
        while (isRunning.get()) {
            var claimed = false
            for (rpc in cfg.rpcUrls) {
                try {
                    val sig = withTimeoutOrNull(12000) {
                        RpcClient.requestAirdrop(rpc, keypair!!.publicKeyBase58, cfg.faucetAmountLamports, cfg.proxy)
                    }
                    if (sig != null) {
                        SweepLog.add("faucet: $rpc airdropped ${cfg.faucetAmountLamports} lamports → $sig")
                        claimed = true
                        break
                    }
                } catch (e: Exception) {
                    SweepLog.add("faucet: $rpc rejected request (${e.message})")
                }
            }
            if (!claimed) SweepLog.add("faucet: no RPC granted this round (rate-limited or not a devnet/testnet endpoint)")
            delay(cfg.faucetIntervalMs)
        }
    }

    private fun stopSelfSweeper() {
        isRunning.set(false)
        SweepLog.add("service stopped")
        scope.coroutineContext[Job]?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning.set(false)
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sol Sweeper", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, SweeperService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sol Sweeper active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private suspend fun blockhashRefreshLoop() {
        val cfg = config ?: return
        while (isRunning.get()) {
            for (rpc in cfg.rpcUrls) {
                try {
                    val bh = RpcClient.getLatestBlockhash(rpc, config?.proxy)
                    cachedBlockhash = Base58.decode(bh)
                    break
                } catch (_: Exception) {
                    // try next rpc
                }
            }
            delay(15_000)
        }
    }

    private suspend fun pollLoop() {
        val cfg = config ?: return
        while (isRunning.get()) {
            val results = cfg.rpcUrls.map { rpc ->
                scope.async {
                    try {
                        val lamports = withTimeoutOrNull(4000) { RpcClient.getBalance(rpc, keypair!!.publicKeyBase58, config?.proxy) }
                        if (lamports != null) rpc to lamports else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            for (deferred in results) {
                val result = try { deferred.await() } catch (_: Exception) { null }
                if (result != null) {
                    val (rpc, lamports) = result
                    maybeSweep(lamports, rpc)
                }
            }
            delay(cfg.pollIntervalMs.toLong())
        }
    }

    private suspend fun maybeSweep(lamports: Long, seenFromRpc: String) {
        val cfg = config ?: return
        val reserve = cfg.feeReserveLamports
        if (lamports <= reserve) return
        if (System.currentTimeMillis() < cooldownUntilMs) return

        sweepLock.withLock {
            if (System.currentTimeMillis() < cooldownUntilMs) return@withLock
            val amount = lamports - reserve
            SweepLog.add("balance $lamports lamports seen via $seenFromRpc → sweeping $amount lamports")

            val blockhash = cachedBlockhash ?: run {
                for (rpc in cfg.rpcUrls) {
                    try {
                        val bh = RpcClient.getLatestBlockhash(rpc, config?.proxy)
                        cachedBlockhash = Base58.decode(bh)
                        break
                    } catch (_: Exception) { /* try next */ }
                }
                cachedBlockhash
            }
            if (blockhash == null) {
                SweepLog.add("sweep failed: no RPC returned a blockhash")
                return@withLock
            }

            val txBase64 = try {
                SolanaTx.buildSignedTransferBase64(keypair!!, destPubkeyBytes!!, amount, blockhash)
            } catch (e: Exception) {
                SweepLog.add("sweep failed: could not build/sign tx: ${e.message}")
                return@withLock
            }

            // broadcast to every configured RPC in parallel - racing for whichever lands first
            val sendResults = cfg.rpcUrls.map { rpc ->
                scope.async {
                    try {
                        val sig = withTimeoutOrNull(6000) { RpcClient.sendTransaction(rpc, txBase64, cfg.proxy) }
                        if (sig != null) rpc to sig else null
                    } catch (e: Exception) {
                        SweepLog.add("send to $rpc failed: ${e.message}")
                        null
                    }
                }
            }
            val accepted = sendResults.awaitAll().filterNotNull()
            if (accepted.isEmpty()) {
                SweepLog.add("sweep failed: no RPC accepted the transaction")
            } else {
                for ((rpc, sig) in accepted) {
                    SweepLog.add("accepted by $rpc → signature $sig")
                }
                updateNotificationSwept(amount, accepted.first().second)
            }

            // give the network a moment to settle the lowered balance before
            // this tries to sweep the same funds again
            cooldownUntilMs = System.currentTimeMillis() + 3000
        }
    }

    private fun updateNotificationSwept(amountLamports: Long, signature: String) {
        val sol = amountLamports / 1_000_000_000.0
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification("swept %.6f SOL · %s".format(sol, signature.take(8))))
    }
}
