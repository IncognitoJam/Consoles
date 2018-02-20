package ca.jarcode.consoles.computer;

import ca.jarcode.ascript.Script;
import ca.jarcode.consoles.Computers;
import ca.jarcode.consoles.api.*;
import ca.jarcode.consoles.api.nms.ConsolesNMS;
import ca.jarcode.consoles.computer.boot.Kernel;
import ca.jarcode.consoles.computer.devices.CommandDevice;
import ca.jarcode.consoles.computer.filesystem.*;
import ca.jarcode.consoles.computer.interpreter.SandboxProgram;
import ca.jarcode.consoles.computer.interpreter.ScriptContext;
import ca.jarcode.consoles.computer.manual.Arg;
import ca.jarcode.consoles.computer.manual.FunctionManual;
import ca.jarcode.consoles.computer.manual.ManualManager;
import ca.jarcode.consoles.event.ButtonEvent;
import ca.jarcode.consoles.event.ConsoleEventListener;
import ca.jarcode.consoles.internal.ConsoleButton;
import ca.jarcode.consoles.internal.ConsoleComponent;
import ca.jarcode.consoles.internal.ConsoleDialog;
import ca.jarcode.consoles.internal.ManagedConsole;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CommandBlock;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static ca.jarcode.consoles.Lang.lang;

public abstract class Computer implements Runnable {

    // Lua<->Java mappings
    static {
        Script.map(Computer::lua_switchSession, "switchSession");
        Script.map(Computer::lua_dialog, "dialog");
        Script.map(Computer::lua_messageOwner, "tellOwner");
        ManualManager.load(Computer.class);
    }

    static final Position2D STATUS_COMPONENT_POSITION = new Position2D(2, 2);
    public static final Position2D ROOT_COMPONENT_POSITION = new Position2D(2, 2 + StatusBar.HEIGHT);

    private String hostname;

    private final ManagedConsole console;

    private FSFolder root = new FSFolder();

    private final ConsoleComponent[] feeds = new ConsoleComponent[8];

    private int componentIndex = -1;

    private final UUID owner;

    private Kernel kernel;

    private int taskId = -1;

    private StatusBar bar;

    private final List<BiConsumer<String, String>> listeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, Position2D>> interactListeners = new CopyOnWriteArrayList<>();

    private final Map<String, Consumer<String>> messageListeners = new ConcurrentHashMap<>();

    // for programs
    private volatile boolean ignoreUnauthorizedViewChange = false;

    public Computer(String hostname, UUID owner, ManagedConsole console) {
        this.hostname = hostname;
        this.owner = owner;
        this.console = console;
        console.setType("computer");
        feeds[0] = new Terminal(this, false);
        setScreenIndex(0);
    }

    public Computer(String hostname, UUID owner, int width, int height) {
        this.hostname = hostname;
        this.owner = owner;
        console = new ManagedConsole(width, height);
        console.setType("computer");
        feeds[0] = new Terminal(this, false);
        setScreenIndex(0);
    }

    public boolean setHostname(String hostname) {
        this.hostname = hostname;
        for (ConsoleComponent component : feeds) {
            if (component instanceof Terminal) {
                ((Terminal) component).updatePrompt();
            }
        }
        return true;
    }

    // This is used to boot a provided program, and actually obtain the instance of the program itself,
    // instead of the wrapper class. This will not work for Lua programs, and these are ran in the current thread.
    // basically, it's our mini boot loader
    @SuppressWarnings("unchecked")
    private <T extends FSProvidedProgram> T boot(String path, Class<T> type) {
        try {
            FSBlock block = root.get(path);
            if (block instanceof FSProvidedProgram) {
                ProgramInstance instance = new ProgramInstance((FSProvidedProgram) block, "", this);
                if (type.isInstance(instance.provided)) {
                    instance.run();
                    return (T) instance.provided;
                }
            }
        }
        // we can ignore this, because the only possible cause is the file itself missing from the drive
        catch (FileNotFoundException ignored) {
        }
        // so, just return null if it's an invalid boot or the expected type is wrong
        return null;
    }

    public void unregisterClickListener(BiConsumer<String, Position2D> consumer) {
        interactListeners.remove(consumer);
    }

    public void registerClickListener(BiConsumer<String, Position2D> consumer) {
        interactListeners.add(consumer);
    }

    public void unregisterCommandListener(BiConsumer<String, String> consumer) {
        listeners.remove(consumer);
    }

    public void registerCommandListener(BiConsumer<String, String> consumer) {
        listeners.add(consumer);
    }

    public boolean isChannelRegistered(String channel) {
        return messageListeners.containsKey(channel);
    }

    public void registerMessageListener(String channel, Consumer<String> consumer) {
        messageListeners.put(channel, consumer);
    }

    public void unregisterMessageListener(String channel) {
        messageListeners.remove(channel);
    }

    public Consumer<String> getMessageListener(String channel) {
        return messageListeners.get(channel);
    }

    public void clickEvent(Position2D pos, String player) {
        interactListeners.stream().forEach(consumer -> consumer.accept(player, pos.copy()));
    }

    public void playerCommand(String command, String player) {
        listeners.stream().forEach(consumer -> consumer.accept(player, command));
    }

    public void status(String status) {
        bar.setText(status);
        console.repaint();
    }

    public void setIgnoreUnauthorizedViewChange(boolean ignore) {
        this.ignoreUnauthorizedViewChange = ignore;
    }

    public int getViewWidth() {
        return getConsole().getWidth() - (2 + ROOT_COMPONENT_POSITION.getX());
    }

    public int getViewHeight() {
        return getConsole().getHeight() - (2 + ROOT_COMPONENT_POSITION.getY());
    }

    public File linkFile(FSStoredFile stored) {
        File parent = new File(ComputerData.computerFolder, getHostname()
                + File.separatorChar + "files");
        File file = new File(parent, stored.uuid.toString() + ".dat");
        validateExists(parent, File::mkdirs);
        validateExists(file, File::createNewFile);
        return file;
    }

    private void validateExists(File file, BlockCreator creator) {
        if (!file.exists()) {
            try {
                if (!creator.create(file))
                    throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Failed to create file: " + file.getAbsolutePath(), e);
            }
        }
    }

    @FunctionalInterface
    private interface BlockCreator {

        boolean create(File file) throws IOException;
    }

    public void load(File file) throws IOException {
        try {
            // create kernel instance for loading fs
            kernel = Kernel.install(Computer.this);
            // create instance
            SerializedFilesystem fs = new SerializedFilesystem(this);
            // map out serialized file tree
            fs.readFrom(new FileInputStream(file));
            // call block serializers
            root = (FSFolder) fs.deserialize();
        } catch (Exception e) {
            if (e instanceof IOException)
                throw (IOException) e;
            else
                e.printStackTrace();
        }
    }

    // Creates and installs the computer.
    public void create(BlockFace face, Location location) throws ConsoleCreateException {
        console.create(face, location);
        bar = new StatusBar(console);
        console.putComponent(STATUS_COMPONENT_POSITION, bar);
        getCurrentTerminal().println(ChatColor.GREEN + "Network boot: " + ChatColor.WHITE + "(" + hostname + ")");
        getCurrentTerminal().advanceLine();
        ComputerHandler.getInstance().updateBlocks(this);
        console.repaint();
        bootTask();
    }

    private void bootTask() {
        Bukkit.getScheduler().scheduleSyncDelayedTask(Computers.getInstance(), () -> {
            if (!root.exists("boot/vmlinuz")) {
                try {
                    kernel = Kernel.install(Computer.this);
                    kernel.routine("install");
                } catch (Exception e) {
                    printAfter(String.format(lang.getString("computer-kernel-install-fail"),
                            e.getClass()), 2);
                    e.printStackTrace();
                    return;
                }
            } else {
                kernel = boot("boot/vmlinuz", Kernel.class);
            }
            // register main task
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(Computers.getInstance(), Computer.this, 1L, 1L);

            Terminal term = getCurrentTerminal();

            // clear the terminal
            term.clear();
            term.setShowPrompt(false);

            // boot routine, installs devices, runs init program, etc
            kernel.routine("boot");

            // task for running computer startup program
            Runnable startup = () -> {
                // task for when the feed finishes writing to the terminal
                term.doAfter(() -> {
                    term.setShowPrompt(true);

                    // setup terminal
                    term.setupPrompt();

                    // repaint
                    console.repaint();

                    // save!
                    save();
                });

                // run server-wide startup program
                SandboxProgram.execFile("startup.lua", term);
            };

            // run init program normally in its own thread
            term.run("/init");

            // if the terminal returned immediately, the program didn't start
            if (term.hasEnded())
                startup.run(); // run our startup task
            else
                term.doAfter(startup); // schedule our startup task to run after the init program
        }, 10);
    }

    public void save() {
        try {
            new ComputerData(this).save();
        } catch (IOException e) {
            Computers.getInstance().getLogger().severe(lang.getString("computer-save-fail"));
            e.printStackTrace();
        }
    }

    private void printAfter(final String text, long delay) {
        Bukkit.getScheduler().scheduleSyncDelayedTask(Computers.getInstance(), () -> {
            getCurrentTerminal().print(text);
            console.repaint();
        }, delay);
    }

    public boolean screenAvailable(int index) {
        return !(index >= 8 || index < 0) && feeds[index] == null;
    }

    public boolean setScreenIndex(int index) {
        if (index >= 8 || index < 0)
            throw new ArrayIndexOutOfBoundsException();
        console.putComponent(ROOT_COMPONENT_POSITION, feeds[index]);
        componentIndex = index;
        if (console.created())
            console.repaint();
        return true;
    }

    public void registerPainter(Integer index, CanvasPainter painter, CanvasInteractListener listener, Integer bg) {
        if (index >= 8 || index < 0)
            throw new ArrayIndexOutOfBoundsException();
        Console api = Console.wrap(console);
        CanvasComponent comp = api.newComponent(getViewWidth(), getViewHeight())
                .painter(painter)
                .listen(listener)
                .enabledHandler(() -> true, (b) -> {
                })
                .background((byte) (int) bg)
                .create();
        setComponent(index, (ConsoleComponent) comp);
    }

    @FunctionManual("Used to switch the current screen session that the user is looking at. The program is " +
            "still attached to the original console it was started it, and should set the screen session back " +
            "after the program exits.")
    private static void lua_switchSession(
            @Arg(name = "id", info = "the id of the screen session to switch to") Integer id) {
        Computer computer = ScriptContext.getComputer();
        computer.switchView(id);
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void requestDevice(CommandBlock block, ConsoleEventListener<ConsoleButton, ButtonEvent> listener) {
        ConsoleButton allow = new ConsoleButton(console, lang.getString("comp-allow"));
        ConsoleButton deny = new ConsoleButton(console, lang.getString("comp-deny"));
        Location loc = block.getLocation();
        Position2D pos = dialog(String.format(lang.getString("computer-command-connect"),
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                , allow, deny);
        allow.addEventListener(event -> {
            ConsolesNMS.commandInternals.sendMessage(block, lang.getString("command-connection-accept"));
            addDeviceFile("cmd", new CommandDevice(block));
            console.removeComponent(pos);
        });
        deny.addEventListener(event -> {
            ConsolesNMS.commandInternals.sendMessage(block, lang.getString("command-connection-deny"));
            console.removeComponent(pos);
        });
        if (listener != null)
            allow.addEventListener(listener);
    }

    public Position2D dialog(String text, ConsoleComponent... components) {
        Map.Entry<Position2D, ConsoleDialog> entry = ConsoleDialog.create(console, text, components);
        Position2D pos = entry.getKey();
        while (console.componentAt(pos))
            pos = new Position2D(pos.getX() + 3, pos.getY() + 3);
        if (pos.getX() > 0 && pos.getY() > 0) {
            console.putComponent(pos, entry.getValue());
            console.repaint();
            return pos;
        } else return null;
    }

    public void addDeviceFile(String baseName, FSFile file) {
        try {
            FSFolder folder = (FSFolder) getRoot().get("dev");
            int index = 0;
            while (folder.contents.keySet().contains(baseName + index))
                index++;
            folder.contents.put(baseName + index, file);
        }
        // ignore adding dev ice files when the device folder doesn't exist
        catch (FileNotFoundException | ClassCastException ignored) {
        }
    }

    // ALL cleanup should be done in here
    // turns out we don't actually have to do much.
    public void destroy(boolean delete) {
        console.remove();
        if (taskId != -1)
            Bukkit.getScheduler().cancelTask(taskId);
        console.getLocation().getWorld().dropItemNaturally(console.getLocation(),
                delete ? ComputerHandler.newComputerStack() : ComputerHandler.newComputerStack(hostname));
    }

    public Kernel getKernel() {
        return kernel;
    }

    public FSFolder getRoot() {
        return root;
    }

    public String getHostname() {
        return hostname;
    }

    public ManagedConsole getConsole() {
        return console;
    }

    public UUID getOwner() {
        return owner;
    }

    public List<String> getSystemPath() {
        return kernel.getSystemPath();
    }

    public void setComponent(int index, ConsoleComponent component) {
        if (index >= 8 || index < 0)
            throw new ArrayIndexOutOfBoundsException();
        if (index == 0)
            throw new IllegalArgumentException("Cannot change default component!");
        feeds[index] = component;
        if (index == componentIndex) {
            console.putComponent(ROOT_COMPONENT_POSITION, feeds[index]);
            if (console.created())
                console.repaint();
        }
    }

    public int getComponentIndex() {
        return componentIndex;
    }

    public ConsoleComponent getCurrentComponent() {
        return feeds[componentIndex];
    }

    @FunctionManual("Opens a dialog with the given text, and lets the program continue to execute " +
            "while the dialog overlays the screen.")
    public static void lua_dialog(
            @Arg(name = "text", info = "the text to display on the dialog") String text) {
        Computer computer = ScriptContext.getComputer();
        Script.main(() -> computer.showDialog(text));
    }

    @FunctionManual("Sends a message to the owner of this computer, in chat.")
    public static void lua_messageOwner(
            @Arg(name = "text", info = "the message to send to the owner") String text) {
        Computer computer = ScriptContext.getComputer();
        Script.main(() -> {
            UUID uuid = computer.getOwner();
            Player player = Bukkit.getPlayer(uuid);
            player.sendMessage(text);
        });
    }

    public ConsoleDialog showDialog(String text, ConsoleComponent... children) {
        ConsoleDialog dialog = ConsoleDialog.show(console, text, children);
        console.repaint();
        return dialog;
    }

    public void showDialog(String text) {
        ConsoleButton button = new ConsoleButton(console, lang.getString("comp-ok"));
        final ConsoleDialog dialog = ConsoleDialog.show(console, text, button);
        button.addEventListener(event -> console.removeComponent(dialog));
        console.repaint();
    }

    @SuppressWarnings("unchecked")
    public <T extends ConsoleComponent> List<T> findComponents(Class<T> type) {
        List<T> list = new ArrayList<>();
        for (ConsoleComponent feed : feeds) {
            if (type.isAssignableFrom(feed.getClass())) {
                list.add((T) feed);
            }
        }
        return list;
    }

    public Terminal getCurrentTerminal() {
        return getCurrentComponent() instanceof Terminal ? (Terminal) getCurrentComponent() : null;
    }

    public boolean switchView(int view, Player player) {
        if (ignoreUnauthorizedViewChange && (player == null || !player.getUniqueId().equals(getOwner())))
            return false;
        return switchView(view);
    }

    public boolean switchView(int view) {
        view--;
        if (view < 0 || view >= feeds.length)
            return false;
        int v = view;
        Bukkit.getScheduler().scheduleSyncDelayedTask(Computers.getInstance(), () -> {
            setScreenIndex(v);
            status(String.format(lang.getString("computer-session-switch"), (v + 1)));
        });
        return true;
    }

    public void showDialogWithClose(String text, ConsoleComponent... children) {
        ConsoleButton button = new ConsoleButton(console, lang.getString("comp-close"));
        ConsoleComponent[] arr = new ConsoleComponent[children.length + 1];
        arr[0] = button;
        System.arraycopy(children, 0, arr, 1, children.length);
        final ConsoleDialog dialog = ConsoleDialog.show(console, text, arr);
        button.addEventListener(event -> console.removeComponent(dialog));
        console.repaint();
    }

    // Called by applications to get its own terminal instance
    public Terminal getTerminal(Object program) {
        for (ConsoleComponent component : feeds) {
            if (component instanceof Terminal && ((Terminal) component).getLastProgramInstance().contains(program))
                return (Terminal) component;
        }
        return null; // this is bad. If this happens, a program has been detached from its terminal instance!
    }

    public Terminal getTerminal(ProgramInstance instance) {
        for (ConsoleComponent component : feeds) {
            if (component instanceof Terminal && ((Terminal) component).getLastProgramInstance() == instance)
                return (Terminal) component;
        }
        return null;
    }

    public FSBlock resolve(String input, Object program) {
        return getBlock(input, getTerminal(program).getCurrentDirectory());
    }

    public FSBlock getBlock(String input, String currentDirectory) {
        // start from root
        if (input.startsWith("/")) {
            input = input.substring(1);
            if (input.isEmpty())
                return root;
            try {
                return root.get(input);
            } catch (FileNotFoundException e) {
                return null;
            }
        }
        // start from current directory
        else {
            try {
                String str;
                if (currentDirectory.startsWith("/"))
                    str = currentDirectory.substring(1);
                else str = currentDirectory;
                FSBlock block = root.get(str);
                return ((FSFolder) block).get(input);
            } catch (FileNotFoundException e) {
                return null;
            }
        }

    }

    public void run() {
        kernel.tick();
    }

}
