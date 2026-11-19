package dev.forexsignals.feed;

import dev.forexsignals.market.Tick;

/** Receives feed events. Called from adapter threads; implementations hand off to the engine loop. */
public interface FeedListener {

    enum Status { CONNECTED, DISCONNECTED }

    void onTick(Tick tick);

    void onStatus(Status status, String detail);
}
