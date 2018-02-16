package com.github.incognitojam.consoles.v1_12_R1;

import ca.jarcode.consoles.api.nms.MapInternals;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multisets;
import net.minecraft.server.v1_12_R1.*;
import net.minecraft.server.v1_12_R1.Chunk;
import net.minecraft.server.v1_12_R1.World;
import org.bukkit.craftbukkit.v1_12_R1.CraftWorld;

import java.lang.reflect.Field;

public class ChunkMapper {

    private static final Field MAP_COLOR;

    static {
        try {
            MAP_COLOR = Block.class.getDeclaredField("y");
            MAP_COLOR.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static MaterialMapColor getMapColor(Block block) {
        try {
            return (MaterialMapColor) MAP_COLOR.get(block);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    // most of this code is decompiled and extracted from the minecraft server
    // I have not yet bothered to try to understand it completely. Most of this is
    // difficult to read because of the decompiler used.
    @SuppressWarnings({ "unchecked", "ConstantConditions" })
    public static boolean updateSection(MapInternals.PreparedMapSection section, org.bukkit.World bukkitWorld,
                                        int centerX, int centerZ, int updateX, int updateZ, int scale) {

        World world = ((CraftWorld) bukkitWorld).getHandle();

        boolean updated = false;

        // blocks per pixel
        int blockAmount = 1 << scale;

        // translating and offsetting coordinates from the update position to the section area
        int translatedX = (updateX - centerX) / blockAmount + 64;
        int translatedZ = (updateZ - centerZ) / blockAmount + 64;

        // update/render radius
        int renderRadius = 128 / blockAmount;

        // I have no idea
        if (world.worldProvider.n()) {
            renderRadius /= 2;
        }

        // yeah... I have no idea what the rest of this does, other than iterate through the
        // section area.
        for (int k1 = translatedX - renderRadius + 1; k1 < translatedX + renderRadius; ++k1) {

            // this variable in particular is confusing.
            double d0 = 0.0D;

            for (int l1 = translatedZ - renderRadius - 1; l1 < translatedZ + renderRadius; ++l1) {
                if (k1 >= 0 && l1 >= -1 && k1 < 128 && l1 < 128) {
                    int i2 = k1 - translatedX;
                    int j2 = l1 - translatedZ;
                    boolean flag1 = i2 * i2 + j2 * j2 > (renderRadius - 2) * (renderRadius - 2);
                    int k2 = (centerX / blockAmount + k1 - 64) * blockAmount;
                    int l2 = (centerZ / blockAmount + l1 - 64) * blockAmount;
                    HashMultiset hashmultiset = HashMultiset.create();
                    Chunk chunk = world.getChunkAt(k2 >> 4, l2 >> 4);

                    if (chunk.isEmpty()) {
                        world.getWorld().loadChunk(k2 >> 4, l2 >> 4, false);
                        chunk = world.getChunkAt(k2 >> 4, l2 >> 4);
                    }

                    int i3 = k2 & 15;
                    int j3 = l2 & 15;
                    int k3 = 0;
                    double d1 = 0.0D;
                    if (world.worldProvider.n()) {
                        int blockPosition = k2 + l2 * 231871;
                        blockPosition = blockPosition * blockPosition * 31287121 + blockPosition * 11;
                        if ((blockPosition >> 20 & 1) == 0) {
                            hashmultiset.add(getMapColor(Blocks.DIRT), 10);
                        } else {
                            hashmultiset.add(getMapColor(Blocks.STONE), 100);
                        }

                        d1 = 100.0D;
                    } else {
                        BlockPosition.MutableBlockPosition var37 = new BlockPosition.MutableBlockPosition();

                        for (int i4 = 0; i4 < blockAmount; ++i4) {
                            for (int b0 = 0; b0 < blockAmount; ++b0) {
                                int height = chunk.b(i4 + i3, b0 + j3) + 1;
                                IBlockData blockData = Blocks.AIR.getBlockData();
                                if (height > 1) {
                                    do {
                                        --height;
                                        blockData = chunk.getBlockData(var37.c(i4 + i3, height, b0 + j3));
                                    } while (blockData.a((IBlockAccess) world, var37) == MaterialMapColor.c && height > 0);

                                    if (height > 0 && blockData.getMaterial().isLiquid()) {
                                        int b2 = height - 1;

                                        IBlockData block;
                                        do {
                                            block = chunk.a(i4 + i3, b2--, b0 + j3);
                                            ++k3;
                                        } while (b2 > 0 && blockData.getMaterial().isLiquid());
                                    }
                                }

                                d1 += (double) height / (double) (blockAmount * blockAmount);
                                hashmultiset.add(blockData.a((IBlockAccess) world, var37));
                            }
                        }
                    }

                    k3 /= blockAmount * blockAmount;
                    double d2 = (d1 - d0) * 4.0D / (double) (blockAmount + 4) + ((double) (k1 + l1 & 1) - 0.5D) * 0.4D;
                    byte var38 = 1;
                    if (d2 > 0.6D) {
                        var38 = 2;
                    }

                    if (d2 < -0.6D) {
                        var38 = 0;
                    }

                    MaterialMapColor var39 = Iterables.getFirst(Multisets.copyHighestCountFirst(hashmultiset), MaterialMapColor.c);
                    if (var39 == MaterialMapColor.n) {
                        d2 = (double) k3 * 0.1D + (double) (k1 + l1 & 1) * 0.2D;
                        var38 = 1;
                        if (d2 < 0.5D) {
                            var38 = 2;
                        }

                        if (d2 > 0.9D) {
                            var38 = 0;
                        }
                    }

                    d0 = d1;
                    // this check (with flag1) is used to create the pixel outline for the renders
                    if (l1 >= 0 && i2 * i2 + j2 * j2 < renderRadius * renderRadius && (!flag1 || (k1 + l1 & 1) != 0)) {
                        synchronized (section.LOCK) {

                            // sample color from this section
                            byte var40 = section.colors[k1 + l1 * 128];
                            assert var39 != null;

                            // get color
                            byte var41 = (byte) (var39.ad * 4 + var38);

                            // if this color corresponds to unexplored space, even though
                            // it's in our radius, then there's no blocks at this position!

                            // I add a special pattern for missing blocks/unloaded chunks
                            if (var41 >= 0 && var41 <= 3)
                                var41 = (byte) ((k1 + l1 & 1) != 0 ? 44 : 46);

                            // if the color is different than the sampled color, add an update flag
                            if (var40 != var41) {
                                section.colors[k1 + l1 * 128] = var41;
                                updated = true;
                            }
                        }
                    }
                }
            }
        }
        return updated;
    }

}
