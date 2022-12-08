package xyz.duncanruns.julti.resetting;

import xyz.duncanruns.julti.AffinityManager;
import xyz.duncanruns.julti.Julti;
import xyz.duncanruns.julti.JultiOptions;
import xyz.duncanruns.julti.instance.InstanceManager;
import xyz.duncanruns.julti.instance.MinecraftInstance;
import xyz.duncanruns.julti.util.KeyboardUtil;

import java.util.Collections;
import java.util.Set;

public abstract class ResetManager {
    protected final InstanceManager instanceManager;
    protected final Julti julti;

    public ResetManager(Julti julti) {
        this.julti = julti;
        this.instanceManager = julti.getInstanceManager();
    }

    public boolean doReset() {
        String toCopy = JultiOptions.getInstance().clipboardOnReset;
        if (!toCopy.isEmpty()) {
            KeyboardUtil.copyToClipboard(toCopy);
        }
        return true;
    }

    public boolean doBGReset() {
        return false;
    }

    public boolean doWallFullReset() {
        return false;
    }

    public boolean doWallSingleReset() {
        return false;
    }

    public boolean doWallLock() {
        return false;
    }

    public boolean doWallFocusReset() {
        return false;
    }

    public boolean doWallPlay() {
        return false;
    }

    public void notifyPreviewLoaded(MinecraftInstance instance) {
        if (JultiOptions.getInstance().useAffinity) {
            AffinityManager.ping(julti);
            AffinityManager.ping(julti, 301);
        }
    }

    public void notifyWorldLoaded(MinecraftInstance instance) {
        if (JultiOptions.getInstance().useAffinity) {
            AffinityManager.ping(julti);
        }
    }

    public boolean shouldDirtCover(MinecraftInstance instance) {
        if (JultiOptions.getInstance().resetForBeach) {
            return instance.hasPreviewEverStarted() && (((!getLockedInstances().contains(instance)) && !instance.isPreviewLoaded()) || instance.shouldDirtCover());
        }
        return instance.shouldDirtCover();
    }

    public Set<MinecraftInstance> getLockedInstances() {
        return Collections.emptySet();
    }
}
