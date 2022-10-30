/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.registry.populator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableMap;
import com.nukkitx.nbt.NBTInputStream;
import com.nukkitx.nbt.NbtList;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtMapBuilder;
import com.nukkitx.nbt.NbtType;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.cloudburstmc.protocol.bedrock.codec.v527.Bedrock_v527;
import org.cloudburstmc.protocol.bedrock.codec.v544.Bedrock_v544;
import org.cloudburstmc.protocol.bedrock.data.defintions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.defintions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.defintions.SimpleDefinitionRegistry;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.level.block.BlockStateValues;
import org.geysermc.geyser.level.physics.PistonBehavior;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.type.BlockMapping;
import org.geysermc.geyser.registry.type.BlockMappings;
import org.geysermc.geyser.util.BlockUtils;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;

/**
 * Populates the block registries.
 */
public final class BlockRegistryPopulator {
    /**
     * Stores the raw blocks JSON until it is no longer needed.
     */
    private static JsonNode BLOCKS_JSON;

    public static void populate() {
        registerJavaBlocks();
        registerBedrockBlocks();

        BLOCKS_JSON = null;
    }

    private static void registerBedrockBlocks() {
        BiFunction<String, NbtMapBuilder, String> emptyMapper = (bedrockIdentifier, statesBuilder) -> null;
        ImmutableMap<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>> blockMappers = ImmutableMap.<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>>builder()
                .put(ObjectIntPair.of("1_19_0", Bedrock_v527.CODEC.getProtocolVersion()), (bedrockIdentifier, statesBuilder) -> {
                    if (bedrockIdentifier.equals("minecraft:muddy_mangrove_roots")) {
                        statesBuilder.remove("pillar_axis");
                    }
                    return null;
                })
                .put(ObjectIntPair.of("1_19_20", Bedrock_v544.CODEC.getProtocolVersion()), emptyMapper).build();

        for (Map.Entry<ObjectIntPair<String>, BiFunction<String, NbtMapBuilder, String>> palette : blockMappers.entrySet()) {
            NbtList<NbtMap> blocksTag;
            try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResource(String.format("bedrock/block_palette.%s.nbt", palette.getKey().key()));
                 NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)))) {
                NbtMap blockPalette = (NbtMap) nbtInputStream.readTag();
                blocksTag = (NbtList<NbtMap>) blockPalette.getList("blocks", NbtType.COMPOUND);
            } catch (Exception e) {
                throw new AssertionError("Unable to get blocks from runtime block states", e);
            }
            // New since 1.16.100 - find the block runtime ID by the order given to us in the block palette,
            // as we no longer send a block palette
            Object2ObjectMap<NbtMap, BlockDefinition> blockStateOrderedMap = new Object2ObjectOpenHashMap<>(blocksTag.size());

            int stateVersion = -1;
            for (int i = 0; i < blocksTag.size(); i++) {
                NbtMapBuilder builder = blocksTag.get(i).toBuilder();
                builder.remove("name_hash"); // Quick workaround - was added in 1.19.20
                NbtMap tag = builder.build();
                if (blockStateOrderedMap.containsKey(tag)) {
                    throw new AssertionError("Duplicate block states in Bedrock palette: " + tag);
                }
                blockStateOrderedMap.put(tag, new SimpleBlockDefinition(tag.getString("name"), i, tag));
                if (stateVersion == -1) {
                    stateVersion = tag.getInt("version");
                }
            }
            int javaRuntimeId = -1;

            BlockDefinition airDefinition = null;
            BlockDefinition commandBlockDefinition = null;
            BlockDefinition waterDefinition = null;
            BlockDefinition movingBlockDefinition = null;
            Iterator<Map.Entry<String, JsonNode>> blocksIterator = BLOCKS_JSON.fields();

            BiFunction<String, NbtMapBuilder, String> stateMapper = blockMappers.getOrDefault(palette.getKey(), emptyMapper);

            BlockDefinition[] javaToBedrockBlocks = new BlockDefinition[BLOCKS_JSON.size()];
            DefinitionRegistry.Builder<BlockDefinition> registry = SimpleDefinitionRegistry.builder();

            Map<String, NbtMap> flowerPotBlocks = new Object2ObjectOpenHashMap<>();
            Map<NbtMap, BlockDefinition> itemFrames = new Object2ObjectOpenHashMap<>();

            Set<BlockDefinition> jigsawDefinitions = new ObjectOpenHashSet<>();

            BlockMappings.BlockMappingsBuilder builder = BlockMappings.builder();
            while (blocksIterator.hasNext()) {
                javaRuntimeId++;
                Map.Entry<String, JsonNode> entry = blocksIterator.next();
                String javaId = entry.getKey();

                BlockDefinition bedrockDefinition = blockStateOrderedMap.get(buildBedrockState(entry.getValue(), stateVersion, stateMapper));
                if (bedrockDefinition == null) {
                    throw new RuntimeException("Unable to find " + javaId + " Bedrock BlockDefinition! Built NBT tag: \n" +
                            buildBedrockState(entry.getValue(), stateVersion, stateMapper));
                }

                switch (javaId) {
                    case "minecraft:air" -> airDefinition = bedrockDefinition;
                    case "minecraft:water[level=0]" -> waterDefinition = bedrockDefinition;
                    case "minecraft:command_block[conditional=false,facing=north]" -> commandBlockDefinition = bedrockDefinition;
                    case "minecraft:moving_piston[facing=north,type=normal]" -> movingBlockDefinition = bedrockDefinition;
                }

                if (javaId.contains("jigsaw")) {
                    jigsawDefinitions.add(bedrockDefinition);
                }

                boolean waterlogged = entry.getKey().contains("waterlogged=true")
                        || javaId.contains("minecraft:bubble_column") || javaId.contains("minecraft:kelp") || javaId.contains("seagrass");

                if (waterlogged) {
                    int finalJavaRuntimeId = javaRuntimeId;
                    BlockRegistries.WATERLOGGED.register(set -> set.add(finalJavaRuntimeId));
                }

                String cleanJavaIdentifier = BlockUtils.getCleanIdentifier(entry.getKey());

                // Get the tag needed for non-empty flower pots
                if (entry.getValue().get("pottable") != null) {
                    flowerPotBlocks.put(cleanJavaIdentifier.intern(), blocksTag.get(bedrockDefinition.getRuntimeId()));
                }

                javaToBedrockBlocks[javaRuntimeId] = bedrockDefinition;
            }

            Arrays.stream(javaToBedrockBlocks).distinct().forEach(registry::add);

            if (commandBlockDefinition == null) {
                throw new AssertionError("Unable to find command block in palette");
            }

            builder.commandBlock(commandBlockDefinition);

            if (waterDefinition  == null) {
                throw new AssertionError("Unable to find water in palette");
            }
            builder.bedrockWater(waterDefinition);

            if (airDefinition  == null) {
                throw new AssertionError("Unable to find air in palette");
            }
            builder.bedrockAir(airDefinition);

            if (movingBlockDefinition  == null) {
                throw new AssertionError("Unable to find moving block in palette");
            }
            builder.bedrockMovingBlock(movingBlockDefinition);

            // Loop around again to find all item frame runtime IDs
            Object2ObjectMaps.fastForEach(blockStateOrderedMap, entry -> {
                String name = entry.getKey().getString("name");
                if (name.equals("minecraft:frame") || name.equals("minecraft:glow_frame")) {
                    itemFrames.put(entry.getKey(), entry.getValue());
                }
            });

            builder.bedrockBlockPalette(blocksTag);

            BlockRegistries.BLOCKS.register(palette.getKey().valueInt(), builder.blockStateVersion(stateVersion)
                    .definitionRegistry(registry.build())
                    .javaToBedrockBlocks(javaToBedrockBlocks)
                    .itemFrames(itemFrames)
                    .flowerPotBlocks(flowerPotBlocks)
                    .jigsawStates(jigsawDefinitions)
                    .build());
        }
    }

    private static void registerJavaBlocks() {
        JsonNode blocksJson;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResource("mappings/blocks.json")) {
            blocksJson = GeyserImpl.JSON_MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block mappings", e);
        }

        BlockRegistries.JAVA_BLOCKS.set(new BlockMapping[blocksJson.size()]); // Set array size to number of blockstates

        Deque<String> cleanIdentifiers = new ArrayDeque<>();

        int javaRuntimeId = -1;
        int bellBlockId = -1;
        int cobwebBlockId = -1;
        int furnaceRuntimeId = -1;
        int furnaceLitRuntimeId = -1;
        int honeyBlockRuntimeId = -1;
        int slimeBlockRuntimeId = -1;
        int spawnerRuntimeId = -1;
        int uniqueJavaId = -1;
        int waterRuntimeId = -1;
        Iterator<Map.Entry<String, JsonNode>> blocksIterator = blocksJson.fields();
        while (blocksIterator.hasNext()) {
            javaRuntimeId++;
            Map.Entry<String, JsonNode> entry = blocksIterator.next();
            String javaId = entry.getKey();

            // TODO fix this, (no block should have a null hardness)
            BlockMapping.BlockMappingBuilder builder = BlockMapping.builder();
            JsonNode hardnessNode = entry.getValue().get("block_hardness");
            if (hardnessNode != null) {
                builder.hardness(hardnessNode.floatValue());
            }

            JsonNode canBreakWithHandNode = entry.getValue().get("can_break_with_hand");
            if (canBreakWithHandNode != null) {
                builder.canBreakWithHand(canBreakWithHandNode.booleanValue());
            } else {
                builder.canBreakWithHand(false);
            }

            JsonNode collisionIndexNode = entry.getValue().get("collision_index");
            if (hardnessNode != null) {
                builder.collisionIndex(collisionIndexNode.intValue());
            }

            JsonNode pickItemNode = entry.getValue().get("pick_item");
            if (pickItemNode != null) {
                builder.pickItem(pickItemNode.textValue().intern());
            }

            if (javaId.equals("minecraft:obsidian") || javaId.equals("minecraft:crying_obsidian") || javaId.startsWith("minecraft:respawn_anchor") || javaId.startsWith("minecraft:reinforced_deepslate")) {
                builder.pistonBehavior(PistonBehavior.BLOCK);
            } else {
                JsonNode pistonBehaviorNode = entry.getValue().get("piston_behavior");
                if (pistonBehaviorNode != null) {
                    builder.pistonBehavior(PistonBehavior.getByName(pistonBehaviorNode.textValue()));
                } else {
                    builder.pistonBehavior(PistonBehavior.NORMAL);
                }
            }

            JsonNode hasBlockEntityNode = entry.getValue().get("has_block_entity");
            if (hasBlockEntityNode != null) {
                builder.isBlockEntity(hasBlockEntityNode.booleanValue());
            } else {
                builder.isBlockEntity(false);
            }

            BlockStateValues.storeBlockStateValues(entry.getKey(), javaRuntimeId, entry.getValue());

            String cleanJavaIdentifier = BlockUtils.getCleanIdentifier(entry.getKey());
            String bedrockIdentifier = entry.getValue().get("bedrock_identifier").asText();

            if (!cleanJavaIdentifier.equals(cleanIdentifiers.peekLast())) {
                uniqueJavaId++;
                cleanIdentifiers.add(cleanJavaIdentifier.intern());
            }

            builder.javaIdentifier(javaId);
            builder.javaBlockId(uniqueJavaId);

            BlockRegistries.JAVA_IDENTIFIERS.register(javaId, javaRuntimeId);
            BlockRegistries.JAVA_BLOCKS.register(javaRuntimeId, builder.build());

            // Keeping this here since this is currently unchanged between versions
            // It's possible to only have this store differences in names, but the key set of all Java names is used in sending command suggestions
            BlockRegistries.JAVA_TO_BEDROCK_IDENTIFIERS.register(cleanJavaIdentifier.intern(), bedrockIdentifier.intern());

            if (javaId.startsWith("minecraft:bell[")) {
                bellBlockId = uniqueJavaId;

            } else if (javaId.contains("cobweb")) {
                cobwebBlockId = uniqueJavaId;

            } else if (javaId.startsWith("minecraft:furnace[facing=north")) {
                if (javaId.contains("lit=true")) {
                    furnaceLitRuntimeId = javaRuntimeId;
                } else {
                    furnaceRuntimeId = javaRuntimeId;
                }

            } else if (javaId.startsWith("minecraft:spawner")) {
                spawnerRuntimeId = javaRuntimeId;

            } else if ("minecraft:water[level=0]".equals(javaId)) {
                waterRuntimeId = javaRuntimeId;
            } else if (javaId.equals("minecraft:honey_block")) {
                honeyBlockRuntimeId = javaRuntimeId;
            } else if (javaId.equals("minecraft:slime_block")) {
                slimeBlockRuntimeId = javaRuntimeId;
            }
        }
        if (bellBlockId == -1) {
            throw new AssertionError("Unable to find bell in palette");
        }
        BlockStateValues.JAVA_BELL_ID = bellBlockId;

        if (cobwebBlockId == -1) {
            throw new AssertionError("Unable to find cobwebs in palette");
        }
        BlockStateValues.JAVA_COBWEB_ID = cobwebBlockId;

        if (furnaceRuntimeId == -1) {
            throw new AssertionError("Unable to find furnace in palette");
        }
        BlockStateValues.JAVA_FURNACE_ID = furnaceRuntimeId;

        if (furnaceLitRuntimeId == -1) {
            throw new AssertionError("Unable to find lit furnace in palette");
        }
        BlockStateValues.JAVA_FURNACE_LIT_ID = furnaceLitRuntimeId;

        if (honeyBlockRuntimeId == -1) {
            throw new AssertionError("Unable to find honey block in palette");
        }
        BlockStateValues.JAVA_HONEY_BLOCK_ID = honeyBlockRuntimeId;

        if (slimeBlockRuntimeId == -1) {
            throw new AssertionError("Unable to find slime block in palette");
        }
        BlockStateValues.JAVA_SLIME_BLOCK_ID = slimeBlockRuntimeId;

        if (spawnerRuntimeId == -1) {
            throw new AssertionError("Unable to find spawner in palette");
        }
        BlockStateValues.JAVA_SPAWNER_ID = spawnerRuntimeId;

        if (waterRuntimeId == -1) {
            throw new AssertionError("Unable to find Java water in palette");
        }
        BlockStateValues.JAVA_WATER_ID = waterRuntimeId;

        BlockRegistries.CLEAN_JAVA_IDENTIFIERS.set(cleanIdentifiers.toArray(new String[0]));

        BLOCKS_JSON = blocksJson;

        JsonNode blockInteractionsJson;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResource("mappings/interactions.json")) {
            blockInteractionsJson = GeyserImpl.JSON_MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block interaction mappings", e);
        }

        BlockRegistries.INTERACTIVE.set(toBlockStateSet((ArrayNode) blockInteractionsJson.get("always_consumes")));
        BlockRegistries.INTERACTIVE_MAY_BUILD.set(toBlockStateSet((ArrayNode) blockInteractionsJson.get("requires_may_build")));
    }

    private static IntSet toBlockStateSet(ArrayNode node) {
        IntSet blockStateSet = new IntOpenHashSet(node.size());
        for (JsonNode javaIdentifier : node) {
            blockStateSet.add(BlockRegistries.JAVA_IDENTIFIERS.get().getInt(javaIdentifier.textValue()));
        }
        return blockStateSet;
    }

    private static NbtMap buildBedrockState(JsonNode node, int blockStateVersion, BiFunction<String, NbtMapBuilder, String> statesMapper) {
        NbtMapBuilder tagBuilder = NbtMap.builder();
        String bedrockIdentifier = node.get("bedrock_identifier").textValue();
        tagBuilder.putString("name", bedrockIdentifier)
                .putInt("version", blockStateVersion);

        NbtMapBuilder statesBuilder = NbtMap.builder();

        // check for states
        if (node.has("bedrock_states")) {
            Iterator<Map.Entry<String, JsonNode>> statesIterator = node.get("bedrock_states").fields();

            while (statesIterator.hasNext()) {
                Map.Entry<String, JsonNode> stateEntry = statesIterator.next();
                JsonNode stateValue = stateEntry.getValue();
                switch (stateValue.getNodeType()) {
                    case BOOLEAN -> statesBuilder.putBoolean(stateEntry.getKey(), stateValue.booleanValue());
                    case STRING -> statesBuilder.putString(stateEntry.getKey(), stateValue.textValue());
                    case NUMBER -> statesBuilder.putInt(stateEntry.getKey(), stateValue.intValue());
                }
            }
        }
        String newIdentifier = statesMapper.apply(bedrockIdentifier, statesBuilder);
        if (newIdentifier != null) {
            tagBuilder.putString("name", newIdentifier);
        }
        tagBuilder.put("states", statesBuilder.build());
        return tagBuilder.build();
    }
}