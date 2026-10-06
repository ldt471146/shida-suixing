package cn.gxnu.campus.network

import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.hasSameIdentityAs

/** Owns network handles and guards the binder reads that can finish after a newer event. */
internal class WifiNetworkCache<N> {
    data class Entry<N>(val network: N, val snapshot: NetworkSnapshot)
    data class ReadTicket(val epoch: Long, val id: String, val revision: Long)
    data class RefreshTicket(val epoch: Long, val revision: Long)

    private val entries = mutableMapOf<String, Entry<N>>()
    private val lostIds = mutableSetOf<String>()
    private val revisions = mutableMapOf<String, Long>()
    private var epoch = 0L
    private var revision = 0L
    private var latestRefresh = 0L

    @Synchronized fun beginRead(id: String, available: Boolean = false): ReadTicket? {
        if (available) lostIds.remove(id)
        if (id in lostIds) return null
        val next = ++revision
        revisions[id] = next
        return ReadTicket(epoch, id, next)
    }

    @Synchronized fun beginRefresh(): RefreshTicket {
        latestRefresh = ++revision
        return RefreshTicket(epoch, latestRefresh)
    }

    @Synchronized fun beginRefreshRead(refresh: RefreshTicket, id: String): ReadTicket? {
        if (refresh.epoch != epoch || refresh.revision != latestRefresh || (revisions[id] ?: 0L) > refresh.revision) return null
        return beginRead(id)
    }

    @Synchronized fun isCurrent(ticket: ReadTicket): Boolean =
        ticket.epoch == epoch && ticket.id !in lostIds && revisions[ticket.id] == ticket.revision

    @Synchronized fun apply(ticket: ReadTicket, network: N, snapshot: NetworkSnapshot?) {
        if (!isCurrent(ticket) || (snapshot != null && snapshot.id != ticket.id)) return
        if (snapshot == null) entries.remove(ticket.id)
        else entries[ticket.id] = Entry(network, snapshot)
    }

    @Synchronized fun prune(refresh: RefreshTicket, liveIds: Set<String>) {
        if (refresh.epoch != epoch || refresh.revision != latestRefresh) return
        val absent = entries.keys.filter { it !in liveIds && (revisions[it] ?: 0L) <= refresh.revision }
        for (id in absent) {
            entries.remove(id)
            revisions[id] = ++revision
        }
    }

    @Synchronized fun remove(id: String) {
        lostIds.add(id)
        revisions[id] = ++revision
        entries.remove(id)
    }

    @Synchronized fun clear() {
        epoch++
        entries.clear()
        lostIds.clear()
        revisions.clear()
    }

    @Synchronized fun entry(id: String): Entry<N>? = entries[id]?.takeUnless { id in lostIds }
    @Synchronized fun entryFor(snapshot: NetworkSnapshot): Entry<N>? =
        entry(snapshot.id)?.takeIf { it.snapshot.hasSameIdentityAs(snapshot) }
    @Synchronized fun isLost(id: String): Boolean = id in lostIds
    @Synchronized fun snapshots(): List<NetworkSnapshot> = entries.values.map { it.snapshot }
}
