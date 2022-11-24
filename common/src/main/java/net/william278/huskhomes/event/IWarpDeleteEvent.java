package net.william278.huskhomes.event;

import net.william278.huskhomes.position.Warp;
import org.jetbrains.annotations.NotNull;

/**
 * Representation of an event that fires when a single warp is deleted
 */
public interface IWarpDeleteEvent extends CancellableEvent {

    /**
     * Get the warp being deleted
     *
     * @return the {@link Warp} being deleted
     */
    @NotNull
    Warp getWarp();

}
