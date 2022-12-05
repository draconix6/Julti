package xyz.duncanruns.julti.instance;

import com.sun.jna.Pointer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.duncanruns.julti.Julti;
import xyz.duncanruns.julti.JultiOptions;
import xyz.duncanruns.julti.util.*;
import xyz.duncanruns.julti.win32.Win32Con;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class MinecraftInstance {

    private static final Logger LOGGER = LogManager.getLogger("MinecraftInstance");
    private static final Pattern advancementsLoadedPattern = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d] \\[.+/INFO]: Loaded 0 advancements$");
    private static final Pattern startPreviewPattern = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d] \\[.*/INFO]: Starting Preview at \\(-?\\d+\\.\\d+, -?\\d+\\.\\d+, -?\\d+\\.\\d+\\)$");
    private static final Pattern startWorldGenPattern = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d] \\[.*/INFO]: Preparing start region for dimension minecraft:overworld$");
    private static final Pattern openToLanPattern = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d] \\[.*/INFO]: Started serving on \\d+$");

    // Basic instance information
    private final WindowTitleInfo titleInfo;
    private Pointer hwnd;
    private Path instancePath = null;

    // Missing Window Stuff
    private boolean notMC = false; // true when a MinecraftInstance is constructed with a window handle which points to a non-mc window
    private boolean missingReported = false;

    // Information to be discovered
    private ResetType resetType = null;
    private Integer createWorldKey = null;
    private Integer leavePreviewKey = null;

    // State tracking
    private boolean inPreview = false;
    private boolean worldLoaded = false;
    private boolean worldGenerating = false;
    private long lastPreviewStart = -1L;

    // Log tracking
    private long logProgress = -1;
    private FileTime lastLogModify = null;
    private Integer pid = null;


    public MinecraftInstance(Pointer hwnd) {
        this.hwnd = hwnd;
        this.titleInfo = new WindowTitleInfo(getCurrentWindowTitle());
    }

    private String getCurrentWindowTitle() {
        if (!hasWindow()) return "Missing Window";
        return HwndUtil.getHwndTitle(hwnd);
    }

    public boolean hasWindow() {
        if (hwnd != null && HwndUtil.hwndExists(hwnd)) {
            return true;
        } else {
            hwnd = null;
            return false;
        }
    }

    public MinecraftInstance(Path instancePath) {
        this.hwnd = null;
        this.titleInfo = new WindowTitleInfo();
        this.instancePath = instancePath;
        this.notMC = false;
    }

    public boolean hasWindowQuick() {
        return hwnd != null;
    }

    synchronized public long getLastPreviewStart() {
        return lastPreviewStart;
    }

    public String getOriginalTitle() {
        if (titleInfo.waiting()) {
            titleInfo.provide(HwndUtil.getHwndTitle(hwnd));
        }
        return titleInfo.getOriginalTitle();
    }

    private Integer getCreateWorldKey() {
        if (createWorldKey == null) {
            createWorldKey = getKey("key_Create New World");
        }
        return createWorldKey;
    }

    private Integer getKey(String keybindingTranslation) {
        Path optionsPath = getInstancePath().resolve("options.txt");
        try {
            for (String line : Files.readAllLines(optionsPath)) {
                String[] args = line.split(":");
                if (args.length > 1 && keybindingTranslation.equals(args[0])) {
                    return McKeyUtil.getVkFromMCTranslation(args[1]);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Integer getLeavePreviewKey() {
        if (leavePreviewKey == null) {
            leavePreviewKey = getKey("key_Leave Preview");
        }
        return leavePreviewKey;
    }

    public boolean isActuallyMC() {
        getInstancePath();
        return !notMC;
    }

    public Path getInstancePath() {
        if (instancePath != null) return instancePath;

        if (notMC || !hasWindow()) return null;

        instancePath = HwndUtil.getInstancePathFromPid(getPid());
        if (instancePath == null) {
            notMC = true;
        }

        return instancePath;
    }

    public int getPid() {
        if (pid == null) {
            pid = HwndUtil.getPidFromHwnd(hwnd);
            return pid;
        }
        return pid;
    }

    public Pointer getHwnd() {
        // Note: if hwnd == null, the instance is unusable. The proper way to manage this is to replace the object with a new one.
        return hwnd;
    }

    /**
     * Returns a sorting number which can be summarized as follows: For each character in the instance name that is a
     * number, add 11 plus the number itself. For example, "Multi 16 2" would be (11+1)+(11+6)+(11+2) = 42.
     *
     * @return a sorting number to correctly sort instances.
     */
    public int getSortingNum() {
        int i = 0;
        for (char c : getName().toCharArray()) {
            if (c >= '0' && c <= '9') {
                i += 11 + (c - '0');
            }
        }
        return i;
    }

    public String getName() {
        Path instancePath = getInstancePath();
        if (instancePath == null) {
            return "Unknown Instance"; //This name should probably never be seen, regardless it is here.
        }
        if (instancePath.getName(instancePath.getNameCount() - 1).toString().equals(".minecraft")) {
            instancePath = instancePath.getParent();
        }
        String name = instancePath.getName(instancePath.getNameCount() - 1).toString();
        if (name.equals("Roaming")) {
            return "Default Launcher";
        }
        return name;
    }

    public boolean justWentMissing() {
        if (!hasWindow() && !missingReported) {
            missingReported = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MinecraftInstance that = (MinecraftInstance) o;

        if (hwnd == null && that.hwnd == null) {
            return false;
        }

        return Objects.equals(hwnd, that.hwnd);
    }

    @Override
    public String toString() {
        return "Instance \"" + getName() + "\"";
    }

    /**
     * @param instanceNum -1 for not updating title, otherwise the real instance number (index + 1).
     */
    synchronized public void activate(int instanceNum) {
        if (hasWindow()) {
            new Thread(this::ensureWindowState).start();
            HwndUtil.showHwnd(hwnd);
            HwndUtil.activateHwnd(hwnd);
            if (worldLoaded) {
                new Thread(() -> {
                    sleep(70);// Magic number; without this, the mouse does not get locked into MC
                    pressEsc();
                    sleep(70);
                    while (MouseUtil.isMouseVisible() && isActive()) {
                        MouseUtil.keyDown(Win32Con.VK_XBUTTON1);
                        MouseUtil.keyUp(Win32Con.VK_XBUTTON1);
                        sleep(70);
                    }
                }).start();
            }
            if (instanceNum != -1) setWindowTitle("Minecraft* - Instance " + instanceNum);
            log(Level.INFO, "Activated instance " + getName());
        } else {
            log(Level.WARN, "Could not activate instance " + getName() + " (not opened)");
        }
    }

    public void ensureWindowState() {
        JultiOptions options = JultiOptions.getInstance();

        // "Do nothing" conditions
        if (!options.letJultiMoveWindows) return;
        Rectangle rectangle = getWindowRectangle();
        if (options.windowPos[0] == rectangle.x &&
                options.windowPos[1] == rectangle.y &&
                options.windowSize[0] == rectangle.width &&
                options.windowSize[1] == rectangle.height &&
                options.useBorderless == isBorderless() &&
                (options.useBorderless || options.useMaximize == isMaximized())
        ) return;

        if (options.useBorderless) setBorderless();
        else undoBorderless();


        if (options.useMaximize && !options.useBorderless) maximize();
        else {
            restore();
            move(options.windowPos[0], options.windowPos[1], options.windowSize[0], options.windowSize[1]);
        }

    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private void pressEsc() {
        KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_ESCAPE);
    }

    private boolean isActive() {
        return Objects.equals(HwndUtil.getCurrentHwnd(), hwnd);
    }

    public void setWindowTitle(String title) {
        if (hasWindow()) {
            HwndUtil.setHwndTitle(hwnd, title);
        }
    }

    public static void log(Level level, String message) {
        LOGGER.log(level, message);
        LogReceiver.receive(level, message);
    }

    public Rectangle getWindowRectangle() {
        return HwndUtil.getHwndRectangle(hwnd);
    }

    public boolean isBorderless() {
        return HwndUtil.isHwndBorderless(hwnd);
    }

    public boolean isMaximized() {
        return HwndUtil.isHwndMaximized(hwnd);
    }

    public void setBorderless() {
        HwndUtil.setHwndBorderless(hwnd);
    }

    public void undoBorderless() {
        HwndUtil.undoHwndBorderless(hwnd);
    }

    public void maximize() {
        HwndUtil.maximizeHwnd(hwnd);
    }

    public void restore() {
        HwndUtil.restoreHwnd(hwnd);
    }

    public void move(int x, int y, int w, int h) {
        HwndUtil.moveHwnd(hwnd, x, y, w, h);
    }

    public void squish(float squish) {
        if (squish == 1f) return;

        JultiOptions options = JultiOptions.getInstance();
        Rectangle resultRectangle = new Rectangle(options.windowPos[0], options.windowPos[1], options.windowSize[0], (int) (options.windowSize[1] / squish));
        if (options.useMaximize && isMaximized()) {
            restore();
        } else {
            if (getWindowRectangle().equals(resultRectangle)) return;
        }
        move(resultRectangle.x, resultRectangle.y, resultRectangle.width, resultRectangle.height);
    }

    public ScreenCapUtil.ImageInfo captureScreen() {
        return ScreenCapUtil.capture(this.hwnd);
    }

    public void closeWindow() {
        if (hasWindow()) {
            HwndUtil.sendCloseMessage(hwnd);
            log(Level.INFO, "Closed " + getName());
        } else {
            log(Level.WARN, "Could not close " + getName() + " because it is not open.");
        }
    }

    public void launch() {
        try {
            String multiMCPath = JultiOptions.getInstance().multiMCPath;
            if (!multiMCPath.isEmpty())
                Runtime.getRuntime().exec(new String[]{multiMCPath, "--launch", getName()});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void pressF3Esc() {
        KeyboardUtil.sendKeyDownToHwnd(hwnd, Win32Con.VK_F3, true);
        KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_ESCAPE);
        KeyboardUtil.sendKeyUpToHwnd(hwnd, Win32Con.VK_F3, true);
    }

    private void pressF3() {
        KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_F3);
    }

    synchronized public void reset(boolean singleInstance) {
        // If no window, do nothing
        if (!hasWindow()) {
            log(Level.INFO, "Could not reset instance " + getName() + " (not opened)");
            return;
        }

        JultiOptions options = JultiOptions.getInstance();

        if (!singleInstance && options.letJultiMoveWindows) new Thread(() -> squish(options.wideResetSquish)).start();

        // Press f3 before reset to potentially get rid of pie chart
        //if (worldLoaded) {
        //    pressF3();
        //    new Timer().schedule(new TimerTask() {
        //        @Override
        //        public void run() {
        //            Thread.currentThread().setName("reset-finisher");
        //            finishReset();
        //        }
        //    }, 100);
        //} else
        finishReset();

    }

    private void finishReset() {
        switch (getResetType()) {
            case NEW_ATUM:
                KeyboardUtil.sendKeyToHwnd(hwnd, getCreateWorldKey());
            case HAS_PREVIEW:
                if (inPreview) {
                    KeyboardUtil.sendKeyToHwnd(hwnd, getLeavePreviewKey());
                } else {
                    runNoAtumLeave();
                }
            case EXIT_WORLD:
                runNoAtumLeave();
        }
        worldLoaded = false;
        worldGenerating = false;
        setInPreview(false);
        log(Level.INFO, "Reset instance " + getName());
    }

    private void runNoAtumLeave() {
        WindowTitleInfo.Version version = titleInfo.getVersion();

        KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_ESCAPE);
        if (version.getMajor() > 12) {
            KeyboardUtil.sendKeyDownToHwnd(hwnd, Win32Con.VK_LSHIFT, true);
            KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_TAB);
            KeyboardUtil.sendKeyUpToHwnd(hwnd, Win32Con.VK_LSHIFT, true);
        } else if (version.getMajor() == 8 && version.getMinor() == 9) {
            sleep(70); // Magic Number
            // Anchiale Support
            for (int i = 0; i < 7; i++) {
                KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_TAB);
            }
        } else {
            sleep(70); // Magic Number
            KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_TAB);
        }
        KeyboardUtil.sendKeyToHwnd(hwnd, Win32Con.VK_RETURN);
    }

    private ResetType getResetType() {
        if (resetType == null) {
            if (getCreateWorldKey() != null) {
                resetType = ResetType.NEW_ATUM;
            } else if (getLeavePreviewKey() != null) {
                resetType = ResetType.HAS_PREVIEW;
            } else {
                resetType = ResetType.EXIT_WORLD;
            }
        }
        return resetType;
    }

    synchronized private void setInPreview(boolean inPreview) {
        if (inPreview && !this.inPreview) lastPreviewStart = System.currentTimeMillis();
        this.inPreview = inPreview;
    }

    public void checkLog(Julti julti) {
        if (hasWindow()) {
            String newLogContents = getNewLogContents();
            JultiOptions options = JultiOptions.getInstance();
            runLogCheck(newLogContents, options, julti);
        }
    }

    public boolean shouldDirtCover() {
        return hasPreviewEverStarted() && (!isWorldGenerating()) && (!isWorldLoaded()) && (!isPreviewLoaded());
    }

    public boolean hasPreviewEverStarted() {
        return lastPreviewStart != -1L;
    }

    public boolean isWorldGenerating() {
        return worldGenerating;
    }

    synchronized public boolean isWorldLoaded() {
        return worldLoaded;
    }

    synchronized public boolean isPreviewLoaded() {
        return inPreview;
    }

    synchronized private void runLogCheck(String newLogContents, JultiOptions options, final Julti julti) {
        if (!newLogContents.isEmpty()) {
            for (String line : newLogContents.split("\n")) {
                line = line.trim();
                if (startPreviewPattern.matcher(line).matches()) {
                    setInPreview(true);
                    worldLoaded = false;
                    if (options.useF3) {
                        pressF3Esc();
                    }
                    julti.getResetManager().notifyPreviewLoaded(this);
                } else if (advancementsLoadedPattern.matcher(line).matches()) {
                    setInPreview(false);
                    worldLoaded = true;
                    worldGenerating = false;
                    if (JultiOptions.getInstance().pauseOnLoad && !Objects.equals(hwnd, HwndUtil.getCurrentHwnd())) {
                        if (options.useF3) {
                            pressF3Esc();
                        } else {
                            pressEsc();
                        }
                    }
                    julti.getResetManager().notifyWorldLoaded(this);
                } else if (startWorldGenPattern.matcher(line).matches()) {
                    worldGenerating = true;
                } else if (JultiOptions.getInstance().noCopeMode && openToLanPattern.matcher(line).matches()) {
                    julti.getResetManager().doReset();
                }
            }
        }
    }

    String getNewLogContents() {
        Path logPath = getLogPath();

        // If log progress has not been jumped, jump and return
        if (logProgress == -1) {
            tryJumpLogProgress();
            return "";
        }

        // If modification date has not changed, return
        try {
            FileTime newModifyTime = Files.getLastModifiedTime(logPath);
            if (!newModifyTime.equals(lastLogModify)) {
                lastLogModify = newModifyTime;
            } else {
                return "";
            }
        } catch (Exception ignored) {
            return "";
        }

        // If file size is significantly less than log progress, reset log progress
        try {
            long size = Files.size(logPath);
            if (size < (logProgress / 2)) {
                tryJumpLogProgress();
                log(Level.INFO, "Log reading restarted! (" + getName() + ")");
                return "";
            }
        } catch (IOException ignored) {
        }


        // Read new bytes then format and return as a string
        try (InputStream stream = Files.newInputStream(logPath)) {
            stream.skip(logProgress);

            ArrayList<Byte> byteList = new ArrayList<>();

            int next = stream.read();
            while (next != -1) {
                byteList.add((byte) next);
                logProgress++;
                next = stream.read();
            }

            byte[] bytes = new byte[byteList.size()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = byteList.get(i);
            }

            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Sets logProgress to the amount of bytes in the latest log of the instance.
     * Failure is ignored, as logProgress will still be -1 afterwards, indicating the task would still need to be done.
     */
    private void tryJumpLogProgress() {
        try {
            Path logPath = getLogPath();
            if (Files.isRegularFile(logPath)) {
                logProgress = Files.readAllBytes(logPath).length;
                lastLogModify = Files.getLastModifiedTime(logPath);
            }
        } catch (IOException ignored) {
        }
    }

    public Path getLogPath() {
        Path instancePath = getInstancePath();
        if (notMC) {
            return null;
        }
        return instancePath.resolve("logs").resolve("latest.log");
    }

    public void tryClearWorlds() {
        try {
            clearWorlds();
        } catch (Exception ignored) {
        }
    }

    private void clearWorlds() throws IOException {
        Path savesPath = getInstancePath().resolve("saves");
        List<Path> worldsToRemove = new ArrayList<>();
        for (String string : savesPath.toFile().list()) {
            if (!string.startsWith("_")) {
                worldsToRemove.add(savesPath.resolve(string));
            }
        }
        worldsToRemove.removeIf(path -> (!path.toFile().isDirectory()) || (path.resolve("Reset Safe.txt").toFile().isFile()));
        worldsToRemove.sort((o1, o2) -> (int) (o2.toFile().lastModified() - o1.toFile().lastModified()));
        for (int i = 0; i < 6 && !worldsToRemove.isEmpty(); i++) {
            worldsToRemove.remove(0);
        }
        for (Path path : worldsToRemove) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    public boolean wasPreviewInLastMillis(int millis) {
        return System.currentTimeMillis() - lastPreviewStart < millis;
    }

    private enum ResetType {
        EXIT_WORLD, // Esc+Shift+Tab+Enter always
        HAS_PREVIEW, // Esc+Shift+Tab+Enter but use leavePreviewKey when in preview
        NEW_ATUM // Use createWorldKey always
    }
}
