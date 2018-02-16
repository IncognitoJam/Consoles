package com.github.incognitojam.consoles.v1_12_R1;

import ca.jarcode.consoles.api.nms.CommandExecutor;
import net.minecraft.server.v1_12_R1.*;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Wrapper class for the NMS command block listener that we use to
 * listen for changes and events from the command block.
 */
public class CommandBlockListenerWrapper extends CommandBlockListenerAbstract {

    private static final Field COMMAND_RESULT, CHAT_COMPONENT, SENDER;

    private static final String[] OVERRIDE_COMMANDS = {
            "link"
    };

    private CommandExecutor consoleListener;
    private BooleanSupplier commandBlocksEnabled;

    static {
        try {
            COMMAND_RESULT = CommandBlockListenerAbstract.class.getDeclaredField("d");
            CHAT_COMPONENT = CommandBlockListenerAbstract.class.getDeclaredField("d");
            SENDER = CommandBlockListenerAbstract.class.getDeclaredField("sender");
            COMMAND_RESULT.setAccessible(true);
            CHAT_COMPONENT.setAccessible(true);
            SENDER.setAccessible(true);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void setResult(int result) {
        try {
            COMMAND_RESULT.setInt(this, result);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void setChatComponent(IChatBaseComponent chatComponent) {
        try {
            CHAT_COMPONENT.set(this, chatComponent);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static CommandSender getSender(CommandBlockListenerAbstract inst) {
        try {
            return (CommandSender) SENDER.get(inst);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }


    final CommandBlockListenerAbstract underlying;
    // this is a cheat to make this act like an inner class when we get this$0 via reflection later
    @SuppressWarnings({ "FieldCanBeLocal", "UnusedDeclaration" })
    private final TileEntityCommand this$0;

    CommandBlockListenerWrapper(CommandBlockListenerAbstract underlying,
                                BooleanSupplier commandBlocksEnabled, TileEntityCommand command) {
        this.underlying = underlying;
        this.commandBlocksEnabled = commandBlocksEnabled;
        this.sender = getSender(underlying);
        this.this$0 = command;
    }

    public void setConsoleListener(CommandExecutor listener) {
        consoleListener = listener;
    }

    public boolean listening() {
        return consoleListener != null;
    }

    private boolean override() {
        String command = getCommand().toLowerCase();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        command = command.split(" ")[0];
        return Arrays.asList(OVERRIDE_COMMANDS).contains(command);
    }

    // -- override methods --
    // (we can't use @Override for version reasons)

    public int k() {
        return underlying.k();
    }

    public IChatBaseComponent l() {
        return underlying.l();
    }

    public NBTTagCompound a(NBTTagCompound nbttagcompound) {
        return underlying.a(nbttagcompound);
    }

    public void b(NBTTagCompound nbttagcompound) {
        underlying.b(nbttagcompound);
    }

    public boolean a(int i, String s) {
        return underlying.a(i, s);
    }

    public void setCommand(String s) {
        underlying.setCommand(s);
    }

    public String getCommand() {
        return underlying.getCommand();
    }

    public boolean a(World world) {
        if (world.isClientSide) {
            setResult(0);
            return false;
        }
        if (consoleListener != null) {
            sendMessage(new ChatComponentText(consoleListener.execute(sender, getCommand())));
            setResult(1);
            return true;
        }
        if (!commandBlocksEnabled.getAsBoolean() && !override()) {
            setChatComponent(new ChatComponentText("You cannot use server commands"));
//            h(); ???
            setResult(0);
            return true;
        }
        MinecraftServer minecraftServer = C_();
        if (minecraftServer != null && minecraftServer.M() && minecraftServer.getEnableCommandBlock()) {
//            minecraftServer.getCommandHandler(); ???
            try {
                setChatComponent(null);
                setResult(executeCommand(this, sender, getCommand()));
                return true;
            } catch (Throwable var6) {
                var6.printStackTrace();
                return false;
            }
        } else {
            setResult(0);
            return true;
        }
    }

    public String getName() {
        return underlying.getName();
    }

    public IChatBaseComponent getScoreboardDisplayName() {
        return underlying.getScoreboardDisplayName();
    }

    public void setName(String s) {
        underlying.setName(s);
    }

    public void sendMessage(IChatBaseComponent ichatbasecomponent) {
        underlying.sendMessage(ichatbasecomponent);
    }

    public boolean getSendCommandFeedback() {
        return underlying.getSendCommandFeedback();
    }

    public void a(CommandObjectiveExecutor.EnumCommandResult result, int i) {
        underlying.a(result, i);
    }

    public MinecraftServer C_() {
        return underlying.C_();
    }

    public void i() {
        underlying.i();
    }

    public void b(IChatBaseComponent ichatbasecomponent) {
        underlying.b(ichatbasecomponent);
    }

    public void a(boolean flag) {
        underlying.a(flag);
    }

    public boolean n() {
        return underlying.n();
    }

    public boolean a(EntityHuman entityhuman) {
        return override() || underlying.a(entityhuman);
    }

    public CommandObjectiveExecutor o() {
        return underlying.o();
    }

    public BlockPosition getChunkCoordinates() {
        return underlying.getChunkCoordinates();
    }

    public Vec3D d() {
        return underlying.d();
    }

    public World getWorld() {
        return underlying.getWorld();
    }

    public Entity f() {
        return underlying.f();
    }

}
