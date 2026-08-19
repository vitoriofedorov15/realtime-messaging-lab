/* Shared by the browser and Node regression tests; uint64 counters stay exact. */
(function (scope) {
    class ProtocolState {
        constructor() { this.users = new Set(); this.seen = new Set(); this.ready = false; this.watermark = 0n; this.last = 0n; }
        start(watermark, users) {
            this.watermark = BigInt(watermark.toString());
            this.last = 0n; this.ready = false; this.seen.clear(); this.users = new Set(users);
        }
        remember(id) {
            if (!id) throw new Error('Missing message ID');
            if (this.seen.has(id)) return false;
            this.seen.add(id);
            if (this.seen.size > 1000) this.seen.delete(this.seen.values().next().value);
            return true;
        }
        history(batch) {
            if (this.ready || BigInt(batch.snapshot_sequence.toString()) !== this.watermark) throw new Error('Invalid history snapshot');
            const fresh=[];
            for (const message of batch.history || []) {
                const sequence=BigInt(message.sequence.toString());
                if (this.seen.has(message.message_id)) continue;
                if (sequence <= this.last || sequence > this.watermark) throw new Error('History order mismatch');
                this.remember(message.message_id); this.last=sequence; fresh.push(message);
            }
            if (batch.complete) { this.ready=true; this.last=this.watermark; }
            return fresh;
        }
        live(message) {
            if (!this.ready) throw new Error('Live message arrived before history completed');
            if (this.seen.has(message.message_id)) return false;
            const sequence=BigInt(message.sequence.toString());
            if (sequence !== this.last + 1n) throw new Error('Live message sequence gap');
            this.remember(message.message_id); this.last=sequence; return true;
        }
    }
    if (typeof module !== 'undefined') module.exports=ProtocolState;
    else scope.ProtocolState=ProtocolState;
})(typeof window !== 'undefined' ? window : this);
