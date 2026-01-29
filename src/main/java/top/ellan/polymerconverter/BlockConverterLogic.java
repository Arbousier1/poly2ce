package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerHeadBlock;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import xyz.nucleoid.packettweaker.PacketContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.*;

import com.mojang.authlib.GameProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings({"null", "unchecked", "unused"})
public class BlockConverterLogic {
    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerBlockConverter");
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /**
     * Converts a Polymer block to CraftEngine configuration format.
     * @param registeredBlock The actual block registered in the game.
     * @param polymerBlock    The Polymer logic interface.
     * @param level           The ServerLevel context for creating fake players.
     */
    public static Map<String, Object> convert(Block registeredBlock, PolymerBlock polymerBlock, ServerLevel level) {
        Map<String, Object> blockConfig = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();

        Block block = registeredBlock;

        try {
            // Check registration status
            boolean isSynced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block) != null;
            boolean isInstance = block instanceof PolymerBlock;

            if (!isSynced && !isInstance) {
                LOGGER.warn("Block {} appears not to be registered with Polymer correctly (No Sync/Interface)", 
                    BuiltInRegistries.BLOCK.getKey(block));
            }

            BlockState defaultState = block.defaultBlockState();

            // =================================================================================
            // Context Creation & Visual State Fetching (With NPE Fallback Protection)
            // =================================================================================
            PacketContext ctx;
            BlockState visualState;
            
            try {
                // Strategy A: Try with FakePlayer (Best visual accuracy)
                ServerPlayer fakePlayer = createSafeFakePlayer(level);
                if (fakePlayer != null) {
                    ctx = PacketContext.create(fakePlayer);
                    // This might throw NPE if the mod accesses player.connection
                    visualState = PolymerBlockUtils.getPolymerBlockState(defaultState, ctx);
                } else {
                    throw new IllegalStateException("FakePlayer creation failed");
                }
            } catch (Exception e) {
                // Strategy B: Fallback to player-less context
                ctx = PacketContext.create(); 
                try {
                    visualState = PolymerBlockUtils.getPolymerBlockState(defaultState, ctx);
                } catch (Exception e2) {
                    // Strategy C: Complete failure, fallback to default state
                    LOGGER.warn("Visual state fetch failed for {}: {}", BuiltInRegistries.BLOCK.getKey(block), e2.getMessage());
                    visualState = defaultState;
                }
            }
            
            Block visualBlock = visualState.getBlock();
            // =================================================================================

            LOGGER.info("Converting Polymer block: {} -> {}", BuiltInRegistries.BLOCK.getKey(block), BuiltInRegistries.BLOCK.getKey(visualBlock));

            // 1. Extract Settings
            extractBlockSettings(block, defaultState, visualBlock, settings);

            // 2. Polymer Send Logic (Check for null player to avoid NPE)
            if (ctx.getPlayer() != null) {
                try {
                    polymerBlock.onPolymerBlockSend(defaultState, BlockPos.ZERO.mutable(), ctx.asNotNullWithPlayer());
                } catch (Exception e) {
                    LOGGER.debug("onPolymerBlockSend failed: {}", e.getMessage());
                }
            }

            // Note: forceLightUpdates is handled by the server, safe to include
            if (polymerBlock.forceLightUpdates(defaultState)) {
                settings.put("force-light-updates", true);
            }

            // 3. Block Entities
            if (block instanceof EntityBlock) {
                Map<String, Object> blockEntityConfig = new LinkedHashMap<>();
                String type = "custom";
                String renderer = "custom";

                if (block instanceof ChestBlock) { type = "chest"; renderer = "chest"; }
                else if (block instanceof SignBlock) { type = "sign"; renderer = "sign"; }
                else if (block instanceof SpawnerBlock) { type = "spawner"; renderer = "spawner"; }
                else if (block instanceof ShulkerBoxBlock) { type = "shulker_box"; renderer = "shulker_box"; }
                else if (block instanceof BedBlock) { type = "bed"; renderer = "bed"; }

                blockEntityConfig.put("type", type);
                blockEntityConfig.put("renderer", renderer);
                settings.put("block-entity", blockEntityConfig);
            }

            // 4. Occlusion / Light Blocking
            // Use can-occlude instead of culling
            if (visualState.isRedstoneConductor(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO)) {
                settings.put("can-occlude", true);
            }

            // 5. Block States & Appearances
            StateDefinition<Block, BlockState> stateManager = block.getStateDefinition();
            Collection<Property<?>> properties = stateManager.getProperties();

            if (properties.isEmpty()) {
                // --- Single State Block ---
                Map<String, Object> stateConfig = new LinkedHashMap<>();
                
                // Try to get precise visual state again (logic consistency)
                BlockState singleVisualState;
                try {
                    singleVisualState = PolymerBlockUtils.getBlockStateSafely(polymerBlock, defaultState, PolymerBlockUtils.NESTED_DEFAULT_DISTANCE, ctx);
                } catch (Exception e) {
                    singleVisualState = visualState;
                }
                
                fillAppearanceConfig(stateConfig, singleVisualState);
                blockConfig.put("state", stateConfig);

            } else {
                // --- Multi-State Block ---
                Map<String, Object> statesSection = new LinkedHashMap<>();
                Map<String, Object> propConfig = new LinkedHashMap<>();
                
                for (Property<?> prop : properties) {
                    propConfig.put(prop.getName(), createPropertyDefinition(prop, defaultState));
                }
                statesSection.put("properties", propConfig);

                Map<String, Object> appearances = new LinkedHashMap<>();
                Map<String, Object> variants = new LinkedHashMap<>();

                for (BlockState state : stateManager.getPossibleStates()) {
                    String variantKey = getVariantKey(state, properties);
                    
                    // Fetch sub-state visual with fallback protection
                    BlockState subVisualState;
                    try {
                        subVisualState = PolymerBlockUtils.getBlockStateSafely(
                            polymerBlock, state, PolymerBlockUtils.NESTED_DEFAULT_DISTANCE, ctx
                        );
                    } catch (Exception e) {
                        subVisualState = visualState;
                    }

                    Map<String, Object> appearance = new LinkedHashMap<>();
                    fillAppearanceConfig(appearance, subVisualState);

                    appearances.put(variantKey, appearance);

                    Map<String, Object> variant = new LinkedHashMap<>();
                    variant.put("appearance", variantKey);
                    variants.put(variantKey, variant);
                }

                statesSection.put("appearances", appearances);
                statesSection.put("variants", variants);
                blockConfig.put("states", statesSection);
            }

            // 6. Special Handling: Polymer Heads
            if (polymerBlock instanceof PolymerHeadBlock headBlock) {
                settings.put("material", "player_head");
                try {
                    String skinValue = headBlock.getPolymerSkinValue(defaultState, BlockPos.ZERO, ctx);
                    if (skinValue != null && !skinValue.isEmpty()) {
                        List<Map<String, Object>> clientData = new ArrayList<>();
                        Map<String, Object> profile = new LinkedHashMap<>();
                        Map<String, Object> props = new LinkedHashMap<>();
                        List<Map<String, Object>> textures = new ArrayList<>();
                        Map<String, Object> tex = new LinkedHashMap<>();
                        tex.put("Value", skinValue);
                        textures.add(tex);
                        props.put("textures", textures);
                        profile.put("properties", props);

                        Map<String, Object> op = new LinkedHashMap<>();
                        op.put("type", "SET");
                        op.put("path", "SkullOwner");
                        op.put("value", profile);
                        clientData.add(op);

                        settings.put("client-bound-data", clientData);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to get head skin value: {}", e.getMessage());
                }
            }

            // 7. Behaviors & Events
            List<Map<String, Object>> behaviors = inferBehaviors(block, polymerBlock);
            if (!behaviors.isEmpty()) {
                blockConfig.put("behaviors", behaviors);
            }

            Map<String, Object> events = new LinkedHashMap<>();
            if (block instanceof ButtonBlock || block instanceof LeverBlock) {
                events.put("on-interact", Arrays.asList("handle_redstone_toggle"));
            } else if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
                events.put("on-interact", Arrays.asList("handle_door_toggle"));
            } else if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                events.put("on-interact", Arrays.asList("open_inventory"));
            } else if (block instanceof TntBlock) {
                events.put("on-interact", Arrays.asList("ignite_tnt"));
            }

            if (!events.isEmpty()) {
                blockConfig.put("events", events);
            }

            // 8. Loot Tables
            Map<String, Object> lootConfig = new LinkedHashMap<>();
            boolean isOre = defaultState.is(BlockTags.COAL_ORES) || defaultState.is(BlockTags.IRON_ORES) ||
                defaultState.is(BlockTags.COPPER_ORES) || defaultState.is(BlockTags.GOLD_ORES) ||
                defaultState.is(BlockTags.REDSTONE_ORES) || defaultState.is(BlockTags.LAPIS_ORES) ||
                defaultState.is(BlockTags.DIAMOND_ORES) || defaultState.is(BlockTags.EMERALD_ORES);

            if (isOre || block instanceof RedStoneOreBlock) {
                lootConfig.put("template", "default:loot_table/ore");
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("ore_drop", BuiltInRegistries.ITEM.getKey(block.asItem()).toString());
                args.put("ore_block", BuiltInRegistries.BLOCK.getKey(block).toString());
                lootConfig.put("arguments", args);
            } else {
                lootConfig.put("template", "default:loot_table/self");
            }
            blockConfig.put("loot", lootConfig);

            // Final Validation
            if (settings.get("material") == null) throw new IllegalStateException("Material detection failed");
            blockConfig.put("settings", settings);

        } catch (Exception e) {
            LOGGER.error("Block conversion failed for {}", BuiltInRegistries.BLOCK.getKey(block), e);
            blockConfig.put("_error", "Conversion failed: " + e.getMessage());
            blockConfig.put("_error_type", e.getClass().getSimpleName());
            blockConfig.put("_stack_trace", getStackTraceString(e));
            
            // Minimal fallback settings
            settings.put("material", "barrier");
            blockConfig.put("settings", settings);
        }

        return blockConfig;
    }

    // --- Core Extraction Methods ---

    private static void extractBlockSettings(Block block, BlockState state, Block visualBlock, Map<String, Object> settings) {
        settings.put("material", BuiltInRegistries.BLOCK.getKey(visualBlock).toString());
        settings.put("hardness", block.defaultBlockState().getDestroySpeed(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
        settings.put("resistance", block.getExplosionResistance());

        if (state.getLightEmission() > 0) {
            settings.put("luminance", state.getLightEmission());
        }

        try {
            settings.put("map-color", state.getMapColor(null, null).id);
        } catch (Exception ignored) {}

        settings.put("friction", block.getFriction());
        settings.put("speed-factor", block.getSpeedFactor());
        settings.put("jump-factor", block.getJumpFactor());
        settings.put("is-randomly-ticking", state.isRandomlyTicking());
        settings.put("support-shape", BuiltInRegistries.BLOCK.getKey(visualBlock).toString());

        if (block instanceof NoteBlock) {
            Map<String, Object> customData = new LinkedHashMap<>();
            customData.put("note", 0);
            settings.put("custom-data", customData);
        }

        if (visualBlock == Blocks.TNT || state.is(BlockTags.WOOL) || state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) {
            settings.put("burnable", true);
        }

        settings.put("replaceable", state.canBeReplaced());
        
        // Fix: Use UPPERCASE for Enum values
        settings.put("push-reaction", state.getPistonPushReaction().name()); 
        
        settings.put("require-correct-tools", true);
        settings.put("respect-tool-component", false);
        
        // Fix: Correct property name
        settings.put("incorrect-tool-dig-speed", 0.3f); 

        try {
            // Fix: Use Enum name (UPPERCASE) instead of ordinal
            settings.put("instrument", state.instrument().getSerializedName().toUpperCase());
        } catch (Exception ignored) {
            settings.put("instrument", "HARP");
        }

        settings.put("fluid-state", state.getFluidState() != null && !state.getFluidState().isEmpty() ? "water" : "empty");
        
        // Fix: Correct property name
        settings.put("propagate-skylight", state.useShapeForLightOcclusion()); 

        try {
            settings.put("is-redstone-conductor", state.isRedstoneConductor(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
            settings.put("is-suffocating", state.isSuffocating(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
            settings.put("is-view-blocking", state.isViewBlocking(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
        } catch (Exception ignored) {
            settings.put("is-redstone-conductor", true);
            settings.put("is-suffocating", true);
            settings.put("is-view-blocking", true);
        }

        Set<String> correctTools = new HashSet<>();
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) correctTools.add("minecraft:pickaxe");
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) correctTools.add("minecraft:axe");
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) correctTools.add("minecraft:mineable/shovel");
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) correctTools.add("minecraft:hoe");
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) correctTools.add("minecraft:diamond_tier");
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) correctTools.add("minecraft:iron_tier");

        if (!correctTools.isEmpty()) {
            settings.put("correct-tools", correctTools);
        }

        Map<String, String> sounds = new LinkedHashMap<>();
        String blockIdPath = BuiltInRegistries.BLOCK.getKey(visualBlock).getPath();
        sounds.put("break", "minecraft:block." + blockIdPath + ".break");
        sounds.put("place", "minecraft:block." + blockIdPath + ".place");
        sounds.put("hit", "minecraft:block." + blockIdPath + ".hit");
        sounds.put("step", "minecraft:block." + blockIdPath + ".step");
        sounds.put("fall", "minecraft:block." + blockIdPath + ".fall");
        sounds.put("ambient", "minecraft:block." + blockIdPath + ".ambient");
        sounds.put("land", "minecraft:block." + blockIdPath + ".land");
        sounds.put("destroy", "minecraft:block." + blockIdPath + ".break");
        settings.put("sounds", sounds);

        List<String> tags = inferToolTags(state);
        if (!tags.isEmpty()) {
            settings.put("tags", tags);
        }
    }

    private static void fillAppearanceConfig(Map<String, Object> config, BlockState visualState) {
        String visualBlockId = BuiltInRegistries.BLOCK.getKey(visualState.getBlock()).toString();
        
        if (shouldUseCustomModel(visualState.getBlock())) {
            Map<String, Object> modelConfig = new LinkedHashMap<>();
            modelConfig.put("template", "default:model/cube");

            Map<String, Object> args = new LinkedHashMap<>();
            String baseName = BuiltInRegistries.BLOCK.getKey(visualState.getBlock()).getPath();
            args.put("model", "minecraft:block/" + baseName);

            Map<String, String> textures = new LinkedHashMap<>();
            textures.put("all", "minecraft:block/" + baseName);
            args.put("textures", textures);

            modelConfig.put("arguments", args);
            config.put("model", modelConfig);
        } else {
            config.put("auto-state", visualBlockId);
        }
    }

    private static Map<String, Object> createPropertyDefinition(Property<?> property, BlockState defaultState) {
        Map<String, Object> def = new LinkedHashMap<>();
        String type = "string";
        String name = property.getName();

        if (property instanceof IntegerProperty) {
            type = "int";
        } else if (property instanceof BooleanProperty) {
            type = "boolean";
        } else if (property instanceof EnumProperty) {
            if (property.getValueClass() == Direction.class) {
                if (name.equals("axis")) {
                    type = "axis";
                } else {
                    type = isFullDirection(property) ? "direction" : "horizontal_direction";
                }
            } else {
                if (name.equals("half")) type = "double_block_half";
                else if (name.equals("shape")) type = "stairs_shape";
                else if (name.equals("hinge")) type = "hinge";
                else if (name.equals("type")) type = "slab_type";
                else type = "string";
            }
        }

        def.put("type", type);

        if (property instanceof IntegerProperty intProp) {
            int min = intProp.getPossibleValues().stream().min(Integer::compareTo).orElse(0);
            int max = intProp.getPossibleValues().stream().max(Integer::compareTo).orElse(1);
            def.put("range", min + "~" + max);
        } else {
            def.put("range", getSafeRange(property));
        }

        def.put("default", getSafeValue(defaultState, property));
        return def;
    }

    // --- Utility Methods ---

    private static <T extends Comparable<T>> String getSafeValue(BlockState state, Property<?> property) {
        try {
            Property<T> typedProperty = (Property<T>) property;
            if (!state.hasProperty(typedProperty)) return "null";
            T value = state.getValue(typedProperty);
            return value != null ? value.toString().toLowerCase() : "null";
        } catch (Exception e) {
            return "null";
        }
    }

    private static <T extends Comparable<T>> String getSafeRange(Property<?> property) {
        try {
            Property<T> typedProperty = (Property<T>) property;
            return typedProperty.getPossibleValues().stream()
                .map(v -> v == null ? "null" : v.toString().toLowerCase())
                .collect(Collectors.joining(","));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isFullDirection(Property<?> property) {
        try {
            Collection<Direction> values = (Collection<Direction>) property.getPossibleValues();
            return values.contains(Direction.UP) || values.contains(Direction.DOWN);
        } catch (ClassCastException e) {
            return false;
        }
    }

    private static String getVariantKey(BlockState state, Collection<Property<?>> properties) {
        return properties.stream()
            .map(prop -> prop.getName() + "=" + getSafeValue(state, prop))
            .collect(Collectors.joining(","));
    }

    private static boolean shouldUseCustomModel(Block block) {
        return block instanceof ChestBlock ||
            block instanceof DecoratedPotBlock ||
            block instanceof ShulkerBoxBlock ||
            block instanceof BedBlock ||
            block instanceof SkullBlock;
    }

    private static ServerPlayer createSafeFakePlayer(ServerLevel world) {
        try {
            if (world == null) return null;
            return new ServerPlayer(
                world.getServer(),
                world,
                new GameProfile(NIL_UUID, "PolymerBlockConverter"),
                ClientInformation.createDefault()
            ) {
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isCreative() { return false; }
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> inferToolTags(BlockState state) {
        List<String> tags = new ArrayList<>();
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) tags.add("minecraft:mineable/pickaxe");
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) tags.add("minecraft:mineable/axe");
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) tags.add("minecraft:mineable/shovel");
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) tags.add("minecraft:mineable/hoe");
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) tags.add("minecraft:needs_diamond_tool");
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) tags.add("minecraft:needs_iron_tool");
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) tags.add("minecraft:needs_stone_tool");
        return tags;
    }

    private static List<Map<String, Object>> inferBehaviors(Block block, PolymerBlock polymerBlock) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (block instanceof FallingBlock) list.add(Map.of("type", "falling_block"));
        if (block instanceof CropBlock) list.add(Map.of("type", "crop_block"));
        if (block instanceof DoorBlock) list.add(Map.of("type", "door_block"));
        if (block instanceof TrapDoorBlock) list.add(Map.of("type", "trapdoor_block"));
        if (block instanceof SlabBlock) list.add(Map.of("type", "slab_block"));
        if (block instanceof StairBlock) list.add(Map.of("type", "stairs_block"));
        if (block instanceof FenceBlock) list.add(Map.of("type", "fence_block"));
        if (block instanceof FenceGateBlock) list.add(Map.of("type", "fence_gate_block"));
        if (block instanceof WallBlock) list.add(Map.of("type", "wall_block"));
        if (block instanceof PressurePlateBlock) list.add(Map.of("type", "pressure_plate_block"));
        if (block instanceof ButtonBlock) list.add(Map.of("type", "button_block"));
        if (block instanceof LeavesBlock) list.add(Map.of("type", "leaves_block"));
        if (block instanceof SignBlock) list.add(Map.of("type", "sign_block"));
        if (block instanceof ChestBlock) list.add(Map.of("type", "storage_block"));
        if (block instanceof RedstoneTorchBlock) list.add(Map.of("type", "redstone_torch_block"));
        if (block instanceof TntBlock) list.add(Map.of("type", "tnt_block"));
        if (block instanceof BedBlock) list.add(Map.of("type", "bed_block"));
        if (polymerBlock instanceof PolymerHeadBlock) {
            list.add(Map.of("type", "bush_block"));
        }
        return list;
    }

    private static String getStackTraceString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
            if (sb.length() > 500) {
                sb.append("...(truncated)");
                break;
            }
        }
        return sb.toString();
    }
}