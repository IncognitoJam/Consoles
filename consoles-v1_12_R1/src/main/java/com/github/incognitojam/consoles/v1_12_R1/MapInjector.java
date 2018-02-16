package com.github.incognitojam.consoles.v1_12_R1;

import ca.jarcode.consoles.api.nms.MapInternals;
import net.minecraft.server.v1_12_R1.*;
import net.minecraft.server.v1_12_R1.ItemStack;
import net.minecraft.server.v1_12_R1.World;
import org.apache.commons.io.FileUtils;
import org.bukkit.*;
import org.bukkit.craftbukkit.v1_12_R1.CraftServer;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/*

This class breaks maps horribly! You might wonder why I'm doing this.

Well, here's some things that would happen if I didn't write this:

-   The minecraft server's renderer will continue to run and render crap to its
	own buffers. This is a waste of server resources.

-   The minecraft server will send its own packets to client, conflicting with mine.

-   The minecraft server's static map IDs that constantly increment with screw with
	my dynamic map IDs that are different for each client. Map data will overlap on
	the clients, screwing things up.

So, this class basically introduces its own dummy map items, renderers, and trackers
into the server.

I may look at actually hacking this (and craftbukkit) so that plugins that use the
Bukkit API end up actually going through my API to render maps, but that would require
some serious changes to the backend for this plugin (as it is purely based on canvases).

 */
public class MapInjector extends WorldMap {

    private static final Field PERSISTENT_COLLECTION_MAP;

    public static final MapInternals IMPL = new MapInternals() {
        // this is another terrible method. Not as much as the second one, but still terrible
        // there's no way to fix a map once it has been overridden, but the second method just kill map
        // functionality entirely anyway.
        public void overrideMap(int id) {
            World mainWorld = ((CraftServer) Bukkit.getServer()).getServer().worlds.get(0);
            try {
                // register the fake map
                MapInjector fake = new MapInjector(id);
                mainWorld.a("map_" + id, fake);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // don't try this at home
        public void injectTypes() {
            try {
                Field field = Items.class.getDeclaredField("FILLED_MAP");

                // reflect the reflect library. Yo dawg.
                Field modifiersField = Field.class.getDeclaredField("modifiers");
                modifiersField.setAccessible(true);
                // remove the final flag on the security int/bytes
                modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
                // replace the value
                field.set(null, new FakeItemWorldMap());

                // There are a number of things wrong with this, particularity that the JVM optimizes and works around
                // the fact that fields are final or not. You can't do this on compile-time constants (like strings
                // and primitives), and will not work under most security managers.
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void clearVanillaMapFiles() {
            String worldName = ((CraftServer) Bukkit.getServer()).getServer()
                    .getPropertyManager().getString("level-name", "world");
            File world = new File(Bukkit.getWorldContainer().getAbsolutePath() + File.separatorChar
                    + worldName + File.separatorChar + "data");
            if (!world.isDirectory()) return;
            File[] arr = world.listFiles();
            if (arr == null) return;
            for (File file : arr) {
                if (file.getName().startsWith("map_") && !file.isDirectory()) {
                    try {
                        FileUtils.forceDelete(file);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public Object mapItemNMS(short id) {
            overrideMap(id);
            return CraftItemStack.asNMSCopy(new org.bukkit.inventory.ItemStack(org.bukkit.Material.MAP, 1, id));
        }

        @Override
        public boolean updateSection(PreparedMapSection section, org.bukkit.World world, int centerX,
                                     int centerZ, int updateX, int updateZ, int scale) {
            return ChunkMapper.updateSection(section, world, centerX, centerZ, updateX, updateZ, scale);
        }
    };

    static {
        try {
            PERSISTENT_COLLECTION_MAP = PersistentCollection.class.getDeclaredField("a");
            PERSISTENT_COLLECTION_MAP.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException();
        }
    }

    private int id;

    public MapInjector(int id) {
        super("map_" + id);
        this.id = id;
        this.map = 0;
        this.scale = 0;
    }

    // This handles cursors for the map. Safe to remove entirely.
    @Override
    public void a(EntityHuman entityHuman, ItemStack itemStack) {
    }

    // map loading, we're not concerned about any data here.
    @Override
    public void a(NBTTagCompound tag) {
    }

    // map saving, we just ensure data is saved in a valid format
    // this prevents malformed NBT data.
    @Override
    public NBTTagCompound b(NBTTagCompound tag) {
        tag.setByte("dimension", (byte) 0);
        tag.setInt("xCenter", 0);
        tag.setInt("zCenter", 0);
        tag.setByte("scale", (byte) 0);
        tag.setShort("width", (short) 128);
        tag.setShort("height", (short) 128);
        tag.setByteArray("colors", this.colors);
        return tag;
    }

    // cursor packet, just send nothing
    @Override
    public Packet a(ItemStack itemStack, World world, EntityHuman entityHuman) {
        return null;
    }

    // create fake tracker
    @Override
    @SuppressWarnings("unchecked")
    public WorldMapHumanTracker a(EntityHuman entityHuman) {
        WorldMapHumanTracker worldMapHumanTracker = this.k.get(entityHuman);
        if (worldMapHumanTracker == null) {
            worldMapHumanTracker = new FakeTracker(entityHuman);
            this.k.put(entityHuman, worldMapHumanTracker);
            this.i.add(worldMapHumanTracker);
        }

        return worldMapHumanTracker;
    }

    public static class FakeItemWorldMap extends ItemWorldMap {

        // return fake maps
        @Override
        public WorldMap getSavedMap(ItemStack itemStack, World world) {
            return new MapInjector(itemStack.getData());
        }

        // render method, do nothing.
        @Override
        public void a(World world, Entity entity, WorldMap worldMap) {
        }

        // track method, do nothing.
        @Override
        public void a(ItemStack itemstack, World world, Entity entity, int i, boolean flag) {
        }

        // send map data, send dummy packet
        @Override
        public Packet a(ItemStack itemStack, World world, EntityHuman entityHuman) {
            return null;
        }

        // handle map NBT data, remove tags.
        @Override
        public void b(ItemStack itemStack, World world, EntityHuman entityHuman) {
            if (itemStack.hasTag()) {
                NBTTagCompound tag = itemStack.getTag();
                tag.setBoolean("map_is_scaling", false);
                tag.set("Decorations", new NBTTagList());
            }
        }

    }

    public class FakeTracker extends WorldMapHumanTracker {

        public FakeTracker(EntityHuman entityHuman) {
            super(entityHuman);
        }

        // tracker/update packet, send nothing
        @Override
        public Packet a(ItemStack itemStack) {
            return null;
        }

    }

}
