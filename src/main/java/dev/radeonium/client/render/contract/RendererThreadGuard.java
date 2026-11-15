package dev.radeonium.client.render.contract;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit owner-thread guard for renderer operations that touch GPU state.
 *
 * Bind once during renderer initialization on Minecraft's render thread.
 * CPU meshing workers must never use this guard to gain GPU access.
 */
public final class RendererThreadGuard {
    private final AtomicReference<Thread> owner = new AtomicReference<>();

    /** Binds this guard to the calling thread exactly once. */
    public void bindCurrentThread() {
        Thread current = Thread.currentThread();
        Thread existing = owner.get();
        if (existing == current) return;
        if (existing == null && owner.compareAndSet(null, current)) return;
        throw new IllegalStateException("Renderer thread is already bound to another thread");
    }

    /** Throws unless called by the bound renderer thread. */
    public void checkRenderThread() {
        Thread expected = owner.get();
        if (expected == null) {
            throw new IllegalStateException("Renderer thread has not been bound");
        }
        if (Thread.currentThread() != expected) {
            throw new IllegalStateException("GPU/renderer operation must run on the render thread");
        }
    }

    public boolean isRenderThread() {
        return owner.get() == Thread.currentThread();
    }

    public boolean isBound() {
        return owner.get() != null;
    }
}
