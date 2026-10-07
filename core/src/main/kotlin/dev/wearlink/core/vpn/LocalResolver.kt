package dev.wearlink.core.vpn

import android.net.DnsResolver
import android.os.CancellationSignal
import android.system.ErrnoException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * sing-box's "local" DNS server: resolves on the underlying physical network with the
 * system resolver (used to look up the proxy server's own address).
 */
class LocalResolver(private val monitor: DefaultNetworkMonitor) : LocalDNSTransport {

    private val executor = Executors.newCachedThreadPool()

    override fun raw() = true

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = monitor.network ?: throw IllegalStateException("Нет доступной сети")
        val signal = CancellationSignal()
        ctx.onCancel { signal.cancel() }
        val latch = CountDownLatch(1)
        DnsResolver.getInstance().rawQuery(
            network,
            message,
            DnsResolver.FLAG_NO_RETRY,
            executor,
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    latch.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) ctx.errnoCode(cause.errno) else ctx.errorCode(SERVFAIL)
                    latch.countDown()
                }
            },
        )
        latch.await()
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) =
        throw UnsupportedOperationException("raw transport only")

    private companion object {
        const val SERVFAIL = 2
    }
}
