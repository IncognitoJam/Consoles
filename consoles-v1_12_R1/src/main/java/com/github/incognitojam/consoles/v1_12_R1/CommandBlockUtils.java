package com.github.incognitojam.consoles.v1_12_R1;

import ca.jarcode.consoles.api.nms.CommandExecutor;
import ca.jarcode.consoles.api.nms.CommandInternals;
import net.minecraft.server.v1_12_R1.ChatComponentText;
import net.minecraft.server.v1_12_R1.CommandBlockListenerAbstract;
import net.minecraft.server.v1_12_R1.TileEntityCommand;
import org.bukkit.block.CommandBlock;
import org.bukkit.craftbukkit.v1_12_R1.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.v1_12_R1.block.CraftCommandBlock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;

/*

Util class for hacking at command blocks.

 */
public class CommandBlockUtils implements CommandInternals {

    private static Field COMMAND_LISTENER;
    private static Field TILE_ENTITY;

    {
        try {
            COMMAND_LISTENER = TileEntityCommand.class.getDeclaredField("a");
            COMMAND_LISTENER.setAccessible(true);
            overrideFinal(COMMAND_LISTENER);

            TILE_ENTITY = CraftBlockEntityState.class.getDeclaredField("tileEntity");
            TILE_ENTITY.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TileEntityCommand getTileEntity(CraftCommandBlock commandBlock) {
        try {
            return (TileEntityCommand) TILE_ENTITY.get(commandBlock);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isRegistered(CommandBlock block) {
        TileEntityCommand entity = getTileEntity((CraftCommandBlock) block);
        CommandBlockListenerAbstract obj = entity.getCommandBlock();
        return obj instanceof CommandBlockListenerWrapper && ((CommandBlockListenerWrapper) obj).listening();
    }

    @Override
    public boolean registerListener(CommandBlock block, CommandExecutor listener) {
        TileEntityCommand entity = getTileEntity((CraftCommandBlock) block);
        CommandBlockListenerAbstract obj = entity.getCommandBlock();
        if (obj instanceof CommandBlockListenerWrapper && !isRegistered(block)) {
            ((CommandBlockListenerWrapper) obj).setConsoleListener(listener);
            return true;
        } else return false;
    }

    @Override
    public void sendMessage(CommandBlock block, String message) {
        TileEntityCommand command = getTileEntity((CraftCommandBlock) block);
        command.getCommandBlock().sendMessage(new ChatComponentText(message));
    }

    @Override
    public void registerBlockCommand(BlockCommand listener) {
        VanillaBlockCommand.registerLinkCommand(listener);
    }

    @Override
    public boolean wrap(CommandBlock block, BooleanSupplier commandBlocksEnabled) {
        try {
            TileEntityCommand entity = getTileEntity((CraftCommandBlock) block);
            CommandBlockListenerAbstract obj = entity.getCommandBlock();
            if (!(obj instanceof CommandBlockListenerWrapper)) {
                COMMAND_LISTENER.set(entity, new CommandBlockListenerWrapper(obj, commandBlocksEnabled, entity));
                return true;
            } else return false;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean restore(CommandBlock block) {
        TileEntityCommand entity = getTileEntity((CraftCommandBlock) block);
        Object obj = entity.getCommandBlock();
        if (obj instanceof CommandBlockListenerWrapper) {
            ((CommandBlockListenerWrapper) obj).setConsoleListener(null);
            return true;
        } else return false;
    }

    public void overrideFinal(Field field) throws NoSuchFieldException, IllegalAccessException {
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        // remove the final flag on the security int/bytes
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    }

}
