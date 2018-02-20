package ca.jarcode.consoles.computer;

import ca.jarcode.ascript.Script;
import ca.jarcode.consoles.Computers;
import ca.jarcode.consoles.Consoles;
import ca.jarcode.consoles.api.ConsoleCreateException;
import ca.jarcode.consoles.api.Position2D;
import ca.jarcode.consoles.api.nms.ConsolesNMS;
import ca.jarcode.consoles.computer.interpreter.ScriptContext;
import ca.jarcode.consoles.computer.manual.Arg;
import ca.jarcode.consoles.computer.manual.FunctionManual;
import ca.jarcode.consoles.computer.manual.ManualManager;
import ca.jarcode.consoles.internal.ConsoleComponent;
import ca.jarcode.consoles.internal.ConsoleHandler;
import ca.jarcode.consoles.internal.ManagedConsole;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static ca.jarcode.consoles.Lang.lang;
import static ca.jarcode.consoles.computer.ProgramUtils.schedule;

public class ComputerHandler implements Listener {

    private static ComputerHandler instance;

    // register lua functions in this class
    static {
        Script.map(ComputerHandler::lua_redstone, "redstone");
        Script.map(ComputerHandler::lua_redstoneLength, "redstoneLength");
        Script.map(ComputerHandler::lua_redstoneInputLength, "redstoneInputLength");
        Script.map(ComputerHandler::lua_redstoneInput, "redstoneInput");
        ManualManager.load(ComputerHandler.class);
    }

    // create default startup program
    static {
        File file = new File(Computers.getInstance().getDataFolder().getAbsolutePath()
                + File.separatorChar + "startup.lua");
        create:
        try {
            if (!file.exists()) {
                if (!file.createNewFile()) {
                    Computers.getInstance().getLogger().warning(String.format(lang.getString("file-create-fail"),
                            file.getAbsolutePath()));
                    break create;
                }
            } else break create;
            if (file.isDirectory()) {
                Computers.getInstance().getLogger().warning(String.format(lang.getString("file-create-fail"),
                        file.getAbsolutePath()));
                break create;
            }
            FileOutputStream out = new FileOutputStream(file);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, Charset.forName("UTF-8")), true);

            writer.println("-- Use this program to run code on all computers when they start up");
            writer.println("function main()");
            writer.println("\tprintc(\"Logged into &e\" .. hostname() .. \"&f as user &a\" .. getTerminal():getUser())");
            writer.println("\tnextLine()");
            writer.println("\tprintc(\"&7\" .. getTerminal():randomJoke())");
            writer.println("\tnextLine()");
            writer.println("\t-- old prompt style, uncomment this to restore it");
            writer.println("\t-- getTerminal():setPrompt(\"%u@%h:%d$ \")");
            writer.print("end");

            writer.flush();
            writer.close();
        } catch (IOException e) {
            Computers.getInstance().getLogger().warning(String.format(lang.getString("file-create-fail"),
                    file.getAbsolutePath()));
            e.printStackTrace();
        }
    }

    public static ComputerHandler getInstance() {
        return instance;
    }

    public static ItemStack newComputerStack() {
        return newComputerStack(null);
    }

    @SuppressWarnings("RedundantCast")
    public static ItemStack newComputerStack(String hostname) {
        ItemMeta meta;

        meta = Bukkit.getItemFactory().getItemMeta(Computers.useHeads ? Material.SKULL : Material.STAINED_GLASS);
        meta.setDisplayName(ChatColor.GREEN + lang.getString("computer-item-name") + ChatColor.GRAY
                + (hostname != null ? " (" + hostname + ")" : ""));
        meta.setLore(Arrays.asList(ChatColor.RESET + "3x2", ChatColor.RESET + lang.getString("computer-item-tooltip")));

        ItemStack stack = ConsolesNMS.internals.itemStackBuild(
                Computers.useHeads ? Material.SKULL_ITEM : Material.STAINED_GLASS,
                1, Computers.useHeads ? (short) 0 : 15, meta);

        if (Computers.useHeads) {
            // mod the skull item with the proper NBT data, such that clients will be able to see a certain
            // texture. You cannot do this properly with Bukkit in 1.8.x versions.
            ConsolesNMS.internals.modPlayerHead(stack, UUID.fromString(Computers.headUUID), Computers.headTexture);
        }
        // fake enchant the item if we're using the old block style
        else ConsolesNMS.internals.fakeEnchantItem(stack);

        // this is why I go through the effort to set custom NBT tags
        // this prevents players from creating a computer without crafting
        // it, unless they are setting the NBT tags explicitly - which
        // would mean they are probably an admin.
        ConsolesNMS.internals.setItemNBTBoolean(stack, "computer", true);

        if (hostname != null)
            ConsolesNMS.internals.setItemNBTString(stack, "hostname", hostname);
        return stack;
    }

    private ArrayList<String> inactiveHosts = new ArrayList<>();
    private ArrayList<Computer> computers = new ArrayList<>();
    private HashMap<String, CommandBlock> linkRequests = new HashMap<>();
    private HashMap<Location, Computer> trackedBlocks = new LinkedHashMap<>();

    {
        instance = this;
        LinkCommand.registerLinkCommand();
        if (Computers.allowCrafting) {
            ShapedRecipe computerRecipe = new ShapedRecipe(newComputerStack());
            computerRecipe.shape("AAA", "CBC", "AAA");
            computerRecipe.setIngredient('A', Material.STONE);
            computerRecipe.setIngredient('B', Material.REDSTONE_BLOCK);
            computerRecipe.setIngredient('C', Material.DIAMOND);
            Bukkit.getServer().addRecipe(computerRecipe);
        }
    }

    public ComputerHandler() {
        ComputerData.init();
        Bukkit.getScheduler().scheduleSyncRepeatingTask(Computers.getInstance(), this::saveAll, 6000, 6000);
        Bukkit.getScheduler().scheduleSyncDelayedTask(Computers.getInstance(),
                () -> computers.addAll(ComputerData.makeAll(inactiveHosts::add)));
    }

    // updates the computer's cache of tracked blocks that are behind it, re-indexing the array.
    public void updateBlocks(Computer computer) {
        iterateBehind((block) -> {
            if (block.getType() == Material.REDSTONE_BLOCK && !trackedBlocks.containsKey(block.getLocation())) {
                trackedBlocks.put(block.getLocation().clone(), computer);
            }
        }, computer);
    }

    // iterates over blocks behind the computer
    private static void iterateBehind(Consumer<Block> consumer, Computer computer) {
        ManagedConsole console = computer.getConsole();
        BlockFace f = console.getDirection();
        Location at = console.getLocation();
        // horizontal modifiers
        Location[] hm = {
                new Location(at.getWorld(), 1, 0, 0), // N
                new Location(at.getWorld(), 0, 0, 1), // E
                new Location(at.getWorld(), 1, 0, 0), // S
                new Location(at.getWorld(), 0, 0, 1), // W
        };
        // offsets (origin block position behind the computer
        Location[] pm = {
                new Location(at.getWorld(), 0, 0, 1), // N
                new Location(at.getWorld(), -1, 0, 0), // E
                new Location(at.getWorld(), 0, 0, -1), // S
                new Location(at.getWorld(), 1, 0, 0), // W
        };
        int i = -1;
        switch (f) {
            case NORTH:
                i = 0;
                break;
            case EAST:
                i = 1;
                break;
            case SOUTH:
                i = 2;
                break;
            case WEST:
                i = 3;
                break;
        }
        if (i == -1) return;
        // origin
        Location p = at.clone().add(pm[i]);
        Location o;
        for (int h = 0; h < computer.getConsole().getFrameHeight(); h++) {
            p.setY(p.getBlockY() + h);
            o = p.clone();
            for (int t = 0; t < computer.getConsole().getFrameWidth(); t++) {
                consumer.accept(o.getBlock());
                o.add(hm[i]);
            }
        }
    }

    // finds all chests behind the computer
    @SuppressWarnings("SuspiciousMethodCalls")
    public static Chest[] findChests(Computer computer) {
        List<Chest> chests = new ArrayList<>();
        iterateBehind((block) -> {
            if (block.getType() == Material.CHEST && block.getState() instanceof Chest) {
                boolean overlap = false;
                for (Chest chest : chests)
                    if (chest.getBlockInventory() == ((Chest) block.getState()).getBlockInventory())
                        overlap = true;
                if (!overlap)
                    chests.add((Chest) block.getState());
            }
        }, computer);
        return chests.toArray(new Chest[chests.size()]);
    }

    public static boolean[] findInputs(Computer computer) {
        List<Boolean> list = new ArrayList<>();
        iterateBehind(block -> {
            if (block.getType().isSolid() && block.getType() != Material.REDSTONE_BLOCK)
                list.add(block.isBlockPowered());
        }, computer);
        boolean[] arr = new boolean[list.size()];
        for (int t = 0; t < list.size(); t++)
            arr[t] = list.get(t);
        return arr;
    }

    @FunctionManual("Returns the powered state of an input block behind the computer at the specified index. Indexes " +
            "start at 0 and end at the amount of eligible inputs, minus one, The amount of inputs can be obtained with " +
            "redstoneInputLength(). Redstone inputs are determined by the amount of solid, powerable blocks behind the " +
            "computer.")
    public static boolean lua_redstoneInput(
            @Arg(name = "index", info = "the index of the input block to check") Integer index) {
        Computer computer = ScriptContext.getComputer();
        boolean[] inputs;
        inputs = schedule(() -> findInputs(computer), ScriptContext.terminatedSupplier());
        return inputs.length > index && index >= 0 && inputs[index];
    }

    @FunctionManual("Returns the amount of blocks behind the computer that can receive redstone input")
    public static int lua_redstoneInputLength() {
        Computer computer = ScriptContext.getComputer();
        boolean[] inputs;
        inputs = schedule(() -> findInputs(computer), ScriptContext.terminatedSupplier());
        return inputs.length;
    }

    @FunctionManual("Toggles the redstone output at the specified index. Indexes start at 0 and " +
            "end at the amount of redstone outputs, minus one. The amount of available outputs can " +
            "be obtained with redstoneLength(). Redstone outputs are added by placing a redstone block " +
            "directly behind the computer.")
    @SuppressWarnings({ "deprecation", "SynchronizationOnLocalVariableOrMethodParameter" })
    public static boolean lua_redstone(
            @Arg(name = "index", info = "the index of the output to toggle") Integer index,
            @Arg(name = "state", info = "the state of the output, true for on, false for off") Boolean on) {
        Computer computer = ScriptContext.getComputer();
        // our supplier to be called in the main thread
        BooleanSupplier func = () -> {
            Location[] blocks = ComputerHandler.getInstance().trackedFor(computer);
            if (blocks != null && index < blocks.length && index >= 0) {
                Block block = blocks[index].getBlock();
                if (block == null) return false;
                block.setType(on ? Material.REDSTONE_BLOCK : Material.STAINED_GLASS);
                if (!on)
                    block.setData((byte) 14);
                BlockState state = block.getState();
                state.update(true, true);
                return true;
            } else return false;
        };
        // flags and locks
        final Object lock = new Object();
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean result = new AtomicBoolean(false);
        // run our task in the main thread
        Bukkit.getScheduler().scheduleSyncDelayedTask(Computers.getInstance(), () -> {
            synchronized (lock) {
                // set the result
                result.set(func.getAsBoolean());
                // task has finished
                done.set(true);
                // notify the lock
                lock.notify();
            }
        });
        // wait on the lock
        try {
            while (!done.get()) {
                synchronized (lock) {
                    lock.wait();
                }
            }
        } catch (InterruptedException e) {
            // this shouldn't happen, if it does, crash the program.
            throw new RuntimeException(e);
        }
        return result.get();
    }

    @FunctionManual("Returns the amount of redstone outputs there are available for this computer")
    public static int lua_redstoneLength() {
        Computer computer = ScriptContext.getComputer();
        Location[] blocks = ComputerHandler.getInstance().trackedFor(computer);
        return blocks == null ? 0 : blocks.length;
    }

    public Location[] trackedFor(Computer computer) {
        return trackedBlocks.entrySet().stream()
                .filter(entry -> entry.getValue() == computer)
                .map(Map.Entry::getKey)
                .toArray(Location[]::new);
    }

    @EventHandler
    public void scrollListen(PlayerItemHeldEvent event) {
        int diff = event.getNewSlot() - event.getPreviousSlot();
        if (diff == 8) diff = -1;
        if (diff == -8) diff = 1;
        if (Math.abs(diff) > 3) return;
        for (ManagedConsole console : ConsoleHandler.getInstance()
                .getConsolesLookingAt(event.getPlayer().getEyeLocation())) {
            ConsoleComponent component = console.getComponent(Computer.ROOT_COMPONENT_POSITION);
            if (component instanceof EditorComponent) {
                ((EditorComponent) component).scroll(diff * 2);
            }
        }
    }

    @EventHandler
    public void trackBlockPlace(BlockPlaceEvent e) {
        computers.stream()
                .filter(c -> c.getConsole().getLocation().getWorld() == e.getPlayer().getWorld()
                        && c.getConsole().getLocation().distanceSquared(e.getPlayer().getLocation()) < 64)
                .forEach(this::updateBlocks);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (trackedBlocks.containsKey(e.getBlock().getLocation())) {
            if (e.getBlock().getType() != Material.REDSTONE_BLOCK) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.YELLOW + "Change to redstone before removing");
            } else {
                trackedBlocks.remove(e.getBlock().getLocation());
            }
        }
    }

    @EventHandler
    public void onBlockPistonExtend(BlockPistonExtendEvent e) {
        if (trackedBlocks.containsKey(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPistonRetract(BlockPistonRetractEvent e) {
        if (trackedBlocks.containsKey(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }

    public void saveAll() {
        if (!Computers.hideSaveMessages)
            Computers.getInstance().getLogger().info(lang.getString("saving-computers"));
        long count = computers.stream().peek(Computer::save).count();
        if (!Computers.hideSaveMessages)
            Computers.getInstance().getLogger().info(String.format(lang.getString("saved-computers"), count));
    }

    public void interact(Position2D pos, Player player, ManagedConsole console) {
        computers.stream().filter(comp -> comp.getConsole() == console)
                .forEach(comp -> comp.clickEvent(pos, player.getName()));
    }

    public void command(String command, Player player) {
        List<ManagedConsole> lookingAt = Arrays.asList(
                ConsoleHandler.getInstance().getConsolesLookingAt(player.getEyeLocation())
        );
        computers.stream().filter(comp -> lookingAt.contains(comp.getConsole()))
                .forEach(comp -> comp.playerCommand(command, player.getName()));
    }

    @EventHandler
    public void saveAll(PluginDisableEvent e) {
        if (e.getPlugin() == Computers.getInstance()) {
            saveAll();
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isComputer(e.getItemInHand())) {
            String hostname = getHostname(e.getItemInHand());
            if (build(e.getPlayer(), e.getBlockPlaced().getLocation(), getHostname(e.getItemInHand()))) {

                // fix for removing computers from creative inventories
                if (e.getPlayer().getGameMode() == GameMode.CREATIVE) {
                    Inventory inv = e.getPlayer().getInventory();
                    for (int t = 0; t < inv.getSize(); t++) {

                        ItemStack stack = inv.getItem(t);
                        if (stack != null && isComputer(stack) && getHostname(stack).equals(hostname)) {
                            if (stack.getAmount() >= 2) {
                                stack.setAmount(stack.getAmount() - 1);
                                inv.setItem(t, stack);
                            } else
                                inv.setItem(t, null);
                        }
                    }
                }

                Bukkit.getScheduler().scheduleSyncDelayedTask(Computers.getInstance(),
                        () -> e.getBlockPlaced().setType(Material.AIR));
            } else {
                e.setCancelled(true);
            }
        }
    }

    public List<Computer> getComputers() {
        return Collections.unmodifiableList(computers);
    }

    private List<Computer> getComputers(UUID uuid) {
        return computers.stream()
                .filter(computer -> computer.getOwner().equals(uuid))
                .collect(Collectors.toList());
    }

    private boolean build(Player player, Location location, String hostname) {

        if (getComputers(player.getUniqueId()).size() >= Computers.maxComputers
                && !player.hasPermission("computer.limit.ignore")) {
            player.sendMessage(ChatColor.RED + String.format(lang.getString("computer-limit"), Computers.maxComputers));
            return false;
        }

        BlockFace face = direction(player);
        ManagedComputer computer;

        Supplier<ManagedComputer> func = () -> new ManagedComputer(findHostname(player), player.getUniqueId());

        if (hostname.isEmpty())
            hostname = null;

        if (hostname == null)
            computer = func.get();
        else {
            computer = ComputerData.load(hostname);
            if (computer == null) {
                computer = func.get();
                hostname = null;
                player.sendMessage(ChatColor.YELLOW + lang.getString("computer-load-fail"));
            }
        }

        try {
            computer.create(face, location);

            // update metadata if this was created with an existing hostname
            if (hostname != null) {
                boolean r1 = ComputerData.updateMeta(hostname, (meta) -> {
                    meta.face = face;
                    meta.location = location;
                });
                boolean r2 = ComputerData.updateHeader(hostname, (data) -> data.built = true);
                if (!r1 || !r2)
                    Computers.getInstance().getLogger().warning(String.format(lang.getString("metadata-write-fail"),
                            hostname));

                ComputerHandler.getInstance().register(computer);
            }
            return true;
        } catch (ConsoleCreateException e) {
            player.sendMessage(ChatColor.RED + lang.getString("computer-create-fail"));
            if (Computers.debug)
                e.printStackTrace();
            unregister(computer, false);
            return false;
        }
    }

    private String findHostname(Player player) {
        String name = player.getName().toLowerCase() + "-";
        int[] index = { 0 };
        while (computers.stream().filter(comp -> comp.getHostname().equals(name + index[0])).findFirst().isPresent()) {
            index[0]++;
        }
        return name + index[0];
    }

    private BlockFace direction(Player player) {
        // shameless copy-paste from my other math code
        Location eye = player.getEyeLocation();
        double yaw = eye.getYaw() > 0 ? eye.getYaw() : 360 - Math.abs(eye.getYaw()); // remove negative degrees
        yaw += 90; // rotate +90 degrees
        if (yaw > 360)
            yaw -= 360;
        yaw = (yaw * Math.PI) / 180;
        double pitch = ((eye.getPitch() + 90) * Math.PI) / 180;

        double xp = Math.sin(pitch) * Math.cos(yaw);
        double zp = Math.sin(pitch) * Math.sin(yaw);
        if (Math.abs(xp) > Math.abs(zp)) {
            return xp < 0 ? BlockFace.EAST : BlockFace.WEST;
        } else {
            return zp < 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        }
    }

    private String getHostname(ItemStack stack) {
        return ConsolesNMS.internals.getItemNBTString(stack, "hostname");
    }

    // we only check for a special NBT tab to see if the item is a computer
    private boolean isComputer(ItemStack stack) {
        return ConsolesNMS.internals.hasItemNBTTag(stack) && ConsolesNMS.internals.getItemNBTBoolean(stack, "computer");
    }

    public boolean hostnameTaken(String hostname) {
        return inactiveHosts.contains(hostname) || computers.stream()
                .anyMatch(comp -> comp.getHostname().equals(hostname.toLowerCase()));
    }

    public Computer find(String hostname) {
        return computers.stream().filter(comp -> comp.getHostname().equals(hostname))
                .findFirst().orElse(null);
    }

    public void request(String hostname, CommandBlock block) {
        linkRequests.put(hostname, block);
        find(hostname).requestDevice(block, event -> linkRequests.remove(hostname));
    }

    public void register(Computer computer) {
        computers.add(computer);
        inactiveHosts.remove(computer.getHostname());
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent e) {
        if (e.getPlugin() instanceof Consoles)
            trackedBlocks.keySet().stream().map(Location::getBlock).forEach(b -> {
                if (b != null && b.getType() != Material.REDSTONE_BLOCK)
                    b.setType(Material.REDSTONE_BLOCK);
            });
    }

    public void unregister(Computer computer, boolean delete) {
        computers.remove(computer);
        Iterator<Map.Entry<Location, Computer>> it = trackedBlocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, Computer> entry = it.next();
            if (entry.getValue() == computer) {
                it.remove();
                Block block = entry.getKey().getBlock();
                if (block != null) {
                    block.setType(Material.REDSTONE_BLOCK);
                }
            }
        }
        if (delete && !ComputerData.delete(computer.getHostname()))
            Computers.getInstance().getLogger().warning("Failed to remove computer: " + computer.getHostname());
        if (!delete && !inactiveHosts.contains(computer.getHostname()))
            inactiveHosts.add(computer.getHostname());
        if (!delete)
            ComputerData.updateHeader(computer.getHostname(), (data) -> data.built = false);
    }

}
